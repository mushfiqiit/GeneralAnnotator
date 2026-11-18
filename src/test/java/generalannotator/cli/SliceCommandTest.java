package generalannotator.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.diagnostics.Severity;
import generalannotator.exec.CommandRunner;
import generalannotator.exec.CommandSpec;
import generalannotator.graph.InitialWarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.report.JsonParser;
import generalannotator.slicing.FakeSpecimin;
import generalannotator.targets.MemberKind;
import generalannotator.targets.WarningTarget;
import generalannotator.targets.WarningTargetsJson;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code slice} end to end with a fake Specimin (no real Specimin, no checker). */
class SliceCommandTest {

    @TempDir Path temp;

    private Path runDir;
    private Path sourceRoot;
    private Path home;
    private Map<String, String> env;
    private final List<CommandSpec> javaRuns = new ArrayList<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        runDir = Files.createDirectories(temp.resolve("runs/run 1"));
        sourceRoot = Files.createDirectories(runDir.resolve("workspace/target/lib src"));
        Path file = sourceRoot.resolve("a/b/Box.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package a.b; class Box { Object value; Box(int i) {} Object get(Class<?> c) { return null; } }\n");
        home = FakeSpecimin.checkout(temp.resolve("specimin checkout"));
        env = new HashMap<>(Map.of(SliceCommand.SPECIMIN_HOME, home.toString()));

        List<WarningTarget> targets = List.of(
                resolved("W0001", MemberKind.FIELD, "a.b.Box#value"),
                resolved("W0002", MemberKind.CONSTRUCTOR, "a.b.Box#Box(int)"),
                resolved("W0003", MemberKind.METHOD, "a.b.Box#get(Class<?>)"),
                resolved("W0004", MemberKind.METHOD, "a.b.Box#get(Class<?>)"),   // same target as W0003
                new WarningTarget.Unresolved("W0005", "lib src/a/b/Box.java:1:1",
                        "the position is inside an initializer block of a.b.Box", List.of()));
        WarningTargetsJson.save(runDir.resolve("warning-targets.json"), "run 1", runDir.resolve("workspace/target"),
                targets);
        List<Diagnostic> findings = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            findings.add(new Diagnostic(file.toString(), i, OptionalInt.of(1), Severity.ERROR, Optional.of("NullAway"),
                    "m" + i, Optional.empty()));
        }
        WarningGraphJson.save(InitialWarningGraph.build("run 1", "nullaway", new CompletedAnalysis(findings, List.of()),
                runDir.resolve("workspace/target")), runDir.resolve("warning-graph.json"));
    }

    private WarningTarget.Resolved resolved(String id, MemberKind kind, String target) {
        return new WarningTarget.Resolved(id, "lib src/a/b/Box.java:1:1", "lib src", sourceRoot.toString(),
                "a/b/Box.java", kind, "a.b.Box", target, Optional.of("lib src"), false, List.of());
    }

    /** Fake: installDist creates the distribution; java writes output except for targets in {@code failing}. */
    private CommandRunner runner(List<String> failing) {
        return spec -> {
            try {
                if (FakeSpecimin.isInstallDist(spec)) {
                    FakeSpecimin.install(home, FakeSpecimin.MAIN_CLASS);
                    return FakeSpecimin.result(spec, 0, "BUILD SUCCESSFUL", "");
                }
                if (FakeSpecimin.isGit(spec)) {
                    return FakeSpecimin.result(spec, 0, "abc\n", "");
                }
                javaRuns.add(spec);
                List<String> c = spec.command();
                String member = c.contains("--targetField") ? c.get(c.indexOf("--targetField") + 1)
                        : c.get(c.indexOf("--targetMethod") + 1);
                if (failing.contains(member)) {
                    return FakeSpecimin.result(spec, 1, "", "Exception in thread \"main\" java.lang.RuntimeException: "
                            + "Specimin could not locate the following target methods");
                }
                Path output = Path.of(c.get(c.indexOf("--outputDirectory") + 1)).resolve("a/b/Box.java");
                Files.createDirectories(output.getParent());
                Files.writeString(output, "package a.b; class Box {}");
                return FakeSpecimin.result(spec, 0, "", "");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private ExitCode run(Optional<String> warning, List<String> failing) {
        return new SliceCommand(runDir.toString(), warning, env, temp, Path.of(System.getProperty("java.home")),
                runner(failing), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run();
    }

    private Map<String, Map<?, ?>> results() throws IOException {
        Map<?, ?> root = (Map<?, ?>) JsonParser.parse(Files.readString(runDir.resolve("slices/slice-results.json")));
        Map<String, Map<?, ?>> byId = new HashMap<>();
        for (Object o : (List<?>) root.get("results")) {
            Map<?, ?> m = (Map<?, ?>) o;
            byId.put((String) m.get("warningId"), m);
        }
        return byId;
    }

    @Test
    void allTargetsAreProcessedSeparatelyAndFailuresDoNotStopTheRun() throws IOException {
        String snapshotBefore = Files.readString(sourceRoot.resolve("a/b/Box.java"));

        ExitCode exit = run(Optional.empty(), List.of("a.b.Box#Box(int)"));

        assertEquals(ExitCode.EXECUTION_FAILURE, exit, "one slice failed");
        assertEquals(4, javaRuns.size(), "each resolved warning runs Specimin, even with a shared target");
        Map<String, Map<?, ?>> r = results();
        assertEquals("GENERATED", r.get("W0001").get("status"));
        assertEquals("FAILED", r.get("W0002").get("status"));
        assertTrue(((String) r.get("W0002").get("failureReason")).contains("could not locate"));
        assertEquals("GENERATED", r.get("W0003").get("status"));
        assertEquals("GENERATED", r.get("W0004").get("status"), "processing continued after W0002 failed");
        assertEquals("SKIPPED", r.get("W0005").get("status"));
        assertEquals("the position is inside an initializer block of a.b.Box", r.get("W0005").get("failureReason"));
        assertEquals(null, r.get("W0005").get("command"));
        for (Map<?, ?> e : r.values()) {
            assertEquals("NOT_VALIDATED", e.get("reproduction"));
        }
        Map<?, ?> w1 = r.get("W0001");
        assertEquals("slices/W0001/output", w1.get("outputDirectory"));
        assertEquals(List.of("a/b/Box.java"), w1.get("outputFiles"));
        Map<?, ?> cmd = (Map<?, ?>) w1.get("command");
        assertEquals("slices/W0001/logs/specimin.stdout.txt", cmd.get("stdoutFile"));
        List<?> argv = (List<?>) cmd.get("command");
        assertEquals("a.b.Box#value", argv.get(argv.indexOf("--targetField") + 1));
        assertEquals("nullaway", argv.get(argv.indexOf("--modularityModel") + 1));
        assertEquals(runDir.resolve("slices/W0001/root-copy").toString(), argv.get(argv.indexOf("--root") + 1));
        Map<?, ?> target = (Map<?, ?>) r.get("W0003").get("target");
        assertEquals("a.b.Box#get(Class<?>)", target.get("targetMethod"));
        assertEquals(snapshotBefore, Files.readString(sourceRoot.resolve("a/b/Box.java")), "snapshot unchanged");
        assertFalse(Files.exists(runDir.resolve("slices/W0001/root-copy")));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("W0005  SKIPPED"), text);
        assertTrue(text.contains("(3 generated, 1 failed, 1 skipped)"), text);
    }

    @Test
    void singleWarningThenRefusalToOverwriteThenMerge() throws IOException {
        assertEquals(ExitCode.COMPLETED, run(Optional.of("W0003"), List.of()));
        assertEquals(1, javaRuns.size());
        assertEquals(List.of("W0003"), List.copyOf(results().keySet()));

        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Optional.of("W0003"), List.of()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("W0003 already has slice results"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("never overwritten"));
        assertEquals(1, javaRuns.size(), "nothing ran");

        assertEquals(ExitCode.COMPLETED, run(Optional.of("W0001"), List.of()));
        assertEquals(java.util.Set.of("W0001", "W0003"), results().keySet(), "earlier results are kept");

        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Optional.empty(), List.of()),
                "processing all would overwrite W0001 and W0003");
    }

    @Test
    void skippedOnlyRunIsExitZero() {
        assertEquals(ExitCode.COMPLETED, run(Optional.of("W0005"), List.of()));
        assertTrue(javaRuns.isEmpty());
    }

    @Test
    void invalidInputsAreRejectedBeforeAnythingRuns() throws IOException {
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Optional.of("W0999"), List.of()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("W0999 is not in"));

        env.remove(SliceCommand.SPECIMIN_HOME);
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Optional.empty(), List.of()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("GA_SPECIMIN_HOME is not set"));
        env.put(SliceCommand.SPECIMIN_HOME, home.toString());

        env.put(SliceCommand.SPECIMIN_JAR_PATH, temp.resolve("no jars").toString());
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Optional.empty(), List.of()));
        env.remove(SliceCommand.SPECIMIN_JAR_PATH);

        String json = Files.readString(runDir.resolve("warning-targets.json"));
        Files.writeString(runDir.resolve("warning-targets.json"),
                json.replace(sourceRoot.toString(), temp.resolve("elsewhere").toString()));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Optional.empty(), List.of()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("is not an existing directory inside the run's snapshot"));
        Files.writeString(runDir.resolve("warning-targets.json"), json);

        try (var files = Files.walk(runDir.resolve("workspace"))) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Optional.empty(), List.of()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("snapshot"));
        assertTrue(javaRuns.isEmpty());
        assertFalse(Files.exists(runDir.resolve("slices")));
    }

    @Test
    void setupFailureIsExitThree() {
        CommandRunner broken = spec -> FakeSpecimin.result(spec, 1, "", "BUILD FAILED");
        ExitCode exit = new SliceCommand(runDir.toString(), Optional.empty(), env, temp,
                Path.of(System.getProperty("java.home")), broken, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run();
        assertEquals(ExitCode.EXECUTION_FAILURE, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Specimin setup failed"));
        assertFalse(Files.exists(runDir.resolve("slices/slice-results.json")));
    }
}
