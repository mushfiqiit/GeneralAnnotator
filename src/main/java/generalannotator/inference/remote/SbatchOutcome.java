package generalannotator.inference.remote;

/** What happened to one {@code sbatch} call. */
public sealed interface SbatchOutcome {

    /** sbatch accepted the job and printed its ID. */
    record Submitted(String jobId, String output) implements SbatchOutcome {}

    /** sbatch ran and refused the job (for example an invalid account or a limit); nothing was submitted. */
    record Rejected(String reason) implements SbatchOutcome {}

    /** The remote marker for this label already exists: this label was submitted before. */
    record Duplicate(String record) implements SbatchOutcome {}

    /** The outcome is unknown (connection lost, timeout, unreadable output); a job may exist. */
    record Unknown(String reason) implements SbatchOutcome {}
}
