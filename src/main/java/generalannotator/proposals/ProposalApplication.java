package generalannotator.proposals;

import generalannotator.edits.AnnotationApplier;
import generalannotator.edits.AnnotationEdit;
import generalannotator.edits.EditOrigin;
import generalannotator.edits.Proposal;
import generalannotator.edits.ProposedEdit;
import generalannotator.edits.UnifiedDiff;
import generalannotator.inference.SubmissionPreparer;
import generalannotator.isolation.SnapshotCopier;
import generalannotator.report.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Applies one imported proposal to a fresh copy of the run's snapshot and records the outcome in a new
 * directory {@code applications/<UTC timestamp>-<warning>/} of the run:
 * <pre>
 * application.json   provenance, canonical edits with APPLIED / NO_OP / REJECTED, overall outcome,
 *                    changed files with SHA-256 before and after
 * changes.diff       unified diff of the changed files (only when edits were applied)
 * workspace/         the edited copy of the snapshot (only when edits were applied)
 * </pre>
 * The edit set is atomic: if any edit is rejected, no workspace is kept. The directory is built under
 * a temporary name and renamed when complete, and an existing application is never overwritten. The
 * snapshot is only read, and its edited files are hashed before and after to confirm it is unchanged.
 * Applying edits says nothing about the checker: whether the warning is resolved is not known here.
 */
public final class ProposalApplication {

    public static final String DIR = "applications";
    public static final String RECORD = "application.json";
    public static final String DIFF = "changes.diff";
    public static final String NOTE = "Source edits only. The checker was not rerun and the warning graph was not "
            + "changed; an APPLIED outcome means the annotations were written, not that the warning is resolved or "
            + "that the code compiles.";
    private static final DateTimeFormatter ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** The overall result. */
    public enum Outcome {
        /** At least one edit changed the source and none was rejected; the workspace holds the result. */
        APPLIED,
        /** Every edit was a no-op; the source would not change, so no workspace is kept. */
        NO_CHANGE,
        /** At least one edit was rejected; nothing was applied. */
        REJECTED
    }

    /** What was written. */
    public record Applied(Path directory, Outcome outcome, AnnotationApplier.ApplyResult result) {}

    private ProposalApplication() {}

    public static Applied apply(ProposalImporter.Ready ready, AnnotationApplier applier, Clock clock)
            throws IOException {
        ProposalImporter.RunContext run = ready.run();
        Proposal proposal = ready.proposal();
        Path applications = run.runDir().resolve(DIR);
        Files.createDirectories(applications);
        String base = ID_FORMAT.format(clock.instant()) + "-" + proposal.source().warningId();
        String id = base;
        for (int n = 2; Files.exists(applications.resolve(id), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(applications.resolve("." + id + ".partial"), LinkOption.NOFOLLOW_LINKS); n++) {
            id = base + "-" + n;
        }
        Path partial = applications.resolve("." + id + ".partial");
        Path target = applications.resolve(id);
        List<AnnotationEdit> edits = proposal.edits().stream().map(ProposedEdit::edit).toList();
        Map<String, String> snapshotBefore = hashes(run.snapshot(), edits);
        try {
            Files.createDirectories(partial);
            Path workspace = partial.resolve("workspace");
            SnapshotCopier.Copy copy = SnapshotCopier.copy(run.snapshot(), workspace);
            AnnotationApplier.ApplyResult result = applier.apply(workspace, edits);
            Outcome outcome = result.rejected() ? Outcome.REJECTED
                    : result.committed() ? Outcome.APPLIED : Outcome.NO_CHANGE;
            if (outcome != Outcome.APPLIED) {
                deleteTree(workspace);
            } else {
                StringBuilder diff = new StringBuilder();
                for (AnnotationApplier.FileChange c : result.changes()) {
                    diff.append(UnifiedDiff.of(c.path(), c.before(), c.after()));
                }
                Files.writeString(partial.resolve(DIFF), diff.toString(), StandardCharsets.UTF_8);
            }
            Map<String, String> snapshotAfter = hashes(run.snapshot(), edits);
            if (!snapshotBefore.equals(snapshotAfter)) {
                throw new IOException("the run snapshot changed while applying (" + run.snapshot() + ")");
            }
            Files.writeString(partial.resolve(RECORD), Json.write(record(id, ready, outcome, result, copy,
                    snapshotBefore, clock)), StandardCharsets.UTF_8);
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            return new Applied(target, outcome, result);
        } catch (IOException | RuntimeException e) {
            deleteTree(partial);
            throw e;
        }
    }

    private static Map<String, Object> record(String id, ProposalImporter.Ready ready, Outcome outcome,
            AnnotationApplier.ApplyResult result, SnapshotCopier.Copy copy, Map<String, String> snapshotHashes,
            Clock clock) {
        Proposal p = ready.proposal();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("applicationId", id);
        root.put("createdAt", clock.instant().toString());
        root.put("outcome", outcome.name());
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("runId", ready.run().runId());
        run.put("runDirectory", ready.run().runDir().toString());
        run.put("snapshot", ready.run().snapshot().toString());
        run.put("snapshotRecordedInReport", ready.run().recordedWorkingCopy().orElse(null));
        run.put("sourceRoot", ready.run().sourceRoot());
        root.put("run", run);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("submissionId", p.source().submissionId());
        source.put("warningId", p.source().warningId());
        source.put("checker", ready.entry().checker());
        source.put("attempt", p.source().attempt());
        source.put("promptSha256", p.source().promptSha256());
        source.put("answerFile", p.source().answerFile().toString());
        source.put("answerSha256", p.source().answerSha256());
        root.put("proposal", source);
        root.put("rationale", p.rationale());
        root.put("uncertainty", p.uncertainty());
        List<Object> edits = new ArrayList<>();
        for (int i = 0; i < p.edits().size(); i++) {
            ProposedEdit pe = p.edits().get(i);
            AnnotationEdit e = pe.edit();
            AnnotationApplier.EditResult r = result.results().get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i);
            m.put("canonical", e.canonical());
            m.put("sourceRoot", e.sourceRoot());
            m.put("file", e.file());
            m.put("declaringClass", e.declaringClass());
            m.put("member", e.member().canonical());
            m.put("position", e.position().toString());
            m.put("action", e.action().name());
            m.put("annotation", e.annotation());
            List<Object> origins = new ArrayList<>();
            for (EditOrigin o : pe.origins()) {
                Map<String, Object> om = new LinkedHashMap<>();
                om.put("warningId", o.warningId());
                om.put("answerEditIndex", o.editIndex());
                om.put("uncertainty", o.uncertainty().orElse(null));
                origins.add(om);
            }
            m.put("origins", origins);
            m.put("outcome", r.outcome().name());
            m.put("detail", r.detail());
            edits.add(m);
        }
        root.put("edits", edits);
        root.put("workspace", outcome == Outcome.APPLIED ? "workspace" : null);
        root.put("diff", outcome == Outcome.APPLIED ? DIFF : null);
        List<Object> changed = new ArrayList<>();
        for (AnnotationApplier.FileChange c : result.changes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", c.path());
            m.put("beforeSha256", SubmissionPreparer.sha256(c.before().getBytes(StandardCharsets.UTF_8)));
            m.put("afterSha256", SubmissionPreparer.sha256(c.after().getBytes(StandardCharsets.UTF_8)));
            changed.add(m);
        }
        root.put("changedFiles", changed);
        root.put("snapshotFileSha256", snapshotHashes);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("filesCopied", copy.filesCopied());
        c.put("skippedSymlinks", copy.skippedSymlinks());
        root.put("snapshotCopy", c);
        root.put("limitations", List.of(
                "The run records no hashes of the analyzed sources, so a snapshot edited after the check cannot be "
                        + "detected; snapshotFileSha256 records the files as they were read here.",
                "Annotations are written fully qualified; no import or dependency is added to the target, so the "
                        + "annotation library must already be available when compiling."));
        root.put("note", NOTE);
        return root;
    }

    /** SHA-256 of each edited file in the snapshot (absent files are recorded as missing). */
    private static Map<String, String> hashes(Path snapshot, List<AnnotationEdit> edits) throws IOException {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (AnnotationEdit e : edits) {
            Path f = snapshot.resolve(e.projectPath()).normalize();
            if (!f.startsWith(snapshot) || !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                hashes.put(e.projectPath(), "missing");
            } else {
                hashes.put(e.projectPath(), SubmissionPreparer.sha256(Files.readAllBytes(f)));
            }
        }
        return hashes;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
