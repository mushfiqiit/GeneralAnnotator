package generalannotator.checker;

/** Why a checker run did not produce a trustworthy, complete set of findings. */
public enum FailureKind {
    /** The isolated working copy of the target could not be created; the checker never ran. */
    ISOLATION_FAILED,
    /** The checker could not be prepared (for example its build failed or its layout is wrong). */
    SETUP_FAILED,
    /** There was nothing to analyze (for example no source files under the source roots). */
    NO_SOURCES,
    /** An external process could not be started. */
    START_FAILED,
    /** An external process exceeded its timeout and was killed. */
    TIMED_OUT,
    /** The compiler or checker crashed. */
    CRASHED,
    /** The compiler rejected its command line. */
    INVALID_INVOCATION,
    /** The target did not compile for reasons other than checker findings. */
    COMPILATION_FAILED,
    /** The run produced no evidence that the checker was actually active. */
    CHECKER_NOT_ACTIVE,
    /** The output could not be fully understood or was internally inconsistent. */
    UNTRUSTWORTHY_OUTPUT
}
