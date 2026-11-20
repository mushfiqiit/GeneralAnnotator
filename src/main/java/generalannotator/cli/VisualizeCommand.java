package generalannotator.cli;

import generalannotator.exec.CommandRunner;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.visualization.DotExporter;
import generalannotator.visualization.GraphvizRenderer;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@code visualize <warning-graph.json | evaluation-graph.json | run or evaluation directory>}: load a
 * saved graph (no checker run), write {@code <name>.dot} beside it and render {@code <name>.svg} with
 * Graphviz.
 *
 * <p>Exit codes: 0 rendered; 2 bad argument, unreadable or invalid graph file, or invalid {@value
 * GraphvizRenderer#DOT_ENV}; 3 Graphviz missing or rendering failed (the JSON and DOT files are kept);
 * 4 internal error such as failing to write the DOT file.
 */
public final class VisualizeCommand {

    /** The graph file of an evaluation directory ({@code evaluate-annotations}). */
    public static final String EVALUATION_GRAPH = generalannotator.evaluation.Evaluator.GRAPH;

    private final String argument;
    private final Map<String, String> env;
    private final Path workingDirectory;
    private final CommandRunner runner;
    private final PrintStream out;
    private final PrintStream err;

    public VisualizeCommand(String argument, Map<String, String> env, Path workingDirectory, CommandRunner runner,
            PrintStream out, PrintStream err) {
        this.argument = argument;
        this.env = Map.copyOf(env);
        this.workingDirectory = workingDirectory;
        this.runner = runner;
        this.out = out;
        this.err = err;
    }

    public ExitCode run() {
        String dot = env.getOrDefault(GraphvizRenderer.DOT_ENV, GraphvizRenderer.DEFAULT_DOT);
        if (dot.isBlank()) {
            return usage(GraphvizRenderer.DOT_ENV + " is set but blank; unset it to use `dot` from PATH.");
        }
        dot = dot.strip();
        if (dot.contains("/") || dot.contains(java.io.File.separator)) {
            Path executable = Path.of(dot);
            if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
                return usage(GraphvizRenderer.DOT_ENV + "='" + dot + "' is not an executable file.");
            }
        }

        Path graphFile;
        try {
            Path given = workingDirectory.resolve(argument).normalize();
            if (!Files.exists(given)) {
                return usage(given + " does not exist. Pass a warning-graph.json file or a run directory, "
                        + "for example runs/<run id> (list them with: ls runs).");
            }
            graphFile = given;
            if (Files.isDirectory(given)) {
                // a run directory holds warning-graph.json; an evaluation directory holds evaluation-graph.json
                graphFile = given.resolve(WarningGraphJson.FILE_NAME);
                if (!Files.exists(graphFile) && Files.isRegularFile(given.resolve(EVALUATION_GRAPH))) {
                    graphFile = given.resolve(EVALUATION_GRAPH);
                }
            }
        } catch (InvalidPathException e) {
            return usage("'" + argument + "' is not a valid path.");
        }
        if (!Files.isRegularFile(graphFile)) {
            return usage("No warning graph at " + graphFile + ". Pass a warning-graph.json file or a run directory "
                    + "that contains one (runs of completed analyses have it; failed runs do not).");
        }

        WarningGraph graph;
        try {
            graph = WarningGraphJson.load(graphFile);
        } catch (IOException | IllegalArgumentException e) {
            return usage("Cannot load " + graphFile + ": " + e.getMessage());
        }

        String base = graphFile.getFileName().toString();
        base = base.endsWith(".json") ? base.substring(0, base.length() - ".json".length()) : base;
        Path dotFile = graphFile.resolveSibling(base + ".dot");
        Path svgFile = graphFile.resolveSibling(base + ".svg");
        try {
            Files.writeString(dotFile, DotExporter.toDot(graph), StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("GeneralAnnotator: cannot write " + dotFile + ": " + e);
            return ExitCode.INTERNAL_ERROR;
        }

        GraphvizRenderer.Result result;
        try {
            result = new GraphvizRenderer(runner, dot, GraphvizRenderer.TIMEOUT).render(dotFile, svgFile);
        } catch (IOException e) {
            err.println("GeneralAnnotator: rendering " + dotFile + " failed: " + e);
            return ExitCode.INTERNAL_ERROR;
        }

        out.println("GeneralAnnotator visualize");
        long annotations = graph.nodes().size() - graph.warningNodes().size();
        out.println("  graph:  " + graphFile + " (run " + graph.runId() + ", " + graph.warningNodes().size()
                + " warning node(s), " + (annotations > 0 ? annotations + " annotation node(s), " : "")
                + graph.edges().size() + " edge(s))");
        out.println("  dot:    " + dotFile);
        if (!result.rendered()) {
            out.println("  svg:    not rendered");
            err.println("GeneralAnnotator: " + result.problem().orElseThrow());
            err.println("The graph JSON and DOT files were kept. Render manually with:");
            err.println("  " + dot + " -Tsvg -o \"" + svgFile + "\" \"" + dotFile + "\"");
            return ExitCode.EXECUTION_FAILURE;
        }
        out.println("  svg:    " + svgFile);
        out.println(graph.nodes().isEmpty()
                ? "  The graph has no warning nodes; the SVG shows \"No warnings\"."
                : "  Open the SVG in a web browser and hover over a node to see its location and message:");
        out.println("    open \"" + svgFile + "\"    (macOS; elsewhere open it in a browser)");
        return ExitCode.COMPLETED;
    }

    private ExitCode usage(String message) {
        err.println("GeneralAnnotator: " + message);
        return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
    }
}
