package generalannotator.evaluation;

import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Reuse of checker results from an earlier one-package-per-answer evaluation ({@value
 * Candidates#ANSWER_PACKAGE_MODEL}) when evaluating merged packages: the explicit rebuild path
 * ({@code evaluate-annotations --reuse-from}). The earlier evaluation is only read.
 *
 * <p>A result is reused only if everything it depends on is verified equivalent:
 * <ul>
 *   <li>the evaluation key: run, submission, snapshot tree hash, the complete checker configuration
 *       fingerprint, matcher and round are equal (otherwise nothing is reused);
 *   <li>the baseline: the new baseline's findings equal the earlier baseline's findings (paths inside
 *       each baseline copy aside) and the checker identity has no differences;
 *   <li>the package: the earlier attempt recorded exactly the merged package's complete edit set
 *       (compared field by field, not by hash), and its answer is one of the package's origins with the
 *       same answer hash.
 * </ul>
 * Several earlier attempts of one merged package are reused only if their results are equivalent
 * (outcome, detail, counts, resolved, ambiguous and introduced findings, per-edit applier outcomes,
 * diff, comparison and checker identity, with paths inside each attempt's own directory set aside).
 * Otherwise the conflict is reported and the package is evaluated again; results are never chosen
 * between or combined. Only attempts that ran the checker are reused; rejected and no-op packages are
 * evaluated again, which runs no checker.
 */
final class PackageReuse {

    /** Report of each reuse, under the new evaluation directory. */
    static final String REPORT_DIR = "reuse";

    private final Path dir;
    private final String evaluationId;
    private final long round;
    /** Earlier candidate ID to merged candidate ID. */
    private final Map<String, String> mapping;
    private final Map<String, Map<?, ?>> oldCandidates;

    /** The earlier evaluation cannot be used; nothing is reused. */
    static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /** What to do with one merged package. */
    sealed interface Match permits Import, Conflict, NotReused {}

    /**
     * Reuse the result of {@code attempt}, which every equivalent earlier attempt shares.
     *
     * @param record the earlier attempt record
     * @param equivalents every earlier attempt whose result is equivalent (including {@code attempt})
     */
    record Import(Path attemptDir, String attempt, Map<?, ?> record, List<String> equivalents) implements Match {}

    /** Earlier attempts of the package disagree; the package is evaluated again. */
    record Conflict(List<String> attempts, List<String> differences) implements Match {}

    /** Nothing to reuse, and why. */
    record NotReused(List<String> attempts, String reason) implements Match {}

    private PackageReuse(Path dir, String evaluationId, long round, Map<String, String> mapping,
            Map<String, Map<?, ?>> oldCandidates) {
        this.dir = dir;
        this.evaluationId = evaluationId;
        this.round = round;
        this.mapping = mapping;
        this.oldCandidates = oldCandidates;
    }

    Path dir() {
        return dir;
    }

    String evaluationId() {
        return evaluationId;
    }

    /** Earlier candidate ID to merged candidate ID, in earlier order. */
    Map<String, String> mapping() {
        return mapping;
    }

    /**
     * Opens and verifies an earlier evaluation against the new key and candidates.
     *
     * @param newKey the new evaluation key
     */
    static PackageReuse open(Path oldDir, Map<String, Object> newKey, Candidates candidates) throws Refused,
            IOException {
        Path dir = oldDir.toAbsolutePath().normalize();
        Path record = dir.resolve(Evaluator.RECORD);
        if (!Files.isRegularFile(record)) {
            throw new Refused(dir + " is not an evaluation directory (no " + Evaluator.RECORD + ")");
        }
        if (Files.exists(dir.resolve(WarningIdMigration.MARKER))) {
            throw new Refused("a warning-ID migration of " + dir + " did not finish");
        }
        Map<?, ?> r = parse(record);
        Map<?, ?> key = r.get("key") instanceof Map<?, ?> k ? k : Map.of();
        if (!Candidates.ANSWER_PACKAGE_MODEL.equals(key.get("candidateModel"))
                || !Long.valueOf(Evaluator.ANSWER_PACKAGE_RECORD_SCHEMA_VERSION).equals(r.get("schemaVersion"))) {
            throw new Refused(dir + " is not a one-package-per-answer evaluation (" + Candidates.ANSWER_PACKAGE_MODEL
                    + ", records schema " + Evaluator.ANSWER_PACKAGE_RECORD_SCHEMA_VERSION + "); "
                    + (key.get("candidateModel") == null ? "results of single-edit evaluations are never reused for "
                            + "packages" : "it has candidate model " + key.get("candidateModel")));
        }
        List<String> differences = new ArrayList<>();
        for (String field : List.of("runId", "submissionId", "snapshotTreeSha256", "configuration", "matcher",
                "round")) {
            if (!Json.write(newKey.get(field)).equals(Json.write(key.get(field)))) {
                differences.add(field);
            }
        }
        if (!differences.isEmpty()) {
            throw new Refused("its evaluation key differs in " + String.join(", ", differences) + " (baseline snapshot, "
                    + "checker configuration, matcher and round must all be equal)");
        }
        Map<?, ?> cs = parse(dir.resolve(Candidates.FILE));
        Map<String, String> mapping = new LinkedHashMap<>();
        Map<String, Map<?, ?>> oldCandidates = new LinkedHashMap<>();
        for (Object o : (List<?>) cs.get("candidates")) {
            Map<?, ?> old = (Map<?, ?>) o;
            String id = String.valueOf(old.get("id"));
            Optional<Candidates.Candidate> target = candidates.candidates().stream()
                    .filter(c -> origin(c, old).isPresent()).findFirst();
            if (target.isEmpty()) {
                throw new Refused("its candidate " + id + " (" + old.get("group") + ") is not an answer of the current "
                        + "submission with the same answer hash and edit set");
            }
            mapping.put(id, target.get().id());
            oldCandidates.put(id, old);
        }
        return new PackageReuse(dir, String.valueOf(r.get("evaluationId")), (Long) key.get("round"), mapping,
                oldCandidates);
    }

    /** The origin of {@code c} the earlier candidate {@code old} came from, if its edit set is c's. */
    private static Optional<Candidates.Origin> origin(Candidates.Candidate c, Map<?, ?> old) {
        Map<?, ?> proposal = old.get("proposal") instanceof Map<?, ?> p ? p : Map.of();
        if (!Candidates.sameEditSet(old.get("edits"), c)) {
            return Optional.empty();
        }
        return c.origins().stream().filter(o -> o.warningId().equals(old.get("warningId"))
                && o.group().equals(old.get("group")) && o.answerSha256().equals(proposal.get("answerSha256")))
                .findFirst();
    }

    /**
     * Verifies that the new baseline reproduced the earlier one: equal findings and no checker identity
     * difference.
     */
    void checkBaseline(Path newBaselineDir, Map<?, ?> newToolIdentity) throws Refused, IOException {
        Path oldResult = dir.resolve("baseline").resolve("result.json");
        if (!Files.isRegularFile(oldResult)) {
            throw new Refused("its baseline is incomplete");
        }
        Map<?, ?> old = parse(oldResult);
        Map<?, ?> now = parse(newBaselineDir.resolve("result.json"));
        String oldFindings = Json.write(normalize(old.get("findings"), dir.resolve("baseline").resolve("source"),
                "<baseline>"));
        String newFindings = Json.write(normalize(now.get("findings"), newBaselineDir.resolve("source"), "<baseline>"));
        if (!oldFindings.equals(newFindings)) {
            throw new Refused("its baseline findings differ from the new baseline's");
        }
        List<String> tools = ToolIdentity.differences((Map<?, ?>) old.get("toolIdentity"), newToolIdentity);
        if (!tools.isEmpty()) {
            throw new Refused("its baseline was analyzed with a different " + String.join(", ", tools));
        }
    }

    /** Decides what to reuse for one merged package. */
    Match match(Candidates.Candidate c) throws IOException {
        List<String> ids = new ArrayList<>();
        mapping.forEach((old, merged) -> {
            if (merged.equals(c.id())) {
                ids.add(old);
            }
        });
        if (ids.isEmpty()) {
            return new NotReused(ids, "no earlier attempt proposed this package");
        }
        Map<String, Map<?, ?>> records = new LinkedHashMap<>();
        for (String id : ids) {
            Path f = dir.resolve("attempts").resolve(id).resolve(Evaluator.ATTEMPT);
            if (!Files.isRegularFile(f)) {
                continue;
            }
            Map<?, ?> r = parse(f);
            if (!Long.valueOf(Evaluator.ANSWER_PACKAGE_RECORD_SCHEMA_VERSION).equals(r.get("schemaVersion"))
                    || !evaluationId.equals(r.get("evaluationId")) || !Long.valueOf(round).equals(r.get("round"))
                    || !id.equals(r.get("candidateId")) || !Candidates.sameEditSet(r.get("edits"), c)
                    || !String.valueOf(oldCandidates.get(id).get("group")).equals(r.get("group"))) {
                return new NotReused(ids, "the earlier record " + f + " does not match its candidate, round or edit set");
            }
            records.put(id, r);
        }
        if (records.isEmpty()) {
            return new NotReused(ids, "the earlier evaluation has no complete result for it");
        }
        Map<String, String> signatures = new LinkedHashMap<>();
        for (Map.Entry<String, Map<?, ?>> e : records.entrySet()) {
            signatures.put(e.getKey(), Json.write(signature(e.getValue(), dir.resolve("attempts").resolve(e.getKey()))));
        }
        String first = signatures.values().iterator().next();
        if (signatures.values().stream().anyMatch(s -> !s.equals(first))) {
            return new Conflict(List.copyOf(records.keySet()), differences(records));
        }
        String id = records.keySet().iterator().next();
        Map<?, ?> r = records.get(id);
        if (!(r.get("resultState") instanceof String)) {
            return new NotReused(ids, "its earlier outcome " + r.get("outcome") + " involved no checker run; it is "
                    + "evaluated again");
        }
        return new Import(dir.resolve("attempts").resolve(id), id, r, List.copyOf(records.keySet()));
    }

    /** The result-defining content of an attempt, with paths inside its own directory set aside. */
    private static Map<String, Object> signature(Map<?, ?> r, Path attemptDir) throws IOException {
        Map<String, Object> s = new TreeMap<>();
        for (String field : List.of("outcome", "detail", "counts", "resolved", "ambiguous", "findingCount", "failure",
                "toolIdentity", "appliedEdits", "noOpEdits")) {
            s.put(field, r.get(field));
        }
        List<Object> introduced = new ArrayList<>();
        for (Map<?, ?> im : Evaluator.introduced(r)) {
            introduced.add(List.of(String.valueOf(im.get("resultId")), String.valueOf(im.get("targetPath")),
                    im.get("diagnostic")));
        }
        s.put("introduced", introduced);
        Map<String, Object> appliers = new TreeMap<>();
        for (Object o : (List<?>) r.get("edits")) {
            Map<?, ?> e = (Map<?, ?>) o;
            appliers.put(Json.write(new TreeMap<>((Map<?, ?>) e.get("edit"))), e.get("applier"));
        }
        s.put("applier", appliers);
        Path diff = attemptDir.resolve("changes.diff");
        s.put("diff", Files.isRegularFile(diff) ? Files.readString(diff, StandardCharsets.UTF_8) : null);
        Path comparison = attemptDir.resolve(Evaluator.COMPARISON);
        if (Files.isRegularFile(comparison)) {
            Map<Object, Object> c = new TreeMap<>();
            parse(comparison).forEach(c::put);
            c.remove("resultState");
            c.remove("introducedWarnings");
            s.put("comparison", c);
        }
        return normalize(s, attemptDir, "<attempt>");
    }

    private List<String> differences(Map<String, Map<?, ?>> records) throws IOException {
        List<String> fields = new ArrayList<>();
        Map<String, Map<String, Object>> sigs = new LinkedHashMap<>();
        for (Map.Entry<String, Map<?, ?>> e : records.entrySet()) {
            sigs.put(e.getKey(), signature(e.getValue(), dir.resolve("attempts").resolve(e.getKey())));
        }
        Map<String, Object> first = sigs.values().iterator().next();
        for (String field : first.keySet()) {
            String v = Json.write(first.get(field));
            if (sigs.values().stream().anyMatch(s -> !Json.write(s.get(field)).equals(v))) {
                fields.add(field);
            }
        }
        return fields;
    }

    /**
     * {@code value} with every occurrence of {@code dir}'s absolute path in strings replaced by {@code
     * placeholder} (maps and lists are copied).
     */
    @SuppressWarnings("unchecked")
    static <T> T normalize(T value, Path dir, String placeholder) {
        String prefix = dir.toAbsolutePath().normalize().toString();
        return (T) replace(value, prefix, placeholder);
    }

    private static Object replace(Object value, String prefix, String replacement) {
        if (value instanceof String s) {
            return s.replace(prefix, replacement);
        }
        if (value instanceof Map<?, ?> m) {
            Map<Object, Object> copy = m instanceof TreeMap<?, ?> ? new TreeMap<>() : new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(k, replace(v, prefix, replacement)));
            return copy;
        }
        if (value instanceof List<?> l) {
            List<Object> copy = new ArrayList<>();
            l.forEach(v -> copy.add(replace(v, prefix, replacement)));
            return copy;
        }
        return value;
    }

    private static Map<?, ?> parse(Path file) throws IOException {
        try {
            return (Map<?, ?>) JsonParser.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException(file + " is not valid JSON: " + e.getMessage());
        }
    }
}
