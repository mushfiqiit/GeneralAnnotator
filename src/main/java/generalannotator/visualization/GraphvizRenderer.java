package generalannotator.visualization;

import generalannotator.exec.CommandResult;
import generalannotator.exec.CommandRunner;
import generalannotator.exec.CommandSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Renders a DOT file to SVG with Graphviz's {@code dot} executable through a {@link CommandRunner}.
 *
 * <p>The SVG is first written to a temporary file next to the target and moved into place only
 * after {@code dot} succeeded and produced an SVG document. On any failure the DOT file is left
 * untouched, no partial SVG is kept, and a stale SVG from an earlier rendering is removed so it
 * cannot be mistaken for the current graph.
 */
public final class GraphvizRenderer {

    /** Environment variable that overrides the {@code dot} executable. */
    public static final String DOT_ENV = "GA_GRAPHVIZ_DOT";

    public static final String DEFAULT_DOT = "dot";

    public static final Duration TIMEOUT = Duration.ofMinutes(5);

    static final String INSTALL_HINT = "Install Graphviz (macOS: `brew install graphviz`; Debian/Ubuntu: "
            + "`sudo apt-get install graphviz`; other systems: https://graphviz.org/download/) so that `dot` is on "
            + "PATH, or set " + DOT_ENV + " to the full path of the dot executable.";

    private final CommandRunner runner;
    private final String dotExecutable;
    private final Duration timeout;

    public GraphvizRenderer(CommandRunner runner, String dotExecutable, Duration timeout) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.dotExecutable = Objects.requireNonNull(dotExecutable, "dotExecutable");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * Result of one rendering attempt.
     *
     * @param rendered whether the SVG was written
     * @param problem what went wrong, with an actionable hint, if not rendered
     * @param command the {@code dot} command result, if {@code dot} was run
     */
    public record Result(boolean rendered, Optional<String> problem, Optional<CommandResult> command) {}

    public Result render(Path dotFile, Path svgFile) throws IOException {
        Path temp = svgFile.resolveSibling(svgFile.getFileName() + ".tmp");
        Files.deleteIfExists(temp);
        CommandResult result = runner.run(new CommandSpec(
                List.of(dotExecutable, "-Tsvg", "-o", temp.toString(), dotFile.toString()),
                dotFile.toAbsolutePath().getParent(), Map.of(), timeout));

        String problem = null;
        if (result.startFailure().isPresent()) {
            problem = "Graphviz could not be started (`" + dotExecutable + "`: " + result.startFailure().get()
                    + "). " + INSTALL_HINT;
        } else if (result.timedOut()) {
            problem = "Graphviz `" + dotExecutable + "` timed out after " + timeout.toSeconds() + "s and was killed.";
        } else if (!result.succeeded()) {
            problem = "Graphviz `" + dotExecutable + "` failed (" + result.describeStatus() + ")"
                    + stderrExcerpt(result) + ". Check that " + DOT_ENV + " (if set) names Graphviz's dot.";
        } else if (!Files.isRegularFile(temp) || !looksLikeSvg(temp)) {
            problem = "Graphviz `" + dotExecutable + "` exited successfully but did not write an SVG document.";
        }

        if (problem != null) {
            Files.deleteIfExists(temp);
            Files.deleteIfExists(svgFile);
            return new Result(false, Optional.of(problem), Optional.of(result));
        }
        Files.move(temp, svgFile, StandardCopyOption.REPLACE_EXISTING);
        return new Result(true, Optional.empty(), Optional.of(result));
    }

    private static boolean looksLikeSvg(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return text.contains("<svg") && text.contains("</svg>");
    }

    private static String stderrExcerpt(CommandResult result) {
        List<String> lines = result.stderr().lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty()) {
            return "";
        }
        return ": " + String.join(" | ", lines.subList(0, Math.min(5, lines.size())));
    }
}
