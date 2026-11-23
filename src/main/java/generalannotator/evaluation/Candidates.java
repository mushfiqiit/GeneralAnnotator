package generalannotator.evaluation;

import generalannotator.edits.AnnotationEdit;
import generalannotator.edits.EditOrigin;
import generalannotator.edits.Proposal;
import generalannotator.edits.ProposedEdit;
import generalannotator.inference.remote.CollectedResults;
import generalannotator.proposals.ProposalImporter;
import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The candidate annotation packages of one inference submission. Every validated COMPLETED_PROPOSED
 * answer is imported through {@link ProposalImporter} (which reuses the collection's verification and
 * merges identical entries within the answer). Answers whose <b>complete edit sets</b> are equal become
 * <b>one package</b>, whatever warning they were about: the package is applied as a whole, checked once
 * and has one graph node, and every answer stays attached to it as an {@link Origin}.
 *
 * <p>Equivalence is exact equality of {@link AnnotationEdit} values (source root, source-relative file,
 * declaring class, member signature, position including parameter index and type-use path, action and
 * annotation name), as sets: edit order and repeated identical entries do not matter. Only what the edit
 * model already normalizes when an edit is built (parameter type text, a type-use path without steps)
 * is treated as equal; nothing is resolved or matched approximately. Rationale, uncertainty, warning and
 * inference provenance are not part of equivalence. Packages that only overlap, or where one is a subset
 * of another, stay separate, and a package is never split.
 *
 * <p>A package's edits are kept in canonical order (by {@link #editKey}), so equivalent answers give the
 * same package whatever order they listed their edits in. Package IDs {@code A0001, ...} follow the
 * numeric order of each package's first originating warning, so they do not depend on any filter.
 * Failed, missing, invalid and insufficient-evidence answers are listed in {@link #excluded()} with the
 * reason, never turned into empty packages.
 *
 * @param submissionId the inference submission
 * @param runId the analyzed run
 * @param runDir the run directory
 * @param snapshot the run's snapshot ({@code workspace/target}), read only
 * @param sourceRoots the run's configured source roots ({@code report.json})
 * @param candidates the unique packages
 * @param excluded the answers that yielded no package, with reasons
 * @param generator what proposed the packages: {@value #LLM_GENERATOR} for collected LLM answers, or a
 *     deterministic pass such as {@code nullability-propagation-2}
 */
public record Candidates(String submissionId, String runId, Path runDir, Path snapshot, List<String> sourceRoots,
        List<Candidate> candidates, List<Excluded> excluded, String generator) {

    public static final String FILE = "candidates.json";
    /** The candidate model recorded in candidates.json and the evaluation key. */
    public static final String MODEL = "equivalent-package-1";
    /**
     * The earlier model: one package per answer, so equivalent answers were evaluated separately (T8b).
     * Its evaluations are kept; their checker results can be reused only through {@link PackageReuse}.
     */
    public static final String ANSWER_PACKAGE_MODEL = "answer-package-1";
    public static final String NOTE = "Each candidate is one complete edit set, applied together and checked once "
            + "against the baseline. Answers (for any warning) proposing exactly the same edit set share one "
            + "candidate and are all listed as its origins. Candidates are evaluated independently of each other; "
            + "independently successful candidates are not shown to combine safely.";

    /** The generator of packages imported from collected LLM answers. */
    public static final String LLM_GENERATOR = "llm-answers";

    public Candidates(String submissionId, String runId, Path runDir, Path snapshot, List<String> sourceRoots,
            List<Candidate> candidates, List<Excluded> excluded) {
        this(submissionId, runId, runDir, snapshot, sourceRoots, candidates, excluded, LLM_GENERATOR);
    }

    public Candidates {
        java.util.Objects.requireNonNull(generator, "generator");
        sourceRoots = List.copyOf(sourceRoots);
        candidates = List.copyOf(candidates);
        excluded = List.copyOf(excluded);
    }

    /**
     * One unique package: a complete edit set and every answer that proposed exactly that set.
     *
     * @param id the package ID, {@code A0001, ...}
     * @param edits the distinct edits, in canonical order
     * @param origins the answers that proposed the package, in numeric warning order
     */
    public record Candidate(String id, List<AnnotationEdit> edits, List<Origin> origins) {
        public Candidate {
            edits = List.copyOf(edits);
            origins = List.copyOf(origins);
            if (edits.isEmpty()) {
                throw new IllegalArgumentException("a package has at least one edit");
            }
            if (new LinkedHashSet<>(edits).size() != edits.size()) {
                throw new IllegalArgumentException("a package holds distinct edits");
            }
            if (origins.isEmpty()) {
                throw new IllegalArgumentException("a package has at least one originating answer");
            }
            for (Origin o : origins) {
                if (o.edits().size() != edits.size()) {
                    throw new IllegalArgumentException("origin " + o.group() + " must associate every edit");
                }
            }
        }

        /** Whether any originating answer was about {@code warning}. */
        public boolean proposedFor(String warning) {
            return origins.stream().anyMatch(o -> o.warningId().equals(warning));
        }

        /** The warnings of the originating answers, in order. */
        public List<String> warningIds() {
            return origins.stream().map(Origin::warningId).toList();
        }

        /**
         * The exact canonical text of the edit set: every edit's identity fields, in canonical order. Two
         * packages are equivalent exactly when these are equal (it is what resume compares, never only
         * {@link #packageKey}).
         */
        public String canonicalKey() {
            StringBuilder b = new StringBuilder();
            for (AnnotationEdit e : edits) {
                b.append(editKey(e)).append("\n--\n");
            }
            return b.toString();
        }

        /** A SHA-256 of {@link #canonicalKey}: an index for the package, never a replacement for equality. */
        public String packageKey() {
            return EvaluationFiles.sha256(canonicalKey());
        }

        /** A one-line description of the package. */
        public String canonical() {
            String n = "package of " + edits.size() + " edit(s) proposed ";
            if (origins.size() == 1) {
                return n + "for " + origins.get(0).warningId() + " (" + origins.get(0).group() + ")";
            }
            return n + "by " + origins.size() + " answers (" + String.join(", ", warningIds()) + ")";
        }
    }

    /**
     * One answer that proposed a package, with its provenance. Not part of package identity.
     *
     * @param warningId the warning (of the analyzed run) the answer was about
     * @param group the answer ID ({@code <warning>@<attempt>})
     * @param answerFile the answer file, relative to the run directory
     * @param answerEdits the number of entries in the answer's {@code edits} (before merging)
     * @param edits for every edit of the package (same order), the answer entries that proposed it
     */
    public record Origin(String warningId, String group, int attempt, String promptSha256, String answerFile,
            String answerSha256, String rationale, String uncertainty, int answerEdits, List<EditAssociation> edits) {
        public Origin {
            edits = List.copyOf(edits);
        }
    }

    /**
     * The entries of one answer's {@code edits} that proposed one edit of the package.
     *
     * @param answerEditIndices their indices in the answer
     * @param uncertainties their per-entry uncertainty, in the same order
     */
    public record EditAssociation(List<Integer> answerEditIndices, List<Optional<String>> uncertainties) {
        public EditAssociation {
            answerEditIndices = List.copyOf(answerEditIndices);
            uncertainties = List.copyOf(uncertainties);
        }
    }

    /** An answer without a package: its collection or answer status and why. */
    public record Excluded(String warningId, String status, String reason) {}

    /** The submission or run cannot be used at all. */
    public static final class InvalidException extends Exception {
        private static final long serialVersionUID = 1L;

        public InvalidException(String message) {
            super(message);
        }
    }

    /** Imports every collected answer of the submission (read only) and merges equivalent ones. */
    public static Candidates load(Path submissionDir) throws InvalidException, IOException {
        Path submission = submissionDir.toAbsolutePath().normalize();
        Path inference = submission.getParent();
        if (inference == null || !inference.getFileName().toString().equals("inference")
                || inference.getParent() == null) {
            throw new InvalidException(submission + " is not a submission directory (runs/<run id>/inference/<id>)");
        }
        Path runDir = inference.getParent();
        List<String> ids;
        try {
            ids = new ArrayList<>(CollectedResults.warningIds(submission));
        } catch (CollectedResults.InvalidException e) {
            throw new InvalidException(e.getMessage());
        }
        ids.sort(Comparator.comparingLong((String id) -> Long.parseLong(id.substring(1))).thenComparing(id -> id));
        Map<?, ?> report = report(runDir);
        String runId = report.get("runId") instanceof String r ? r : null;
        List<String> roots = new ArrayList<>();
        if (report.get("configuration") instanceof Map<?, ?> c && c.get("sourceRoots") instanceof List<?> l) {
            l.forEach(o -> roots.add(String.valueOf(o)));
        }
        if (runId == null || roots.isEmpty()) {
            throw new InvalidException(runDir.resolve("report.json") + " has no runId or configured source roots");
        }
        Path snapshot = runDir.resolve("workspace").resolve("target");
        if (Files.isSymbolicLink(snapshot) || !Files.isDirectory(snapshot, LinkOption.NOFOLLOW_LINKS)) {
            throw new InvalidException("the run snapshot " + snapshot + " is missing");
        }

        String submissionId = submission.getFileName().toString();
        List<Proposal> proposals = new ArrayList<>();
        List<Excluded> excluded = new ArrayList<>();
        for (String id : ids) {
            ProposalImporter.Result result;
            try {
                result = ProposalImporter.load(submission, id);
            } catch (ProposalImporter.InvalidException e) {
                excluded.add(new Excluded(id, "INVALID", e.getMessage()));
                continue;
            }
            submissionId = result.entry().submissionId();
            if (!result.entry().runId().equals(runId)) {
                throw new InvalidException("submission belongs to run " + result.entry().runId() + ", but "
                        + runDir + " holds run " + runId);
            }
            if (result instanceof ProposalImporter.NotApplicable n) {
                excluded.add(new Excluded(id, n.status(), n.reason()));
                continue;
            }
            proposals.add(((ProposalImporter.Ready) result).proposal());
        }
        return new Candidates(submissionId, runId, runDir, snapshot, roots, merge(runDir, proposals), excluded);
    }

    /**
     * Merges answers with equal complete edit sets into packages. Grouping uses {@link AnnotationEdit}
     * equality on sets (exact, order-free); keys and hashes are only derived from it.
     *
     * @param proposals the validated answers, in numeric warning order
     */
    static List<Candidate> merge(Path runDir, List<Proposal> proposals) {
        return merge(runDir, proposals, p -> p.source().warningId() + "@" + p.source().attempt(), "A");
    }

    /**
     * Packages proposed by something other than collected LLM answers (for example a deterministic pass),
     * merged exactly like answers: one package per complete edit set, every proposal kept as an origin.
     *
     * @param groupSuffix names each origin {@code <warning>@<groupSuffix>}, so nodes show what proposed them
     */
    public static Candidates fromProposals(String submissionId, String runId, Path runDir, Path snapshot,
            List<String> sourceRoots, List<Proposal> proposals, String generator, String groupSuffix) {
        return fromProposals(submissionId, runId, runDir, snapshot, sourceRoots, proposals, generator, groupSuffix,
                "A");
    }

    /** Like the other overload, with package IDs {@code <idPrefix>0001, ...} (for example {@code P0001}). */
    public static Candidates fromProposals(String submissionId, String runId, Path runDir, Path snapshot,
            List<String> sourceRoots, List<Proposal> proposals, String generator, String groupSuffix,
            String idPrefix) {
        return new Candidates(submissionId, runId, runDir, snapshot, sourceRoots,
                merge(runDir, proposals, p -> p.source().warningId() + "@" + groupSuffix, idPrefix), List.of(),
                generator);
    }

    /** These candidates restricted to {@code ids} (same IDs, provenance and generator). */
    public Candidates only(Set<String> ids) {
        return new Candidates(submissionId, runId, runDir, snapshot, sourceRoots,
                candidates.stream().filter(c -> ids.contains(c.id())).toList(), excluded, generator);
    }

    private static List<Candidate> merge(Path runDir, List<Proposal> proposals,
            java.util.function.Function<Proposal, String> group, String idPrefix) {
        Map<Set<AnnotationEdit>, List<Proposal>> groups = new LinkedHashMap<>();
        for (Proposal p : proposals) {
            Set<AnnotationEdit> set = new LinkedHashSet<>();
            p.edits().forEach(pe -> set.add(pe.edit()));
            groups.computeIfAbsent(Set.copyOf(set), s -> new ArrayList<>()).add(p);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<Set<AnnotationEdit>, List<Proposal>> g : groups.entrySet()) {
            List<AnnotationEdit> edits = new ArrayList<>(g.getKey());
            edits.sort(Comparator.comparing(Candidates::editKey));
            for (int i = 1; i < edits.size(); i++) {
                if (editKey(edits.get(i - 1)).equals(editKey(edits.get(i)))) {
                    // equal keys for unequal edits would make the canonical order depend on input order
                    throw new IllegalStateException("distinct edits with one canonical key: " + editKey(edits.get(i)));
                }
            }
            List<Origin> origins = new ArrayList<>();
            for (Proposal p : g.getValue()) {
                List<EditAssociation> associations = new ArrayList<>();
                int answerEdits = 0;
                for (ProposedEdit pe : p.edits()) {
                    answerEdits += pe.origins().size();
                }
                for (AnnotationEdit e : edits) {
                    ProposedEdit pe = p.edits().stream().filter(x -> x.edit().equals(e)).findFirst().orElseThrow();
                    associations.add(new EditAssociation(pe.origins().stream().map(EditOrigin::editIndex).toList(),
                            pe.origins().stream().map(EditOrigin::uncertainty).toList()));
                }
                Proposal.ProposalSource s = p.source();
                origins.add(new Origin(s.warningId(), group.apply(p), s.attempt(), s.promptSha256(),
                        runDir.relativize(s.answerFile().toAbsolutePath().normalize()).toString(), s.answerSha256(),
                        p.rationale(), p.uncertainty(), answerEdits, associations));
            }
            candidates.add(new Candidate(String.format("%s%04d", idPrefix, candidates.size() + 1), edits, origins));
        }
        return candidates;
    }

    private static Map<?, ?> report(Path runDir) throws InvalidException, IOException {
        Path file = runDir.resolve("report.json");
        if (!Files.isRegularFile(file)) {
            throw new InvalidException("the run " + runDir + " has no report.json");
        }
        try {
            return (Map<?, ?>) JsonParser.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new InvalidException(file + " is not a valid report: " + e.getMessage());
        }
    }

    /**
     * The identity of an edit: source root, file, declaring class, member, exact position, action and
     * annotation name, one per line.
     */
    public static String editKey(AnnotationEdit e) {
        return String.join("\n", e.sourceRoot(), e.file(), e.declaringClass(), e.member().canonical(),
                e.position().toString(), e.action().name(), e.annotation());
    }

    /** The edit's identity fields, as written to JSON and graph nodes. */
    public static Map<String, String> editFields(AnnotationEdit e) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("sourceRoot", e.sourceRoot());
        m.put("file", e.file());
        m.put("declaringClass", e.declaringClass());
        m.put("member", e.member().canonical());
        m.put("position", e.position().toString());
        m.put("action", e.action().name());
        m.put("annotation", e.annotation());
        return m;
    }

    /** The number of distinct edits over all packages. */
    public int editCount() {
        return candidates.stream().mapToInt(c -> c.edits().size()).sum();
    }

    /** The number of imported answers (proposals) behind the packages. */
    public int importedProposals() {
        return candidates.stream().mapToInt(c -> c.origins().size()).sum();
    }

    /** {@code candidates.json}: deterministic (no timestamps), so equal imports give equal files. */
    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 3);
        root.put("candidateModel", MODEL);
        if (!generator.equals(LLM_GENERATOR)) {
            // written only for other generators, so answer evaluations keep their keys
            root.put("generator", generator);
        }
        root.put("submissionId", submissionId);
        root.put("runId", runId);
        root.put("note", NOTE);
        root.put("importedProposals", importedProposals());
        root.put("uniquePackages", candidates.size());
        List<Object> cs = new ArrayList<>();
        for (Candidate c : candidates) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("packageKey", c.packageKey());
            m.put("canonical", c.canonical());
            m.put("warningIds", c.warningIds());
            m.put("edits", editsJson(c));
            m.put("origins", originsJson(c));
            cs.add(m);
        }
        root.put("candidates", cs);
        List<Object> xs = new ArrayList<>();
        for (Excluded x : excluded) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("warningId", x.warningId());
            m.put("status", x.status());
            m.put("reason", x.reason());
            xs.add(m);
        }
        root.put("excluded", xs);
        return Json.write(root);
    }

    /** The package's edits as JSON: canonical form and identity fields, in canonical order. */
    static List<Object> editsJson(Candidate c) {
        List<Object> list = new ArrayList<>();
        for (AnnotationEdit e : c.edits()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("canonical", e.canonical());
            m.put("edit", editFields(e));
            list.add(m);
        }
        return list;
    }

    /** The package's originating answers as JSON, with provenance and per-edit associations. */
    List<Object> originsJson(Candidate c) {
        List<Object> list = new ArrayList<>();
        for (Origin o : c.origins()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("warningId", o.warningId());
            m.put("group", o.group());
            m.put("submissionId", submissionId);
            m.put("runId", runId);
            m.put("attempt", o.attempt());
            m.put("promptSha256", o.promptSha256());
            m.put("answerFile", o.answerFile());
            m.put("answerSha256", o.answerSha256());
            m.put("rationale", o.rationale());
            m.put("uncertainty", o.uncertainty());
            m.put("answerEdits", o.answerEdits());
            List<Object> edits = new ArrayList<>();
            for (EditAssociation a : o.edits()) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("answerEditIndices", a.answerEditIndices());
                em.put("uncertainties", a.uncertainties().stream().map(u -> u.orElse(null)).toList());
                edits.add(em);
            }
            m.put("edits", edits);
            list.add(m);
        }
        return list;
    }

    /**
     * Whether a recorded edit list ({@code [{"edit": {fields}}, ...]}) holds exactly this package's
     * edit set, compared field by field (not by hash).
     */
    static boolean sameEditSet(Object recordedEdits, Candidate c) {
        if (!(recordedEdits instanceof List<?> list) || list.size() != c.edits().size()) {
            return false;
        }
        Set<Map<String, String>> expected = new LinkedHashSet<>();
        c.edits().forEach(e -> expected.add(editFields(e)));
        Set<Map<String, String>> recorded = new LinkedHashSet<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m) || !(m.get("edit") instanceof Map<?, ?> f)) {
                return false;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            f.forEach((k, v) -> fields.put(String.valueOf(k), v instanceof String s ? s : null));
            recorded.add(fields);
        }
        return recorded.size() == list.size() && recorded.equals(expected);
    }
}
