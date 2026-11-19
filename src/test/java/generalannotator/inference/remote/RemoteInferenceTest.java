package generalannotator.inference.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.cli.PrepareInferenceCommandTest;
import generalannotator.inference.InferenceSettings;
import generalannotator.inference.SubmissionPreparer;
import generalannotator.inference.remote.RemoteInference.Outcome;
import generalannotator.report.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Upload, submission, monitoring, collection and retry against {@link FakeCluster}. No SSH is used. */
class RemoteInferenceTest {

    static final String REMOTE = "/project/test/generalannotator/inference/run_1/20261119T120000Z-inference";

    @TempDir Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final List<Duration> sleeps = new ArrayList<>();
    private FakeCluster cluster;
    private Path submission;

    /** A prepared submission with {@code prompts} prompts, {@code perTask} per task and {@code concurrency}. */
    private void prepare(int prompts, int perTask, int concurrency) throws Exception {
        Path run = PrepareInferenceCommandTest.makeRun(temp.resolve("runs/run 1"), prompts);
        List<String> problems = new ArrayList<>();
        InferenceSettings settings = InferenceSettings.fromEnvironment(Map.of(
                "GA_INFERENCE_PROMPTS_PER_TASK", String.valueOf(perTask),
                "GA_INFERENCE_ARRAY_CONCURRENCY", String.valueOf(concurrency)), problems);
        assertEquals(List.of(), problems);
        submission = SubmissionPreparer.prepare(run, Optional.empty(), settings, PrepareInferenceCommandTest.CLOCK)
                .directory();
        cluster = new FakeCluster(temp.resolve("remote-fs"));
    }

    static RemoteSettings settings(Map<String, String> extra) {
        Map<String, String> env = new HashMap<>(Map.of("GA_SSH_CONTROL_PATH", "config",
                "GA_REMOTE_BASE", "/project/test", "GA_REMOTE_POLL_SECONDS", "60"));
        env.putAll(extra);
        List<String> problems = new ArrayList<>();
        RemoteSettings s = RemoteSettings.fromEnvironment(env, problems);
        assertEquals(List.of(), problems);
        return s;
    }

    private RemoteInference remote() {
        return remote(sleeps::add);
    }

    private RemoteInference remote(RemoteInference.Sleeper sleeper) {
        return new RemoteInference(submission, settings(Map.of()), cluster, PrepareInferenceCommandTest.CLOCK, sleeper,
                new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private Map<?, ?> state() throws IOException {
        return (Map<?, ?>) JsonParser.parse(Files.readString(submission.resolve("remote/state.json")));
    }

    private List<?> jobs() throws IOException {
        return (List<?>) state().get("jobs");
    }

    private Map<?, ?> job(int i) throws IOException {
        return (Map<?, ?>) jobs().get(i);
    }

    private Map<?, ?> collection() throws IOException {
        return (Map<?, ?>) JsonParser.parse(Files.readString(submission.resolve("remote/collection.json")));
    }

    private Map<?, ?> prompt(String id) throws IOException {
        return ((List<?>) collection().get("prompts")).stream().map(o -> (Map<?, ?>) o)
                .filter(p -> p.get("warningId").equals(id)).findFirst().orElseThrow();
    }

    @Test
    void uploadsOnlyTheInputsAndVerifiesEveryHash() throws Exception {
        prepare(3, 1, 4);
        assertEquals(Outcome.DONE, remote().upload(), output());

        Path d = cluster.dir(REMOTE);
        try (var walk = Files.walk(d)) {
            List<String> files = walk.filter(Files::isRegularFile).map(p -> d.relativize(p).toString()).sorted().toList();
            assertEquals(List.of("prompts/W0001.txt", "prompts/W0002.txt", "prompts/W0003.txt", "run_inference.sbatch",
                    "settings.json", "submission.json", "worker/ga_inference_worker.py"), files);
        }
        assertTrue(Files.isDirectory(d.resolve("logs")), "Slurm needs logs/ for --output");
        assertEquals("VERIFIED", ((Map<?, ?>) state().get("upload")).get("status"));
        assertEquals(REMOTE, state().get("remoteDir"));
        assertEquals("/physical" + REMOTE, state().get("remotePhysicalDir"));
        assertFalse(Files.exists(submission.resolve("remote/incoming")), "nothing downloaded");

        // A rerun re-verifies and uploads nothing new.
        int calls = cluster.uploadCalls;
        assertEquals(Outcome.DONE, remote().upload());
        assertEquals(calls, cluster.uploadCalls);
    }

    @Test
    void lostConnectionDuringUploadKeepsProgressAndResumesWithoutPrompting() throws Exception {
        prepare(6, 1, 4);
        cluster.disconnectAtUpload = 2;
        assertEquals(Outcome.RECONNECT, remote().upload());
        assertTrue(output().contains("inference connect"), output());
        assertEquals("STARTED", ((Map<?, ?>) state().get("upload")).get("status"));
        assertEquals(List.of(), cluster.sbatchCalls);

        // Still disconnected: fails fast, no prompt, nothing changes.
        assertEquals(Outcome.RECONNECT, remote().submit());

        cluster.connected = true; // the user reconnected
        assertEquals(Outcome.DONE, remote().upload(), output());
        assertEquals("VERIFIED", ((Map<?, ?>) state().get("upload")).get("status"));
    }

    @Test
    void hashMismatchAfterUploadBlocksSubmission() throws Exception {
        prepare(2, 1, 4);
        cluster.corruptPromptAfterUpload = true;
        assertEquals(Outcome.INCOMPLETE, remote().run());
        assertTrue(output().contains("prompts/W0001.txt has SHA-256"), output());
        assertEquals(List.of(), cluster.sbatchCalls);
        assertEquals("STARTED", ((Map<?, ?>) state().get("upload")).get("status"));
    }

    @Test
    void refusesARemoteDirectoryHoldingAnotherSubmissionOrForeignFiles() throws Exception {
        prepare(1, 1, 4);
        Files.createDirectories(cluster.dir(REMOTE));
        Files.writeString(cluster.dir(REMOTE).resolve("submission.json"), "{}");
        assertEquals(Outcome.INCOMPLETE, remote().upload());
        assertTrue(output().contains("different submission.json"), output());
        assertEquals("{}", Files.readString(cluster.dir(REMOTE).resolve("submission.json")));

        Files.delete(cluster.dir(REMOTE).resolve("submission.json"));
        Files.writeString(cluster.dir(REMOTE).resolve("notes.txt"), "someone else's");
        assertEquals(Outcome.INCOMPLETE, remote().upload());
        assertTrue(output().contains("did not create it"), output());
        assertEquals(0, cluster.uploadCalls);
    }

    @Test
    void submitsOneArrayOnceAndPersistsTheJobId() throws Exception {
        prepare(41, 10, 2);
        assertEquals(Outcome.DONE, remote().upload());
        assertEquals(Outcome.DONE, remote().submit(), output());
        assertEquals(List.of("job-001 0-4%2"), cluster.sbatchCalls);
        assertEquals("SUBMITTED", job(0).get("status"));
        assertEquals("5000", job(0).get("jobId"));
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L), job(0).get("taskIndices"));
        assertEquals("GeneralAnnotator:20261119T120000Z-inference:job-001", job(0).get("comment"));

        // Ordinary reruns never submit again.
        assertEquals(Outcome.DONE, remote().submit());
        assertTrue(output().contains("not submitting again"), output());
        cluster.setAll("5000", "COMPLETED");
        remote().run();
        assertEquals(1, cluster.sbatchCalls.size());
        assertEquals(1, jobs().size());
    }

    @Test
    void concurrentInvocationIsRefusedWhileAnotherHoldsTheLock() throws Exception {
        prepare(2, 1, 4);
        assertEquals(Outcome.DONE, remote().upload());
        StateStore store = new StateStore(submission, PrepareInferenceCommandTest.CLOCK);
        try (StateStore.Lock lock = store.lock()) {
            assertTrue(lock != null);
            assertEquals(Outcome.INCOMPLETE, remote().submit());
            assertTrue(output().contains("another GeneralAnnotator process"), output());
        }
        assertEquals(List.of(), cluster.sbatchCalls);
        assertEquals(Outcome.DONE, remote().submit());
    }

    @Test
    void remoteMarkerPreventsADuplicateWhenLocalStateIsLost() throws Exception {
        prepare(2, 1, 4);
        assertEquals(Outcome.DONE, remote().upload());
        assertEquals(Outcome.DONE, remote().submit());
        // Simulate a second checkout without the job: drop the job from the local state.
        RemoteState s = new StateStore(submission, PrepareInferenceCommandTest.CLOCK).load().orElseThrow();
        s.jobs.clear();
        new StateStore(submission, PrepareInferenceCommandTest.CLOCK).save(s);

        assertEquals(Outcome.INCOMPLETE, remote().submit());
        assertEquals(1, cluster.scheduler.size(), "no second job");
        assertEquals("UNKNOWN", job(0).get("status"));
        assertTrue(output().contains("already existed"), output());
        assertTrue(output().contains("not yet attached: 5000"), output());
    }

    @Test
    void ambiguousDisconnectIsUnknownNeverResubmittedAndCanBeAttachedAfterVerification() throws Exception {
        prepare(3, 1, 4);
        assertEquals(Outcome.DONE, remote().upload());
        cluster.sbatchMode = FakeCluster.SbatchMode.UNKNOWN_AFTER_SUBMIT;
        assertEquals(Outcome.INCOMPLETE, remote().submit());
        assertEquals("UNKNOWN", job(0).get("status"));
        assertEquals(null, job(0).get("jobId"));
        assertTrue(output().contains("will not be resubmitted automatically"), output());

        cluster.connected = true;
        cluster.sbatchMode = FakeCluster.SbatchMode.SUBMIT;
        assertEquals(Outcome.INCOMPLETE, remote().submit());
        assertEquals(Outcome.INCOMPLETE, remote().run());
        assertEquals(Outcome.INCOMPLETE, remote().retry());
        assertEquals(1, cluster.sbatchCalls.size(), "never resubmitted");
        assertTrue(output().contains("Remote record of job-001: stdout: 5000"), output());

        // A job of another directory is not attached.
        cluster.scheduler.put("777", Map.of(0, "RUNNING"));
        cluster.jobDirs.put("777", "/project/test/elsewhere");
        assertEquals(Outcome.INCOMPLETE, remote().attach("777"));
        assertEquals(Outcome.INCOMPLETE, remote().attach("999"), "unknown to the scheduler");
        assertEquals(Outcome.INVALID, remote().attach("5000_1"));
        // Not-submitted cannot be claimed when the remote record shows a job ID.
        assertEquals(Outcome.INCOMPLETE, remote().attachNone());
        assertEquals("UNKNOWN", job(0).get("status"));

        assertEquals(Outcome.DONE, remote().attach("5000"), output());
        assertEquals("SUBMITTED", job(0).get("status"));
        assertEquals("5000", job(0).get("jobId"));
        assertEquals(Outcome.INVALID, remote().attach("5000"), "nothing left to attach");
    }

    @Test
    void verifiedAbsenceOfAJobAllowsANewSubmissionWithANewLabel() throws Exception {
        prepare(2, 1, 4);
        assertEquals(Outcome.DONE, remote().upload());
        cluster.sbatchMode = FakeCluster.SbatchMode.UNKNOWN_BEFORE_REMOTE;
        assertEquals(Outcome.INCOMPLETE, remote().submit());
        assertEquals(Outcome.RECONNECT, remote().attachNone(), "needs the connection to verify");
        cluster.connected = true;
        cluster.sbatchMode = FakeCluster.SbatchMode.SUBMIT;
        assertEquals(Outcome.DONE, remote().attachNone(), output());
        assertEquals("NOT_SUBMITTED", job(0).get("status"));
        assertEquals(Outcome.DONE, remote().submit());
        assertEquals("job-002", job(1).get("label"));
        assertEquals("SUBMITTED", job(1).get("status"));
    }

    @Test
    void interruptedSbatchIntentIsReadBackAsUnknown() throws Exception {
        prepare(1, 1, 4);
        assertEquals(Outcome.DONE, remote().upload());
        StateStore store = new StateStore(submission, PrepareInferenceCommandTest.CLOCK);
        RemoteState s = store.load().orElseThrow();
        RemoteState.Job j = new RemoteState.Job();
        j.label = "job-001";
        j.kind = "INITIAL";
        j.taskIndices.add(0);
        j.array = "0%4";
        j.status = RemoteState.JobStatus.SUBMITTING;
        j.intentAt = "2026-11-19T12:00:00Z";
        s.jobs.add(j);
        store.save(s);

        assertEquals(Outcome.INCOMPLETE, remote().status(), "status reports it without changing state");
        assertEquals("SUBMITTING", job(0).get("status"));
        assertEquals(Outcome.INCOMPLETE, remote().submit());
        assertEquals("UNKNOWN", job(0).get("status"));
        assertEquals(List.of(), cluster.sbatchCalls);
    }

    @Test
    void rejectedSubmissionIsRecordedAndNothingIsSubmitted() throws Exception {
        prepare(1, 1, 4);
        assertEquals(Outcome.DONE, remote().upload());
        cluster.sbatchMode = FakeCluster.SbatchMode.REJECT;
        assertEquals(Outcome.INCOMPLETE, remote().submit());
        assertEquals("REJECTED", job(0).get("status"));
        assertTrue(output().contains("Invalid account"), output());
        assertTrue(cluster.scheduler.isEmpty());
    }

    @Test
    void schedulerSilenceIsUnknownNotSuccess() throws Exception {
        prepare(2, 1, 4);
        assertEquals(Outcome.DONE, remote().upload());
        assertEquals(Outcome.DONE, remote().submit());
        cluster.workerResult(REMOTE, "W0001", 1, "COMPLETED_PROPOSED", null);
        cluster.workerResult(REMOTE, "W0002", 1, "COMPLETED_PROPOSED", null);
        cluster.schedulerUnavailable = true;

        assertEquals(Outcome.INCOMPLETE, remote().status());
        assertTrue(output().contains("UNKNOWN"), output());
        Map<?, ?> status = (Map<?, ?>) JsonParser.parse(Files.readString(submission.resolve("remote/status.json")));
        assertEquals("UNKNOWN", ((Map<?, ?>) ((List<?>) status.get("tasks")).get(0)).get("category"));

        // Every prompt has a result, but the scheduler is silent: run does not call it done.
        assertEquals(Outcome.INCOMPLETE, remote().run());
        assertEquals(2, sleeps.size(), "stops after GA_REMOTE_MAX_UNKNOWN_POLLS=3 polls");
        assertTrue(output().contains("unknown is not success"), output());
        assertEquals(Outcome.INCOMPLETE, remote().retry());
        assertEquals(1, cluster.sbatchCalls.size());
    }

    @Test
    void partialCollectionWhileRunningKeepsSchedulerAndPromptStatesApart() throws Exception {
        prepare(4, 2, 4);
        assertEquals(Outcome.DONE, remote().upload());
        assertEquals(Outcome.DONE, remote().submit());
        String job = cluster.lastJob();
        cluster.scheduler.get(job).put(0, "COMPLETED");
        cluster.scheduler.get(job).put(1, "RUNNING");
        cluster.workerResult(REMOTE, "W0001", 1, "COMPLETED_PROPOSED", null);
        cluster.workerResult(REMOTE, "W0002", 1, "FAILED", "TRUNCATED");
        cluster.workerResult(REMOTE, "W0003", 1, "COMPLETED_INSUFFICIENT_EVIDENCE", null);
        Files.writeString(cluster.dir(REMOTE).resolve("results/W0004.tmp-123"), "partial");
        Files.writeString(cluster.dir(REMOTE).resolve("logs/ga-inference." + job + "_1.out"), "running\n");

        assertEquals(Outcome.DONE, remote().collect(), output());
        assertEquals("COMPLETED_PROPOSED", prompt("W0001").get("status"));
        assertEquals("FAILED", prompt("W0002").get("status"));
        assertEquals("TRUNCATED", prompt("W0002").get("errorKind"));
        assertEquals("COMPLETED_INSUFFICIENT_EVIDENCE", prompt("W0003").get("status"));
        assertEquals("NO_RESULT", prompt("W0004").get("status"));
        assertEquals("remote/collected/results/W0001/attempt-001/answer.json",
                ((Map<?, ?>) prompt("W0001").get("files")).get("answer"));
        assertFalse(Files.exists(submission.resolve("remote/collected/results/W0004.tmp-123")));
        assertTrue(Files.exists(submission.resolve("remote/collected/logs/ga-inference." + job + "_1.out")));
        assertTrue(Files.exists(submission.resolve("remote/collected/.ga-sbatch/job-001/stdout")));

        // Retry is refused while a task runs.
        assertEquals(Outcome.INCOMPLETE, remote().retry());
        assertTrue(output().contains(job + "_1 RUNNING"), output());
        assertEquals(1, cluster.sbatchCalls.size());
    }

    @Test
    void collectionRejectsResultsThatDoNotMatchTheSubmission() throws Exception {
        prepare(2, 1, 4);
        assertEquals(Outcome.DONE, remote().upload());
        assertEquals(Outcome.DONE, remote().submit());
        cluster.workerResult(REMOTE, "W0001", 1, "COMPLETED_PROPOSED", null);
        Path result = cluster.dir(REMOTE).resolve("results/W0001/result.json");
        Files.writeString(result, Files.readString(result).replaceAll("\"promptSha256\": \"[0-9a-f]+\"",
                "\"promptSha256\": \"" + "0".repeat(64) + "\""));
        Path metadata = cluster.dir(REMOTE).resolve("results/W0001/attempt-001/metadata.json");
        Files.writeString(metadata, Files.readString(result));
        Files.createDirectories(cluster.dir(REMOTE).resolve("results/W0999"));
        Files.writeString(cluster.dir(REMOTE).resolve("results/W0999/result.json"), "{}");

        assertEquals(Outcome.INCOMPLETE, remote().collect());
        assertEquals("INVALID_RESULT", prompt("W0001").get("status"));
        assertTrue(prompt("W0001").get("problems").toString().contains("promptSha256"), output());
        assertEquals(List.of("W0999"), collection().get("unexpectedResults"));

        // A remote submission.json that differs from the local one: nothing is collected.
        cluster.dir(REMOTE).resolve("submission.json").toFile().setWritable(true);
        Files.writeString(cluster.dir(REMOTE).resolve("submission.json"), "{}");
        int downloads = cluster.downloadCalls;
        assertEquals(Outcome.INCOMPLETE, remote().collect());
        assertEquals(downloads, cluster.downloadCalls);
        assertTrue(output().contains("refusing to collect"), output());
    }

    @Test
    void retryResubmitsOnlyFailedAndMissingPromptsAsNewAttemptsAndKeepsCompletedResults() throws Exception {
        prepare(5, 2, 3);
        assertEquals(Outcome.DONE, remote().upload());
        assertEquals(Outcome.DONE, remote().submit());
        String first = cluster.lastJob();
        // Tasks: 0 = W0001, W0002; 1 = W0003, W0004; 2 = W0005.
        cluster.workerResult(REMOTE, "W0001", 1, "COMPLETED_PROPOSED", null);
        cluster.workerResult(REMOTE, "W0002", 1, "FAILED", "TIMEOUT");
        cluster.workerResult(REMOTE, "W0003", 1, "COMPLETED_PROPOSED", null);
        cluster.workerResult(REMOTE, "W0004", 1, "FAILED", "PROMPT_TOO_LONG");
        // W0005: the task was killed before writing anything.
        cluster.scheduler.get(first).put(0, "COMPLETED");
        cluster.scheduler.get(first).put(1, "COMPLETED");
        cluster.scheduler.get(first).put(2, "NODE_FAIL");
        byte[] answer = Files.readAllBytes(cluster.dir(REMOTE).resolve("results/W0001/attempt-001/answer.json"));

        assertEquals(Outcome.INCOMPLETE, remote().run(), output());
        assertTrue(output().contains("inference retry"), output());

        assertEquals(Outcome.DONE, remote().retry(), output());
        assertEquals("job-002 0,2%3", cluster.sbatchCalls.get(1), "tasks of W0002 and W0005 only");
        assertTrue(output().contains("W0004 (PROMPT_TOO_LONG)"), output());
        assertEquals("RETRY", job(1).get("kind"));

        // The retry job: the worker keeps W0001 and writes new attempts; a stray non-completed
        // result.json for W0001 must not replace the collected completed one.
        String second = cluster.lastJob();
        cluster.workerResult(REMOTE, "W0002", 2, "COMPLETED_INSUFFICIENT_EVIDENCE", null);
        cluster.workerResult(REMOTE, "W0005", 1, "COMPLETED_PROPOSED", null);
        Files.writeString(cluster.dir(REMOTE).resolve("results/W0001/result.json"),
                Files.readString(cluster.dir(REMOTE).resolve("results/W0002/attempt-001/metadata.json"))
                        .replace("W0002", "W0001"));
        cluster.setAll(second, "COMPLETED");

        assertEquals(Outcome.INCOMPLETE, remote().run(), output());
        assertEquals("COMPLETED_PROPOSED", prompt("W0001").get("status"));
        assertEquals(Long.valueOf(1), prompt("W0001").get("attempt"));
        assertTrue(collection().get("conflicts").toString().contains("results/W0001/result.json"), output());
        assertTrue(java.util.Arrays.equals(answer, Files.readAllBytes(
                submission.resolve("remote/collected/results/W0001/attempt-001/answer.json"))));
        assertEquals("COMPLETED_INSUFFICIENT_EVIDENCE", prompt("W0002").get("status"));
        assertEquals(Long.valueOf(2), prompt("W0002").get("attempts"));
        assertTrue(Files.exists(submission.resolve("remote/collected/results/W0002/attempt-001/metadata.json")));
        assertEquals("COMPLETED_PROPOSED", prompt("W0005").get("status"));
        assertEquals("FAILED", prompt("W0004").get("status"));
        assertEquals(2, cluster.sbatchCalls.size(), "run never retries by itself");
    }

    @Test
    void runMonitorsUntilEveryTaskFinishesAndCollectsEachPoll() throws Exception {
        prepare(2, 1, 2);
        int[] polls = {0};
        RemoteInference r = remote(d -> {
            sleeps.add(d);
            polls[0]++;
            String job = cluster.lastJob();
            if (polls[0] == 1) {
                cluster.scheduler.get(job).put(0, "RUNNING");
            } else if (polls[0] == 2) {
                try {
                    cluster.workerResult(REMOTE, "W0001", 1, "COMPLETED_PROPOSED", null);
                    cluster.workerResult(REMOTE, "W0002", 1, "COMPLETED_INSUFFICIENT_EVIDENCE", null);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                cluster.setAll(job, "COMPLETED");
            }
        });
        assertEquals(Outcome.DONE, r.run(), output());
        assertEquals(List.of(Duration.ofSeconds(60), Duration.ofSeconds(60)), sleeps);
        assertEquals(3, cluster.downloadCalls);
        assertTrue(output().contains("every prompt has a completed result"), output());
        assertTrue(Files.readString(submission.resolve("remote/events.log")).contains("submitted job 5000"));
    }

    @Test
    void disconnectWhileMonitoringOrInterruptingLeavesJobsAndStateAlone() throws Exception {
        prepare(2, 1, 2);
        RemoteInference r = remote(d -> cluster.connected = false);
        assertEquals(Outcome.RECONNECT, r.run());
        assertEquals("SUBMITTED", job(0).get("status"));
        assertEquals("PENDING", cluster.scheduler.get("5000").get(0));

        cluster.connected = true;
        RemoteInference interrupted = remote(d -> {
            throw new InterruptedException();
        });
        assertEquals(Outcome.INCOMPLETE, interrupted.run());
        Thread.interrupted(); // clear the flag set by the operation
        assertTrue(output().contains("resuming monitoring"), output());
        assertTrue(output().contains("jobs keep running"), output());
        assertEquals(1, cluster.sbatchCalls.size());

        cluster.disconnectAtDownload = true;
        assertEquals(Outcome.RECONNECT, remote().collect());
    }

    @Test
    void refusesStateOfAnotherRemoteLocation() throws Exception {
        prepare(1, 1, 4);
        assertEquals(Outcome.DONE, remote().upload());
        RemoteInference moved = new RemoteInference(submission, settings(Map.of("GA_REMOTE_BASE", "/project/other")),
                cluster, PrepareInferenceCommandTest.CLOCK, sleeps::add, new PrintStream(out, true,
                        StandardCharsets.UTF_8));
        assertEquals(Outcome.INVALID, moved.submit());
        assertTrue(output().contains("belongs to"), output());
    }

    @Test
    void arraySpecificationsCompressConsecutiveTasks() {
        assertEquals("0-40", RemoteInference.arraySpec(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40)));
        assertEquals("1,3-5,9", RemoteInference.arraySpec(List.of(9, 1, 3, 4, 5)));
        assertEquals("0", RemoteInference.arraySpec(List.of(0)));
    }
}
