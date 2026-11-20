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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The candidate annotation packages of one inference submission. Every validated COMPLETED_PROPOSED
 * answer is imported through {@link ProposalImporter} (which reuses the collection's verification)
 * and becomes <b>one package</b>: the complete set of edits proposed together for one warning. A
 * package is applied as a whole and checked once; its edits are never evaluated one by one.
 *
 * <p>Within a package, identical entries of the answer's {@code edits} are merged (identity: source
 * root, file, class, member, exact position, action and annotation, {@link #editKey}), keeping every
 * entry's index and uncertainty. Packages from answers about different warnings stay separate even if
 * they share edits. Failed, missing, invalid and insufficient-evidence answers are listed in
 * {@link #excluded()} with the reason, never turned into empty packages. Package IDs {@code A0001, ...}
 * follow numeric warning order, so they do not depend on any filter.
 *
 * @param submissionId the inference submission
 * @param runId the analyzed run
 * @param runDir the run directory
 * @param snapshot the run's snapshot ({@code workspace/target}), read only
 * @param sourceRoots the run's configured source roots ({@code report.json})
 * @param candidates the packages, one per proposed answer
 * @param excluded the answers that yielded no package, with reasons
 */
public record Candidates(String submissionId, String runId, Path runDir, Path snapshot, List<String> sourceRoots,
        List<Candidate> candidates, List<Excluded> excluded) {

    public static final String FILE = "candidates.json";
    /** The candidate model recorded in candidates.json and the evaluation key. */
    public static final String MODEL = "answer-package-1";
    public static final String NOTE = "Each candidate is one answer's complete edit set for one warning, applied "
            + "together and checked once against the baseline. Packages are evaluated independently of each other; "
            + "independently successful packages are not shown to combine safely.";

    public Candidates {
        sourceRoots = List.copyOf(sourceRoots);
        candidates = List.copyOf(candidates);
        excluded = List.copyOf(excluded);
    }

    /**
     * One package: the distinct edits of one answer, with its provenance.
     *
     * @param id the package ID, {@code A0001, ...}
     * @param warningId the warning of the analyzed run the answer was about
     * @param group the answer ID ({@code <warning>@<attempt>})
     * @param answerFile the answer file, relative to the run directory
     * @param answerEdits the number of entries in the answer's {@code edits} (before merging)
     * @param edits the distinct edits, in answer order
     */
    public record Candidate(String id, String warningId, String group, int attempt, String promptSha256,
            String answerFile, String answerSha256, String rationale, String uncertainty, int answerEdits,
            List<PackageEdit> edits) {
        public Candidate {
            edits = List.copyOf(edits);
            if (edits.isEmpty()) {
                throw new IllegalArgumentException("a package has at least one edit");
            }
        }

        public boolean proposedFor(String warning) {
            return warningId.equals(warning);
        }

        public List<AnnotationEdit> annotationEdits() {
            return edits.stream().map(PackageEdit::edit).toList();
        }

        /**
         * The identity of the package: its originating answer (warning, answer, answer hash) and its complete
         * edit set in answer order. Used for resume together with the evaluation key (baseline,
         * configuration and round).
         */
        public String packageKey() {
            StringBuilder b = new StringBuilder(warningId).append('\n').append(group).append('\n').append(answerSha256);
            for (PackageEdit e : edits) {
                b.append("\n--\n").append(editKey(e.edit()));
            }
            return EvaluationFiles.sha256(b.toString());
        }

        /** A one-line description of the package. */
        public String canonical() {
            return "package of " + edits.size() + " edit(s) proposed for " + warningId + " (" + group + ")";
        }
    }

    /**
     * One distinct edit of a package.
     *
     * @param answerEditIndices the indices of every identical entry in the answer's {@code edits}
     * @param uncertainties the per-entry uncertainty, in the same order
     */
    public record PackageEdit(AnnotationEdit edit, List<Integer> answerEditIndices, List<Optional<String>> uncertainties) {
        public PackageEdit {
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

    /** Imports every collected answer of the submission (read only). */
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
        List<Candidate> candidates = new ArrayList<>();
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
            Proposal p = ((ProposalImporter.Ready) result).proposal();
            List<PackageEdit> edits = new ArrayList<>();
            int answerEdits = 0;
            for (ProposedEdit pe : p.edits()) {
                answerEdits += pe.origins().size();
                edits.add(new PackageEdit(pe.edit(), pe.origins().stream().map(EditOrigin::editIndex).toList(),
                        pe.origins().stream().map(EditOrigin::uncertainty).toList()));
            }
            candidates.add(new Candidate(String.format("A%04d", candidates.size() + 1), p.source().warningId(),
                    p.source().warningId() + "@" + p.source().attempt(), p.source().attempt(),
                    p.source().promptSha256(),
                    runDir.relativize(p.source().answerFile().toAbsolutePath().normalize()).toString(),
                    p.source().answerSha256(), p.rationale(), p.uncertainty(), answerEdits, edits));
        }
        return new Candidates(submissionId, runId, runDir, snapshot, roots, candidates, excluded);
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

    public int editCount() {
        return candidates.stream().mapToInt(c -> c.edits().size()).sum();
    }

    /** {@code candidates.json}: deterministic (no timestamps), so equal imports give equal files. */
    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 2);
        root.put("candidateModel", MODEL);
        root.put("submissionId", submissionId);
        root.put("runId", runId);
        root.put("note", NOTE);
        List<Object> cs = new ArrayList<>();
        for (Candidate c : candidates) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("warningId", c.warningId());
            m.put("group", c.group());
            m.put("packageKey", c.packageKey());
            m.put("canonical", c.canonical());
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("submissionId", submissionId);
            source.put("runId", runId);
            source.put("attempt", c.attempt());
            source.put("promptSha256", c.promptSha256());
            source.put("answerFile", c.answerFile());
            source.put("answerSha256", c.answerSha256());
            source.put("rationale", c.rationale());
            source.put("uncertainty", c.uncertainty());
            source.put("answerEdits", c.answerEdits());
            m.put("proposal", source);
            m.put("edits", editsJson(c));
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

    /** The package's edits as JSON: canonical form, identity fields, answer indices and uncertainties. */
    static List<Object> editsJson(Candidate c) {
        List<Object> list = new ArrayList<>();
        for (PackageEdit e : c.edits()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("canonical", e.edit().canonical());
            m.put("edit", editFields(e.edit()));
            m.put("answerEditIndices", e.answerEditIndices());
            m.put("uncertainties", e.uncertainties().stream().map(u -> u.orElse(null)).toList());
            list.add(m);
        }
        return list;
    }
}
