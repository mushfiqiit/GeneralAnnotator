package generalannotator.proposals;

import generalannotator.cli.PrepareInferenceCommandTest;
import generalannotator.inference.InferenceSettings;
import generalannotator.inference.SubmissionPreparer;
import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import generalannotator.targets.MemberKind;
import generalannotator.targets.WarningTarget;
import generalannotator.targets.WarningTargetsJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A run directory with a snapshot, targets, report, a prepared submission and a hand-written
 * collection (as {@code inference collect} leaves it). No cluster or LLM is involved.
 */
public final class ProposalFixture {

    public static final String SOURCE = """
            package p;

            /** Fixture class. */
            public class A {
                Object x; // x
                Object y;

                Object get() { return x; }
            }
            """;

    public final Path runDir;
    public final Path submission;
    private final Map<?, ?> submissionJson;
    private final Map<String, Map<String, Object>> collected = new LinkedHashMap<>();

    public ProposalFixture(Path temp, int warnings) throws Exception {
        runDir = PrepareInferenceCommandTest.makeRun(temp.resolve("runs/run1"), warnings);
        Path snapshot = runDir.resolve("workspace/target");
        Files.createDirectories(snapshot.resolve("src/p"));
        Files.writeString(snapshot.resolve("src/p/A.java"), SOURCE);
        List<WarningTarget> targets = new ArrayList<>();
        for (int i = 1; i <= warnings; i++) {
            String id = String.format("W%04d", i);
            targets.add(new WarningTarget.Resolved(id, "src/p/A.java:5:12", "src", snapshot.resolve("src").toString(),
                    "p/A.java", MemberKind.FIELD, "p.A", "p.A#x", Optional.of("src"), false, List.of()));
        }
        WarningTargetsJson.save(runDir.resolve(WarningTargetsJson.FILE_NAME), "run 1", snapshot, targets);
        report(List.of("src"));
        List<String> problems = new ArrayList<>();
        submission = SubmissionPreparer.prepare(runDir, Optional.empty(), InferenceSettings.fromEnvironment(Map.of(),
                problems), PrepareInferenceCommandTest.CLOCK).directory();
        submissionJson = (Map<?, ?>) JsonParser.parse(Files.readString(submission.resolve("submission.json")));
    }

    public void report(List<String> sourceRoots) throws IOException {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", "run 1");
        report.put("configuration", Map.of("sourceRoots", sourceRoots));
        report.put("isolation", Map.of("workingCopy", runDir.resolve("workspace/target").toString()));
        Files.writeString(runDir.resolve("report.json"), Json.write(report));
    }

    public static Map<String, Object> editJson(String member, Map<String, Object> position, String action) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("file", "p/A.java");
        e.put("declaringClass", "p.A");
        e.put("member", member);
        e.put("position", position);
        e.put("action", action);
        e.put("annotation", "org.jspecify.annotations.Nullable");
        e.put("uncertainty", "u-" + member);
        return e;
    }

    public static Map<String, Object> answer(String id, String status, List<Map<String, Object>> edits) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("warningId", id);
        a.put("status", status);
        a.put("edits", edits);
        a.put("rationale", "because");
        a.put("uncertainty", "some");
        return a;
    }

    /** Writes a completed result with this answer (attempt 1), as the worker would. */
    public void completed(String id, Map<String, Object> answer) throws IOException {
        String status = "PROPOSED".equals(answer.get("status")) ? "COMPLETED_PROPOSED"
                : "COMPLETED_INSUFFICIENT_EVIDENCE";
        Path attempt = submission.resolve("remote/collected/results/" + id + "/attempt-001");
        Files.createDirectories(attempt);
        Files.writeString(attempt.resolve("answer.json"), Json.write(answer));
        Files.writeString(attempt.resolve("raw-response.json"), "{}");
        result(id, status, null, Map.of("answer", "answer.json", "rawResponse", "raw-response.json"));
    }

    public void failed(String id, String kind) throws IOException {
        Files.createDirectories(submission.resolve("remote/collected/results/" + id + "/attempt-001"));
        result(id, "FAILED", kind, Map.of());
    }

    private void result(String id, String status, String errorKind, Map<String, Object> files) throws IOException {
        Map<?, ?> prompt = ((List<?>) submissionJson.get("prompts")).stream().map(o -> (Map<?, ?>) o)
                .filter(p -> p.get("warningId").equals(id)).findFirst().orElseThrow();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", 1);
        m.put("submissionId", submissionJson.get("submissionId"));
        m.put("runId", submissionJson.get("runId"));
        m.put("warningId", id);
        m.put("attempt", 1);
        m.put("promptSha256", prompt.get("sha256"));
        m.put("status", status);
        m.put("errorKind", errorKind);
        m.put("error", errorKind == null ? null : "it failed");
        m.put("files", files);
        Path dir = submission.resolve("remote/collected/results/" + id);
        Files.writeString(dir.resolve("attempt-001/metadata.json"), Json.write(m));
        Files.writeString(dir.resolve("result.json"), Json.write(m));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("warningId", id);
        entry.put("status", status);
        entry.put("attempt", 1);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("answer", files.containsKey("answer") ? "remote/collected/results/" + id + "/attempt-001/answer.json"
                : null);
        entry.put("files", f);
        collected.put(id, entry);
    }

    /** Writes remote/collection.json for every prompt (NO_RESULT where nothing was written). */
    public void collection() throws IOException {
        List<Object> prompts = new ArrayList<>();
        for (Object o : (List<?>) submissionJson.get("prompts")) {
            String id = (String) ((Map<?, ?>) o).get("warningId");
            Map<String, Object> e = collected.get(id);
            if (e == null) {
                e = new LinkedHashMap<>();
                e.put("warningId", id);
                e.put("status", "NO_RESULT");
                e.put("attempt", null);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("answer", null);
                e.put("files", f);
            }
            prompts.add(e);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("submissionId", submissionJson.get("submissionId"));
        root.put("runId", submissionJson.get("runId"));
        root.put("prompts", prompts);
        Files.createDirectories(submission.resolve("remote"));
        Files.writeString(submission.resolve("remote/collection.json"), Json.write(root));
    }

    /** Overrides the status recorded in collection.json for one warning (a stale collection). */
    public void recordStatus(String id, String status) {
        collected.get(id).put("status", status);
    }
}
