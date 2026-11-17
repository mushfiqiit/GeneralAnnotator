package generalannotator.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Everything recognized (and not recognized) in captured javac output.
 *
 * @param diagnostics file-located diagnostics in output order
 * @param generalMessages messages without a source location ({@code error: ...}, {@code Note: ...})
 * @param reportedErrorCount the count from javac's trailing {@code N errors} line, if present
 * @param reportedWarningCount the count from javac's trailing {@code N warnings} line, if present
 * @param unrecognizedLines non-blank lines that matched no known javac output form
 * @param problems detected inconsistencies or signs of truncated output
 */
public record CompilerOutput(
        List<Diagnostic> diagnostics,
        List<GeneralMessage> generalMessages,
        OptionalInt reportedErrorCount,
        OptionalInt reportedWarningCount,
        List<String> unrecognizedLines,
        List<String> problems) {

    /** A compiler message that is not attached to a source location. */
    public record GeneralMessage(Severity severity, String text) {
        public GeneralMessage {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(text, "text");
        }
    }

    public CompilerOutput {
        diagnostics = List.copyOf(diagnostics);
        generalMessages = List.copyOf(generalMessages);
        Objects.requireNonNull(reportedErrorCount, "reportedErrorCount");
        Objects.requireNonNull(reportedWarningCount, "reportedWarningCount");
        unrecognizedLines = List.copyOf(unrecognizedLines);
        problems = List.copyOf(problems);
    }

    /** Whether the output was fully understood and internally consistent. */
    public boolean isClean() {
        return unrecognizedLines.isEmpty() && problems.isEmpty();
    }

    /**
     * Combines the parse of two streams (for example stderr and stdout) of the same run. Counts are
     * taken from whichever stream reported them; reporting them in both is a problem.
     */
    public CompilerOutput merge(CompilerOutput other) {
        List<Diagnostic> mergedDiagnostics = new ArrayList<>(diagnostics);
        mergedDiagnostics.addAll(other.diagnostics);
        List<GeneralMessage> mergedMessages = new ArrayList<>(generalMessages);
        mergedMessages.addAll(other.generalMessages);
        List<String> mergedUnrecognized = new ArrayList<>(unrecognizedLines);
        mergedUnrecognized.addAll(other.unrecognizedLines);
        List<String> mergedProblems = new ArrayList<>(problems);
        mergedProblems.addAll(other.problems);
        if (reportedErrorCount.isPresent() && other.reportedErrorCount.isPresent()) {
            mergedProblems.add("javac error count was reported in more than one stream");
        }
        if (reportedWarningCount.isPresent() && other.reportedWarningCount.isPresent()) {
            mergedProblems.add("javac warning count was reported in more than one stream");
        }
        return new CompilerOutput(
                mergedDiagnostics,
                mergedMessages,
                reportedErrorCount.isPresent() ? reportedErrorCount : other.reportedErrorCount,
                reportedWarningCount.isPresent() ? reportedWarningCount : other.reportedWarningCount,
                mergedUnrecognized,
                mergedProblems);
    }
}
