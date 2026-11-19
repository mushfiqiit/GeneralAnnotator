package generalannotator.inference.remote;

import generalannotator.inference.SubmissionPreparer;
import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * An in-process cluster: remote paths map into a local directory, sbatch creates the same {@code
 * .ga-sbatch/<label>} markers as the real script, and a programmable scheduler answers queries.
 * Failure injection covers lost connections, rejected and ambiguous submissions, corrupted uploads
 * and unavailable scheduler information. Tests play the worker by writing results remotely.
 */
final class FakeCluster implements Cluster {

    enum SbatchMode { SUBMIT, REJECT, UNKNOWN_AFTER_SUBMIT, UNKNOWN_BEFORE_REMOTE }

    final Path root;
    boolean connected = true;
    int disconnectAtUpload = -1;
    boolean corruptPromptAfterUpload;
    boolean disconnectAtDownload;
    boolean schedulerUnavailable;
    SbatchMode sbatchMode = SbatchMode.SUBMIT;
    String jobName = "ga-inference";
    int uploadCalls;
    int downloadCalls;
    final List<String> sbatchCalls = new ArrayList<>();
    /** jobId → task → state. */
    final Map<String, Map<Integer, String>> scheduler = new LinkedHashMap<>();
    final Map<String, String> jobDirs = new LinkedHashMap<>();
    private int nextJob = 5000;

    FakeCluster(Path root) {
        this.root = root;
    }

    Path dir(String remote) {
        return root.resolve(remote.substring(1));
    }

    private void requireConnected() throws RemoteException {
        if (!connected) {
            throw new RemoteException(RemoteException.Kind.DISCONNECTED, "fake: master gone");
        }
    }

    @Override
    public ConnectionCheck check() {
        return new ConnectionCheck(connected, connected ? "Master running (pid=1)" : "Control socket connect: No such file");
    }

    @Override
    public DirectoryProbe probe(String remoteDir) throws RemoteException {
        requireConnected();
        Path d = dir(remoteDir);
        if (Files.isRegularFile(d.resolve("submission.json"))) {
            return new DirectoryProbe(DirectoryProbe.Kind.HAS_SUBMISSION, Optional.of(sha(d.resolve("submission.json"))));
        }
        if (!Files.exists(d)) {
            return new DirectoryProbe(DirectoryProbe.Kind.ABSENT, Optional.empty());
        }
        try (Stream<Path> s = Files.list(d)) {
            return new DirectoryProbe(s.findAny().isPresent() ? DirectoryProbe.Kind.OTHER : DirectoryProbe.Kind.EMPTY,
                    Optional.empty());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String createDirectory(String remoteDir) throws RemoteException {
        requireConnected();
        try {
            Files.createDirectories(dir(remoteDir).resolve("logs"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return "/physical" + remoteDir;
    }

    @Override
    public void upload(Path localDir, List<String> files, String remoteDir) throws RemoteException {
        requireConnected();
        uploadCalls++;
        try {
            for (int i = 0; i < files.size(); i++) {
                if (uploadCalls == disconnectAtUpload && i == files.size() / 2) {
                    connected = false;
                    throw new RemoteException(RemoteException.Kind.DISCONNECTED, "fake: connection lost mid-upload");
                }
                Path target = dir(remoteDir).resolve(files.get(i));
                Files.createDirectories(target.getParent());
                Files.copy(localDir.resolve(files.get(i)), target, StandardCopyOption.REPLACE_EXISTING);
                target.toFile().setWritable(true);
            }
            if (corruptPromptAfterUpload && Files.isDirectory(dir(remoteDir).resolve("prompts"))) {
                try (Stream<Path> s = Files.list(dir(remoteDir).resolve("prompts"))) {
                    Path first = s.sorted().findFirst().orElseThrow();
                    Files.writeString(first, "tampered");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Map<String, String> hashes(String remoteDir, List<String> files) throws RemoteException {
        requireConnected();
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String f : files) {
            Path p = dir(remoteDir).resolve(f);
            if (Files.isRegularFile(p)) {
                hashes.put(f, sha(p));
            }
        }
        return hashes;
    }

    @Override
    public SbatchOutcome sbatch(String remoteDir, String label, String arraySpec, String comment) {
        sbatchCalls.add(label + " " + arraySpec);
        if (!connected || sbatchMode == SbatchMode.UNKNOWN_BEFORE_REMOTE) {
            connected = false;
            return new SbatchOutcome.Unknown("DISCONNECTED: fake: connection lost before the remote side");
        }
        Path marker = dir(remoteDir).resolve(".ga-sbatch").resolve(label);
        try {
            if (Files.exists(marker)) {
                return new SbatchOutcome.Duplicate("stdout: " + read(marker.resolve("stdout")));
            }
            Files.createDirectories(marker);
            if (sbatchMode == SbatchMode.REJECT) {
                Files.writeString(marker.resolve("stdout"), "\n");
                Files.writeString(marker.resolve("exit"), "1\n");
                return new SbatchOutcome.Rejected("sbatch exited with 1: Invalid account or account/partition combination");
            }
            String id = String.valueOf(nextJob++);
            Map<Integer, String> tasks = new TreeMap<>();
            for (int t : tasks(arraySpec)) {
                tasks.put(t, "PENDING");
            }
            scheduler.put(id, tasks);
            jobDirs.put(id, remoteDir);
            Files.writeString(marker.resolve("stdout"), id + "\n");
            Files.writeString(marker.resolve("exit"), "0\n");
            if (sbatchMode == SbatchMode.UNKNOWN_AFTER_SUBMIT) {
                connected = false;
                return new SbatchOutcome.Unknown("DISCONNECTED: fake: connection lost after sbatch ran");
            }
            return new SbatchOutcome.Submitted(id, id);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Integer> tasks(String arraySpec) {
        String spec = arraySpec.contains("%") ? arraySpec.substring(0, arraySpec.indexOf('%')) : arraySpec;
        List<Integer> tasks = new ArrayList<>();
        for (String part : spec.split(",")) {
            String[] b = part.split("-");
            int from = Integer.parseInt(b[0]);
            int to = b.length > 1 ? Integer.parseInt(b[1]) : from;
            for (int i = from; i <= to; i++) {
                tasks.add(i);
            }
        }
        return tasks;
    }

    void setAll(String jobId, String state) {
        scheduler.get(jobId).replaceAll((t, s) -> state);
    }

    String lastJob() {
        return new ArrayList<>(scheduler.keySet()).get(scheduler.size() - 1);
    }

    @Override
    public SchedulerSnapshot query(List<String> jobIds) {
        if (!connected) {
            return SchedulerSnapshot.unavailable("DISCONNECTED: fake");
        }
        if (schedulerUnavailable) {
            return new SchedulerSnapshot(false, false, List.of(), List.of("squeue failed", "sacct failed"));
        }
        List<SchedulerSnapshot.Row> rows = new ArrayList<>();
        for (String id : jobIds) {
            scheduler.getOrDefault(id, Map.of()).forEach((t, s) -> rows.add(new SchedulerSnapshot.Row(id, t, t, s, "",
                    SchedulerSnapshot.categorize(s) == SchedulerSnapshot.Category.ACTIVE ? SchedulerSnapshot.Source.SQUEUE
                            : SchedulerSnapshot.Source.SACCT)));
        }
        return new SchedulerSnapshot(true, true, rows, List.of());
    }

    @Override
    public List<JobRecord> describe(String jobId) throws RemoteException {
        requireConnected();
        List<JobRecord> records = new ArrayList<>();
        scheduler.getOrDefault(jobId, Map.of()).forEach((t, s) -> records.add(new JobRecord(jobId + "_" + t, jobName,
                jobDirs.get(jobId), s, "sacct")));
        return records;
    }

    @Override
    public List<JobRecord> findJobs(String name, LocalDate since) throws RemoteException {
        requireConnected();
        List<JobRecord> records = new ArrayList<>();
        for (String id : scheduler.keySet()) {
            records.addAll(describe(id));
        }
        return records;
    }

    @Override
    public Optional<String> sbatchRecord(String remoteDir, String label) throws RemoteException {
        requireConnected();
        Path marker = dir(remoteDir).resolve(".ga-sbatch").resolve(label);
        if (!Files.isDirectory(marker)) {
            return Optional.empty();
        }
        return Optional.of("stdout: " + read(marker.resolve("stdout")).strip() + "\nexit: "
                + read(marker.resolve("exit")).strip());
    }

    @Override
    public void download(String remoteDir, Path incoming) throws RemoteException {
        requireConnected();
        downloadCalls++;
        if (disconnectAtDownload) {
            connected = false;
            throw new RemoteException(RemoteException.Kind.DISCONNECTED, "fake: lost during download");
        }
        Path d = dir(remoteDir);
        try (Stream<Path> walk = Files.walk(d)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = d.relativize(p).toString();
                if (!(rel.startsWith("results/") || rel.startsWith("logs/") || rel.startsWith(".ga-sbatch/"))
                        || rel.contains(".tmp-")) {
                    continue;
                }
                Path target = incoming.resolve(rel);
                Files.createDirectories(target.getParent());
                Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ playing the worker

    /** Writes an attempt and {@code result.json} like the worker does. */
    void workerResult(String remoteDir, String warningId, int attempt, String status, String errorKind)
            throws IOException {
        Path d = dir(remoteDir);
        Map<?, ?> submission = (Map<?, ?>) JsonParser.parse(Files.readString(d.resolve("submission.json")));
        Map<?, ?> prompt = ((List<?>) submission.get("prompts")).stream().map(o -> (Map<?, ?>) o)
                .filter(p -> p.get("warningId").equals(warningId)).findFirst().orElseThrow();
        Path attemptDir = d.resolve("results").resolve(warningId).resolve(String.format("attempt-%03d", attempt));
        Files.createDirectories(attemptDir);
        Map<String, Object> files = new LinkedHashMap<>();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", 1);
        m.put("submissionId", submission.get("submissionId"));
        m.put("runId", submission.get("runId"));
        m.put("warningId", warningId);
        m.put("taskIndex", prompt.get("taskIndex"));
        m.put("attempt", attempt);
        m.put("promptSha256", prompt.get("sha256"));
        m.put("status", status);
        m.put("errorKind", errorKind);
        m.put("error", errorKind == null ? null : "fake failure " + errorKind);
        if (status.startsWith("COMPLETED")) {
            Files.writeString(attemptDir.resolve("raw-response.json"), "{\"choices\": []}\n");
            files.put("rawResponse", "raw-response.json");
            String answerStatus = status.equals("COMPLETED_PROPOSED") ? "PROPOSED" : "INSUFFICIENT_EVIDENCE";
            Files.writeString(attemptDir.resolve("answer.json"), "{\"warningId\": \"" + warningId
                    + "\", \"status\": \"" + answerStatus + "\", \"edits\": [], \"rationale\": \"r\"}\n");
            files.put("answer", "answer.json");
        }
        m.put("files", files);
        String json = Json.write(m);
        Files.writeString(attemptDir.resolve("metadata.json"), json);
        Files.writeString(d.resolve("results").resolve(warningId).resolve("result.json"), json);
    }

    static String sha(Path p) {
        try {
            return SubmissionPreparer.sha256(Files.readAllBytes(p));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
