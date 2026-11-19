package generalannotator.inference.remote;

import generalannotator.inference.SubmissionPreparer;
import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Upload, submission, monitoring and collection of one prepared submission on the cluster, through
 * a {@link Cluster}. All local bookkeeping is in the submission's {@code remote/} directory:
 * <ul>
 *   <li>{@code state.json}: remote directory, upload verification, and every sbatch call (intent saved
 *       before the call, job ID saved immediately after);
 *   <li>{@code incoming/}: a mirror of the remote {@code results/}, {@code logs/} and {@code .ga-sbatch/};
 *   <li>{@code collected/}: the verified local collection, merged without losing completed results;
 *   <li>{@code collection.json} and {@code status.json}: per-prompt results and scheduler states, kept
 *       apart.
 * </ul>
 * Nothing here authenticates: every operation needs a live shared connection and reports {@link
 * Outcome#RECONNECT} without one. Nothing is deleted remotely, prompts are never executed, a
 * submission with an unknown sbatch outcome is never resubmitted automatically, and jobs are never
 * cancelled.
 */
public final class RemoteInference {

    /** How an operation ended. */
    public enum Outcome {
        /** Done; for {@code run}, every prompt has a completed result. */
        DONE,
        /** Refused or incomplete: failures, unknown states, verification problems; see the message. */
        INCOMPLETE,
        /** The shared SSH connection is not available; reconnect interactively and rerun. Progress is kept. */
        RECONNECT,
        /** Invalid submission or settings; nothing was done. */
        INVALID
    }

    /** Waits between polls; replaced in tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** Failures that cannot be retried by running the same prompt again. */
    static final Set<String> NOT_RETRYABLE = Set.of("PROMPT_TOO_LONG", "PROMPT_HASH_MISMATCH", "PROMPT_MISSING",
            "UNSUPPORTED_TEMPLATE", "INVALID_SUBMISSION");
    static final String COLLECTION = "collection.json";
    static final String STATUS = "status.json";

    private final Path submissionDir;
    private final RemoteSettings settings;
    private final Cluster cluster;
    private final Clock clock;
    private final Sleeper sleeper;
    private final PrintStream out;
    private final StateStore store;

    private LocalSubmission local;
    private RemoteState state;

    public RemoteInference(Path submissionDir, RemoteSettings settings, Cluster cluster, Clock clock, Sleeper sleeper,
            PrintStream out) {
        this.submissionDir = submissionDir;
        this.settings = settings;
        this.cluster = cluster;
        this.clock = clock;
        this.sleeper = sleeper;
        this.out = out;
        this.store = new StateStore(submissionDir, clock);
    }

    /** Signals the end of an operation with an outcome and a message. */
    private static final class Stop extends Exception {
        private static final long serialVersionUID = 1L;
        final Outcome outcome;

        Stop(Outcome outcome, String message) {
            super(message);
            this.outcome = outcome;
        }
    }

    @FunctionalInterface
    private interface Body {
        Outcome run() throws Stop, IOException;
    }

    // ------------------------------------------------------------------ operations

    public Outcome upload() {
        return locked(this::doUpload);
    }

    public Outcome submit() {
        return locked(() -> {
            requireConnection();
            return doSubmit();
        });
    }

    public Outcome attach(String jobId) {
        return locked(() -> {
            requireConnection();
            return doAttach(jobId);
        });
    }

    public Outcome attachNone() {
        return locked(() -> {
            requireConnection();
            return doAttachNone();
        });
    }

    public Outcome collect() {
        return locked(() -> {
            requireConnection();
            Collected c = doCollect();
            printCollection(c);
            return c.clean() ? Outcome.DONE : Outcome.INCOMPLETE;
        });
    }

    public Outcome retry() {
        return locked(() -> {
            requireConnection();
            return doRetry();
        });
    }

    /** Scheduler state of every array task, without taking the lock (it only reads state). */
    public Outcome status() {
        return guarded(() -> {
            open(false);
            if (state.remoteDir == null || state.upload == null) {
                out.println("Not uploaded yet: nothing to query.");
                return Outcome.DONE;
            }
            requireConnection();
            boolean unknown = printJobs();
            Tasks tasks = taskStates();
            printTasks(tasks, true);
            loadCollection().ifPresent(c -> out.println("Prompts (last collection " + c.get("collectedAt") + "): "
                    + summaryLine(c.get("summary"))));
            return unknown || tasks.unknown() > 0 ? Outcome.INCOMPLETE : Outcome.DONE;
        });
    }

    /**
     * End to end: upload (if not verified), submit (if never submitted), then poll the scheduler and
     * collect until every array task is finished. Interrupting stops only local monitoring; remote
     * jobs keep running and rerunning resumes. Failed prompts are not retried automatically.
     */
    public Outcome run() {
        return locked(() -> {
            requireConnection();
            if (state.upload != RemoteState.UploadStatus.VERIFIED) {
                Outcome o = doUpload();
                if (o != Outcome.DONE) {
                    return o;
                }
            }
            if (state.submitted().isEmpty()) {
                Outcome o = doSubmit();
                if (o != Outcome.DONE) {
                    return o;
                }
            } else {
                out.println("Already submitted (" + jobList() + "); resuming monitoring. Nothing is resubmitted.");
            }
            return monitor();
        });
    }

    // ------------------------------------------------------------------ setup

    @SuppressWarnings("try")
    private Outcome locked(Body body) {
        return guarded(() -> {
            try (StateStore.Lock lock = store.lock()) {
                open(true);
                return body.run();
            } catch (StateStore.BusyException e) {
                throw new Stop(Outcome.INCOMPLETE, e.getMessage());
            }
        });
    }

    private Outcome guarded(Body body) {
        try {
            return body.run();
        } catch (Stop s) {
            out.println(s.getMessage());
            return s.outcome;
        } catch (IOException e) {
            out.println("Local I/O error: " + e.getMessage() + ". Local state is unchanged after the last completed "
                    + "step.");
            return Outcome.INCOMPLETE;
        }
    }

    /** Loads the submission and state; with the lock held, an interrupted sbatch intent becomes UNKNOWN. */
    private void open(boolean locked) throws Stop, IOException {
        try {
            local = LocalSubmission.load(submissionDir);
        } catch (LocalSubmission.InvalidException e) {
            throw new Stop(Outcome.INVALID, e.getMessage());
        }
        String remoteDir = settings.remoteDirectory(local.runId(), local.submissionId());
        Optional<RemoteState> loaded = store.load();
        if (loaded.isEmpty()) {
            state = new RemoteState();
            state.submissionId = local.submissionId();
            state.runId = local.runId();
            state.submissionSha256 = local.sha256();
            state.sshTarget = settings.sshTarget();
            state.remoteDir = remoteDir;
            return;
        }
        state = loaded.get();
        if (!local.sha256().equals(state.submissionSha256) || !local.submissionId().equals(state.submissionId)) {
            throw new Stop(Outcome.INVALID, "remote/state.json belongs to another submission.json; refusing");
        }
        if (!state.remoteDir.equals(remoteDir) || !state.sshTarget.equals(settings.sshTarget())) {
            throw new Stop(Outcome.INVALID, "This submission belongs to " + state.sshTarget + ":" + state.remoteDir
                    + ", but the settings give " + settings.sshTarget() + ":" + remoteDir + ". Restore GA_SSH_TARGET, "
                    + "GA_REMOTE_BASE and GA_REMOTE_SUBDIR, or prepare a new submission.");
        }
        if (!locked) {
            return;
        }
        boolean changed = false;
        for (RemoteState.Job j : state.jobs) {
            if (j.status == RemoteState.JobStatus.SUBMITTING) {
                // We hold the lock, so the process that saved this intent is gone.
                j.status = RemoteState.JobStatus.UNKNOWN;
                j.detail = "interrupted during sbatch; whether a job exists is unknown";
                changed = true;
            }
        }
        if (changed) {
            store.save(state);
            store.event("an interrupted sbatch call was marked UNKNOWN");
        }
    }

    private void requireConnection() throws Stop {
        Cluster.ConnectionCheck c = cluster.check();
        if (!c.alive()) {
            throw new Stop(Outcome.RECONNECT, "The shared SSH connection to " + settings.sshTarget()
                    + " is not available (" + c.detail() + "). Progress is saved. Reconnect with `inference "
                    + "connect` (one interactive login), then rerun this command.");
        }
    }

    private Stop remote(RemoteException e, String what) {
        return switch (e.kind()) {
            case DISCONNECTED -> new Stop(Outcome.RECONNECT, what + ": the SSH connection was lost (" + e.getMessage()
                    + "). Progress is saved; reconnect with `inference connect` and rerun.");
            case TIMED_OUT, FAILED -> new Stop(Outcome.INCOMPLETE, what + " failed: " + e.getMessage());
        };
    }

    private String now() {
        return clock.instant().toString();
    }

    // ------------------------------------------------------------------ upload

    private List<String> files() {
        return new ArrayList<>(local.fileHashes().keySet());
    }

    private Outcome doUpload() throws Stop, IOException {
        requireConnection();
        if (state.upload == RemoteState.UploadStatus.VERIFIED) {
            List<String> problems = verifyRemote();
            if (problems.isEmpty()) {
                out.println("Already uploaded to " + state.remoteDir + "; all " + local.fileHashes().size()
                        + " files verified again.");
                return Outcome.DONE;
            }
            if (!state.jobs.isEmpty()) {
                throw new Stop(Outcome.INCOMPLETE, "Remote files changed after upload and sbatch was already called; "
                        + "refusing to replace inputs of submitted jobs: " + String.join("; ", problems));
            }
            out.println("Remote files changed since the last verification; uploading again: "
                    + String.join("; ", problems));
        }
        Cluster.DirectoryProbe probe;
        try {
            probe = cluster.probe(state.remoteDir);
        } catch (RemoteException e) {
            throw remote(e, "Checking " + state.remoteDir);
        }
        switch (probe.kind()) {
            case HAS_SUBMISSION -> {
                if (!probe.submissionSha256().orElse("").equals(local.sha256())) {
                    throw new Stop(Outcome.INCOMPLETE, state.remoteDir + " already holds a different submission.json;"
                            + " refusing to write into it.");
                }
                out.println(state.remoteDir + " already holds this submission; completing the upload.");
            }
            case OTHER -> {
                if (state.upload == null) {
                    throw new Stop(Outcome.INCOMPLETE, state.remoteDir + " exists and is not empty, and this "
                            + "submission did not create it; refusing to write into it.");
                }
            }
            case ABSENT, EMPTY -> { }
        }
        if (state.upload == null) {
            state.upload = RemoteState.UploadStatus.STARTED;
            state.uploadStartedAt = now();
            store.save(state);
            store.event("upload started to " + state.sshTarget + ":" + state.remoteDir);
        }
        try {
            state.remotePhysicalDir = cluster.createDirectory(state.remoteDir);
            store.save(state);
            // submission.json first: a partly uploaded directory is then recognizable as this submission.
            List<String> files = files();
            cluster.upload(submissionDir, files.subList(0, 1), state.remoteDir);
            cluster.upload(submissionDir, files.subList(1, files.size()), state.remoteDir);
        } catch (RemoteException e) {
            throw remote(e, "Upload");
        }
        List<String> problems = verifyRemote();
        if (!problems.isEmpty()) {
            store.event("upload verification failed: " + String.join("; ", problems));
            throw new Stop(Outcome.INCOMPLETE, "Upload verification failed (nothing was submitted): "
                    + String.join("; ", problems));
        }
        state.upload = RemoteState.UploadStatus.VERIFIED;
        state.uploadVerifiedAt = now();
        store.save(state);
        store.event("upload verified: " + local.fileHashes().size() + " files");
        out.println("Uploaded " + local.fileHashes().size() + " files (" + local.prompts().size() + " prompts, "
                + "settings, batch script, worker) to " + settings.sshTarget() + ":" + state.remoteDir
                + "; every SHA-256 verified on the cluster.");
        return Outcome.DONE;
    }

    /** Problems found comparing the remote files with the expected hashes (empty if all match). */
    private List<String> verifyRemote() throws Stop {
        Map<String, String> remote;
        try {
            remote = cluster.hashes(state.remoteDir, files());
        } catch (RemoteException e) {
            throw remote(e, "Verifying remote hashes");
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : local.fileHashes().entrySet()) {
            String actual = remote.get(e.getKey());
            if (actual == null) {
                problems.add(e.getKey() + " is missing remotely");
            } else if (!actual.equals(e.getValue())) {
                problems.add(e.getKey() + " has SHA-256 " + actual + " remotely, expected " + e.getValue());
            }
        }
        return problems;
    }

    // ------------------------------------------------------------------ submit

    private Outcome doSubmit() throws Stop, IOException {
        if (!state.submitted().isEmpty()) {
            out.println("Already submitted (" + jobList() + "); not submitting again. Use `inference status`, "
                    + "`inference collect` or `inference retry`.");
            return Outcome.DONE;
        }
        refuseUnresolved();
        if (state.upload != RemoteState.UploadStatus.VERIFIED) {
            throw new Stop(Outcome.INCOMPLETE, "Not uploaded and verified yet; run `inference upload` first.");
        }
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < local.taskCount(); i++) {
            all.add(i);
        }
        return submitTasks("INITIAL", all);
    }

    private void refuseUnresolved() throws Stop {
        Optional<RemoteState.Job> unresolved = state.unresolved();
        if (unresolved.isPresent()) {
            RemoteState.Job j = unresolved.get();
            throw new Stop(Outcome.INCOMPLETE, "The outcome of sbatch call " + j.label + " (" + j.intentAt + ") is "
                    + "UNKNOWN: " + j.detail + ". It is not resubmitted automatically. Check the cluster (for example "
                    + "squeue -u $USER) and then either attach the job:\n  inference attach " + submissionDir
                    + " <job id>\nor, if no job was created:\n  inference attach " + submissionDir
                    + " --not-submitted\n" + candidates(j));
        }
    }

    /** Jobs of this submission's directory that the scheduler knows, as a hint (best effort). */
    private String candidates(RemoteState.Job j) {
        try {
            LocalDate since = LocalDate.ofInstant(Instant.parse(j.intentAt), ZoneOffset.UTC).minusDays(1);
            List<String> ids = new ArrayList<>();
            for (Cluster.JobRecord r : cluster.findJobs(local.jobName(), since)) {
                if (inThisDirectory(r) && !knownJob(baseId(r.jobId()))) {
                    ids.add(baseId(r.jobId()));
                }
            }
            Optional<String> record = cluster.sbatchRecord(state.remoteDir, j.label);
            String recorded = record.map(r -> "Remote record of " + j.label + ": " + r.replace('\n', ' '))
                    .orElse("No remote record of " + j.label + " exists (sbatch was not reached).");
            return recorded + "\nScheduler jobs in this submission's directory not yet attached: "
                    + (ids.isEmpty() ? "none" : String.join(", ", new LinkedHashSet<>(ids)));
        } catch (RemoteException | RuntimeException e) {
            return "(Could not list candidate jobs: " + e.getMessage() + ")";
        }
    }

    private Outcome submitTasks(String kind, List<Integer> taskIndices) throws Stop, IOException {
        int largest = taskIndices.get(taskIndices.size() - 1);
        if (largest >= settings.maxArraySize()) {
            throw new Stop(Outcome.INCOMPLETE, "Array index " + largest + " exceeds MaxArraySize "
                    + settings.maxArraySize() + " (GA_SLURM_MAX_ARRAY_SIZE).");
        }
        List<String> problems = verifyRemote();
        if (!problems.isEmpty()) {
            throw new Stop(Outcome.INCOMPLETE, "Remote files do not match submission.json; not submitting: "
                    + String.join("; ", problems));
        }
        RemoteState.Job job = new RemoteState.Job();
        job.label = state.nextLabel();
        job.kind = kind;
        job.taskIndices.addAll(taskIndices);
        job.array = arraySpec(taskIndices) + "%" + local.arrayConcurrency();
        job.comment = "GeneralAnnotator:" + local.submissionId() + ":" + job.label;
        job.status = RemoteState.JobStatus.SUBMITTING;
        job.intentAt = now();
        state.jobs.add(job);
        store.save(state); // intent first
        store.event("sbatch " + job.label + " (" + kind + ", array " + job.array + ") intent saved");
        SbatchOutcome outcome = cluster.sbatch(state.remoteDir, job.label, job.array, job.comment);
        switch (outcome) {
            case SbatchOutcome.Submitted s -> {
                job.status = RemoteState.JobStatus.SUBMITTED;
                job.jobId = s.jobId();
                job.submittedAt = now();
                store.save(state); // job ID immediately
                store.event("sbatch " + job.label + " submitted job " + s.jobId());
                out.println("Submitted array job " + s.jobId() + " (" + job.label + ", " + kind.toLowerCase()
                        + "): " + taskIndices.size() + " task(s), --array=" + job.array + ", from "
                        + state.remoteDir + ".");
                return Outcome.DONE;
            }
            case SbatchOutcome.Rejected r -> {
                job.status = RemoteState.JobStatus.REJECTED;
                job.detail = r.reason();
                job.resolvedAt = now();
                store.save(state);
                store.event("sbatch " + job.label + " rejected: " + r.reason());
                throw new Stop(Outcome.INCOMPLETE, "sbatch refused the job; nothing was submitted: " + r.reason());
            }
            case SbatchOutcome.Duplicate d -> {
                job.status = RemoteState.JobStatus.UNKNOWN;
                job.detail = "the remote marker .ga-sbatch/" + job.label + " already existed (another process or "
                        + "an earlier, unrecorded call): " + d.record().replace('\n', ' ');
                store.save(state);
                store.event("sbatch " + job.label + " not run: marker exists");
                refuseUnresolved();
                throw new IllegalStateException("unreachable");
            }
            case SbatchOutcome.Unknown u -> {
                job.status = RemoteState.JobStatus.UNKNOWN;
                job.detail = u.reason();
                store.save(state);
                store.event("sbatch " + job.label + " outcome UNKNOWN: " + u.reason());
                throw new Stop(Outcome.INCOMPLETE, "The outcome of sbatch is UNKNOWN (" + u.reason() + "). A job may "
                        + "exist; it will not be resubmitted automatically. After reconnecting if needed, check with "
                        + "`inference status " + submissionDir + "`, then `inference attach " + submissionDir
                        + " <job id>` or `--not-submitted`.");
            }
        }
    }

    /** {@code 0-40} or {@code 1,3-5}: the indices in increasing order, consecutive runs as ranges. */
    static String arraySpec(List<Integer> indices) {
        List<Integer> sorted = new ArrayList<>(new TreeSet<>(indices));
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < sorted.size();) {
            int j = i;
            while (j + 1 < sorted.size() && sorted.get(j + 1) == sorted.get(j) + 1) {
                j++;
            }
            parts.add(i == j ? String.valueOf(sorted.get(i)) : sorted.get(i) + "-" + sorted.get(j));
            i = j + 1;
        }
        return String.join(",", parts);
    }

    // ------------------------------------------------------------------ attach

    private Outcome doAttach(String jobId) throws Stop, IOException {
        if (!jobId.matches("[0-9]+")) {
            throw new Stop(Outcome.INVALID, "'" + jobId + "' is not a Slurm job ID (digits only, without _task).");
        }
        RemoteState.Job job = state.unresolved().orElseThrow(() -> new Stop(Outcome.INVALID,
                "No sbatch call of this submission has an unknown outcome; there is nothing to attach."));
        if (knownJob(jobId)) {
            throw new Stop(Outcome.INVALID, "Job " + jobId + " is already recorded for this submission.");
        }
        List<Cluster.JobRecord> records;
        try {
            records = cluster.describe(jobId).stream()
                    .filter(r -> r.jobId().equals(jobId) || r.jobId().startsWith(jobId + "_")).toList();
        } catch (RemoteException e) {
            throw remote(e, "Looking up job " + jobId);
        }
        if (records.isEmpty()) {
            throw new Stop(Outcome.INCOMPLETE, "The scheduler has no record of job " + jobId + "; it cannot be "
                    + "verified, so it is not attached.");
        }
        for (Cluster.JobRecord r : records) {
            if (!inThisDirectory(r) || !r.jobName().equals(local.jobName())) {
                throw new Stop(Outcome.INCOMPLETE, "Job " + r.jobId() + " is '" + r.jobName() + "' in " + r.workDir()
                        + ", not '" + local.jobName() + "' in " + state.remoteDir + "; not attached.");
            }
            Optional<SchedulerSnapshot.Row> range = SchedulerSnapshot.rows(r.jobId(), r.state(), "",
                    SchedulerSnapshot.Source.SACCT).stream().findFirst();
            if (range.isPresent()) {
                for (int i = range.get().from(); i <= range.get().to(); i++) {
                    if (!job.taskIndices.contains(i)) {
                        throw new Stop(Outcome.INCOMPLETE, "Job " + jobId + " has array task " + i + ", which "
                                + job.label + " did not request (" + job.array + "); not attached.");
                    }
                }
            }
        }
        job.status = RemoteState.JobStatus.SUBMITTED;
        job.jobId = jobId;
        job.resolvedAt = now();
        job.detail = "attached after verifying job name and working directory with the scheduler";
        store.save(state);
        store.event("job " + jobId + " attached to " + job.label);
        out.println("Attached job " + jobId + " to " + job.label + " (verified: name " + local.jobName()
                + ", directory " + state.remoteDir + ").");
        return Outcome.DONE;
    }

    private Outcome doAttachNone() throws Stop, IOException {
        RemoteState.Job job = state.unresolved().orElseThrow(() -> new Stop(Outcome.INVALID,
                "No sbatch call of this submission has an unknown outcome; there is nothing to resolve."));
        Optional<String> record;
        List<String> found = new ArrayList<>();
        try {
            record = cluster.sbatchRecord(state.remoteDir, job.label);
            if (record.isPresent()) {
                LocalDate since = LocalDate.ofInstant(Instant.parse(job.intentAt), ZoneOffset.UTC).minusDays(1);
                for (Cluster.JobRecord r : cluster.findJobs(local.jobName(), since)) {
                    if (inThisDirectory(r) && !knownJob(baseId(r.jobId()))) {
                        found.add(baseId(r.jobId()));
                    }
                }
            }
        } catch (RemoteException e) {
            throw remote(e, "Verifying that no job exists");
        }
        if (record.isPresent()) {
            String r = record.get();
            boolean rejected = r.lines().anyMatch(l -> l.matches("exit: [1-9][0-9]*"));
            boolean printedId = r.lines().anyMatch(l -> l.matches("stdout: [0-9]+(;.*)?"));
            if (printedId || !rejected || !found.isEmpty()) {
                throw new Stop(Outcome.INCOMPLETE, "Cannot confirm that no job exists: remote record of " + job.label
                        + ": " + r.replace('\n', ' ') + "; scheduler jobs in this directory: "
                        + (found.isEmpty() ? "none" : String.join(", ", new LinkedHashSet<>(found)))
                        + ". Attach the job instead if one exists.");
            }
        }
        job.status = RemoteState.JobStatus.NOT_SUBMITTED;
        job.resolvedAt = now();
        job.detail = record.isEmpty() ? "verified: the remote marker was never created, so sbatch was not reached"
                : "verified: sbatch exited with an error and the scheduler has no job in this directory";
        store.save(state);
        store.event(job.label + " resolved as not submitted");
        out.println(job.label + " resolved as not submitted (" + job.detail + "). It can be submitted again.");
        return Outcome.DONE;
    }

    private boolean inThisDirectory(Cluster.JobRecord r) {
        return r.workDir().equals(state.remoteDir) || r.workDir().equals(state.remotePhysicalDir);
    }

    private boolean knownJob(String jobId) {
        return state.jobs.stream().anyMatch(j -> jobId.equals(j.jobId));
    }

    private static String baseId(String jobId) {
        int u = jobId.indexOf('_');
        return u < 0 ? jobId : jobId.substring(0, u);
    }

    private String jobList() {
        List<String> parts = new ArrayList<>();
        for (RemoteState.Job j : state.submitted()) {
            parts.add("job " + j.jobId + " [" + j.label + ", " + j.kind.toLowerCase() + ", array " + j.array + "]");
        }
        return String.join(", ", parts);
    }

    // ------------------------------------------------------------------ status

    /** Per-task scheduler state: the latest submitted job that includes the task. */
    record Tasks(Map<Integer, String> jobOf, Map<Integer, SchedulerSnapshot.TaskState> states, List<String> problems) {
        long count(SchedulerSnapshot.Category c) {
            return states.values().stream().filter(s -> s.category() == c).count();
        }

        long unknown() {
            return count(SchedulerSnapshot.Category.UNKNOWN);
        }

        boolean allTerminal() {
            return !states.isEmpty() && count(SchedulerSnapshot.Category.TERMINAL) == states.size();
        }
    }

    private Tasks taskStates() throws IOException {
        List<RemoteState.Job> jobs = state.submitted();
        SchedulerSnapshot snapshot = cluster.query(jobs.stream().map(j -> j.jobId).distinct().toList());
        Map<Integer, String> jobOf = new TreeMap<>();
        Map<Integer, SchedulerSnapshot.TaskState> states = new TreeMap<>();
        for (RemoteState.Job j : jobs) {
            for (int t : j.taskIndices) {
                jobOf.put(t, j.jobId);
                states.put(t, snapshot.state(j.jobId, t));
            }
        }
        Tasks tasks = new Tasks(jobOf, states, snapshot.problems());
        writeStatus(tasks);
        return tasks;
    }

    /** Whether any job still needs attention, after printing the sbatch calls. */
    private boolean printJobs() {
        boolean unresolved = false;
        for (RemoteState.Job j : state.jobs) {
            out.println("  " + j.label + " (" + j.kind.toLowerCase() + ", array " + j.array + "): " + j.status
                    + (j.jobId == null ? "" : " job " + j.jobId) + (j.detail == null ? "" : " — " + j.detail));
            unresolved |= j.status == RemoteState.JobStatus.UNKNOWN || j.status == RemoteState.JobStatus.SUBMITTING;
        }
        if (state.jobs.isEmpty()) {
            out.println("  not submitted yet");
        }
        return unresolved;
    }

    private void printTasks(Tasks tasks, boolean detailed) {
        Map<String, Integer> counts = new TreeMap<>();
        tasks.states().values().forEach(s -> counts.merge(s.state(), 1, Integer::sum));
        out.println("Scheduler (array tasks, not prompts): " + (counts.isEmpty() ? "no submitted tasks" : counts));
        if (detailed) {
            for (Map.Entry<Integer, SchedulerSnapshot.TaskState> e : tasks.states().entrySet()) {
                int t = e.getKey();
                out.println(String.format("  task %d  job %s_%d  %-12s %s  [%s]", t, tasks.jobOf().get(t), t,
                        e.getValue().state(), e.getValue().detail(), String.join(", ", local.tasks().get(t))));
            }
        }
        tasks.problems().forEach(p -> out.println("  scheduler: " + p));
    }

    private void writeStatus(Tasks tasks) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("submissionId", local.submissionId());
        root.put("queriedAt", now());
        List<Object> list = new ArrayList<>();
        for (Map.Entry<Integer, SchedulerSnapshot.TaskState> e : tasks.states().entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskIndex", e.getKey());
            m.put("jobId", tasks.jobOf().get(e.getKey()));
            m.put("state", e.getValue().state());
            m.put("category", e.getValue().category().name());
            m.put("detail", e.getValue().detail());
            m.put("warningIds", local.tasks().get(e.getKey()));
            list.add(m);
        }
        root.put("tasks", list);
        root.put("problems", tasks.problems());
        root.put("note", "Scheduler states of array tasks. They say nothing about whether a prompt got a valid "
                + "response; see collection.json.");
        StateStore.writeAtomically(store.dir().resolve(STATUS), Json.write(root));
    }

    // ------------------------------------------------------------------ collect

    /** A finished collection. */
    record Collected(ResultCollector.Merge merge, List<ResultCollector.PromptResult> prompts, List<String> unexpected) {
        boolean clean() {
            return merge.conflicts().isEmpty() && unexpected.isEmpty()
                    && prompts.stream().noneMatch(p -> p.status() == ResultCollector.PromptStatus.INVALID_RESULT);
        }

        Map<ResultCollector.PromptStatus, Integer> counts() {
            Map<ResultCollector.PromptStatus, Integer> c = new EnumMap<>(ResultCollector.PromptStatus.class);
            prompts.forEach(p -> c.merge(p.status(), 1, Integer::sum));
            return c;
        }
    }

    private Collected doCollect() throws Stop, IOException {
        if (state.upload == null) {
            throw new Stop(Outcome.INVALID, "Nothing was uploaded for this submission; there is nothing to collect.");
        }
        Path incoming = store.dir().resolve("incoming");
        Path collected = store.dir().resolve("collected");
        try {
            Map<String, String> remote = cluster.hashes(state.remoteDir, List.of(SubmissionPreparer.SUBMISSION_FILE));
            if (!local.sha256().equals(remote.get(SubmissionPreparer.SUBMISSION_FILE))) {
                throw new Stop(Outcome.INCOMPLETE, "The remote submission.json in " + state.remoteDir + " is "
                        + (remote.isEmpty() ? "missing" : "different") + "; refusing to collect from it.");
            }
            Files.createDirectories(incoming);
            cluster.download(state.remoteDir, incoming);
        } catch (RemoteException e) {
            throw remote(e, "Collecting results");
        }
        ResultCollector.Merge merge = ResultCollector.merge(incoming, collected);
        List<ResultCollector.PromptResult> prompts = ResultCollector.verify(local, collected);
        List<String> unexpected = ResultCollector.unexpected(local, collected);
        Collected c = new Collected(merge, prompts, unexpected);
        writeCollection(c);
        store.event("collected: " + c.counts() + (merge.conflicts().isEmpty() ? "" : ", "
                + merge.conflicts().size() + " conflict(s)"));
        return c;
    }

    private void writeCollection(Collected c) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("submissionId", local.submissionId());
        root.put("runId", local.runId());
        root.put("remote", settings.sshTarget() + ":" + state.remoteDir);
        root.put("collectedAt", now());
        Map<String, Object> summary = new LinkedHashMap<>();
        for (ResultCollector.PromptStatus s : ResultCollector.PromptStatus.values()) {
            summary.put(s.name(), c.counts().getOrDefault(s, 0));
        }
        root.put("summary", summary);
        List<Object> prompts = new ArrayList<>();
        for (ResultCollector.PromptResult p : c.prompts()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("warningId", p.warningId());
            m.put("taskIndex", p.taskIndex());
            m.put("status", p.status().name());
            m.put("errorKind", p.errorKind());
            m.put("error", p.error());
            m.put("attempt", p.attempt());
            m.put("attempts", p.attempts());
            Map<String, Object> files = new LinkedHashMap<>();
            files.put("result", p.resultFile());
            files.put("answer", p.answerFile());
            files.put("rawResponse", p.rawResponseFile());
            files.put("reasoning", p.reasoningFile());
            m.put("files", files);
            m.put("problems", p.problems());
            prompts.add(m);
        }
        root.put("prompts", prompts);
        root.put("conflicts", c.merge().conflicts());
        root.put("unexpectedResults", c.unexpected());
        root.put("note", "Per-prompt results collected from the cluster and checked against submission.json. A "
                + "completed result is an LLM proposal (or a statement of insufficient evidence); it is not "
                + "checker-validated and does not establish that a warning is resolved. NO_RESULT means no result was "
                + "written yet, or the task ended before writing one.");
        StateStore.writeAtomically(store.dir().resolve(COLLECTION), Json.write(root));
    }

    private Optional<Map<?, ?>> loadCollection() {
        try {
            Path f = store.dir().resolve(COLLECTION);
            if (Files.isRegularFile(f) && JsonParser.parse(Files.readString(f, StandardCharsets.UTF_8))
                    instanceof Map<?, ?> m) {
                return Optional.of(m);
            }
        } catch (IOException | RuntimeException e) {
            // no usable collection
        }
        return Optional.empty();
    }

    private static String summaryLine(Object summary) {
        if (!(summary instanceof Map<?, ?> s)) {
            return "unknown";
        }
        List<String> parts = new ArrayList<>();
        s.forEach((k, v) -> {
            if (!Long.valueOf(0).equals(v) && !Integer.valueOf(0).equals(v)) {
                parts.add(k + " " + v);
            }
        });
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    private void printCollection(Collected c) {
        out.println("Collected into " + store.dir().resolve("collected") + " (" + c.merge().added() + " new, "
                + c.merge().updated() + " updated file(s)); details in " + store.dir().resolve(COLLECTION) + ".");
        Map<String, Object> counts = new LinkedHashMap<>();
        c.counts().forEach((k, v) -> counts.put(k.name(), (long) v));
        out.println("Prompts: " + summaryLine(counts));
        for (ResultCollector.PromptResult p : c.prompts()) {
            if (p.status() == ResultCollector.PromptStatus.FAILED) {
                out.println("  " + p.warningId() + " FAILED " + p.errorKind() + " (attempt " + p.attempt() + "): "
                        + p.error());
            } else if (p.status() == ResultCollector.PromptStatus.INVALID_RESULT) {
                out.println("  " + p.warningId() + " INVALID_RESULT: " + String.join("; ", p.problems()));
            }
        }
        c.merge().conflicts().forEach(x -> out.println("  conflict: " + x));
        c.unexpected().forEach(x -> out.println("  unexpected result directory: results/" + x));
    }

    // ------------------------------------------------------------------ retry

    private Outcome doRetry() throws Stop, IOException {
        refuseUnresolved();
        if (state.submitted().isEmpty()) {
            throw new Stop(Outcome.INCOMPLETE, "Nothing was submitted yet; use `inference submit` or `inference run`.");
        }
        SchedulerSnapshot snapshot = cluster.query(state.submitted().stream().map(j -> j.jobId).distinct().toList());
        List<String> blocking = new ArrayList<>();
        for (RemoteState.Job j : state.submitted()) {
            for (int t : j.taskIndices) {
                SchedulerSnapshot.TaskState s = snapshot.state(j.jobId, t);
                if (s.category() != SchedulerSnapshot.Category.TERMINAL) {
                    blocking.add(j.jobId + "_" + t + " " + s.state() + " (" + s.detail() + ")");
                }
            }
        }
        if (!blocking.isEmpty()) {
            throw new Stop(Outcome.INCOMPLETE, "Not retrying: every array task must be finished according to the "
                    + "scheduler, but these are running, pending or unknown: " + String.join("; ", blocking)
                    + (snapshot.problems().isEmpty() ? "" : " [" + String.join("; ", snapshot.problems()) + "]"));
        }
        Collected c = doCollect();
        printCollection(c);
        if (!c.clean()) {
            throw new Stop(Outcome.INCOMPLETE, "Not retrying: the collection has invalid results, conflicts or "
                    + "unexpected results that need a look first (see " + store.dir().resolve(COLLECTION) + ").");
        }
        Set<Integer> tasks = new TreeSet<>();
        List<String> retried = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (ResultCollector.PromptResult p : c.prompts()) {
            if (p.status() == ResultCollector.PromptStatus.NO_RESULT
                    || (p.status() == ResultCollector.PromptStatus.FAILED && !NOT_RETRYABLE.contains(p.errorKind()))) {
                tasks.add(p.taskIndex());
                retried.add(p.warningId());
            } else if (p.status() == ResultCollector.PromptStatus.FAILED) {
                skipped.add(p.warningId() + " (" + p.errorKind() + ")");
            }
        }
        if (!skipped.isEmpty()) {
            out.println("Not retried, because the same prompt would fail the same way: " + String.join(", ", skipped));
        }
        if (tasks.isEmpty()) {
            out.println("Nothing to retry: every prompt has a completed result" + (skipped.isEmpty() ? "."
                    : " or a failure that a retry cannot fix."));
            return Outcome.DONE;
        }
        out.println("Retrying " + retried.size() + " prompt(s) (" + String.join(", ", retried) + ") in "
                + tasks.size() + " array task(s). Completed results are kept: the worker skips them and writes "
                + "new attempt directories for the others.");
        return submitTasks("RETRY", new ArrayList<>(tasks));
    }

    // ------------------------------------------------------------------ monitor

    private Outcome monitor() throws Stop, IOException {
        int unknownStreak = 0;
        out.println("Monitoring every " + settings.pollInterval().toSeconds() + "s. Interrupting (Ctrl-C) stops only "
                + "this local monitoring; the jobs keep running and `inference run " + submissionDir
                + "` resumes it.");
        while (true) {
            requireConnection();
            Tasks tasks = taskStates();
            Collected c;
            try {
                c = doCollect();
            } catch (Stop s) {
                if (s.outcome == Outcome.RECONNECT) {
                    throw s;
                }
                out.println("  collection failed this round: " + s.getMessage());
                c = null;
            }
            Map<String, Integer> counts = new TreeMap<>();
            tasks.states().values().forEach(s -> counts.merge(s.state(), 1, Integer::sum));
            Map<String, Object> promptCounts = new LinkedHashMap<>();
            if (c != null) {
                c.counts().forEach((k, v) -> promptCounts.put(k.name(), (long) v));
            }
            out.println("[" + now() + "] tasks " + counts + " | prompts " + (c == null ? "not collected"
                    : summaryLine(promptCounts)));
            tasks.problems().forEach(p -> out.println("  scheduler: " + p));
            if (tasks.allTerminal() && c != null) {
                printCollection(c);
                boolean allCompleted = c.prompts().stream().allMatch(ResultCollector.PromptResult::completed);
                if (allCompleted && c.clean()) {
                    out.println("All array tasks finished and every prompt has a completed result.");
                    return Outcome.DONE;
                }
                out.println("All array tasks finished, but not every prompt has a completed result. Failed and "
                        + "missing prompts are not retried automatically; to retry them as new attempts:\n"
                        + "  inference retry " + submissionDir);
                return Outcome.INCOMPLETE;
            }
            boolean anyActive = tasks.count(SchedulerSnapshot.Category.ACTIVE) > 0;
            unknownStreak = tasks.unknown() > 0 && !anyActive ? unknownStreak + 1 : 0;
            if (unknownStreak >= settings.maxUnknownPolls()) {
                throw new Stop(Outcome.INCOMPLETE, "The scheduler gave no information about " + tasks.unknown()
                        + " task(s) in " + unknownStreak + " polls in a row; stopping monitoring (unknown is not "
                        + "success). Jobs are not touched; check with `inference status " + submissionDir + "`.");
            }
            try {
                sleeper.sleep(settings.pollInterval());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Stop(Outcome.INCOMPLETE, "Monitoring interrupted; the jobs keep running. Resume with "
                        + "`inference run " + submissionDir + "`.");
            }
        }
    }
}
