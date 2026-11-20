package generalannotator.inference.remote;

import generalannotator.report.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only access to one collected inference result for later pipeline stages. It reuses the
 * collection's own checks: the prepared submission's inputs must still match their recorded SHA-256
 * ({@link LocalSubmission}), and the result in {@code remote/collected/} is verified again
 * ({@link ResultCollector#verify}: submission, run, warning, prompt hash, attempt metadata, answer
 * and raw response files). The entry recorded in {@code remote/collection.json} must agree with that
 * verification, so the result used is exactly the one the collection selected.
 */
public final class CollectedResults {

    private CollectedResults() {}

    /** The result cannot be used; the message says why. */
    public static final class InvalidException extends Exception {
        private static final long serialVersionUID = 1L;

        public InvalidException(String message) {
            super(message);
        }
    }

    /**
     * One verified result. {@code status} is a collection status: COMPLETED_PROPOSED,
     * COMPLETED_INSUFFICIENT_EVIDENCE, FAILED, NO_RESULT or INVALID_RESULT. Paths are absolute; files
     * that do not exist are empty.
     */
    public record Entry(String submissionId, String runId, String checker, String warningId, String promptSha256,
            String status, Optional<String> errorKind, Optional<String> error, Optional<Integer> attempt,
            Optional<Path> resultFile, Optional<Path> answerFile, Optional<Path> rawResponseFile,
            Optional<Path> reasoningFile, List<String> problems) {}

    /**
     * The warnings of a prepared submission in submission order, after verifying the submission's inputs
     * and that results were collected ({@code remote/collection.json} exists).
     */
    public static List<String> warningIds(Path submissionDir) throws InvalidException, IOException {
        LocalSubmission local;
        try {
            local = LocalSubmission.load(submissionDir);
        } catch (LocalSubmission.InvalidException e) {
            throw new InvalidException(e.getMessage());
        }
        if (!Files.isRegularFile(submissionDir.resolve(StateStore.DIR).resolve(RemoteInference.COLLECTION))) {
            throw new InvalidException("no " + StateStore.DIR + "/" + RemoteInference.COLLECTION + " in " + submissionDir
                    + "; collect the results first (inference collect)");
        }
        return local.prompts().stream().map(LocalSubmission.Prompt::warningId).toList();
    }

    public static Entry select(Path submissionDir, String warningId) throws InvalidException, IOException {
        LocalSubmission local;
        try {
            local = LocalSubmission.load(submissionDir);
        } catch (LocalSubmission.InvalidException e) {
            throw new InvalidException(e.getMessage());
        }
        LocalSubmission.Prompt prompt = local.prompts().stream().filter(p -> p.warningId().equals(warningId))
                .findFirst().orElseThrow(() -> new InvalidException("warning " + warningId + " is not in submission "
                        + local.submissionId()));
        Path collectionFile = submissionDir.resolve(StateStore.DIR).resolve(RemoteInference.COLLECTION);
        if (!Files.isRegularFile(collectionFile)) {
            throw new InvalidException("no " + StateStore.DIR + "/" + RemoteInference.COLLECTION + " in " + submissionDir
                    + "; collect the results first (inference collect)");
        }
        Map<?, ?> collection;
        try {
            collection = (Map<?, ?>) JsonParser.parse(Files.readString(collectionFile, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new InvalidException(collectionFile + " is not valid JSON: " + e.getMessage());
        }
        if (!local.submissionId().equals(collection.get("submissionId")) || !local.runId().equals(collection.get("runId"))) {
            throw new InvalidException(collectionFile + " belongs to another submission or run");
        }
        Map<?, ?> recorded = null;
        if (collection.get("prompts") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && warningId.equals(m.get("warningId"))) {
                    recorded = m;
                }
            }
        }
        if (recorded == null) {
            throw new InvalidException(collectionFile + " has no entry for " + warningId);
        }
        ResultCollector.PromptResult verified = ResultCollector.verify(local,
                submissionDir.resolve(StateStore.DIR).resolve("collected")).stream()
                .filter(p -> p.warningId().equals(warningId)).findFirst().orElseThrow();
        Map<?, ?> files = recorded.get("files") instanceof Map<?, ?> f ? f : Map.of();
        Integer recordedAttempt = recorded.get("attempt") instanceof Long a ? a.intValue() : null;
        if (!verified.status().name().equals(recorded.get("status"))
                || !java.util.Objects.equals(verified.attempt(), recordedAttempt)
                || !java.util.Objects.equals(verified.answerFile(), files.get("answer"))) {
            throw new InvalidException("collection.json records " + warningId + " as " + recorded.get("status")
                    + " (attempt " + recordedAttempt + ", answer " + files.get("answer") + "), but the collected files "
                    + "verify as " + verified.status() + " (attempt " + verified.attempt() + ", answer "
                    + verified.answerFile() + "); run inference collect again");
        }
        String checker = null;
        try {
            Map<?, ?> submission = (Map<?, ?>) JsonParser.parse(Files.readString(submissionDir.resolve(
                    "submission.json"), StandardCharsets.UTF_8));
            checker = submission.get("checker") instanceof String c ? c : null;
        } catch (RuntimeException e) {
            // LocalSubmission already parsed it; unreachable in practice
        }
        return new Entry(local.submissionId(), local.runId(), checker, warningId, prompt.sha256(),
                verified.status().name(), Optional.ofNullable(verified.errorKind()), Optional.ofNullable(verified.error()),
                Optional.ofNullable(verified.attempt()), path(submissionDir, verified.resultFile()),
                path(submissionDir, verified.answerFile()), path(submissionDir, verified.rawResponseFile()),
                path(submissionDir, verified.reasoningFile()), verified.problems());
    }

    private static Optional<Path> path(Path base, String relative) {
        return relative == null ? Optional.empty() : Optional.of(base.resolve(relative).toAbsolutePath().normalize());
    }
}
