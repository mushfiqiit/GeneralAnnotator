package generalannotator.evaluation;

/**
 * The outcome of evaluating one annotation package against the baseline. Only {@link #COMPLETED} and
 * {@link #AMBIGUOUS} come from a trustworthy comparison and carry counts; every other outcome means
 * there is no evidence about warnings (and never "zero warnings").
 */
public enum AttemptOutcome {
    /** The edit was applied, the analysis completed and every finding was matched or categorized. */
    COMPLETED,
    /** As COMPLETED, but some findings could not be matched; they support no claim. */
    AMBIGUOUS,
    /** The edit changed nothing (the annotation was already present or absent); no checker run. */
    NO_OP,
    /** The applier rejected the edit, or the edit would suppress or disable checking; nothing was run. */
    REJECTED,
    /** The edited source did not compile for reasons other than checker findings. */
    COMPILATION_FAILED,
    /** The checker or compiler exceeded its timeout. */
    TIMED_OUT,
    /** The checker or compiler crashed. */
    CRASHED,
    /** Another execution failure (setup, missing tool, untrustworthy output, ...). */
    EXECUTION_FAILED,
    /** The analysis completed, but with a different checker build or scope than the baseline. */
    NOT_COMPARABLE,
    /** Graph only: an earlier attempt was interrupted and has not been rerun. */
    INTERRUPTED,
    /** Graph only: the candidate has not been evaluated. */
    UNEVALUATED;

    /** Whether the outcome is based on a trustworthy comparison. */
    public boolean compared() {
        return this == COMPLETED || this == AMBIGUOUS;
    }
}
