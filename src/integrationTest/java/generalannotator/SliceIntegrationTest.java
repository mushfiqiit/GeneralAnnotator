package generalannotator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.cli.ExitCode;
import generalannotator.cli.SliceCommand;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.diagnostics.Severity;
import generalannotator.exec.ProcessCommandRunner;
import generalannotator.graph.InitialWarningGraph;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.report.JsonParser;
import generalannotator.targets.TargetExtractor;
import generalannotator.targets.WarningTarget;
import generalannotator.targets.WarningTargetsJson;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Generates slices with the real local Specimin (GA_SPECIMIN_HOME) for the fixture's targets. */
class SliceIntegrationTest {

    @TempDir Path temp;

    private static Path speciminHome() {
        String home = System.getenv(SliceCommand.SPECIMIN_HOME);
        if (home == null || home.isBlank() || !Files.isDirectory(Path.of(home))) {
            fail("Slice integration tests need " + SliceCommand.SPECIMIN_HOME + " to name a local Specimin checkout; got: "
                    + home);
        }
        return Path.of(home);
    }

    @Test
    void generatesSlicesForFixtureTargetsAndReportsAFailedTarget() throws Exception {
        Path home = speciminHome();
        Path runDir = Files.createDirectories(temp.resolve("runs/run with spaces"));
        Path copy = runDir.resolve("workspace/target");
        Path fixtureSources = IntegrationEnvironment.fixture("simple project").resolve("src/main/java");
        Path copiedSources = copy.resolve("src/main/java");
        try (var files = Files.walk(fixtureSources)) {
            for (Path p : files.toList()) {
                Path dest = copiedSources.resolve(fixtureSources.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest);
                }
            }
        }
        Path greeter = copiedSources.resolve("com/example/fixture/Greeter.java");
        // The fixture's two NullAway findings (lines 12 and 16), plus a third pointing at the same method as W0001.
        List<Diagnostic> findings = List.of(
                new Diagnostic(greeter.toString(), 12, OptionalInt.of(9), Severity.ERROR, Optional.of("NullAway"),
                        "dereferenced expression 'find(key)' is @Nullable", Optional.empty()),
                new Diagnostic(greeter.toString(), 16, OptionalInt.of(9), Severity.ERROR, Optional.of("NullAway"),
                        "returning @Nullable expression from method with @NonNull return type", Optional.empty()));
        WarningGraph graph = InitialWarningGraph.build("run with spaces", "nullaway",
                new CompletedAnalysis(findings, List.of()), copy);
        WarningGraphJson.save(graph, runDir.resolve("warning-graph.json"));
        List<WarningTarget> targets = new ArrayList<>(
                new TargetExtractor(copy, List.of(Path.of("src/main/java")), OptionalInt.empty()).extract(graph));
        WarningTarget.Resolved first = (WarningTarget.Resolved) targets.getFirst();
        assertEquals("com.example.fixture.Greeter#lengthOf(String)", first.target());
        // A target Specimin cannot find, to exercise a real failure.
        targets.add(new WarningTarget.Resolved("W0003", first.location(), first.sourceRoot(), first.isolatedSourceRoot(),
                first.targetFile(), first.memberKind(), first.enclosingClass(), "com.example.fixture.Greeter#missing()",
                first.configuredSourceRoot(), false, List.of()));
        WarningTargetsJson.save(runDir.resolve("warning-targets.json"), graph.runId(), copy, targets);

        Map<String, String> env = new HashMap<>(Map.of(SliceCommand.SPECIMIN_HOME, home.toString()));
        String javaHome = System.getenv("GA_SPECIMIN_JAVA_HOME");
        if (javaHome != null) {
            env.put("GA_SPECIMIN_JAVA_HOME", javaHome);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ExitCode exit = new SliceCommand(runDir.toString(), Optional.empty(), env, temp,
                Path.of(System.getProperty("java.home")), new ProcessCommandRunner(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
                .run();
        System.out.println(out.toString(StandardCharsets.UTF_8));
        System.out.println(err.toString(StandardCharsets.UTF_8));

        assertEquals(ExitCode.EXECUTION_FAILURE, exit, "W0003 is expected to fail");
        Map<?, ?> root = (Map<?, ?>) JsonParser.parse(Files.readString(runDir.resolve("slices/slice-results.json")));
        Map<String, Map<?, ?>> byId = new HashMap<>();
        for (Object o : (List<?>) root.get("results")) {
            byId.put((String) ((Map<?, ?>) o).get("warningId"), (Map<?, ?>) o);
        }
        for (String id : List.of("W0001", "W0002")) {
            Map<?, ?> r = byId.get(id);
            assertEquals("GENERATED", r.get("status"), () -> id + ": " + r);
            assertEquals("NOT_VALIDATED", r.get("reproduction"));
            assertTrue(((List<?>) r.get("outputFiles")).contains("com/example/fixture/Greeter.java"), r::toString);
            assertTrue(Files.isRegularFile(runDir.resolve("slices/" + id + "/output/com/example/fixture/Greeter.java")));
        }
        String slice = Files.readString(runDir.resolve("slices/W0001/output/com/example/fixture/Greeter.java"));
        assertTrue(slice.contains("lengthOf"), slice);
        Map<?, ?> failed = byId.get("W0003");
        assertEquals("FAILED", failed.get("status"));
        assertTrue(((String) failed.get("failureReason")).contains("could not locate"), failed::toString);
        assertEquals(Files.readString(IntegrationEnvironment.fixture("simple project")
                .resolve("src/main/java/com/example/fixture/Greeter.java")), Files.readString(greeter),
                "the run snapshot is unchanged");
    }
}
