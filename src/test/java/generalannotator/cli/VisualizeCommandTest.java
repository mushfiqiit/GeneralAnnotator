package generalannotator.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.exec.CommandResult;
import generalannotator.exec.CommandRunner;
import generalannotator.exec.CommandSpec;
import generalannotator.graph.WarningGraphJson;
import generalannotator.visualization.VisualizationFixtures;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code visualize} with a fake dot, so no Graphviz installation is needed. */
class VisualizeCommandTest {

    @TempDir Path temp;

    private Path runDir;
    private Path graphFile;
    private final List<CommandSpec> specs = new ArrayList<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private final CommandRunner fakeDot = spec -> {
        specs.add(spec);
        try {
            Files.writeString(Path.of(spec.command().get(3)), "<svg></svg>");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new CommandResult(spec, OptionalInt.of(0), "", "", Duration.ZERO, false, Optional.empty());
    };

    private final CommandRunner missingDot = spec -> new CommandResult(spec, OptionalInt.empty(), "", "",
            Duration.ZERO, false, Optional.of("IOException: Cannot run program \"dot\""));

    @BeforeEach
    void setUp() throws IOException {
        runDir = Files.createDirectories(temp.resolve("runs/20261118T000203Z-nullaway"));
        graphFile = runDir.resolve("warning-graph.json");
        WarningGraphJson.save(VisualizationFixtures.threeIsolated(), graphFile);
    }

    private ExitCode run(String argument, Map<String, String> env, CommandRunner runner) {
        return new VisualizeCommand(argument, env, temp, runner,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
                .run();
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void rendersFromRunDirectoryWithoutRerunningTheChecker() throws IOException {
        String jsonBefore = Files.readString(graphFile);

        assertEquals(ExitCode.COMPLETED, run("runs/20261118T000203Z-nullaway", Map.of(), fakeDot), stderr());

        assertTrue(Files.exists(runDir.resolve("warning-graph.dot")));
        assertTrue(Files.exists(runDir.resolve("warning-graph.svg")));
        assertEquals(jsonBefore, Files.readString(graphFile), "graph JSON is not modified; IDs are not regenerated");
        assertEquals(1, specs.size(), "only dot is run");
        assertEquals("dot", specs.getFirst().command().getFirst());
        assertTrue(stdout().contains("(run 20261118T000203Z-nullaway, 3 warning node(s), 0 edge(s))"), stdout());
        assertTrue(stdout().contains("svg:    " + runDir.resolve("warning-graph.svg")), stdout());
    }

    @Test
    void acceptsTheJsonFileAndHonoursDotOverride() throws IOException {
        Path fakeExecutable = Files.writeString(temp.resolve("my dot"), "");
        fakeExecutable.toFile().setExecutable(true);

        assertEquals(ExitCode.COMPLETED,
                run(graphFile.toString(), Map.of("GA_GRAPHVIZ_DOT", fakeExecutable.toString()), fakeDot), stderr());
        assertEquals(fakeExecutable.toString(), specs.getFirst().command().getFirst());
    }

    @Test
    void missingGraphvizIsExit3AndKeepsJsonAndDot() {
        assertEquals(ExitCode.EXECUTION_FAILURE, run(runDir.toString(), Map.of(), missingDot));

        assertTrue(Files.exists(graphFile));
        assertTrue(Files.exists(runDir.resolve("warning-graph.dot")));
        assertFalse(Files.exists(runDir.resolve("warning-graph.svg")));
        assertTrue(stderr().contains("brew install graphviz"), stderr());
        assertTrue(stderr().contains("The graph JSON and DOT files were kept"), stderr());
        assertTrue(stdout().contains("svg:    not rendered"));
    }

    @Test
    void emptyGraphRenders() throws IOException {
        WarningGraphJson.save(VisualizationFixtures.empty(), graphFile);
        assertEquals(ExitCode.COMPLETED, run(runDir.toString(), Map.of(), fakeDot));
        assertTrue(Files.readString(runDir.resolve("warning-graph.dot")).contains("No warnings"));
        assertTrue(stdout().contains("the SVG shows \"No warnings\""), stdout());
    }

    @Test
    void runDirectoryWithoutGraphIsUsageError() throws IOException {
        Path failedRun = Files.createDirectories(temp.resolve("runs/failed"));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(failedRun.toString(), Map.of(), fakeDot));
        assertTrue(stderr().contains("No warning graph at"), stderr());
        assertTrue(specs.isEmpty());
    }

    @Test
    void invalidGraphJsonIsUsageErrorAndNothingIsWritten() throws IOException {
        Files.writeString(graphFile, "{\"schemaVersion\": 1}");
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(graphFile.toString(), Map.of(), fakeDot));
        assertTrue(stderr().contains("Cannot load"), stderr());
        assertFalse(Files.exists(runDir.resolve("warning-graph.dot")));
    }

    @Test
    void invalidDotOverrideIsUsageError() {
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR,
                run(runDir.toString(), Map.of("GA_GRAPHVIZ_DOT", " "), fakeDot));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR,
                run(runDir.toString(), Map.of("GA_GRAPHVIZ_DOT", temp.resolve("no/dot").toString()), fakeDot));
        assertTrue(stderr().contains("is not an executable file"), stderr());
        assertTrue(specs.isEmpty());
    }
}
