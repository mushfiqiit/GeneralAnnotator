package generalannotator.cli;

/**
 * Process exit codes of the GeneralAnnotator CLI.
 *
 * <p>Exit 1 is deliberately unused: the JVM itself exits with 1 for an uncaught error, so it never
 * means anything about the analysis.
 */
public enum ExitCode {
    /** Analysis completed; the report lists all findings (there may be zero or many). */
    COMPLETED(0),
    /** Invalid command-line usage or configuration; nothing was run. */
    USAGE_OR_CONFIGURATION_ERROR(2),
    /** Execution failure: the analysis did not complete or cannot be trusted; findings are incomplete. */
    EXECUTION_FAILURE(3),
    /** Unexpected internal error in GeneralAnnotator (including failure to write the report). */
    INTERNAL_ERROR(4);

    private final int code;

    ExitCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
