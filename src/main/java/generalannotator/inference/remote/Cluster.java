package generalannotator.inference.remote;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The cluster operations the inference stage needs. {@link SshCluster} implements them over one
 * shared OpenSSH connection; tests use fakes. No operation prompts for authentication, executes
 * prompt contents, or deletes remote files.
 */
public interface Cluster {

    /** Whether the shared connection is usable now, checked without prompting. */
    ConnectionCheck check();

    /** What exists at {@code remoteDir}. */
    DirectoryProbe probe(String remoteDir) throws RemoteException;

    /** Creates {@code remoteDir} and {@code remoteDir/logs}; returns the directory's physical path. */
    String createDirectory(String remoteDir) throws RemoteException;

    /** Copies {@code files} (relative to {@code localDir}) into {@code remoteDir}; never deletes anything. */
    void upload(Path localDir, List<String> files, String remoteDir) throws RemoteException;

    /** SHA-256 of each of {@code files} in {@code remoteDir}; missing files are absent from the map. */
    Map<String, String> hashes(String remoteDir, List<String> files) throws RemoteException;

    /**
     * Submits {@code run_inference.sbatch} in {@code remoteDir} as one array job, guarded by the
     * remote marker {@code .ga-sbatch/<label>} so the same label is never submitted twice. Never
     * throws: a lost connection or unreadable output is {@link SbatchOutcome.Unknown}.
     */
    SbatchOutcome sbatch(String remoteDir, String label, String arraySpec, String comment);

    /** Scheduler state of the array tasks of {@code jobIds} (squeue, then sacct). Never throws. */
    SchedulerSnapshot query(List<String> jobIds);

    /** Scheduler records (sacct and squeue) of one job, for verifying a job ID. */
    List<JobRecord> describe(String jobId) throws RemoteException;

    /** The user's jobs named {@code jobName} since {@code since} (sacct). */
    List<JobRecord> findJobs(String jobName, LocalDate since) throws RemoteException;

    /** The remote record of the sbatch call made for {@code label}, if the marker exists. */
    Optional<String> sbatchRecord(String remoteDir, String label) throws RemoteException;

    /**
     * Copies {@code remoteDir}'s {@code results/}, {@code logs/} and {@code .ga-sbatch/} into {@code
     * incoming} without deleting anything on either side. Files that are still being written
     * atomically (temporary names) are skipped.
     */
    void download(String remoteDir, Path incoming) throws RemoteException;

    /** Result of {@link #check()}. */
    record ConnectionCheck(boolean alive, String detail) {}

    /** Result of {@link #probe(String)}. */
    record DirectoryProbe(Kind kind, Optional<String> submissionSha256) {
        /** What exists at the path. */
        public enum Kind { ABSENT, EMPTY, HAS_SUBMISSION, OTHER }
    }

    /** One scheduler record: {@code jobId} is the full ID such as {@code 123_4}. */
    record JobRecord(String jobId, String jobName, String workDir, String state, String source) {}
}
