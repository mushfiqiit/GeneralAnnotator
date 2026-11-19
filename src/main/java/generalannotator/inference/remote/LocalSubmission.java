package generalannotator.inference.remote;

import generalannotator.inference.SlurmScript;
import generalannotator.inference.SubmissionPreparer;
import generalannotator.report.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A submission prepared by {@code prepare-inference}, read back and verified: every input file must
 * still have the SHA-256 recorded in {@code submission.json}. Only these files are uploaded.
 *
 * @param fileHashes the files to upload (relative paths) and their expected SHA-256, {@code
 *     submission.json} first
 */
record LocalSubmission(Path dir, String submissionId, String runId, String sha256, String jobName,
        int arrayConcurrency, List<Prompt> prompts, List<List<String>> tasks, Map<String, String> fileHashes) {

    /** One prompt and the array task it belongs to. */
    record Prompt(String warningId, String file, String sha256, int taskIndex) {}

    /** The submission cannot be used; the message says why. */
    static final class InvalidException extends Exception {
        private static final long serialVersionUID = 1L;

        InvalidException(String message) {
            super(message);
        }
    }

    private static final Pattern WARNING_ID = Pattern.compile("W[0-9]{4,}");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    int taskCount() {
        return tasks.size();
    }

    static LocalSubmission load(Path dir) throws IOException, InvalidException {
        Path file = dir.resolve(SubmissionPreparer.SUBMISSION_FILE);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new InvalidException(dir + " is not a prepared submission (no " + SubmissionPreparer.SUBMISSION_FILE
                    + "). Prepare one with: prepare-inference <run directory>");
        }
        byte[] bytes = Files.readAllBytes(file);
        Map<?, ?> root;
        Map<?, ?> settings;
        try {
            root = (Map<?, ?>) JsonParser.parse(new String(bytes, StandardCharsets.UTF_8));
            settings = (Map<?, ?>) JsonParser.parse(Files.readString(dir.resolve(SubmissionPreparer.SETTINGS_FILE),
                    StandardCharsets.UTF_8));
        } catch (RuntimeException | IOException e) {
            throw new InvalidException("cannot read submission.json/settings.json in " + dir + ": " + e.getMessage());
        }
        if (!Long.valueOf(1).equals(root.get("schemaVersion"))) {
            throw new InvalidException("unsupported submission schemaVersion " + root.get("schemaVersion"));
        }
        String submissionId = string(root, "submissionId");
        String runId = string(root, "runId");
        if (!dir.getFileName().toString().equals(submissionId)) {
            throw new InvalidException(dir + " holds submission " + submissionId + "; its directory name differs");
        }
        String jobName = settings.get("slurm") instanceof Map<?, ?> slurm && slurm.get("jobName") instanceof String n
                ? n : null;
        if (jobName == null) {
            throw new InvalidException("settings.json has no slurm.jobName");
        }
        int concurrency = number(root, "arrayConcurrency");

        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put(SubmissionPreparer.SUBMISSION_FILE, SubmissionPreparer.sha256(bytes));
        if (!(root.get("files") instanceof Map<?, ?> files)) {
            throw new InvalidException("submission.json has no files section");
        }
        expectFile(files, "settings", SubmissionPreparer.SETTINGS_FILE, hashes);
        expectFile(files, "batchScript", SlurmScript.FILE_NAME, hashes);
        expectFile(files, "worker", SubmissionPreparer.WORKER_FILE, hashes);

        List<Prompt> prompts = new ArrayList<>();
        if (!(root.get("prompts") instanceof List<?> list) || list.isEmpty()) {
            throw new InvalidException("submission.json lists no prompts");
        }
        Set<String> ids = new HashSet<>();
        for (Object o : list) {
            Map<?, ?> p = (Map<?, ?>) o;
            String id = string(p, "warningId");
            String path = string(p, "file");
            String sha = string(p, "sha256");
            if (!WARNING_ID.matcher(id).matches() || !path.equals("prompts/" + id + ".txt")
                    || !SHA.matcher(sha).matches() || !ids.add(id)) {
                throw new InvalidException("submission.json has an invalid prompt entry for " + id);
            }
            prompts.add(new Prompt(id, path, sha, number(p, "taskIndex")));
            hashes.put(path, sha);
        }
        List<List<String>> tasks = new ArrayList<>();
        if (!(root.get("tasks") instanceof List<?> taskList) || taskList.isEmpty()) {
            throw new InvalidException("submission.json lists no tasks");
        }
        Set<String> assigned = new HashSet<>();
        for (int i = 0; i < taskList.size(); i++) {
            Map<?, ?> t = (Map<?, ?>) taskList.get(i);
            if (number(t, "taskIndex") != i || !(t.get("warningIds") instanceof List<?> w) || w.isEmpty()) {
                throw new InvalidException("submission.json task " + i + " is invalid");
            }
            List<String> group = new ArrayList<>();
            for (Object id : w) {
                if (!ids.contains(id) || !assigned.add((String) id)) {
                    throw new InvalidException("submission.json task " + i + " lists " + id + " wrongly");
                }
                group.add((String) id);
            }
            tasks.add(List.copyOf(group));
        }
        for (Prompt p : prompts) {
            if (p.taskIndex() < 0 || p.taskIndex() >= tasks.size() || !tasks.get(p.taskIndex()).contains(p.warningId())) {
                throw new InvalidException("submission.json assigns " + p.warningId() + " inconsistently");
            }
        }
        if (assigned.size() != ids.size()) {
            throw new InvalidException("submission.json has prompts that belong to no task");
        }
        for (Map.Entry<String, String> e : hashes.entrySet()) {
            Path f = dir.resolve(e.getKey());
            if (Files.isSymbolicLink(f) || !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                throw new InvalidException(e.getKey() + " is missing from " + dir);
            }
            String actual = SubmissionPreparer.sha256(Files.readAllBytes(f));
            if (!actual.equals(e.getValue())) {
                throw new InvalidException(e.getKey() + " changed after preparation (SHA-256 " + actual
                        + ", submission.json records " + e.getValue() + "); prepare a new submission");
            }
        }
        return new LocalSubmission(dir, submissionId, runId, hashes.get(SubmissionPreparer.SUBMISSION_FILE), jobName,
                concurrency, List.copyOf(prompts), List.copyOf(tasks), hashes);
    }

    private static void expectFile(Map<?, ?> files, String key, String path, Map<String, String> hashes)
            throws InvalidException {
        if (!(files.get(key) instanceof Map<?, ?> f) || !path.equals(f.get("path"))
                || !(f.get("sha256") instanceof String sha) || !SHA.matcher(sha).matches()) {
            throw new InvalidException("submission.json files." + key + " must name " + path + " with a SHA-256");
        }
        hashes.put(path, sha);
    }

    private static String string(Map<?, ?> m, String key) throws InvalidException {
        if (!(m.get(key) instanceof String s) || s.isEmpty()) {
            throw new InvalidException("submission.json: missing " + key);
        }
        return s;
    }

    private static int number(Map<?, ?> m, String key) throws InvalidException {
        if (!(m.get(key) instanceof Long n)) {
            throw new InvalidException("submission.json: missing number " + key);
        }
        return n.intValue();
    }
}
