package generalannotator.proposals;

import generalannotator.edits.AnnotationEdit;
import generalannotator.edits.AnnotationPosition;
import generalannotator.edits.EditAction;
import generalannotator.edits.EditOrigin;
import generalannotator.edits.MemberRef;
import generalannotator.edits.Proposal;
import generalannotator.edits.TypeUsePath;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.inference.SubmissionPreparer;
import generalannotator.inference.remote.CollectedResults;
import generalannotator.report.JsonParser;
import generalannotator.targets.WarningTarget;
import generalannotator.targets.WarningTargetsJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Imports the collected answer of one warning as a {@link Proposal}.
 *
 * <ol>
 *   <li>The result is selected through {@link CollectedResults}: the entry {@code remote/collection.json}
 *       records, verified again against the submission (identities, prompt hash, attempt metadata).
 *   <li>Only COMPLETED_PROPOSED results are imported. FAILED, NO_RESULT, INVALID_RESULT and
 *       COMPLETED_INSUFFICIENT_EVIDENCE are reported as {@link NotApplicable}, never as empty
 *       proposals.
 *   <li>The answer is validated against the {@code prompt-template-1} response format and converted
 *       to {@link AnnotationEdit}s; identical edits are merged, keeping every origin.
 *   <li>The source root comes from the run's artifacts, not from the proposal: the warning's
 *       resolved target in {@code warning-targets.json} (the package root the prompt named), whose
 *       configured root must be one of {@code report.json}'s source roots. The snapshot is the
 *       run's {@code workspace/target}.
 * </ol>
 */
public final class ProposalImporter {

    private static final Pattern WARNING_ID = Pattern.compile("W[0-9]{4,}");

    private ProposalImporter() {}

    /** The artifacts are missing or inconsistent; nothing can be imported. */
    public static final class InvalidException extends Exception {
        private static final long serialVersionUID = 1L;

        public InvalidException(String message) {
            super(message);
        }
    }

    /** The run a proposal belongs to. */
    public record RunContext(Path runDir, String runId, Path snapshot, String sourceRoot,
            Optional<String> recordedWorkingCopy) {}

    /** The outcome of an import. */
    public sealed interface Result permits Ready, NotApplicable {
        CollectedResults.Entry entry();
    }

    /** A proposal ready to apply. */
    public record Ready(CollectedResults.Entry entry, RunContext run, Proposal proposal) implements Result {}

    /** No proposal to apply, with the result's status and why. */
    public record NotApplicable(CollectedResults.Entry entry, String status, String reason) implements Result {}

    public static Result load(Path submissionDir, String warningId) throws InvalidException, IOException {
        if (!WARNING_ID.matcher(warningId).matches()) {
            throw new InvalidException("'" + warningId + "' is not a warning ID like W0001");
        }
        Path submission = submissionDir.toAbsolutePath().normalize();
        Path inference = submission.getParent();
        if (inference == null || !inference.getFileName().toString().equals("inference")
                || inference.getParent() == null) {
            throw new InvalidException(submission + " is not a submission directory (runs/<run id>/inference/<id>)");
        }
        Path runDir = inference.getParent();
        CollectedResults.Entry entry;
        try {
            entry = CollectedResults.select(submission, warningId);
        } catch (CollectedResults.InvalidException e) {
            throw new InvalidException(e.getMessage());
        }
        RunContext run = run(runDir, entry);
        switch (entry.status()) {
            case "COMPLETED_PROPOSED" -> { }
            case "COMPLETED_INSUFFICIENT_EVIDENCE" -> {
                return new NotApplicable(entry, entry.status(), "the model answered INSUFFICIENT_EVIDENCE and proposed "
                        + "no edits; there is nothing to apply");
            }
            case "FAILED" -> {
                return new NotApplicable(entry, entry.status(), "inference failed (" + entry.errorKind().orElse("?")
                        + "): " + entry.error().orElse("") + "; there is no answer to apply");
            }
            case "NO_RESULT" -> {
                return new NotApplicable(entry, entry.status(), "no result was collected for " + warningId);
            }
            default -> {
                return new NotApplicable(entry, entry.status(), "the collected result is invalid: "
                        + String.join("; ", entry.problems()));
            }
        }
        Path answerFile = entry.answerFile().orElseThrow(() -> new InvalidException("no answer file for " + warningId));
        byte[] bytes = Files.readAllBytes(answerFile);
        Map<?, ?> answer;
        try {
            answer = JsonParser.parse(new String(bytes, StandardCharsets.UTF_8)) instanceof Map<?, ?> m ? m : null;
        } catch (RuntimeException e) {
            answer = null;
        }
        if (answer == null) {
            return new NotApplicable(entry, "INVALID_ANSWER", answerFile + " is not a JSON object");
        }
        List<String> problems = new ArrayList<>();
        List<AnnotationEdit> edits = new ArrayList<>();
        List<EditOrigin> origins = new ArrayList<>();
        if (!warningId.equals(answer.get("warningId"))) {
            problems.add("warningId is " + answer.get("warningId") + ", expected " + warningId);
        }
        if (!"PROPOSED".equals(answer.get("status"))) {
            problems.add("status is " + answer.get("status") + ", expected PROPOSED for a COMPLETED_PROPOSED result");
        }
        String rationale = text(answer, "rationale", "", problems);
        String uncertainty = text(answer, "uncertainty", "", problems);
        if (!(answer.get("edits") instanceof List<?> list) || list.isEmpty()) {
            problems.add("edits must be a non-empty array");
        } else {
            for (int i = 0; i < list.size(); i++) {
                String where = "edits[" + i + "].";
                if (!(list.get(i) instanceof Map<?, ?> e)) {
                    problems.add("edits[" + i + "] must be an object");
                    continue;
                }
                try {
                    edits.add(edit(e, run.sourceRoot(), where, problems));
                    origins.add(new EditOrigin(warningId, i, Optional.ofNullable(text(e, "uncertainty", where,
                            problems))));
                } catch (IllegalArgumentException x) {
                    problems.add(where + " " + x.getMessage());
                }
            }
        }
        if (!problems.isEmpty()) {
            return new NotApplicable(entry, "INVALID_ANSWER", "the answer does not describe valid annotation edits: "
                    + String.join("; ", problems));
        }
        Proposal.ProposalSource source = new Proposal.ProposalSource(entry.runId(), entry.submissionId(), warningId,
                entry.attempt().orElseThrow(), entry.promptSha256(), answerFile, SubmissionPreparer.sha256(bytes));
        return new Ready(entry, run, new Proposal(source, Proposal.deduplicate(edits, origins), rationale,
                uncertainty));
    }

    private static AnnotationEdit edit(Map<?, ?> e, String sourceRoot, String where, List<String> problems) {
        String file = required(e, "file", where);
        String declaringClass = required(e, "declaringClass", where);
        MemberRef member = MemberRef.parse(required(e, "member", where));
        String action = required(e, "action", where);
        String annotation = required(e, "annotation", where);
        if (!(e.get("position") instanceof Map<?, ?> p) || !(p.get("kind") instanceof String kind)) {
            throw new IllegalArgumentException("position.kind is missing");
        }
        Object index = p.get("parameterIndex");
        Object path = p.get("typeUsePath");
        AnnotationPosition position = switch (kind) {
            case "FIELD" -> {
                requireAbsent(index, path, "FIELD");
                yield AnnotationPosition.field();
            }
            case "RETURN" -> {
                requireAbsent(index, path, "RETURN");
                yield AnnotationPosition.returnType();
            }
            case "PARAMETER" -> {
                if (!(index instanceof Long i) || i < 0 || i > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("position.parameterIndex must be an integer >= 0 for PARAMETER");
                }
                if (path != null) {
                    throw new IllegalArgumentException("PARAMETER takes no typeUsePath");
                }
                yield AnnotationPosition.parameter(i.intValue());
            }
            case "TYPE_USE" -> {
                if (!(path instanceof String s)) {
                    throw new IllegalArgumentException("position.typeUsePath is required for TYPE_USE");
                }
                if (index != null) {
                    throw new IllegalArgumentException("TYPE_USE takes the parameter index inside typeUsePath");
                }
                yield AnnotationPosition.of(TypeUsePath.parse(s));
            }
            default -> throw new IllegalArgumentException("position.kind " + kind + " is not FIELD, RETURN, PARAMETER "
                    + "or TYPE_USE");
        };
        EditAction a;
        try {
            a = EditAction.valueOf(action);
        } catch (IllegalArgumentException x) {
            throw new IllegalArgumentException("action " + action + " is not ADD or REMOVE");
        }
        return new AnnotationEdit(sourceRoot, file, declaringClass, member, position, a, annotation);
    }

    private static void requireAbsent(Object index, Object path, String kind) {
        if (index != null || path != null) {
            throw new IllegalArgumentException(kind + " takes neither parameterIndex nor typeUsePath");
        }
    }

    private static String required(Map<?, ?> m, String key, String where) {
        if (!(m.get(key) instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(key + " must be a non-empty string");
        }
        return s.strip();
    }

    private static String text(Map<?, ?> m, String key, String where, List<String> problems) {
        if (!(m.get(key) instanceof String s) || s.isBlank()) {
            problems.add(where + key + " must be a non-empty string");
            return null;
        }
        return s;
    }

    /** The run's snapshot and the warning's source root, from the run's own artifacts. */
    private static RunContext run(Path runDir, CollectedResults.Entry entry) throws InvalidException, IOException {
        Path graphFile = runDir.resolve(WarningGraphJson.FILE_NAME);
        Path targetsFile = runDir.resolve(WarningTargetsJson.FILE_NAME);
        Path reportFile = runDir.resolve("report.json");
        for (Path f : List.of(graphFile, targetsFile, reportFile)) {
            if (!Files.isRegularFile(f)) {
                throw new InvalidException("the run " + runDir + " has no " + f.getFileName());
            }
        }
        WarningGraph graph;
        WarningTargetsJson.TargetsFile targets;
        Map<?, ?> report;
        try {
            graph = WarningGraphJson.load(graphFile);
            targets = WarningTargetsJson.load(targetsFile);
            report = (Map<?, ?>) JsonParser.parse(Files.readString(reportFile, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new InvalidException("cannot read the run artifacts in " + runDir + ": " + e.getMessage());
        }
        if (!graph.runId().equals(entry.runId()) || !targets.runId().equals(entry.runId())
                || !entry.runId().equals(report.get("runId"))) {
            throw new InvalidException("submission " + entry.submissionId() + " belongs to run " + entry.runId()
                    + ", but " + runDir + " holds run " + graph.runId());
        }
        WarningTarget target = targets.targets().stream().filter(t -> t.warningId().equals(entry.warningId()))
                .findFirst().orElseThrow(() -> new InvalidException(entry.warningId() + " is not in "
                        + WarningTargetsJson.FILE_NAME));
        if (!(target instanceof WarningTarget.Resolved resolved)) {
            throw new InvalidException(entry.warningId() + " has no resolved target, so its source root is unknown");
        }
        List<?> roots = report.get("configuration") instanceof Map<?, ?> c && c.get("sourceRoots") instanceof List<?> l
                ? l : List.of();
        if (resolved.configuredSourceRoot().isEmpty() || !roots.contains(resolved.configuredSourceRoot().get())) {
            throw new InvalidException("the source root of " + entry.warningId() + " (configured "
                    + resolved.configuredSourceRoot().orElse("none") + ") is not among report.json's source roots "
                    + roots);
        }
        Path snapshot = runDir.resolve("workspace").resolve("target");
        if (Files.isSymbolicLink(snapshot) || !Files.isDirectory(snapshot, LinkOption.NOFOLLOW_LINKS)) {
            throw new InvalidException("the run snapshot " + snapshot + " is missing");
        }
        String sourceRoot = resolved.sourceRoot();
        Path rootDir = snapshot.resolve(sourceRoot).normalize();
        if (!rootDir.startsWith(snapshot) || !Files.isDirectory(rootDir, LinkOption.NOFOLLOW_LINKS)
                || !rootDir.toRealPath().startsWith(snapshot.toRealPath())) {
            throw new InvalidException("source root " + sourceRoot + " is not a directory inside the snapshot");
        }
        Optional<String> recorded = report.get("isolation") instanceof Map<?, ?> iso
                && iso.get("workingCopy") instanceof String w ? Optional.of(w) : Optional.empty();
        return new RunContext(runDir, entry.runId(), snapshot, sourceRoot, recorded);
    }
}
