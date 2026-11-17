package generalannotator.checker;

/**
 * A type checker that can analyze an isolated working copy.
 *
 * <p>Implementations must not modify anything outside the request's working copy and run
 * directory (building the checker in its own checkout is the exception and must be documented),
 * and must report setup, execution and output problems as {@link
 * CheckerOutcome.ExecutionFailure}, never as a clean analysis.
 */
public interface Checker {

    /** Stable identifier, for example {@code nullaway}. */
    String id();

    CheckerRun analyze(AnalysisRequest request);
}
