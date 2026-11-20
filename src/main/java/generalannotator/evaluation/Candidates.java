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
 * The candidate edits of one inference submission: every validated COMPLETED_PROPOSED answer is
 * imported through {@link ProposalImporter} (which reuses the collection's verification), and
 * identical edits are merged across answers.
 *
 * <p>Edit identity is {@link #editKey}: source root, file, declaring class, member, exact position,
 * action and annotation name. Each merged candidate keeps every originating association (warning,
 * proposal group and index in the answer). A proposal group is one answer: all of its edits were
 * proposed together, but each candidate is evaluated alone, so a candidate's result does not establish
 * the effect of its group's complete edit set.
 *
 * <p>Failed, missing, invalid and insufficient-evidence answers are listed in {@link #excluded()} with
 * the reason, never turned into empty proposals. Candidate IDs {@code A0001, ...} follow the first
 * occurrence in numeric warning order and answer edit order, so they do not depend on any filter.
 *
 * @param submissionId the inference submission
 * @param runId the analyzed run
 * @param runDir the run directory
 * @param snapshot the run's snapshot ({@code workspace/target}), read only
 * @param sourceRoots the run's configured source roots ({@code report.json})
 * @param candidates the distinct edits
 * @param groups the imported answers
 * @param excluded the answers that yielded no candidates, with reasons
 */
public record Candidates(String submissionId, String runId, Path runDir, Path snapshot, List<String> sourceRoots,
        List<Candidate> candidates, List<Group> groups, List<Excluded> excluded) {

    public static final String FILE = "candidates.json";
    public static final String GROUP_NOTE = "Each candidate is evaluated alone against the baseline. For an answer "
            + "with several edits, the individual results do not establish the effect of the complete edit set, and "
            + "independently successful edits are not shown to combine safely.";

    public Candidates {
        sourceRoots = List.copyOf(sourceRoots);
        candidates = List.copyOf(candidates);
        groups = List.copyOf(groups);
        excluded = List.copyOf(excluded);
    }

    /** One distinct edit and every answer entry that proposed it. */
    public record Candidate(String id, AnnotationEdit edit, List<Association> associations) {
        public Candidate {
            associations = List.copyOf(associations);
        }

        public boolean proposedFor(String warningId) {
            return associations.stream().anyMatch(a -> a.warningId().equals(warningId));
        }
    }

    /**
     * One answer entry that proposed a candidate.
     *
     * @param warningId the warning of the analyzed run the answer was about
     * @param group the proposal group ID ({@code <warning>@<attempt>})
     * @param answerEditIndex the index of the edit in the answer's {@code edits}
     * @param uncertainty the per-edit uncertainty the model gave
     */
    public record Association(String warningId, String group, int answerEditIndex, Optional<String> uncertainty) {}

    /**
     * One imported answer: the edits proposed together for one warning, with provenance.
     *
     * @param answerFile the answer file, relative to the run directory
     * @param candidateIds the candidates of its distinct edits, in answer order
     */
    public record Group(String id, String warningId, int attempt, String promptSha256, String answerFile,
            String answerSha256, String rationale, String uncertainty, int answerEdits, List<String> candidateIds) {
        public Group {
            candidateIds = List.copyOf(candidateIds);
        }
    }

    /** An answer without candidates: its collection or answer status and why. */
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
        Map<String, List<Association>> byEdit = new LinkedHashMap<>();
        Map<String, AnnotationEdit> edits = new LinkedHashMap<>();
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
            Proposal p = ((ProposalImporter.Ready) result).proposal();
            proposals.add(p);
            String group = group(p);
            for (ProposedEdit pe : p.edits()) {
                String key = editKey(pe.edit());
                edits.putIfAbsent(key, pe.edit());
                for (EditOrigin o : pe.origins()) {
                    byEdit.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(new Association(o.warningId(), group, o.editIndex(), o.uncertainty()));
                }
            }
        }
        Map<String, String> idByKey = new LinkedHashMap<>();
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, AnnotationEdit> e : edits.entrySet()) {
            String cid = String.format("A%04d", candidates.size() + 1);
            idByKey.put(e.getKey(), cid);
            candidates.add(new Candidate(cid, e.getValue(), byEdit.get(e.getKey())));
        }
        List<Group> groups = new ArrayList<>();
        for (Proposal p : proposals) {
            int answerEdits = p.edits().stream().mapToInt(pe -> pe.origins().size()).sum();
            groups.add(new Group(group(p), p.source().warningId(), p.source().attempt(), p.source().promptSha256(),
                    runDir.relativize(p.source().answerFile().toAbsolutePath().normalize()).toString(),
                    p.source().answerSha256(), p.rationale(), p.uncertainty(), answerEdits,
                    p.edits().stream().map(pe -> idByKey.get(editKey(pe.edit()))).toList()));
        }
        return new Candidates(submissionId, runId, runDir, snapshot, roots, candidates, groups, excluded);
    }

    private static String group(Proposal p) {
        return p.source().warningId() + "@" + p.source().attempt();
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

    public Optional<Group> group(String id) {
        return groups.stream().filter(g -> g.id().equals(id)).findFirst();
    }

    /** {@code candidates.json}: deterministic (no timestamps), so equal imports give equal files. */
    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("submissionId", submissionId);
        root.put("runId", runId);
        root.put("note", GROUP_NOTE);
        List<Object> cs = new ArrayList<>();
        for (Candidate c : candidates) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("canonical", c.edit().canonical());
            m.put("edit", editFields(c.edit()));
            List<Object> as = new ArrayList<>();
            for (Association a : c.associations()) {
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("warningId", a.warningId());
                am.put("group", a.group());
                am.put("answerEditIndex", a.answerEditIndex());
                am.put("uncertainty", a.uncertainty().orElse(null));
                as.add(am);
            }
            m.put("associations", as);
            cs.add(m);
        }
        root.put("candidates", cs);
        List<Object> gs = new ArrayList<>();
        for (Group g : groups) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.id());
            m.put("warningId", g.warningId());
            m.put("submissionId", submissionId);
            m.put("runId", runId);
            m.put("attempt", g.attempt());
            m.put("promptSha256", g.promptSha256());
            m.put("answerFile", g.answerFile());
            m.put("answerSha256", g.answerSha256());
            m.put("rationale", g.rationale());
            m.put("uncertainty", g.uncertainty());
            m.put("answerEdits", g.answerEdits());
            m.put("candidates", g.candidateIds());
            gs.add(m);
        }
        root.put("groups", gs);
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
}
