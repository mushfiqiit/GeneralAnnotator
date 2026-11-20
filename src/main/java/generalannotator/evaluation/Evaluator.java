package generalannotator.evaluation;

import generalannotator.checker.AnalysisRequest;
import generalannotator.checker.Checker;
import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.checker.CheckerOutcome.ExecutionFailure;
import generalannotator.checker.CheckerRun;
import generalannotator.config.AnalysisConfig;
import generalannotator.edits.AnnotationApplier;
import generalannotator.edits.AnnotationEdit;
import generalannotator.edits.EditAction;
import generalannotator.edits.UnifiedDiff;
import generalannotator.evaluation.matching.DeclarationAnchors;
import generalannotator.evaluation.matching.ObservedFinding;
import generalannotator.evaluation.matching.WarningMatcher;
import generalannotator.exec.RecordedCommand;
import generalannotator.graph.AnnotationNode;
import generalannotator.graph.InitialWarningGraph;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.graph.WarningIds;
import generalannotator.graph.WarningNode;
import generalannotator.isolation.SnapshotCopier;
import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Evaluates every candidate edit independently against one immutable baseline and records the results
 * as an expanded graph.
 *
 * <p>Layout of {@code runs/<run>/evaluations/<submission id>-<key>/}, where the key is a hash of
 * everything results depend on (run snapshot tree hash, checker configuration fingerprint, candidate
 * set and matching rule), so results are only ever reused for the same baseline, configuration and
 * edit:
 * <pre>
 * evaluation.json        the key material, settings, matching assumptions and limitations
 * lock                   held by the one writing process
 * candidates.json        candidates, proposal groups and excluded answers ({@link Candidates})
 * baseline/              complete only when result.json exists
 *   source/              a full explicit copy of the run snapshot (state S0, never changed)
 *   check/               checker logs and generated files
 *   result.json          findings with IDs and anchors, tool identity, configuration
 * baseline-failed/<t>/   a failed baseline, kept for inspection (evaluation is blocked)
 * attempts/<A0001>/      complete only when attempt.json exists
 *   source/              baseline copy with this package's complete edit set (applied packages only)
 *   changes.diff         the package as a unified diff over every changed file (applied packages only)
 *   check/               checker logs of the one checker run (applied packages only)
 *   comparison.json      matcher result (completed analyses only)
 *   attempt.json         outcome, counts, resolved and introduced warnings, provenance
 * attempts/interrupted/<A0001>-<n>/   an attempt directory left without attempt.json, moved aside
 * evaluation-graph.json  the expanded graph (rebuilt from the records after every invocation)
 * summary.json           per-candidate outcomes and counts
 * </pre>
 * One attempt is one annotation package ({@link Candidates.Candidate}): every edit of one answer is
 * applied together, all or nothing, and the checker runs once. Packages never accumulate: every attempt
 * starts from {@code baseline/source}. The run's own
 * snapshot, its {@code warning-graph.json} and the inference artifacts are only read; the snapshot's
 * tree hash is checked again at the end.
 */
public final class Evaluator {

    public static final String DIR = "evaluations";
    public static final String RECORD = "evaluation.json";
    public static final String GRAPH = "evaluation-graph.json";
    public static final String SUMMARY = "summary.json";
    public static final String ATTEMPT = "attempt.json";
    public static final String COMPARISON = "comparison.json";
    public static final String BASELINE_STATE = "S0";
    /** Version of baseline/result.json. */
    static final int SCHEMA_VERSION = 1;
    /**
     * Version of the evaluation key. Version 2 adds the candidate model ({@value Candidates#MODEL}) and the
     * round, so package evaluations never share a directory (or cached results) with the earlier single-edit
     * evaluations (key version 1), which are kept as they are.
     */
    static final int KEY_SCHEMA_VERSION = 2;
    /**
     * Version of evaluation.json, attempt.json and summary.json for package evaluations. Version 1 records
     * were single-edit records without rounds, version 2 single-edit records with rounds ({@link
     * #EDIT_LEVEL_RECORD_SCHEMA_VERSION}, what {@link WarningIdMigration} produces); neither is reused.
     */
    public static final int RECORD_SCHEMA_VERSION = 3;
    /** The record version of single-edit evaluations with rounds (historical). */
    public static final int EDIT_LEVEL_RECORD_SCHEMA_VERSION = 2;

    public static final List<String> LIMITATIONS = List.of(
            "Each candidate is one answer's complete edit set, applied together and checked once. Packages are "
                    + "evaluated alone against the same baseline and never accumulate, so the graph does not show "
                    + "whether independently successful packages combine safely.",
            "Warnings introduced by a package are recorded, but no fixes are inferred for them.",
            "Warning identity across states is the provisional rule recorded with each comparison "
                    + "(project-spec §6.1); ambiguous findings support no resolution or introduction claim.",
            "A package's result is that of its whole edit set; it says nothing about which edit of the package "
                    + "caused which change.",
            "An incomplete analysis (compilation failure, crash, timeout) gives no evidence about warnings; it never "
                    + "counts as zero warnings or as a resolution.");

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final Candidates candidates;
    private final EvaluationConfig config;
    private final Checker checker;
    private final AnnotationApplier applier;
    private final WarningMatcher matcher;
    private final int round;
    private final Clock clock;
    private final PrintStream out;
    private final ToolIdentity tools = new ToolIdentity();

    /**
     * @param round the inference round these candidates were proposed in (1 for answers about the
     *     run's initial warnings); recorded in evaluation.json and on every node it creates
     */
    public Evaluator(Candidates candidates, EvaluationConfig config, Checker checker, AnnotationApplier applier,
            WarningMatcher matcher, int round, Clock clock, PrintStream out) {
        if (round < 1) {
            throw new IllegalArgumentException("round must be >= 1: " + round);
        }
        this.round = round;
        this.candidates = candidates;
        this.config = config;
        this.checker = checker;
        this.applier = applier;
        this.matcher = matcher;
        this.clock = clock;
        this.out = out;
    }

    /** How an invocation ended. */
    public enum Status {
        /** Every selected candidate has a recorded outcome. */
        COMPLETED,
        /** The baseline analysis failed; nothing was evaluated. */
        BASELINE_FAILED,
        /** Another process is writing the evaluation directory. */
        BUSY,
        /** The evaluation directory cannot be continued as it is (for example it needs migration). */
        REFUSED
    }

    /** One line of the per-candidate summary. */
    public record AttemptSummary(String candidateId, AttemptOutcome outcome, String detail,
            Optional<AnnotationNode.Counts> counts, String how, Path directory) {}

    /** The result of {@link #run}. */
    public record Result(Status status, Path directory, String message, List<AttemptSummary> attempts) {
        public Result {
            attempts = List.copyOf(attempts);
        }
    }

    /** The completed baseline. */
    record Baseline(Path dir, List<ObservedFinding> findings, List<WarningNode> nodes, Map<?, ?> toolIdentity,
            String sourceTreeHash, boolean reused) {}

    /**
     * Evaluates the selected candidates (all, or those proposed for {@code warningFilter}); earlier
     * complete attempts are reused, interrupted ones are moved aside and rerun. The filter selects
     * candidates only: the checker always analyzes every configured source root and comparisons use
     * every finding.
     */
    @SuppressWarnings("try")
    public Result run(Optional<String> warningFilter) throws IOException {
        String snapshotHash = EvaluationFiles.treeHash(candidates.snapshot());
        String candidatesJson = candidates.toJson();
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("schemaVersion", KEY_SCHEMA_VERSION);
        key.put("candidateModel", Candidates.MODEL);
        key.put("round", round);
        key.put("runId", candidates.runId());
        key.put("submissionId", candidates.submissionId());
        key.put("snapshotTreeSha256", snapshotHash);
        key.put("configuration", config.fingerprint());
        key.put("candidatesSha256", EvaluationFiles.sha256(candidatesJson));
        key.put("matcher", matcher.id());
        String keyJson = Json.write(key);
        String evaluationId = candidates.submissionId() + "-" + EvaluationFiles.sha256(keyJson).substring(0, 12);
        Path dir = candidates.runDir().resolve(DIR).resolve(evaluationId);
        try (EvaluationFiles.Lock lock = EvaluationFiles.lock(dir, "pid " + ProcessHandle.current().pid()
                + " since " + clock.instant())) {
            return locked(dir, evaluationId, key, keyJson, candidatesJson, snapshotHash, warningFilter);
        } catch (EvaluationFiles.BusyException e) {
            return new Result(Status.BUSY, dir, e.getMessage(), List.of());
        }
    }

    private Result locked(Path dir, String evaluationId, Map<String, Object> key, String keyJson,
            String candidatesJson, String snapshotHash, Optional<String> warningFilter) throws IOException {
        Path record = dir.resolve(RECORD);
        if (Files.exists(dir.resolve(WarningIdMigration.MARKER))) {
            return refused(dir, "a warning-ID migration of " + dir + " did not finish; run migrate-warning-ids '" + dir
                    + "' again to complete it");
        }
        if (Files.exists(record)) {
            Map<?, ?> existing = parse(record);
            if (!keyJson.equals(Json.write(existing.get("key")))) {
                throw new IOException(record + " belongs to a different baseline, configuration or candidate set");
            }
            if (!(existing.get("round") instanceof Long recorded)) {
                return refused(dir, dir + " was written before warning IDs recorded the inference round (introduced "
                        + "warnings are named like A0001-N1). Convert it first, keeping a backup: migrate-warning-ids '"
                        + dir + "'");
            }
            if (recorded != round) {
                return refused(dir, dir + " records round " + recorded + ", not round " + round);
            }
        } else {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("schemaVersion", RECORD_SCHEMA_VERSION);
            m.put("evaluationId", evaluationId);
            m.put("createdAt", clock.instant().toString());
            m.put("round", round);
            m.put("warningIdScheme", WarningIds.SCHEME);
            m.put("candidateModel", Candidates.MODEL);
            m.put("key", key);
            m.put("runDirectory", candidates.runDir().toString());
            m.put("snapshot", candidates.snapshot().toString());
            m.put("baselineState", BASELINE_STATE);
            m.put("timeoutSeconds", config.analysis().timeout().toSeconds());
            m.put("matcher", matcher.id());
            m.put("matcherAssumptions", matcher.assumptions());
            m.put("limitations", LIMITATIONS);
            EvaluationFiles.writeAtomically(record, Json.write(m));
        }
        Path candidatesFile = dir.resolve(Candidates.FILE);
        if (!Files.exists(candidatesFile)) {
            EvaluationFiles.writeAtomically(candidatesFile, candidatesJson);
        } else if (!Files.readString(candidatesFile, StandardCharsets.UTF_8).equals(candidatesJson)) {
            throw new IOException(candidatesFile + " was changed; it no longer matches the evaluation key");
        }

        for (Candidates.Candidate c : candidates.candidates()) {
            Path f = dir.resolve("attempts").resolve(c.id()).resolve(ATTEMPT);
            if (Files.exists(f) && !Long.valueOf(RECORD_SCHEMA_VERSION).equals(parse(f).get("schemaVersion"))) {
                return refused(dir, f + " is not a package record (schema " + RECORD_SCHEMA_VERSION + "); results of "
                        + "earlier single-edit evaluations are never reused as package results");
            }
        }

        out.println("  evaluation: " + dir + " (round " + round + ", one annotation package per answer)");
        for (Path other : olderEvaluations(dir)) {
            out.println("  kept, not reused: " + other + " (an earlier single-edit evaluation; its per-edit results "
                    + "are never combined into package results)");
        }
        Optional<Baseline> found = baseline(dir, evaluationId, snapshotHash);
        if (found.isEmpty()) {
            return new Result(Status.BASELINE_FAILED, dir, "the baseline analysis did not complete; see "
                    + dir.resolve("baseline-failed"), List.of());
        }
        Baseline baseline = found.get();
        out.println("  baseline:   " + (baseline.reused() ? "reused, " : "") + baseline.findings().size()
                + " finding(s) in state " + BASELINE_STATE + " (" + baseline.dir() + ")");
        Map<String, String> original = originalMapping(baseline);
        Optional<String> invalid = Optional.empty();
        WarningIds.Allocator ids;
        {
            Set<String> taken = new LinkedHashSet<>();
            baseline.nodes().forEach(n -> taken.add(n.id()));
            int last = 0;
            for (Map.Entry<String, Map<?, ?>> e : records(dir).entrySet()) {
                for (Map<?, ?> im : introduced(e.getValue())) {
                    String id = String.valueOf(im.get("warningId"));
                    java.util.OptionalInt seq = WarningIds.sequence(id, round);
                    if (!Long.valueOf(round).equals(im.get("round")) || seq.isEmpty() || !taken.add(id)) {
                        invalid = Optional.of("attempt " + e.getKey() + " records introduced warning " + id + " (round "
                                + im.get("round") + "), which is not a unique round-" + round + " ID");
                    } else {
                        last = Math.max(last, seq.getAsInt());
                    }
                }
            }
            if (invalid.isPresent()) {
                return refused(dir, invalid.get());
            }
            ids = new WarningIds.Allocator(round, taken, last);
        }

        List<AttemptSummary> summaries = new ArrayList<>();
        for (Candidates.Candidate c : candidates.candidates()) {
            if (warningFilter.isPresent() && !c.proposedFor(warningFilter.get())) {
                continue;
            }
            AttemptSummary s = attempt(dir, evaluationId, baseline, c, ids);
            summaries.add(s);
            out.println("  " + c.id() + "  " + c.canonical() + ":");
            for (Candidates.PackageEdit pe : c.edits()) {
                out.println("          " + pe.edit().canonical());
            }
            out.println("  " + line(s));
        }

        if (!snapshotHash.equals(EvaluationFiles.treeHash(candidates.snapshot()))) {
            throw new IOException("the run snapshot " + candidates.snapshot() + " changed during the evaluation");
        }
        if (!baseline.sourceTreeHash().equals(EvaluationFiles.treeHash(baseline.dir().resolve("source")))) {
            throw new IOException("the baseline source " + baseline.dir().resolve("source") + " changed");
        }
        Map<String, Map<?, ?>> records = records(dir);
        Set<String> interrupted = interrupted(dir, records.keySet());
        WarningGraph graph = EvaluationGraph.build(candidates, baseline.nodes(), records, interrupted, original,
                config.analysis().checkerId(), round);
        EvaluationFiles.writeAtomically(dir.resolve(GRAPH), WarningGraphJson.write(graph));
        EvaluationFiles.writeAtomically(dir.resolve(SUMMARY), Json.write(summary(evaluationId, baseline, original,
                records, interrupted, graph, ids)));
        return new Result(Status.COMPLETED, dir, "", summaries);
    }

    // ---------------------------------------------------------------------------------------------
    // Baseline

    private Optional<Baseline> baseline(Path dir, String evaluationId, String snapshotHash) throws IOException {
        Path bdir = dir.resolve("baseline");
        Path result = bdir.resolve("result.json");
        if (Files.exists(result)) {
            Map<?, ?> r = parse(result);
            String recorded = (String) r.get("sourceTreeSha256");
            if (!evaluationId.equals(r.get("evaluationId"))
                    || !recorded.equals(EvaluationFiles.treeHash(bdir.resolve("source")))) {
                throw new IOException(bdir + " does not match its record (evaluation ID or source hash); remove the "
                        + "evaluation directory to start again");
            }
            List<ObservedFinding> findings = new ArrayList<>();
            List<WarningNode> nodes = new ArrayList<>();
            for (Object o : (List<?>) r.get("findings")) {
                Map<?, ?> f = (Map<?, ?>) o;
                ObservedFinding of = FindingJson.finding(f);
                findings.add(of);
                nodes.add(new WarningNode(of.id(), of.diagnostic(), Optional.ofNullable((String) f.get("targetPath")),
                        true, 0, Optional.of(BASELINE_STATE), java.util.OptionalInt.of(0)));
            }
            return Optional.of(new Baseline(bdir, findings, nodes, (Map<?, ?>) r.get("toolIdentity"), recorded, true));
        }
        if (Files.exists(bdir, LinkOption.NOFOLLOW_LINKS)) {
            out.println("  baseline:   an earlier baseline run was interrupted; starting it again");
            moveAside(bdir, dir.resolve("baseline-failed").resolve(STAMP.format(clock.instant()) + "-interrupted"));
        }
        Instant started = clock.instant();
        Path source = bdir.resolve("source");
        SnapshotCopier.Copy copy = SnapshotCopier.copy(candidates.snapshot(), source);
        if (!snapshotHash.equals(EvaluationFiles.treeHash(candidates.snapshot()))) {
            throw new IOException("the run snapshot " + candidates.snapshot() + " changed while it was copied");
        }
        String sourceHash = EvaluationFiles.treeHash(source);
        out.println("  baseline:   running " + config.analysis().checkerId() + " on " + source);
        CheckerRun run = checker.analyze(request(source, bdir.resolve("check")));
        if (!(run.outcome() instanceof CompletedAnalysis completed)) {
            ExecutionFailure f = (ExecutionFailure) run.outcome();
            Path failed = dir.resolve("baseline-failed").resolve(STAMP.format(clock.instant()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("evaluationId", evaluationId);
            m.put("status", "EXECUTION_FAILURE");
            m.put("failureKind", f.kind().name());
            m.put("reasons", f.reasons());
            m.put("findingsObservedBeforeFailure", f.findings().size());
            m.put("commands", commands(run, bdir));
            m.put("note", "A failed baseline blocks the evaluation; its observed findings are incomplete.");
            EvaluationFiles.writeAtomically(bdir.resolve("failure.json"), Json.write(m));
            moveAside(bdir, failed);
            out.println("  baseline:   EXECUTION FAILURE (" + f.kind() + "): " + firstLine(f.reasons().get(0)));
            out.println("              kept in " + failed);
            return Optional.empty();
        }
        WarningGraph graph = InitialWarningGraph.build(candidates.runId(), checker.id(), completed, source);
        DeclarationAnchors anchors = new DeclarationAnchors(source);
        List<ObservedFinding> findings = new ArrayList<>();
        List<WarningNode> nodes = new ArrayList<>();
        List<Object> findingJson = new ArrayList<>();
        for (WarningNode n : graph.warningNodes()) {
            ObservedFinding f = observe(n.id(), n.diagnostic(), n.targetPath(), anchors);
            findings.add(f);
            nodes.add(new WarningNode(n.id(), n.diagnostic(), n.targetPath(), true, 0, Optional.of(BASELINE_STATE),
                    java.util.OptionalInt.of(0)));
            Map<String, Object> m = FindingJson.finding(f);
            m.put("targetPath", n.targetPath().orElse(null));
            findingJson.add(m);
        }
        Map<String, Object> identity = tools.of(run);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", SCHEMA_VERSION);
        m.put("evaluationId", evaluationId);
        m.put("state", BASELINE_STATE);
        m.put("status", "COMPLETED");
        m.put("startedAt", started.toString());
        m.put("finishedAt", clock.instant().toString());
        m.put("snapshot", candidates.snapshot().toString());
        m.put("snapshotTreeSha256", snapshotHash);
        m.put("sourceTreeSha256", sourceHash);
        Map<String, Object> copied = new LinkedHashMap<>();
        copied.put("filesCopied", copy.filesCopied());
        copied.put("skippedSymlinks", copy.skippedSymlinks());
        m.put("snapshotCopy", copied);
        m.put("configuration", config.fingerprint());
        m.put("toolIdentity", identity);
        m.put("checkerDetails", run.details());
        m.put("coverage", coverage(run));
        m.put("commands", commands(run, bdir));
        m.put("findingCount", findings.size());
        m.put("otherDiagnosticCount", completed.otherDiagnostics().size());
        m.put("findings", findingJson);
        EvaluationFiles.writeAtomically(result, Json.write(m));
        return Optional.of(new Baseline(bdir, findings, nodes, parse(result).get("toolIdentity") instanceof Map<?, ?> t
                ? t : identity, sourceHash, false));
    }

    /**
     * Maps warning IDs of the analyzed run's {@code warning-graph.json} to baseline nodes with an
     * identical diagnostic (path, line, column, severity, checker, message). The sources are the same
     * snapshot, so a complete identity mapping is expected; anything else is reported.
     */
    private Map<String, String> originalMapping(Baseline baseline) throws IOException {
        Path file = candidates.runDir().resolve(WarningGraphJson.FILE_NAME);
        Map<String, String> mapping = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return mapping;
        }
        WarningGraph original;
        try {
            original = WarningGraphJson.load(file);
        } catch (IllegalArgumentException e) {
            throw new IOException("cannot read " + file + ": " + e.getMessage());
        }
        Set<String> used = new LinkedHashSet<>();
        for (WarningNode o : original.warningNodes()) {
            for (WarningNode b : baseline.nodes()) {
                if (!used.contains(b.id()) && o.targetPath().equals(b.targetPath())
                        && o.diagnostic().line() == b.diagnostic().line()
                        && o.diagnostic().column().equals(b.diagnostic().column())
                        && o.diagnostic().severity() == b.diagnostic().severity()
                        && o.diagnostic().checkerName().equals(b.diagnostic().checkerName())
                        && o.diagnostic().message().equals(b.diagnostic().message())) {
                    used.add(b.id());
                    mapping.put(o.id(), b.id());
                    break;
                }
            }
        }
        return mapping;
    }

    // ---------------------------------------------------------------------------------------------
    // Attempts

    private AttemptSummary attempt(Path dir, String evaluationId, Baseline baseline, Candidates.Candidate c,
            WarningIds.Allocator ids) throws IOException {
        Path adir = dir.resolve("attempts").resolve(c.id());
        Path recordFile = adir.resolve(ATTEMPT);
        String packageKey = c.packageKey();
        if (Files.exists(recordFile)) {
            Map<?, ?> r = parse(recordFile);
            if (!evaluationId.equals(r.get("evaluationId")) || !packageKey.equals(r.get("packageKey"))
                    || !Long.valueOf(round).equals(r.get("round"))) {
                throw new IOException(recordFile + " records another evaluation, round or package; remove the "
                        + "evaluation directory to start again");
            }
            return summary(c.id(), r, "reused", adir);
        }
        String how = "evaluated";
        if (Files.exists(adir, LinkOption.NOFOLLOW_LINKS)) {
            Path aside = nextFree(dir.resolve("attempts").resolve("interrupted"), c.id());
            moveAside(adir, aside);
            how = "rerun after an interrupted attempt (kept in " + dir.relativize(aside) + ")";
        }
        Files.createDirectories(adir);
        Instant started = clock.instant();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", RECORD_SCHEMA_VERSION);
        m.put("evaluationId", evaluationId);
        m.put("round", round);
        m.put("candidateId", c.id());
        m.put("packageKey", packageKey);
        m.put("canonical", c.canonical());
        m.put("warningId", c.warningId());
        m.put("group", c.group());
        m.put("baselineState", BASELINE_STATE);
        List<Object> editRecords = Candidates.editsJson(c);
        m.put("edits", editRecords);
        m.put("startedAt", started.toString());

        AttemptOutcome outcome;
        String detail;
        AnnotationNode.Counts counts = null;
        List<String> suppressions = new ArrayList<>();
        for (int i = 0; i < c.edits().size(); i++) {
            int index = i;
            suppression(c.edits().get(i).edit()).ifPresent(s -> suppressions.add("edit " + index + ": " + s));
        }
        if (!suppressions.isEmpty()) {
            outcome = AttemptOutcome.REJECTED;
            detail = "package rejected, nothing applied: " + String.join("; ", suppressions);
        } else {
            Path source = adir.resolve("source");
            SnapshotCopier.copy(baseline.dir().resolve("source"), source);
            AnnotationApplier.ApplyResult applied = applier.apply(source, c.annotationEdits());
            int applies = 0;
            List<String> rejections = new ArrayList<>();
            for (int i = 0; i < applied.results().size(); i++) {
                AnnotationApplier.EditResult er = applied.results().get(i);
                @SuppressWarnings("unchecked")
                Map<String, Object> em = (Map<String, Object>) editRecords.get(i);
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("outcome", er.outcome().name());
                am.put("detail", er.detail());
                em.put("applier", am);
                if (er.outcome() == AnnotationApplier.Outcome.APPLIED) {
                    applies++;
                } else if (er.outcome() == AnnotationApplier.Outcome.REJECTED) {
                    rejections.add("edit " + i + " (" + er.edit().canonical() + "): " + er.detail());
                }
            }
            if (applied.rejected() || !applied.committed()) {
                // all or nothing: a rejected or entirely no-op package leaves no workspace behind
                EvaluationFiles.deleteTree(source);
                outcome = applied.rejected() ? AttemptOutcome.REJECTED : AttemptOutcome.NO_OP;
                detail = applied.rejected() ? "package rejected, nothing applied: " + String.join("; ", rejections)
                        : "no-op: all " + c.edits().size() + " edit(s) of the package are already satisfied";
            } else {
                m.put("appliedEdits", applies);
                m.put("noOpEdits", c.edits().size() - applies);
                StringBuilder diff = new StringBuilder();
                for (AnnotationApplier.FileChange fc : applied.changes()) {
                    diff.append(UnifiedDiff.of(fc.path(), fc.before(), fc.after()));
                }
                Files.writeString(adir.resolve("changes.diff"), diff.toString(), StandardCharsets.UTF_8);
                m.put("workspace", "source");
                m.put("diff", "changes.diff");
                CheckerRun run = checker.analyze(request(source, adir.resolve("check")));
                m.put("commands", commands(run, adir));
                m.put("coverage", coverage(run));
                m.put("resultState", BASELINE_STATE + "+" + c.id());
                if (run.outcome() instanceof ExecutionFailure f) {
                    outcome = switch (f.kind()) {
                        case COMPILATION_FAILED -> AttemptOutcome.COMPILATION_FAILED;
                        case TIMED_OUT -> AttemptOutcome.TIMED_OUT;
                        case CRASHED -> AttemptOutcome.CRASHED;
                        default -> AttemptOutcome.EXECUTION_FAILED;
                    };
                    detail = f.kind() + ": " + firstLine(f.reasons().get(0)) + " (" + f.findings().size()
                            + " finding(s) observed before the failure; incomplete, not compared)";
                    Map<String, Object> fm = new LinkedHashMap<>();
                    fm.put("kind", f.kind().name());
                    fm.put("reasons", f.reasons());
                    fm.put("findingsObservedBeforeFailure", f.findings().size());
                    fm.put("compilerErrors", f.otherDiagnostics().stream().limit(20).map(FindingJson::diagnostic)
                            .toList());
                    m.put("failure", fm);
                } else {
                    CompletedAnalysis completed = (CompletedAnalysis) run.outcome();
                    Map<String, Object> identity = tools.of(run);
                    List<String> differences = ToolIdentity.differences(baseline.toolIdentity(), identity);
                    m.put("toolIdentity", identity);
                    if (!differences.isEmpty()) {
                        outcome = AttemptOutcome.NOT_COMPARABLE;
                        detail = "the analysis completed with a different " + String.join(", ", differences)
                                + " than the baseline; findings are not compared";
                        m.put("findingCount", completed.findings().size());
                    } else {
                        Compared cmp = compare(baseline, completed, source, c.id(), ids);
                        counts = cmp.counts();
                        outcome = cmp.comparison().ambiguous().isEmpty() ? AttemptOutcome.COMPLETED
                                : AttemptOutcome.AMBIGUOUS;
                        detail = describe(counts);
                        m.put("findingCount", completed.findings().size());
                        m.put("resolved", cmp.comparison().resolved());
                        m.put("introduced", cmp.introduced());
                        m.put("ambiguous", cmp.ambiguous());
                        m.put("comparison", COMPARISON);
                        EvaluationFiles.writeAtomically(adir.resolve(COMPARISON), Json.write(cmp.json()));
                    }
                }
            }
        }
        m.put("outcome", outcome.name());
        m.put("detail", detail);
        m.put("counts", counts == null ? null : countsJson(counts));
        m.put("finishedAt", clock.instant().toString());
        EvaluationFiles.writeAtomically(recordFile, Json.write(m));
        return new AttemptSummary(c.id(), outcome, detail, Optional.ofNullable(counts), how, adir);
    }

    private record Compared(WarningMatcher.Comparison comparison, AnnotationNode.Counts counts,
            List<Object> introduced, List<Object> ambiguous, Map<String, Object> json) {}

    /**
     * Matches the findings and gives every introduced finding the next ID of the round's sequence. Persisting
     * findings keep their baseline IDs; resolved ones are baseline IDs; nothing else gets an ID.
     */
    private Compared compare(Baseline baseline, CompletedAnalysis completed, Path source, String candidateId,
            WarningIds.Allocator ids) {
        WarningGraph sorted = InitialWarningGraph.build(candidates.runId(), checker.id(), completed, source);
        DeclarationAnchors anchors = new DeclarationAnchors(source);
        List<ObservedFinding> result = new ArrayList<>();
        Map<String, Optional<String>> targetPaths = new LinkedHashMap<>();
        int n = 0;
        for (WarningNode w : sorted.warningNodes()) {
            String id = String.format("R%03d", ++n);
            result.add(observe(id, w.diagnostic(), w.targetPath(), anchors));
            targetPaths.put(id, w.targetPath());
        }
        WarningMatcher.Comparison cmp = matcher.compare(baseline.findings(), result);
        List<Object> introduced = new ArrayList<>();
        List<Object> mapping = new ArrayList<>();
        for (String id : cmp.introduced()) {
            ObservedFinding f = result.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
            String warningId = ids.next();
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("warningId", warningId);
            im.put("round", round);
            im.put("resultId", id);
            im.put("targetPath", targetPaths.get(id).orElse(null));
            im.put("diagnostic", FindingJson.diagnostic(f.diagnostic()));
            introduced.add(im);
            mapping.add(introducedMapping(id, warningId, round));
        }
        List<Object> ambiguous = new ArrayList<>();
        for (WarningMatcher.Ambiguity a : cmp.ambiguous()) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("baseline", a.baselineIds());
            am.put("result", a.resultIds());
            am.put("reason", a.reason());
            ambiguous.add(am);
        }
        AnnotationNode.Counts counts = new AnnotationNode.Counts(baseline.findings().size(), result.size(),
                cmp.resolved().size(), cmp.persisting().size(), cmp.introduced().size(), cmp.ambiguousBaseline(),
                cmp.ambiguousResult());
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("matcher", matcher.id());
        json.put("assumptions", matcher.assumptions());
        json.put("baselineState", BASELINE_STATE);
        json.put("resultState", BASELINE_STATE + "+" + candidateId);
        json.put("counts", countsJson(counts));
        json.put("resolved", cmp.resolved());
        json.put("introduced", cmp.introduced());
        json.put("introducedWarnings", mapping);
        List<Object> pairs = new ArrayList<>();
        for (WarningMatcher.Pair p : cmp.persisting()) {
            pairs.add(Map.of("baseline", p.baselineId(), "result", p.resultId()));
        }
        json.put("persisting", pairs);
        json.put("ambiguous", ambiguous);
        json.put("resultFindings", result.stream().map(FindingJson::finding).toList());
        return new Compared(cmp, counts, introduced, ambiguous, json);
    }

    static Map<String, Object> introducedMapping(String resultId, String warningId, int round) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resultId", resultId);
        m.put("warningId", warningId);
        m.put("round", round);
        return m;
    }

    /** The introduced-warning entries of an attempt record (empty if none). */
    static List<Map<?, ?>> introduced(Map<?, ?> record) {
        List<Map<?, ?>> list = new ArrayList<>();
        if (record.get("introduced") instanceof List<?> l) {
            l.forEach(o -> list.add((Map<?, ?>) o));
        }
        return list;
    }

    /** Sibling evaluation directories of the same submission written with an earlier candidate model. */
    private List<Path> olderEvaluations(Path dir) throws IOException {
        List<Path> older = new ArrayList<>();
        Path parent = dir.getParent();
        try (var entries = Files.list(parent)) {
            for (Path p : entries.sorted().toList()) {
                Path record = p.resolve(RECORD);
                if (p.equals(dir) || !p.getFileName().toString().startsWith(candidates.submissionId())
                        || !Files.isRegularFile(record)) {
                    continue;
                }
                Map<?, ?> r = parse(record);
                if (!(r.get("key") instanceof Map<?, ?> k) || !Candidates.MODEL.equals(k.get("candidateModel"))) {
                    older.add(p);
                }
            }
        }
        return older;
    }

    private Result refused(Path dir, String message) {
        return new Result(Status.REFUSED, dir, message, List.of());
    }

    private static ObservedFinding observe(String id, generalannotator.diagnostics.Diagnostic d,
            Optional<String> targetPath, DeclarationAnchors anchors) {
        String path = targetPath.map(p -> p.replace('\\', '/')).orElse(d.sourcePath());
        ObservedFinding.Anchor anchor = targetPath.isPresent() ? anchors.anchor(path, d.line(), d.column())
                : ObservedFinding.Anchor.unresolved("the finding is outside the state's source");
        return new ObservedFinding(id, d, path, anchor);
    }

    /**
     * Provisional policy (project-spec §6): edits that would suppress warnings or switch checking off
     * instead of annotating types are not evaluated.
     */
    static Optional<String> suppression(AnnotationEdit edit) {
        String simple = edit.annotation().substring(edit.annotation().lastIndexOf('.') + 1);
        if (edit.action() == EditAction.ADD && (simple.equals("SuppressWarnings") || simple.equals("NullUnmarked"))) {
            return Optional.of("@" + edit.annotation() + " suppresses or disables checking; not evaluated");
        }
        if (edit.action() == EditAction.REMOVE && simple.equals("NullMarked")) {
            return Optional.of("removing @" + edit.annotation() + " disables checking; not evaluated");
        }
        return Optional.empty();
    }

    private AnalysisRequest request(Path source, Path runDirectory) {
        AnalysisConfig c = config.analysis();
        List<Path> classpath = c.classpath().stream()
                .map(e -> e.relativeToTarget() ? source.resolve(e.path()) : e.path()).toList();
        return new AnalysisRequest(source, c.sourceRoots(), classpath, c.checkerJavaHome(), c.javacRelease(),
                runDirectory, c.timeout());
    }

    // ---------------------------------------------------------------------------------------------
    // Records, summary and helpers

    private static List<Object> commands(CheckerRun run, Path base) {
        List<Object> list = new ArrayList<>();
        for (RecordedCommand rc : run.commands()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("purpose", rc.purpose());
            m.put("command", rc.result().spec().command());
            m.put("exitCode", rc.result().exitCode().isPresent() ? rc.result().exitCode().getAsInt() : null);
            m.put("durationMillis", rc.result().duration().toMillis());
            m.put("timedOut", rc.result().timedOut());
            m.put("startFailure", rc.result().startFailure().orElse(null));
            m.put("stdoutFile", relative(base, rc.stdoutFile()));
            m.put("stderrFile", relative(base, rc.stderrFile()));
            list.add(m);
        }
        return list;
    }

    private static String relative(Path base, Path file) {
        Path f = file.toAbsolutePath().normalize();
        Path b = base.toAbsolutePath().normalize();
        return f.startsWith(b) ? b.relativize(f).toString() : f.toString();
    }

    private static Map<String, Object> coverage(CheckerRun run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("analyzedSourceRoots", run.coverage().analyzedSourceRoots());
        m.put("analyzedSourceFiles", run.coverage().analyzedSourceFiles());
        m.put("javaFilesOutsideSourceRoots", new TreeMap<>(run.coverage().javaFilesOutsideSourceRoots()));
        m.put("limitations", run.coverage().limitations());
        return m;
    }

    static Map<String, Object> countsJson(AnnotationNode.Counts c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baselineFindings", c.baselineFindings());
        m.put("resultFindings", c.resultFindings());
        m.put("resolved", c.resolved());
        m.put("persisting", c.persisting());
        m.put("introduced", c.introduced());
        m.put("ambiguousBaseline", c.ambiguousBaseline());
        m.put("ambiguousResult", c.ambiguousResult());
        return m;
    }

    static Optional<AnnotationNode.Counts> counts(Object value) {
        if (!(value instanceof Map<?, ?> m)) {
            return Optional.empty();
        }
        return Optional.of(new AnnotationNode.Counts(i(m, "baselineFindings"), i(m, "resultFindings"),
                i(m, "resolved"), i(m, "persisting"), i(m, "introduced"), i(m, "ambiguousBaseline"),
                i(m, "ambiguousResult")));
    }

    private static int i(Map<?, ?> m, String key) {
        return Math.toIntExact((Long) m.get(key));
    }

    private static String describe(AnnotationNode.Counts c) {
        String s = "findings " + c.baselineFindings() + " -> " + c.resultFindings() + ": " + c.resolved()
                + " resolved, " + c.introduced() + " introduced, " + c.persisting() + " persisting";
        if (c.ambiguousBaseline() + c.ambiguousResult() > 0) {
            s += ", " + c.ambiguousBaseline() + " baseline / " + c.ambiguousResult() + " later finding(s) ambiguous";
        }
        if (c.resolved() == 0 && c.introduced() == 0 && c.ambiguousBaseline() + c.ambiguousResult() == 0) {
            s += " (no finding changed)";
        }
        return s;
    }

    private static AttemptSummary summary(String id, Map<?, ?> r, String how, Path dir) {
        return new AttemptSummary(id, AttemptOutcome.valueOf((String) r.get("outcome")), (String) r.get("detail"),
                counts(r.get("counts")), how, dir);
    }

    /** One printed line per attempt; count changes are shown for completed analyses only. */
    public static String line(AttemptSummary s) {
        StringBuilder b = new StringBuilder(s.candidateId()).append("  ").append(s.outcome());
        if (s.counts().isPresent() && s.outcome().compared()) {
            AnnotationNode.Counts c = s.counts().get();
            int delta = c.resultFindings() - c.baselineFindings();
            b.append("  findings ").append(c.baselineFindings()).append(" -> ").append(c.resultFindings())
                    .append(" (").append(delta > 0 ? "+" : "").append(delta).append("); resolved ").append(c.resolved())
                    .append(", introduced ").append(c.introduced()).append(", persisting ").append(c.persisting());
            if (c.ambiguousBaseline() + c.ambiguousResult() > 0) {
                b.append(", ambiguous ").append(c.ambiguousBaseline()).append('/').append(c.ambiguousResult());
            }
        } else {
            b.append("  ").append(s.detail());
        }
        b.append("  [").append(s.how()).append("] ").append(s.directory());
        return b.toString();
    }

    private Map<String, Map<?, ?>> records(Path dir) throws IOException {
        Map<String, Map<?, ?>> records = new LinkedHashMap<>();
        for (Candidates.Candidate c : candidates.candidates()) {
            Path f = dir.resolve("attempts").resolve(c.id()).resolve(ATTEMPT);
            if (Files.exists(f)) {
                records.put(c.id(), parse(f));
            }
        }
        return records;
    }

    private Set<String> interrupted(Path dir, Set<String> complete) {
        Set<String> ids = new LinkedHashSet<>();
        for (Candidates.Candidate c : candidates.candidates()) {
            if (!complete.contains(c.id()) && Files.exists(dir.resolve("attempts").resolve(c.id()),
                    LinkOption.NOFOLLOW_LINKS)) {
                ids.add(c.id());
            }
        }
        return ids;
    }

    private Map<String, Object> summary(String evaluationId, Baseline baseline, Map<String, String> original,
            Map<String, Map<?, ?>> records, Set<String> interrupted, WarningGraph graph, WarningIds.Allocator ids) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", RECORD_SCHEMA_VERSION);
        m.put("evaluationId", evaluationId);
        m.put("round", round);
        Map<String, Object> seq = new LinkedHashMap<>();
        seq.put("scheme", WarningIds.SCHEME);
        seq.put("round", round);
        seq.put("lastSequence", ids.lastSequence());
        seq.put("next", ids.lastSequence() < WarningIds.MAX_PER_ROUND
                ? WarningIds.introduced(round, ids.lastSequence() + 1) : null);
        m.put("warningIdSequence", seq);
        m.put("runId", candidates.runId());
        m.put("submissionId", candidates.submissionId());
        m.put("baselineState", BASELINE_STATE);
        m.put("baselineFindings", baseline.findings().size());
        boolean identical = original.size() == baseline.nodes().size()
                && original.entrySet().stream().allMatch(e -> e.getKey().equals(e.getValue()));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("identicalToRunWarningGraph", identical);
        o.put("mappedWarnings", original.size());
        o.put("note", identical ? "The baseline reproduced the run's findings exactly; proposal warning IDs are "
                + "baseline node IDs." : "The baseline findings differ from the run's warning-graph.json (for example "
                + "because of a different classpath); proposal warning IDs are mapped only where a diagnostic is "
                + "identical.");
        m.put("runWarningGraph", o);
        Map<String, Integer> byOutcome = new LinkedHashMap<>();
        for (AttemptOutcome a : AttemptOutcome.values()) {
            byOutcome.put(a.name(), 0);
        }
        List<Object> list = new ArrayList<>();
        for (Candidates.Candidate c : candidates.candidates()) {
            Map<?, ?> r = records.get(c.id());
            String outcome = r != null ? (String) r.get("outcome")
                    : interrupted.contains(c.id()) ? AttemptOutcome.INTERRUPTED.name()
                    : AttemptOutcome.UNEVALUATED.name();
            byOutcome.merge(outcome, 1, Integer::sum);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("candidateId", c.id());
            e.put("canonical", c.canonical());
            e.put("warningId", c.warningId());
            e.put("group", c.group());
            e.put("edits", c.edits().stream().map(pe -> pe.edit().canonical()).toList());
            e.put("outcome", outcome);
            e.put("detail", r == null ? null : r.get("detail"));
            e.put("counts", r == null ? null : r.get("counts"));
            e.put("resolved", r == null || r.get("resolved") == null ? List.of() : r.get("resolved"));
            e.put("introduced", r == null || !(r.get("introduced") instanceof List<?> l) ? List.of()
                    : l.stream().map(x -> ((Map<?, ?>) x).get("warningId")).toList());
            e.put("directory", "attempts/" + c.id());
            list.add(e);
        }
        m.put("outcomes", byOutcome);
        m.put("candidates", list);
        m.put("excluded", candidates.excluded().stream().map(x -> Map.of("warningId", x.warningId(), "status",
                x.status(), "reason", x.reason())).toList());
        m.put("graph", Map.of("file", GRAPH, "nodes", graph.nodes().size(), "edges", graph.edges().size()));
        m.put("matcher", matcher.id());
        m.put("limitations", LIMITATIONS);
        return m;
    }

    private static Path nextFree(Path parent, String base) {
        for (int n = 1; ; n++) {
            Path p = parent.resolve(base + "-" + n);
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                return p;
            }
        }
    }

    private static void moveAside(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Map<?, ?> parse(Path file) throws IOException {
        try {
            return (Map<?, ?>) JsonParser.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException(file + " is not valid JSON: " + e.getMessage());
        }
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline) + " ...";
    }
}
