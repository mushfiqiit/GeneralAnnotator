package generalannotator.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.diagnostics.Severity;
import generalannotator.graph.InitialWarningGraph;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.report.JsonParser;
import generalannotator.targets.MemberKind;
import generalannotator.targets.TargetExtractor;
import generalannotator.targets.WarningTarget;
import generalannotator.targets.WarningTargetsJson;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code context} on a hand-built run directory (no checker, no Specimin, no LLM). */
class ContextCommandTest {

    static final String LIB = """
            package a;

            public class Lib {
                private String name;

                public String find(String key) {
                    return key == null ? null : key.trim();
                }

                static {
                    System.out.println();
                }
            }
            """;
    static final String USER = """
            package b;

            import missing.Dependency;

            class User {
                int use(a.Lib lib) {
                    String s = lib.find("k");
                    return s.length();
                }

                void broken(Dependency d) {
                    d.find("x");
                }
            }
            """;

    @TempDir Path temp;

    private Path runDir;
    private Path lib;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        runDir = Files.createDirectories(temp.resolve("runs/run 1"));
        Path copy = Files.createDirectories(runDir.resolve("workspace/target"));
        lib = copy.resolve("core/src/a/Lib.java");
        Path user = copy.resolve("core/src/b/User.java");
        Files.createDirectories(lib.getParent());
        Files.createDirectories(user.getParent());
        Files.writeString(lib, LIB);
        Files.writeString(user, USER);
        Files.writeString(runDir.resolve("report.json"),
                "{\"configuration\": {\"sourceRoots\": [\"core/src\"], \"classpath\": [], \"javacRelease\": null}}");
        List<Diagnostic> findings = List.of(
                finding(lib, 4, 20, "@NonNull field 'name' not initialized"),
                finding(lib, 7, 16, "returning @Nullable expression from method with @NonNull return type"),
                finding(lib, 11, 9, "something in a static initializer"));
        WarningGraph graph = InitialWarningGraph.build("run 1", "nullaway", new CompletedAnalysis(findings, List.of()),
                copy);
        WarningGraphJson.save(graph, runDir.resolve("warning-graph.json"));
        List<WarningTarget> targets = new TargetExtractor(copy, List.of(Path.of("core/src")), OptionalInt.empty())
                .extract(graph);
        WarningTargetsJson.save(runDir.resolve("warning-targets.json"), "run 1", copy, targets);
    }

    private static Diagnostic finding(Path file, int line, int column, String message) {
        return new Diagnostic(file.toString(), line, OptionalInt.of(column), Severity.ERROR,
                Optional.of("NullAway"), message, Optional.empty());
    }

    private ExitCode run(Map<String, String> env, String... args) {
        Optional<String> warning = args.length == 2 ? Optional.of(args[1]) : Optional.empty();
        return new ContextCommand(args[0], warning, env, temp, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run();
    }

    private Map<?, ?> json(String id) throws IOException {
        return (Map<?, ?>) JsonParser.parse(Files.readString(runDir.resolve("context/" + id + ".json")));
    }

    @Test
    void writesJsonAndTextPerWarningKeepingIds() throws IOException {
        assertEquals(ExitCode.COMPLETED, run(Map.of(), "runs/run 1"), err.toString(StandardCharsets.UTF_8));

        Map<?, ?> field = json("W0001");
        assertEquals("W0001", field.get("warningId"));
        assertEquals("run 1", field.get("runId"));
        assertEquals("EXTRACTED", field.get("status"));
        assertEquals("a.Lib#name", ((Map<?, ?>) field.get("target")).get("target"));
        assertEquals("@NonNull field 'name' not initialized", ((Map<?, ?>) field.get("warning")).get("message"));

        Map<?, ?> method = json("W0002");
        Map<?, ?> evidence = (Map<?, ?>) method.get("evidence");
        Map<?, ?> calls = (Map<?, ?>) evidence.get("callSites");
        assertEquals(1L, calls.get("total"));
        Map<?, ?> call = (Map<?, ?>) ((List<?>) calls.get("items")).getFirst();
        assertEquals("core/src/b/User.java:7:20", call.get("location"));
        Map<?, ?> unresolved = (Map<?, ?>) evidence.get("unresolvedReferences");
        assertEquals(1L, unresolved.get("total"), "d.find(\"x\") on a missing type is reported, not matched");
        Map<?, ?> analysis = (Map<?, ?>) method.get("analysis");
        assertEquals(2L, analysis.get("filesAnalyzed"));
        assertTrue((Long) analysis.get("compilerErrorCount") > 0);
        assertTrue(((List<?>) method.get("limitations")).stream().anyMatch(l -> ((String) l).contains("javac reported")));

        Map<?, ?> skipped = json("W0003");
        assertEquals("SKIPPED", skipped.get("status"));
        assertTrue(((String) skipped.get("reason")).contains("initializer block"));

        String text = Files.readString(runDir.resolve("context/W0002.txt"));
        assertTrue(text.startsWith("Context for W0002 (EXTRACTED)"), text);
        assertTrue(text.contains("== callSites (1 of 1) =="), text);
        assertTrue(text.contains("| 7  String s = lib.find(\"k\");"), text);
        String console = out.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("W0002  EXTRACTED  METHOD a.Lib#find(String) callSites=1"), console);
        assertTrue(console.contains("W0003  SKIPPED"), console);
        assertEquals(LIB, Files.readString(lib), "the snapshot is never modified");
    }

    @Test
    void singleWarningAndRegenerationAreDeterministic() throws IOException {
        assertEquals(ExitCode.COMPLETED, run(Map.of(), "runs/run 1", "W0002"));
        try (Stream<Path> files = Files.list(runDir.resolve("context"))) {
            assertEquals(List.of("W0002.json", "W0002.txt"),
                    files.map(p -> p.getFileName().toString()).sorted().toList());
        }
        String first = Files.readString(runDir.resolve("context/W0002.json"));
        assertEquals(ExitCode.COMPLETED, run(Map.of(), "runs/run 1", "W0002"));
        assertEquals(first, Files.readString(runDir.resolve("context/W0002.json")));
    }

    @Test
    void limitsComeFromTheEnvironment() throws IOException {
        assertEquals(ExitCode.COMPLETED, run(Map.of(ContextCommand.MAX_ITEMS, "1", ContextCommand.MAX_SNIPPET_LINES,
                "3", ContextCommand.MAX_CONDITIONS, "0", ContextCommand.MAX_LOCAL_USES, "0"), "runs/run 1", "W0002"));
        Map<?, ?> limits = (Map<?, ?>) json("W0002").get("limits");
        assertEquals(Map.of("maxItemsPerCategory", 1L, "maxSnippetLines", 3L, "maxConditions", 0L, "maxLocalUses", 0L),
                limits);
        Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) json("W0002").get("evidence")).get("parameterUses");
        assertEquals(2L, params.get("total"));
        assertEquals(1L, params.get("omitted"));
    }

    @Test
    void invalidInputsAreUsageErrorsAndWriteNothing() throws IOException {
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(ContextCommand.MAX_ITEMS, "0"), "runs/run 1"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("GA_CONTEXT_MAX_ITEMS='0' must be an integer >= 1"));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "runs/run 1", "W0099"));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "runs/missing"));
        assertFalse(Files.exists(runDir.resolve("context")));
        Files.delete(runDir.resolve("warning-targets.json"));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "runs/run 1"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("targets "), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aTargetMissingFromTheSnapshotFails() throws IOException {
        WarningTargetsJson.TargetsFile file = WarningTargetsJson.load(runDir.resolve("warning-targets.json"));
        List<WarningTarget> targets = new ArrayList<>(file.targets());
        WarningTarget.Resolved r = (WarningTarget.Resolved) targets.get(1);
        targets.set(1, new WarningTarget.Resolved(r.warningId(), r.location(), r.sourceRoot(), r.isolatedSourceRoot(),
                r.targetFile(), MemberKind.METHOD, r.enclosingClass(), "a.Lib#gone(int)", r.configuredSourceRoot(),
                false, List.of()));
        WarningTargetsJson.save(runDir.resolve("warning-targets.json"), file.runId(), file.workingCopy(), targets);

        assertEquals(ExitCode.EXECUTION_FAILURE, run(Map.of(), "runs/run 1"));
        assertEquals("FAILED", json("W0002").get("status"));
        assertEquals("EXTRACTED", json("W0001").get("status"));
    }
}
