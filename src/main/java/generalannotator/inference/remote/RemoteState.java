package generalannotator.inference.remote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What GeneralAnnotator knows about the remote side of one submission ({@code remote/state.json}):
 * where it was uploaded, whether the upload was verified, and every sbatch call with its outcome.
 * The intent of each sbatch call is saved before the call and its job ID immediately after, so an
 * interrupted or disconnected submission is never forgotten and never silently repeated.
 */
final class RemoteState {

    static final int SCHEMA_VERSION = 1;

    /** Upload progress. */
    enum UploadStatus { STARTED, VERIFIED }

    /** Outcome of one sbatch call. */
    enum JobStatus {
        /** Intent saved; sbatch is being (or was being) called. Read back as UNKNOWN by the next command. */
        SUBMITTING,
        /** sbatch returned a job ID, or a verified job ID was attached. */
        SUBMITTED,
        /** It is unknown whether a job exists (connection lost, timeout, unreadable output). */
        UNKNOWN,
        /** sbatch refused the job; nothing was submitted. */
        REJECTED,
        /** An UNKNOWN outcome was resolved: it was verified that no job was submitted. */
        NOT_SUBMITTED
    }

    /** One sbatch call. */
    static final class Job {
        String label;
        String kind; // INITIAL or RETRY
        List<Integer> taskIndices = new ArrayList<>();
        String array;
        String comment;
        JobStatus status;
        String intentAt;
        String jobId;
        String submittedAt;
        String resolvedAt;
        String detail;

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", label);
            m.put("kind", kind);
            m.put("taskIndices", taskIndices);
            m.put("array", array);
            m.put("comment", comment);
            m.put("status", status.name());
            m.put("intentAt", intentAt);
            m.put("jobId", jobId);
            m.put("submittedAt", submittedAt);
            m.put("resolvedAt", resolvedAt);
            m.put("detail", detail);
            return m;
        }

        static Job fromJson(Map<?, ?> m) {
            Job j = new Job();
            j.label = (String) m.get("label");
            j.kind = (String) m.get("kind");
            if (m.get("taskIndices") instanceof List<?> l) {
                for (Object o : l) {
                    j.taskIndices.add(((Number) o).intValue());
                }
            }
            j.array = (String) m.get("array");
            j.comment = (String) m.get("comment");
            j.status = JobStatus.valueOf((String) m.get("status"));
            j.intentAt = (String) m.get("intentAt");
            j.jobId = (String) m.get("jobId");
            j.submittedAt = (String) m.get("submittedAt");
            j.resolvedAt = (String) m.get("resolvedAt");
            j.detail = (String) m.get("detail");
            return j;
        }
    }

    String submissionId;
    String runId;
    String submissionSha256;
    String sshTarget;
    String remoteDir;
    String remotePhysicalDir;
    UploadStatus upload;
    String uploadStartedAt;
    String uploadVerifiedAt;
    final List<Job> jobs = new ArrayList<>();

    List<Job> submitted() {
        return jobs.stream().filter(j -> j.status == JobStatus.SUBMITTED).toList();
    }

    Optional<Job> unresolved() {
        return jobs.stream().filter(j -> j.status == JobStatus.UNKNOWN || j.status == JobStatus.SUBMITTING)
                .reduce((a, b) -> b);
    }

    String nextLabel() {
        return String.format("job-%03d", jobs.size() + 1);
    }

    Map<String, Object> toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("submissionId", submissionId);
        root.put("runId", runId);
        root.put("submissionSha256", submissionSha256);
        root.put("sshTarget", sshTarget);
        root.put("remoteDir", remoteDir);
        root.put("remotePhysicalDir", remotePhysicalDir);
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("status", upload == null ? null : upload.name());
        u.put("startedAt", uploadStartedAt);
        u.put("verifiedAt", uploadVerifiedAt);
        root.put("upload", u);
        root.put("jobs", jobs.stream().map(Job::toJson).toList());
        root.put("note", "Remote state of this submission. Scheduler states describe array tasks, not prompts; "
                + "per-prompt results are in collection.json.");
        return root;
    }

    static RemoteState fromJson(Map<?, ?> root) {
        if (!Long.valueOf(SCHEMA_VERSION).equals(root.get("schemaVersion"))) {
            throw new IllegalArgumentException("unsupported schemaVersion " + root.get("schemaVersion"));
        }
        RemoteState s = new RemoteState();
        s.submissionId = (String) root.get("submissionId");
        s.runId = (String) root.get("runId");
        s.submissionSha256 = (String) root.get("submissionSha256");
        s.sshTarget = (String) root.get("sshTarget");
        s.remoteDir = (String) root.get("remoteDir");
        s.remotePhysicalDir = (String) root.get("remotePhysicalDir");
        if (root.get("upload") instanceof Map<?, ?> u) {
            s.upload = u.get("status") == null ? null : UploadStatus.valueOf((String) u.get("status"));
            s.uploadStartedAt = (String) u.get("startedAt");
            s.uploadVerifiedAt = (String) u.get("verifiedAt");
        }
        if (root.get("jobs") instanceof List<?> l) {
            for (Object o : l) {
                s.jobs.add(Job.fromJson((Map<?, ?>) o));
            }
        }
        return s;
    }
}
