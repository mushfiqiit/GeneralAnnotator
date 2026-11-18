package generalannotator.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.diagnostics.Severity;
import generalannotator.graph.InitialWarningGraph;
import generalannotator.graph.WarningGraphJson;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code targets} on a hand-built run directory (no checker, no Specimin). */
class TargetsCommandTest {

    @TempDir Path temp;

    private Path runDir;
    private Path copy;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        runDir = Files.createDirectories(temp.resolve("runs/20261118T010000Z-nullaway"));
        copy = Files.createDirectories(runDir.resolve("workspace/target"));
        Path source = copy.resolve("lib/src/a/b/Box.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package a.b;
                class Box {
                    Object value;
                    Object get(int i) { return value.hashCode(); }
                    static { }
                }
                """);
        Files.writeString(runDir.resolve("report.json"),
                "{\"configuration\": {\"sourceRoots\": [\"lib/src\"], \"javacRelease\": null}}");
        List<Diagnostic> findings = List.of(
                finding(source, 3, 12, "@NonNull field 'value' not initialized"),
                finding(source, 4, 38, "dereferenced expression value is @Nullable"),
                finding(source, 5, 12, "something in a static initializer"));
        WarningGraphJson.save(InitialWarningGraph.build("20261118T010000Z-nullaway", "nullaway",
                new CompletedAnalysis(findings, List.of()), copy), runDir.resolve("warning-graph.json"));
    }

    private static Diagnostic finding(Path file, int line, int column, String message) {
        return new Diagnostic(file.toString(), line, OptionalInt.of(column), Severity.ERROR,
                Optional.of("NullAway"), message, Optional.empty());
    }

    private ExitCode run(String argument) {
        return new TargetsCommand(argument, temp, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run();
    }

    @Test
    void writesMappingBesideTheGraphAndPrintsIt() throws IOException {
        String graphBefore = Files.readString(runDir.resolve("warning-graph.json"));

        assertEquals(ExitCode.COMPLETED, run("runs/20261118T010000Z-nullaway"), err.toString(StandardCharsets.UTF_8));

        String json = Files.readString(runDir.resolve("warning-targets.json"));
        assertTrue(json.contains("\"targetField\": \"a.b.Box#value\""), json);
        assertTrue(json.contains("\"targetMethod\": \"a.b.Box#get(int)\""), json);
        assertTrue(json.contains("\"sourceRoot\": \"lib/src\""), json);
        assertTrue(json.contains("\"targetFile\": \"a/b/Box.java\""), json);
        assertTrue(json.contains("\"summary\": {\n    \"total\": 3,\n    \"resolved\": 2,\n    \"unresolved\": 1\n  }"),
                json);
        assertEquals(graphBefore, Files.readString(runDir.resolve("warning-graph.json")), "IDs are preserved");
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("W0001  FIELD        a.b.Box#value  [root lib/src, file a/b/Box.java]"), text);
        assertTrue(text.contains("W0002  METHOD       a.b.Box#get(int)"), text);
        assertTrue(text.contains("W0003  UNRESOLVED   the position is inside an initializer block"), text);
        assertTrue(text.contains("Specimin was not run"), text);
    }

    @Test
    void acceptsTheGraphFileItself() {
        assertEquals(ExitCode.COMPLETED, run(runDir.resolve("warning-graph.json").toString()));
    }

    @Test
    void missingRunFilesAreUsageErrors() throws IOException {
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run("runs/nope"));
        Files.delete(runDir.resolve("report.json"));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(runDir.toString()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("No report.json"));
        assertFalse(Files.exists(runDir.resolve("warning-targets.json")));
    }

    @Test
    void missingSnapshotIsAUsageError() throws IOException {
        try (var files = Files.walk(copy)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(runDir.toString()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("isolated working copy"));
    }
}
