package generalannotator.report;

import generalannotator.checker.CheckerOutcome;
import generalannotator.checker.CheckerOutcome.ExecutionFailure;
import generalannotator.diagnostics.Diagnostic;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Plain-text list of checker findings ({@code warnings.txt}), one finding per line:
 *
 * <pre>
 * &lt;target-relative path&gt;:&lt;line&gt;:&lt;column&gt;: &lt;severity&gt;: [&lt;checker&gt;] &lt;first message line&gt;
 * </pre>
 *
 * <p>Header lines start with {@code #}. Paths are relative to the original target project; a
 * finding outside the working copy keeps the path javac reported. The column is omitted when javac
 * printed no caret. Only the first message line is shown; {@code report.json} has the full message.
 */
public final class WarningsText {

    public static final String FILE_NAME = "warnings.txt";

    private WarningsText() {}

    public static String format(String checkerId, String targetProject, CheckerOutcome outcome, Path workingCopyRoot) {
        StringBuilder out = new StringBuilder();
        out.append("# GeneralAnnotator warnings\n");
        out.append("# checker: ").append(checkerId).append('\n');
        out.append("# target:  ").append(targetProject).append('\n');
        if (outcome instanceof ExecutionFailure f) {
            out.append("# status:  EXECUTION_FAILURE (").append(f.kind()).append(")\n");
            out.append("# WARNING: the analysis did not complete; this list is INCOMPLETE. See report.json.\n");
        } else {
            out.append("# status:  COMPLETED\n");
        }
        out.append("# count:   ").append(outcome.findings().size()).append('\n');
        out.append("# format:  path:line:column: severity: [checker] message (first line; full text in report.json)\n");
        for (Diagnostic d : outcome.findings()) {
            out.append(line(d, workingCopyRoot)).append('\n');
        }
        return out.toString();
    }

    static String line(Diagnostic d, Path workingCopyRoot) {
        Optional<String> relative = RunReport.targetRelativePath(d.sourcePath(), workingCopyRoot);
        StringBuilder line = new StringBuilder(relative.orElse(d.sourcePath()));
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
