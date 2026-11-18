package generalannotator.slicing;

/** Outcome of slicing one warning. */
public enum SliceStatus {
    /** The slicer exited successfully and wrote at least one Java file. Reproduction is not validated. */
    GENERATED,
    /** The slicer could not be run, failed, timed out, or produced no Java output. */
    FAILED,
    /** The warning's target is unresolved, so the slicer was not run. */
    SKIPPED
}
