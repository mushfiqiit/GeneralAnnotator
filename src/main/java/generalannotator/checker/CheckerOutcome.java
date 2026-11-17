package generalannotator.checker;

import generalannotator.diagnostics.Diagnostic;
import java.util.List;
import java.util.Objects;

/**
 * Result of one checker run: either a completed analysis or an execution failure. The two are
 * distinct types so a failure can never be mistaken for a clean analysis.
 */
public sealed interface CheckerOutcome {

    /** Checker findings observed in the run. Complete only for {@link CompletedAnalysis}. */
    List<Diagnostic> findings();

    /** Diagnostics from the same run that are not checker findings (compiler errors, lint). */
    List<Diagnostic> otherDiagnostics();

    /**
     * The checker ran over all requested sources and its output was fully understood. A non-zero
     * compiler exit caused only by checker findings still counts as completed.
     */
    record CompletedAnalysis(List<Diagnostic> findings, List<Diagnostic> otherDiagnostics)
            implements CheckerOutcome {
        public CompletedAnalysis {
            findings = List.copyOf(findings);
            otherDiagnostics = List.copyOf(otherDiagnostics);
        }
    }

    /**
     * The run did not produce a trustworthy, complete analysis. Any findings observed before the
     * failure are kept but are incomplete.
     *
     * @param kind the primary failure kind
     * @param reasons every detected reason, most important first
     */
    record ExecutionFailure(
            FailureKind kind, List<String> reasons, List<Diagnostic> findings, List<Diagnostic> otherDiagnostics)
            implements CheckerOutcome {
        public ExecutionFailure {
            Objects.requireNonNull(kind, "kind");
            reasons = List.copyOf(reasons);
            if (reasons.isEmpty()) {
                throw new IllegalArgumentException("a failure needs at least one reason");
            }
            findings = List.copyOf(findings);
            otherDiagnostics = List.copyOf(otherDiagnostics);
        }

        /** A failure before any checker output existed. */
        public static ExecutionFailure before(FailureKind kind, String reason) {
            return new ExecutionFailure(kind, List.of(reason), List.of(), List.of());
        }
    }
}
