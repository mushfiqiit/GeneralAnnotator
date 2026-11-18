package generalannotator.context;

/** Outcome of context extraction for one warning. */
public enum ContextStatus {
    /** The target member was found and its usages were collected. */
    EXTRACTED,
    /** The warning has no resolved target in {@code warning-targets.json}; only the warning site is described. */
    SKIPPED,
    /** The target could not be located in the attributed snapshot. */
    FAILED
}
