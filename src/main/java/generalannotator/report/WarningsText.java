package generalannotator.report;

import generalannotator.checker.CheckerOutcome;
import generalannotator.checker.CheckerOutcome.ExecutionFailure;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningNode;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Plain-text list of checker findings ({@code warnings.txt}).
 *
 * <p>For a completed analysis each line starts with the finding's warning-graph ID, in graph order:
 *
 * <pre>
 * W0001  &lt;target-relative path&gt;:&lt;line&gt;:&lt;column&gt;: &lt;severity&gt;: [&lt;checker&gt;] &lt;first message line&gt;
 * </pre>
 *
 * For an execution failure there is no graph, so lines have no ID and the header marks the list
 * as incomplete. Header lines start with {@code #}. Paths are relative to the original target
 * project; a finding outside the working copy keeps the path javac reported. The column is omitted
 * when javac printed no caret. Only the first message line is shown; {@code report.json} and
 * {@code warning-graph.json} have the full message.
 */
public final class WarningsText {

    public static final String FILE_NAME = "warnings.txt";

    private WarningsText() {}

    /**
     * @param graph the initial warning graph; must be present exactly when the outcome is a
     *     completed analysis
     */
    public static String format(String checkerId, String targetProject, CheckerOutcome outcome,
            Path workingCopyRoot, Optional<WarningGraph> graph) {
        boolean failed = outcome instanceof ExecutionFailure;
        if (failed == graph.isPresent()) {
            throw new IllegalArgumentException("a warning graph exists exactly for completed analyses");
        }
        StringBuilder out = new StringBuilder();
        out.append("# GeneralAnnotator warnings\n");
        out.append("# checker: ").append(checkerId).append('\n');
        out.append("# target:  ").append(targetProject).append('\n');
        if (outcome instanceof ExecutionFailure f) {
            out.append("# status:  EXECUTION_FAILURE (").append(f.kind()).append(")\n");
            out.append("# WARNING: the analysis did not complete; this list is INCOMPLETE and no warning\n");
            out.append("#          graph was created. See report.json.\n");
        } else {
            out.append("# status:  COMPLETED\n");
        }
        out.append("# count:   ").append(outcome.findings().size()).append('\n');
        if (graph.isPresent()) {
            out.append("# format:  ID  path:line:column: severity: [checker] message"
                    + " (IDs as in warning-graph.json; full text in report.json)\n");
            for (WarningNode node : graph.get().warningNodes()) {
                out.append(mappingLine(node)).append('\n');
            }
        } else {
            out.append("# format:  path:line:column: severity: [checker] message (first line; full text in report.json)\n");
            for (Diagnostic d : outcome.findings()) {
                out.append(line(d, RunReport.targetRelativePath(d.sourcePath(), workingCopyRoot))).append('\n');
            }
        }
        return out.toString();
    }

    /** {@code W0001  path:line:column: severity: [checker] first message line}. */
    public static String mappingLine(WarningNode node) {
        return node.id() + "  " + line(node.diagnostic(), node.targetPath());
    }

    static String line(Diagnostic d, Optional<String> targetPath) {
        StringBuilder line = new StringBuilder(targetPath.orElse(d.sourcePath()));
        line.append(':').append(d.line());
        if (d.column().isPresent()) {
            line.append(':').append(d.column().getAsInt());
        }
        line.append(": ").append(d.severity().label()).append(": ");
        d.checkerName().ifPresent(name -> line.append('[').append(name).append("] "));
        String message = d.message();
        int newline = message.indexOf('\n');
        line.append(newline < 0 ? message : message.substring(0, newline));
        return line.toString();
    }
}
