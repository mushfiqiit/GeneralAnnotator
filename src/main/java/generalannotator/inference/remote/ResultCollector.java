package generalannotator.inference.remote;

import generalannotator.report.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns downloaded files into the local collection. {@link #merge} copies the mirror ({@code
 * remote/incoming/}) into {@code remote/collected/} without losing anything: attempt files and
 * sbatch records are write-once, so a differing copy is a conflict and the local copy is kept; a
 * completed {@code result.json} is never replaced by one that is not completed. Logs and task
 * summaries are replaced by their newer versions. {@link #verify} then checks each prompt's result
 * against {@code submission.json} (submission, run, warning and prompt hash) and the files it names.
 */
final class ResultCollector {

    static final Set<String> COMPLETED = Set.of("COMPLETED_PROPOSED", "COMPLETED_INSUFFICIENT_EVIDENCE");
    private static final Pattern ATTEMPT_FILE = Pattern.compile("results/W[0-9]{4,}/attempt-[0-9]{3,}/.+");
    private static final Pattern RESULT_FILE = Pattern.compile("results/W[0-9]{4,}/result\\.json");
    private static final Pattern WARNING_DIR = Pattern.compile("W[0-9]{4,}");

    private ResultCollector() {}

    /** What {@link #merge} did. */
    record Merge(int added, int updated, List<String> conflicts) {}

    /** Per-prompt status after verification; {@code status} is never a scheduler state. */
    enum PromptStatus { COMPLETED_PROPOSED, COMPLETED_INSUFFICIENT_EVIDENCE, FAILED, NO_RESULT, INVALID_RESULT }

    /** One prompt's collected result. Paths are relative to the submission directory. */
    record PromptResult(String warningId, int taskIndex, PromptStatus status, String errorKind, String error,
            Integer attempt, int attempts, String resultFile, String answerFile, String rawResponseFile,
            String reasoningFile, List<String> problems) {

        boolean completed() {
            return status == PromptStatus.COMPLETED_PROPOSED || status == PromptStatus.COMPLETED_INSUFFICIENT_EVIDENCE;
        }
    }

    static Merge merge(Path incoming, Path collected) throws IOException {
        int added = 0;
        int updated = 0;
        List<String> conflicts = new ArrayList<>();
        if (!Files.isDirectory(incoming)) {
            return new Merge(0, 0, conflicts);
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(incoming)) {
            files = walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
        }
        for (Path source : files) {
            String rel = incoming.relativize(source).toString().replace('\\', '/');
            if (rel.contains(".tmp-")) {
                continue;
            }
            Path target = collected.resolve(rel);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                copy(source, target);
                added++;
                continue;
            }
            byte[] newer = Files.readAllBytes(source);
            byte[] older = Files.readAllBytes(target);
            if (Arrays.equals(newer, older)) {
                continue;
            }
            if (ATTEMPT_FILE.matcher(rel).matches() || rel.startsWith(".ga-sbatch/")) {
                conflicts.add(rel + ": the remote copy differs from the collected one; the collected copy is kept "
                        + "(the remote copy stays in remote/incoming/)");
                continue;
            }
            if (RESULT_FILE.matcher(rel).matches()) {
                Map<?, ?> was = parse(older);
                Map<?, ?> now = parse(newer);
                if (was != null && COMPLETED.contains(was.get("status")) && (now == null
                        || !COMPLETED.contains(now.get("status"))
                        || !Objects.equals(was.get("promptSha256"), now.get("promptSha256")))) {
                    conflicts.add(rel + ": the collected result is completed and the remote one is not (or is for "
                            + "another prompt hash); the collected result is kept");
                    continue;
                }
            }
            copy(source, target);
            updated++;
        }
        return new Merge(added, updated, conflicts);
    }

    private static void copy(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling("." + target.getFileName() + ".ga-tmp");
        Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Map<?, ?> parse(byte[] bytes) {
        try {
            return JsonParser.parse(new String(bytes, StandardCharsets.UTF_8)) instanceof Map<?, ?> m ? m : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Map<?, ?> parse(Path file) {
        try {
            return parse(Files.readAllBytes(file));
        } catch (IOException e) {
            return null;
        }
    }

    static List<PromptResult> verify(LocalSubmission submission, Path collected) {
        List<PromptResult> results = new ArrayList<>();
        Path base = submission.dir();
        for (LocalSubmission.Prompt p : submission.prompts()) {
            Path dir = collected.resolve("results").resolve(p.warningId());
            int attempts = attemptCount(dir);
            Path resultFile = dir.resolve("result.json");
            if (!Files.isRegularFile(resultFile, LinkOption.NOFOLLOW_LINKS)) {
                results.add(new PromptResult(p.warningId(), p.taskIndex(), PromptStatus.NO_RESULT, null, null, null,
                        attempts, null, null, null, null, List.of()));
                continue;
            }
            List<String> problems = new ArrayList<>();
            Map<?, ?> r = parse(resultFile);
            String rel = rel(base, resultFile);
            if (r == null) {
                results.add(new PromptResult(p.warningId(), p.taskIndex(), PromptStatus.INVALID_RESULT, null, null,
                        null, attempts, rel, null, null, null, List.of("result.json is not a JSON object")));
                continue;
            }
            expect(r, "submissionId", submission.submissionId(), problems);
            expect(r, "runId", submission.runId(), problems);
            expect(r, "warningId", p.warningId(), problems);
            expect(r, "promptSha256", p.sha256(), problems);
            Object status = r.get("status");
            Integer attempt = r.get("attempt") instanceof Long a ? a.intValue() : null;
            String errorKind = r.get("errorKind") instanceof String s ? s : null;
            String error = r.get("error") instanceof String s ? clip(s) : null;
            String answer = null;
            String raw = null;
            String reasoning = null;
            Map<?, ?> files = r.get("files") instanceof Map<?, ?> f ? f : Map.of();
            Path attemptDir = attempt == null ? null : dir.resolve(String.format("attempt-%03d", attempt));
            if (attemptDir == null) {
                problems.add("result.json has no attempt number");
            } else {
                raw = existing(base, attemptDir, files.get("rawResponse"), "raw response", problems, false);
                reasoning = existing(base, attemptDir, files.get("reasoning"), "reasoning", problems, false);
                Map<?, ?> metadata = parse(attemptDir.resolve("metadata.json"));
                if (metadata == null) {
                    problems.add(String.format("attempt-%03d/metadata.json is missing or invalid", attempt));
                } else if (!metadata.equals(r)) {
                    problems.add(String.format("result.json differs from attempt-%03d/metadata.json", attempt));
                }
            }
            PromptStatus promptStatus;
            if (COMPLETED.contains(status)) {
                promptStatus = PromptStatus.valueOf((String) status);
                if (attemptDir != null) {
                    answer = existing(base, attemptDir, files.get("answer"), "answer", problems, true);
                    if (raw == null) {
                        problems.add("a completed result has no raw response file");
                    }
                    if (answer != null) {
                        Map<?, ?> a = parse(base.resolve(answer));
                        String expected = promptStatus == PromptStatus.COMPLETED_PROPOSED ? "PROPOSED"
                                : "INSUFFICIENT_EVIDENCE";
                        if (a == null || !p.warningId().equals(a.get("warningId")) || !expected.equals(a.get("status"))) {
                            problems.add("answer.json does not match the result (warningId " + p.warningId()
                                    + ", status " + expected + ")");
                        }
                    }
                }
            } else if ("FAILED".equals(status)) {
                promptStatus = PromptStatus.FAILED;
                if (errorKind == null) {
                    problems.add("a failed result has no errorKind");
                }
            } else {
                promptStatus = PromptStatus.INVALID_RESULT;
                problems.add("unexpected status " + status);
            }
            if (!problems.isEmpty()) {
                promptStatus = PromptStatus.INVALID_RESULT;
            }
            results.add(new PromptResult(p.warningId(), p.taskIndex(), promptStatus, errorKind, error, attempt,
                    attempts, rel, answer, raw, reasoning, List.copyOf(problems)));
        }
        return results;
    }

    /** Result directories for warnings that are not in this submission. */
    static List<String> unexpected(LocalSubmission submission, Path collected) throws IOException {
        Path results = collected.resolve("results");
        Set<String> known = new TreeSet<>();
        submission.prompts().forEach(p -> known.add(p.warningId()));
        List<String> extra = new ArrayList<>();
        if (!Files.isDirectory(results)) {
            return extra;
        }
        try (Stream<Path> list = Files.list(results)) {
            for (Path p : list.sorted().toList()) {
                String name = p.getFileName().toString();
                if (WARNING_DIR.matcher(name).matches() && !known.contains(name)) {
                    extra.add(name);
                }
            }
        }
        return extra;
    }

    private static int attemptCount(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> list = Files.list(dir)) {
            return (int) list.filter(p -> p.getFileName().toString().matches("attempt-[0-9]{3,}")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String existing(Path base, Path attemptDir, Object name, String what, List<String> problems,
            boolean required) {
        if (!(name instanceof String n)) {
            if (required) {
                problems.add("result.json names no " + what + " file");
            }
            return null;
        }
        if (!n.matches("[A-Za-z0-9._-]+")) {
            problems.add("unsafe " + what + " file name " + n);
            return null;
        }
        Path f = attemptDir.resolve(n);
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
            problems.add(what + " file " + attemptDir.getFileName() + "/" + n + " is missing");
            return null;
        }
        return rel(base, f);
    }

    private static void expect(Map<?, ?> r, String key, String expected, List<String> problems) {
        if (!expected.equals(r.get(key))) {
            problems.add(key + " is " + r.get(key) + ", expected " + expected);
        }
    }

    private static String rel(Path base, Path file) {
        return base.relativize(file).toString().replace('\\', '/');
    }

    private static String clip(String s) {
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }
}
