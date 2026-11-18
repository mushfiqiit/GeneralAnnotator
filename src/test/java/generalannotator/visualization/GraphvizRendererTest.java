package generalannotator.visualization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.exec.CommandResult;
import generalannotator.exec.CommandRunner;
import generalannotator.exec.CommandSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphvizRendererTest {

    @TempDir Path temp;

    private Path dotFile;
    private Path svgFile;
    private final List<CommandSpec> specs = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        Path dir = Files.createDirectories(temp.resolve("run dir"));
        dotFile = Files.writeString(dir.resolve("warning-graph.dot"), "digraph g {}\n");
        svgFile = dir.resolve("warning-graph.svg");
    }

    /** A fake dot that writes {@code output} to its -o file and exits with {@code exit}. */
    private CommandRunner fakeDot(int exit, String output, String stderr) {
        return spec -> {
            specs.add(spec);
            Path out = Path.of(spec.command().get(spec.command().indexOf("-o") + 1));
            try {
                if (output != null) {
                    Files.writeString(out, output);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new CommandResult(spec, OptionalInt.of(exit), "", stderr, Duration.ofMillis(1), false,
                    Optional.empty());
        };
    }

    private GraphvizRenderer.Result render(CommandRunner runner) throws IOException {
        return new GraphvizRenderer(runner, "my dot", Duration.ofSeconds(9)).render(dotFile, svgFile);
    }

    @Test
    void successWritesSvgUsingTheConfiguredExecutableAndArgumentList() throws IOException {
        GraphvizRenderer.Result r = render(fakeDot(0, "<svg>ok</svg>", ""));

        assertTrue(r.rendered());
        assertEquals("<svg>ok</svg>", Files.readString(svgFile));
        List<String> command = specs.getFirst().command();
        assertEquals("my dot", command.getFirst());
        assertEquals(List.of("-Tsvg", "-o", svgFile + ".tmp", dotFile.toString()), command.subList(1, 5));
        assertEquals(dotFile.getParent(), specs.getFirst().workingDirectory());
        assertFalse(Files.exists(Path.of(svgFile + ".tmp")));
    }

    @Test
    void missingGraphvizKeepsDotRemovesStaleSvgAndExplainsHowToFix() throws IOException {
        Files.writeString(svgFile, "<svg>stale</svg>");
        CommandRunner notInstalled = spec -> new CommandResult(spec, OptionalInt.empty(), "", "", Duration.ZERO,
                false, Optional.of("IOException: Cannot run program \"my dot\": error=2, No such file or directory"));

        GraphvizRenderer.Result r = render(notInstalled);

        assertFalse(r.rendered());
        String problem = r.problem().orElseThrow();
        assertTrue(problem.contains("Graphviz could not be started"), problem);
        assertTrue(problem.contains("brew install graphviz"), problem);
        assertTrue(problem.contains("GA_GRAPHVIZ_DOT"), problem);
        assertTrue(Files.exists(dotFile), "DOT file is kept");
        assertFalse(Files.exists(svgFile), "stale SVG is removed");
    }

    @Test
    void failingDotReportsStderrAndLeavesNoPartialSvg() throws IOException {
        GraphvizRenderer.Result r = render(fakeDot(1, "<svg>partial", "Error: syntax error in line 1"));

        assertFalse(r.rendered());
        assertTrue(r.problem().orElseThrow().contains("exit 1"));
        assertTrue(r.problem().orElseThrow().contains("syntax error in line 1"));
        assertFalse(Files.exists(svgFile));
        assertFalse(Files.exists(Path.of(svgFile + ".tmp")));
        assertTrue(Files.exists(dotFile));
    }

    @Test
    void successWithoutSvgOutputIsAFailure() throws IOException {
        assertFalse(render(fakeDot(0, null, "")).rendered());
        assertFalse(render(fakeDot(0, "not svg", "")).rendered());
        assertFalse(Files.exists(svgFile));
    }

    @Test
    void timeoutIsReported() throws IOException {
        CommandRunner slow = spec -> new CommandResult(spec, OptionalInt.empty(), "", "", Duration.ofSeconds(9), true,
                Optional.empty());
        GraphvizRenderer.Result r = render(slow);
        assertFalse(r.rendered());
        assertTrue(r.problem().orElseThrow().contains("timed out after 9s"));
    }
}
