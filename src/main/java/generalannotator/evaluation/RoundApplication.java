package generalannotator.evaluation;

import generalannotator.checker.AnalysisRequest;
import generalannotator.checker.Checker;
import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.checker.CheckerOutcome.ExecutionFailure;
import generalannotator.checker.CheckerRun;
import generalannotator.config.AnalysisConfig;
import generalannotator.edits.AnnotationApplier;
import generalannotator.edits.AnnotationEdit;
import generalannotator.edits.UnifiedDiff;
import generalannotator.evaluation.matching.DeclarationAnchors;
import generalannotator.evaluation.matching.ObservedFinding;
import generalannotator.evaluation.matching.WarningMatcher;
import generalannotator.graph.InitialWarningGraph;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.graph.WarningIds;
import generalannotator.graph.WarningNode;
import generalannotator.isolation.SnapshotCopier;
import generalannotator.report.Json;
import generalannotator.report.WarningsText;
import java.io.IOException;
import java.io.PrintStream;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Applies <b>all</b> validated annotation packages of one inference round together to one isolated copy
 * of the run snapshot, runs the checker once on that combined source state, and saves every finding of it
 * with its comparison to the baseline ({@code apply-round}).
 *
 * <ul>
 *   <li><b>Union.</b> Every package ({@link Candidates}: answers merged by complete edit set, provenance
 *       kept) contributes all its edits; identical edits of different packages are applied once and
 *       list every package that proposed them. No package is left out for having increased warnings
 *       when evaluated alone.
 *   <li><b>Validation before anything is published.</b> The AST applier compares every edit with the
 *       declarations of the snapshot: already-present annotations are NO_OP; conflicting actions,
 *       incompatible nullness annotations at one position, missing or ambiguous targets and unsupported
 *       placements are rejected, and so is an edit that would switch checking off ({@link
 *       Evaluator#suppression}). One rejection rejects the round: the conflicts are reported with their
 *       packages and origins, and no workspace is published. Nothing is resolved by order or dropped.
 *   <li><b>One combined state.</b> The edits are applied to one fresh full copy of the run snapshot (the
 *       checked repository copy) in one AST rewrite, and the checker runs once on the result. Independent
 *       attempt results are never combined.
 *   <li><b>Baseline.</b> An evaluation's baseline is reused only if its snapshot tree hash, configuration
 *       fingerprint and matcher equal this round's and its source copy still has its recorded hash;
 *       otherwise a fresh baseline is checked. A different checker build makes the comparison
 *       unavailable (NOT_COMPARABLE).
 *   <li><b>Findings.</b> Every finding of the combined state is saved. The {@link WarningMatcher}
 *       classifies them as persisting (baseline ID kept), introduced or ambiguous (new IDs of the round's
 *       sequence, {@link WarningIds}), and baseline findings as resolved; nothing is inferred from counts.
 *       An execution failure is never zero warnings: its partial findings are kept as incomplete and no
 *       comparison is made.
 * </ul>
 *
 * <p>Results live in {@code runs/<run>/rounds/<id>/}, where {@code <id> = round<r>-<hash of the key>} and
 * the key covers the run, submission, snapshot tree hash, checker configuration, the package set with
 * provenance, the complete edit set and the matcher. They are built in a temporary directory and
 * published by an atomic rename, never overwriting: a completed (or rejected, or not comparable) result
 * is reused by later invocations, a failed one is kept as {@code <id>-failed-<time>} and the next
 * invocation tries again. A completed result is laid out like a run directory ({@code report.json},
 * {@code warning-graph.json}, {@code workspace/target}), so {@code targets}, {@code slice} and {@code
 * context} accept it as the input of the next round. The run snapshot, the inference artifacts and
 * earlier evaluations are only read; nothing is attributed to individual annotation nodes.
 */
public final class RoundApplication {

    public static final String DIR = "rounds";
    public static final String RECORD = "round.json";
    public static final String FINDINGS = "findings.json";
    public static final String COMPARISON = "comparison.json";
    public static final String DIFF = "changes.diff";
    public static final String WARNINGS = "warnings.txt";
    public static final String MODEL = "combined-round-1";
    public static final int SCHEMA_VERSION = 1;
    /** The state every round result starts from: the run snapshot, as in evaluations. */
    public static final String BASE_STATE = Evaluator.BASELINE_STATE;

    public static final List<String> LIMITATIONS = List.of(
            "All packages of the round are applied together; the result says nothing about which package or edit "
                    + "caused which change, and nothing is attributed to individual annotation nodes.",
            "Only the configured source roots are analyzed; the rest of the copied repository is not checked.",
            "Warning identity across states is the provisional matcher rule recorded in comparison.json; ambiguous "
                    + "findings support no resolution or introduction claim.",
            "Raw checker logs record the temporary build directory the check ran in; it was renamed on "
                    + "publication.");

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

    private final String selection;
    private Optional<Path> requiredBaseline = Optional.empty();
    private String baseState = BASE_STATE;
    private Set<String> reserved = Set.of();

    /**
     * Applies the packages to a state other than the run snapshot (for example a combined round result): the
     * baseline must be the one of {@code evaluationDir} (whose warning IDs are that state's), never a fresh
     * renumbering, and introduced warnings get IDs after every ID in {@code reservedIds} (for example those of the
     * independent evaluations of the same packages), so the two never collide.
     */
    public RoundApplication withBaseline(Path evaluationDir, String state, Set<String> reservedIds) {
        this.requiredBaseline = Optional.of(evaluationDir);
        this.baseState = state;
        this.reserved = Set.copyOf(reservedIds);
        return this;
    }

    public RoundApplication(Candidates candidates, EvaluationConfig config, Checker checker, AnnotationApplier applier,
            WarningMatcher matcher, int round, Clock clock, PrintStream out) {
        this(candidates, config, checker, applier, matcher, round, clock, out, "every validated round-" + round
                + " package");
    }

    /**
     * @param selection which packages {@code candidates} holds, for the recorded state description (for
     *     example "every compatible, successfully evaluated propagation package")
     */
    public RoundApplication(Candidates candidates, EvaluationConfig config, Checker checker, AnnotationApplier applier,
            WarningMatcher matcher, int round, Clock clock, PrintStream out, String selection) {
        this.selection = selection;
        if (round < 1) {
            throw new IllegalArgumentException("round must be >= 1: " + round);
        }
        this.candidates = candidates;
        this.config = config;
        this.checker = checker;
        this.applier = applier;
        this.matcher = matcher;
        this.round = round;
        this.clock = clock;
        this.out = out;
    }

    /** How an invocation ended. */
    public enum Status {
        /** The combined state was checked completely and compared with the baseline. */
        COMPLETED,
        /** The combined state was checked completely, but by a different checker build: not compared. */
        NOT_COMPARABLE,
        /** At least one edit was rejected or conflicts; nothing was applied. */
        REJECTED,
        /** The baseline analysis failed; nothing was applied. */
        BASELINE_FAILED,
        /** The analysis of the combined state did not complete; its findings are incomplete. */
        CHECK_FAILED,
        /** Another process is writing this round result. */
        BUSY
    }

    /**
     * @param directory the published result directory
     * @param reused whether an earlier result with the same key was reported instead of a new run
     * @param record the contents of round.json
     */
    public record Result(Status status, Path directory, boolean reused, Map<?, ?> record) {}

    /** One distinct edit of the union and the packages that proposed it. */
    record UnionEdit(AnnotationEdit edit, List<Candidates.Candidate> packages) {}

    /** The distinct edits of every package, in canonical order. */
    static List<UnionEdit> union(Candidates candidates) {
        Map<AnnotationEdit, List<Candidates.Candidate>> union = new LinkedHashMap<>();
        for (Candidates.Candidate c : candidates.candidates()) {
            for (AnnotationEdit e : c.edits()) {
                union.computeIfAbsent(e, x -> new ArrayList<>()).add(c);
            }
        }
        List<UnionEdit> list = new ArrayList<>();
        union.forEach((e, ps) -> list.add(new UnionEdit(e, List.copyOf(ps))));
        list.sort(Comparator.comparing(u -> Candidates.editKey(u.edit())));
        return list;
    }

    @SuppressWarnings("try")
    public Result run() throws IOException {
        if (candidates.candidates().isEmpty()) {
            throw new IllegalStateException("no package to apply");
        }
        List<UnionEdit> union = union(candidates);
        String snapshotHash = EvaluationFiles.treeHash(candidates.snapshot());
        String candidatesJson = candidates.toJson();
        StringBuilder editSet = new StringBuilder();
        union.forEach(u -> editSet.append(Candidates.editKey(u.edit())).append("\n--\n"));
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("schemaVersion", SCHEMA_VERSION);
        key.put("kind", MODEL);
        key.put("candidateModel", Candidates.MODEL);
        key.put("round", round);
        key.put("runId", candidates.runId());
        key.put("submissionId", candidates.submissionId());
        key.put("snapshotTreeSha256", snapshotHash);
        key.put("configuration", config.fingerprint());
        key.put("candidatesSha256", EvaluationFiles.sha256(candidatesJson));
        key.put("editSetSha256", EvaluationFiles.sha256(editSet.toString()));
        key.put("matcher", matcher.id());
        if (requiredBaseline.isPresent()) {
            key.put("baseState", baseState);
            key.put("reservedIdsSha256", EvaluationFiles.sha256(String.join("\n", new java.util.TreeSet<>(reserved))));
        }
        String keyJson = Json.write(key);
        String id = "round" + round + "-" + EvaluationFiles.sha256(keyJson).substring(0, 12);
        Path rounds = candidates.runDir().resolve(DIR);
        Path dir = rounds.resolve(id);
        try (EvaluationFiles.Lock lock = EvaluationFiles.lock(rounds.resolve(".locks").resolve(id),
                "pid " + ProcessHandle.current().pid() + " since " + clock.instant())) {
            Result result = Files.exists(dir, LinkOption.NOFOLLOW_LINKS) ? existing(dir, keyJson)
                    : build(rounds, id, key, candidatesJson, union, snapshotHash);
            if (result.status() == Status.COMPLETED) {
                // the run's warning registry records every public ID of the combined state
                WarningRegistry.update(candidates.runDir(), r -> r.registerRound(result.directory()));
            }
            return result;
        } catch (EvaluationFiles.BusyException e) {
            out.println("  " + e.getMessage());
            return new Result(Status.BUSY, dir, false, Map.of());
        }
    }

    /** An earlier result with the same key: verified and reported, never recomputed or overwritten. */
    private Result existing(Path dir, String keyJson) throws IOException {
        Map<?, ?> r = Evaluator.parse(dir.resolve(RECORD));
        if (!keyJson.equals(Json.write(r.get("key")))) {
            throw new IOException(dir.resolve(RECORD) + " records a different key; it was not written for these "
                    + "inputs");
        }
        if (r.get("workspace") instanceof Map<?, ?> w && w.get("treeSha256") instanceof String hash
                && !hash.equals(EvaluationFiles.treeHash(dir.resolve("workspace").resolve("target")))) {
            throw new IOException(dir.resolve("workspace/target") + " was changed after the round was applied; "
                    + "its recorded warnings no longer describe it");
        }
        return new Result(Status.valueOf((String) r.get("status")), dir, true, r);
    }

    private Result build(Path rounds, String id, Map<String, Object> key, String candidatesJson,
            List<UnionEdit> union, String snapshotHash) throws IOException {
        String stamp = STAMP.format(clock.instant());
        Path tmp = rounds.resolve(".tmp-" + id + "-" + stamp + "-" + ProcessHandle.current().pid());
        Files.createDirectories(tmp);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", SCHEMA_VERSION);
        m.put("roundResultId", id);
        m.put("stateId", id);
        m.put("baseState", baseState);
        m.put("stateDescription", "the state " + baseState + " with " + selection
                + " applied together; not the state of any single annotation attempt");
        m.put("generator", candidates.generator());
        m.put("round", round);
        m.put("status", null);
        m.put("detail", null);
        m.put("createdAt", clock.instant().toString());
        m.put("key", key);
        m.put("runId", candidates.runId());
        m.put("submissionId", candidates.submissionId());
        m.put("runDirectory", candidates.runDir().toString());
        m.put("snapshot", candidates.snapshot().toString());
        m.put("timeoutSeconds", config.analysis().timeout().toSeconds());
        m.put("matcher", matcher.id());
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("importedProposals", candidates.importedProposals());
        counts.put("packages", candidates.candidates().size());
        counts.put("excludedAnswers", candidates.excluded().size());
        counts.put("packageEdits", candidates.editCount());
        counts.put("distinctEdits", union.size());
        counts.put("sharedEdits", union.stream().filter(u -> u.packages().size() > 1).count());
        m.put("counts", counts);
        m.put("packages", packagesJson());
        m.put("excluded", candidates.excluded().stream().map(x -> Map.of("warningId", x.warningId(), "status",
                x.status(), "reason", x.reason())).toList());
        m.put("limitations", LIMITATIONS);
        Files.writeString(tmp.resolve(Candidates.FILE), candidatesJson, StandardCharsets.UTF_8);

        // 1. Validate and apply the complete union to one fresh copy (before any checker run).
        List<Object> edits = new ArrayList<>();
        List<Object> conflicts = new ArrayList<>();
        for (UnionEdit u : union) {
            Evaluator.suppression(u.edit()).ifPresent(s -> conflicts.add(conflict(u, "UNSUPPORTED", s)));
        }
        Path workspace = tmp.resolve("workspace").resolve("target");
        AnnotationApplier.ApplyResult applied = null;
        SnapshotCopier.Copy copy = null;
        if (conflicts.isEmpty()) {
            copy = SnapshotCopier.copy(candidates.snapshot(), workspace);
            requireSnapshot(snapshotHash, "copied");
            applied = applier.apply(workspace, union.stream().map(UnionEdit::edit).toList());
            for (int i = 0; i < union.size(); i++) {
                AnnotationApplier.EditResult er = applied.results().get(i);
                if (er.outcome() == AnnotationApplier.Outcome.REJECTED) {
                    conflicts.add(conflict(union.get(i), "REJECTED", er.detail()));
                }
            }
        }
        for (int i = 0; i < union.size(); i++) {
            edits.add(unionEditJson(union.get(i), applied == null ? null : applied.results().get(i)));
        }
        m.put("edits", edits);
        if (!conflicts.isEmpty()) {
            EvaluationFiles.deleteTree(tmp.resolve("workspace"));
            m.put("status", Status.REJECTED.name());
            m.put("detail", conflicts.size() + " edit(s) rejected or conflicting; nothing was applied and no "
                    + "workspace was published");
            m.put("conflicts", conflicts);
            return publish(tmp, rounds.resolve(id), m, Status.REJECTED);
        }
        long appliedCount = applied.results().stream().filter(r -> r.outcome() == AnnotationApplier.Outcome.APPLIED)
                .count();
        counts.put("appliedEdits", appliedCount);
        counts.put("noOpEdits", union.size() - appliedCount);
        StringBuilder diff = new StringBuilder();
        List<Object> changed = new ArrayList<>();
        for (AnnotationApplier.FileChange fc : applied.changes()) {
            diff.append(UnifiedDiff.of(fc.path(), fc.before(), fc.after()));
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("path", fc.path());
            f.put("sha256Before", EvaluationFiles.sha256(fc.before()));
            f.put("sha256After", EvaluationFiles.sha256(fc.after()));
            changed.add(f);
        }
        Files.writeString(tmp.resolve(DIFF), diff.toString(), StandardCharsets.UTF_8);
        Map<String, Object> ws = new LinkedHashMap<>();
        ws.put("path", "workspace/target");
        ws.put("copiedFrom", candidates.snapshot().toString());
        ws.put("filesCopied", copy.filesCopied());
        ws.put("skippedSymlinks", copy.skippedSymlinks());
        ws.put("treeSha256", EvaluationFiles.treeHash(workspace));
        m.put("workspace", ws);
        m.put("changedFiles", changed);
        m.put("diff", DIFF);

        // 2. A verified baseline.
        Optional<Baseline> baseline = baseline(tmp, snapshotHash, m);
        if (baseline.isEmpty()) {
            m.put("status", Status.BASELINE_FAILED.name());
            return publish(tmp, rounds.resolve(id + "-failed-" + stampOf(rounds, id)), m, Status.BASELINE_FAILED);
        }

        // 3. One checker run on the combined state.
        out.println("  checking:   the combined state " + workspace + " (once)");
        CheckerRun run = checker.analyze(request(workspace, tmp.resolve("check")));
        m.put("check", checkJson(run, tmp));
        if (run.outcome() instanceof ExecutionFailure f) {
            m.put("status", Status.CHECK_FAILED.name());
            m.put("detail", f.kind() + ": " + Evaluator.firstLine(f.reasons().get(0)) + " (" + f.findings().size()
                    + " finding(s) observed before the failure; INCOMPLETE, not compared)");
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("kind", f.kind().name());
            fm.put("reasons", f.reasons());
            fm.put("findingsObservedBeforeFailure", f.findings().size());
            fm.put("compilerErrors", f.otherDiagnostics().stream().limit(50).map(FindingJson::diagnostic).toList());
            m.put("failure", fm);
            m.put("comparison", Map.of("available", false, "reason", "the analysis did not complete"));
            Files.writeString(tmp.resolve(WARNINGS), WarningsText.format(config.analysis().checkerId(),
                    workspace.toString(), f, workspace, Optional.empty()), StandardCharsets.UTF_8);
            return publish(tmp, rounds.resolve(id + "-failed-" + stampOf(rounds, id)), m, Status.CHECK_FAILED);
        }
        CompletedAnalysis completed = (CompletedAnalysis) run.outcome();
        Map<String, Object> identity = tools.of(run);
        List<String> differences = ToolIdentity.differences(baseline.get().toolIdentity(), identity);
        WarningGraph sorted = InitialWarningGraph.build(candidates.runId(), checker.id(), completed, workspace);
        DeclarationAnchors anchors = new DeclarationAnchors(workspace);
        List<ObservedFinding> result = new ArrayList<>();
        Map<String, WarningNode> byResult = new LinkedHashMap<>();
        int n = 0;
        for (WarningNode w : sorted.warningNodes()) {
            String rid = String.format("R%03d", ++n);
            result.add(Evaluator.observe(rid, w.diagnostic(), w.targetPath(), anchors));
            byResult.put(rid, w);
        }
        m.put("findingCount", result.size());
        m.put("otherDiagnosticCount", completed.otherDiagnostics().size());
        if (!differences.isEmpty()) {
            m.put("status", Status.NOT_COMPARABLE.name());
            m.put("detail", "the combined state was analyzed with a different " + String.join(", ", differences)
                    + " than the baseline; findings are saved but not compared");
            m.put("comparison", Map.of("available", false, "reason", m.get("detail")));
            List<Object> findings = new ArrayList<>();
            for (ObservedFinding f : result) {
                Map<String, Object> fm = FindingJson.finding(f);
                fm.put("status", "NOT_COMPARED");
                fm.put("targetPath", byResult.get(f.id()).targetPath().orElse(null));
                findings.add(fm);
            }
            write(tmp, FINDINGS, findingsFile(id, findings));
            List<String> lines = new ArrayList<>();
            for (ObservedFinding f : result) {
                WarningNode w = byResult.get(f.id());
                lines.add(WarningsText.mappingLine(new WarningNode(f.id(), w.diagnostic(), w.targetPath(), true, 0))
                        + "  [NOT_COMPARED]");
            }
            Files.writeString(tmp.resolve(WARNINGS), warningsText(id, lines, "not compared: " + m.get("detail")),
                    StandardCharsets.UTF_8);
            return publish(tmp, rounds.resolve(id), m, Status.NOT_COMPARABLE);
        }

        // 4. Compare and name every finding of the combined state.
        WarningMatcher.Comparison cmp = matcher.compare(baseline.get().findings(), result);
        Map<String, String> persisting = new LinkedHashMap<>();
        cmp.persisting().forEach(p -> persisting.put(p.resultId(), p.baselineId()));
        Set<String> ambiguousResults = new LinkedHashSet<>();
        cmp.ambiguous().forEach(a -> ambiguousResults.addAll(a.resultIds()));
        Set<String> taken = new LinkedHashSet<>();
        baseline.get().findings().forEach(f -> taken.add(f.id()));
        int last = 0;
        for (String r : reserved) {
            last = Math.max(last, WarningIds.sequence(r, round).orElse(0));
        }
        taken.addAll(reserved);
        // one allocator per round, shared with the round's independent evaluations through the run's registry
        for (String r : WarningRegistry.current(candidates.runDir()).idsOutside(rounds.resolve(id))) {
            last = Math.max(last, WarningIds.sequence(r, round).orElse(0));
            taken.add(r);
        }
        WarningIds.Allocator ids = new WarningIds.Allocator(round, taken, last);
        List<Object> findings = new ArrayList<>();
        List<WarningNode> nodes = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        Map<String, String> idByResult = new LinkedHashMap<>();
        for (ObservedFinding f : result) {
            WarningNode w = byResult.get(f.id());
            String status = persisting.containsKey(f.id()) ? "PERSISTING"
                    : ambiguousResults.contains(f.id()) ? "AMBIGUOUS" : "INTRODUCED";
            String warningId = status.equals("PERSISTING") ? persisting.get(f.id()) : ids.next();
            idByResult.put(f.id(), warningId);
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("warningId", warningId);
            fm.put("round", status.equals("PERSISTING") ? 0 : round);
            fm.put("status", status);
            fm.put("baselineWarningId", persisting.get(f.id()));
            fm.put("resultId", f.id());
            fm.put("targetPath", w.targetPath().orElse(null));
            FindingJson.finding(f).forEach((k, v) -> {
                if (!k.equals("id")) {
                    fm.put(k, v);
                }
            });
            findings.add(fm);
            WarningNode node = new WarningNode(warningId, w.diagnostic(), w.targetPath(), true, 0);
            nodes.add(node);
            lines.add(WarningsText.mappingLine(node) + "  [" + status + "]");
        }
        List<Object> resolved = new ArrayList<>();
        Map<String, ObservedFinding> baseById = new LinkedHashMap<>();
        baseline.get().findings().forEach(f -> baseById.put(f.id(), f));
        for (String r : cmp.resolved()) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("warningId", r);
            rm.put("diagnostic", FindingJson.diagnostic(baseById.get(r).diagnostic()));
            resolved.add(rm);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("available", true);
        summary.put("baselineFindings", baseline.get().findings().size());
        summary.put("resultFindings", result.size());
        summary.put("resolved", cmp.resolved().size());
        summary.put("persisting", cmp.persisting().size());
        summary.put("introduced", cmp.introduced().size());
        summary.put("ambiguousBaseline", cmp.ambiguousBaseline());
        summary.put("ambiguousResult", cmp.ambiguousResult());
        summary.put("file", COMPARISON);
        m.put("comparison", summary);
        m.put("warningIdSequence", Map.of("scheme", WarningIds.SCHEME, "round", round, "lastSequence",
                ids.lastSequence(), "scope", "this round result (state " + id + ")"));
        m.put("status", Status.COMPLETED.name());
        m.put("detail", "findings " + baseline.get().findings().size() + " -> " + result.size() + ": "
                + cmp.resolved().size() + " resolved, " + cmp.introduced().size() + " introduced, "
                + cmp.persisting().size() + " persisting" + (cmp.ambiguousBaseline() + cmp.ambiguousResult() > 0
                ? ", " + cmp.ambiguousBaseline() + " baseline / " + cmp.ambiguousResult() + " later ambiguous" : ""));

        Map<String, Object> c = new LinkedHashMap<>();
        c.put("matcher", matcher.id());
        c.put("assumptions", matcher.assumptions());
        c.put("baselineState", baseState);
        c.put("resultState", id);
        c.put("counts", summary);
        c.put("resolved", resolved);
        c.put("persisting", cmp.persisting().stream().map(p -> Map.of("baseline", p.baselineId(), "result",
                p.resultId(), "warningId", idByResult.get(p.resultId()))).toList());
        c.put("introduced", cmp.introduced().stream().map(r -> Map.of("result", r, "warningId", idByResult.get(r)))
                .toList());
        c.put("ambiguous", cmp.ambiguous().stream().map(a -> Map.of("baseline", a.baselineIds(), "result",
                a.resultIds().stream().map(idByResult::get).toList(), "reason", a.reason())).toList());
        write(tmp, COMPARISON, c);
        write(tmp, FINDINGS, findingsFile(id, findings));
        Files.writeString(tmp.resolve(WARNINGS), warningsText(id, lines, m.get("detail") + " (" + matcher.id()
                + "; see comparison.json)"), StandardCharsets.UTF_8);

        // 5. Next-round input: a run-directory layout for the combined state.
        Path dest = rounds.resolve(id);
        String nextRunId = candidates.runId() + "-" + id;
        WarningGraphJson.save(new WarningGraph(nextRunId, checker.id(), List.copyOf(nodes), List.of()),
                tmp.resolve(WarningGraphJson.FILE_NAME));
        write(tmp, "report.json", nextRunReport(nextRunId, dest, result.size()));
        m.put("nextRound", Map.of("runId", nextRunId, "runDirectory", dest.toString(), "files",
                List.of("report.json", WarningGraphJson.FILE_NAME, "workspace/target"), "note",
                "warning-graph.json holds every finding of the combined state (persisting ones keep their baseline "
                        + "IDs, introduced and ambiguous ones have round-" + round + " IDs) as root nodes, so targets, "
                        + "slice and context accept this directory as a run."));
        return publish(tmp, dest, m, Status.COMPLETED);
    }

    /** The baseline: a verified evaluation baseline if one matches, otherwise a fresh check. */
    private record Baseline(List<ObservedFinding> findings, Map<?, ?> toolIdentity) {}

    private Optional<Baseline> baseline(Path tmp, String snapshotHash, Map<String, Object> m) throws IOException {
        Path evaluations = candidates.runDir().resolve(Evaluator.DIR);
        Map<String, Object> bm = new LinkedHashMap<>();
        m.put("baseline", bm);
        if (requiredBaseline.isPresent() || Files.isDirectory(evaluations)) {
            List<Path> dirs;
            if (requiredBaseline.isPresent()) {
                dirs = List.of(requiredBaseline.get());
            } else {
                try (var s = Files.list(evaluations)) {
                    dirs = s.sorted().toList();
                }
            }
            for (Path d : dirs) {
                Path record = d.resolve(Evaluator.RECORD);
                Path result = d.resolve("baseline").resolve("result.json");
                if (!Files.isRegularFile(record) || !Files.isRegularFile(result)) {
                    continue;
                }
                Map<?, ?> key = Evaluator.parse(record).get("key") instanceof Map<?, ?> k ? k : Map.of();
                if (!snapshotHash.equals(key.get("snapshotTreeSha256"))
                        || !Json.write(config.fingerprint()).equals(Json.write(key.get("configuration")))
                        || !matcher.id().equals(key.get("matcher"))) {
                    continue;
                }
                Map<?, ?> r = Evaluator.parse(result);
                if (!(r.get("sourceTreeSha256") instanceof String h)
                        || !h.equals(EvaluationFiles.treeHash(d.resolve("baseline").resolve("source")))) {
                    continue;
                }
                List<ObservedFinding> findings = new ArrayList<>();
                for (Object o : (List<?>) r.get("findings")) {
                    findings.add(FindingJson.finding((Map<?, ?>) o));
                }
                bm.put("mode", "reused");
                bm.put("reusedFrom", d.resolve("baseline").toString());
                bm.put("verified", "same snapshot tree hash, configuration fingerprint and matcher; the baseline "
                        + "source still has its recorded hash");
                bm.put("findingCount", findings.size());
                bm.put("toolIdentity", r.get("toolIdentity"));
                out.println("  baseline:   reused " + findings.size() + " finding(s) from " + d.resolve("baseline")
                        + " (verified equivalent)");
                return Optional.of(new Baseline(findings, (Map<?, ?>) r.get("toolIdentity")));
            }
            if (requiredBaseline.isPresent()) {
                throw new IOException("the evaluation baseline of " + requiredBaseline.get() + " cannot be reused for "
                        + "state " + baseState + " (different snapshot, configuration or matcher, or a changed source "
                        + "copy); a fresh baseline would renumber its warnings, so nothing was applied");
            }
        }
        Path bdir = tmp.resolve("baseline");
        Path source = bdir.resolve("source");
        SnapshotCopier.copy(candidates.snapshot(), source);
        requireSnapshot(snapshotHash, "copied");
        out.println("  baseline:   running " + config.analysis().checkerId() + " on " + source);
        CheckerRun run = checker.analyze(request(source, bdir.resolve("check")));
        bm.put("mode", "fresh");
        bm.put("directory", "baseline");
        bm.put("check", checkJson(run, tmp));
        if (!(run.outcome() instanceof CompletedAnalysis completed)) {
            ExecutionFailure f = (ExecutionFailure) run.outcome();
            bm.put("status", "EXECUTION_FAILURE");
            bm.put("failureKind", f.kind().name());
            bm.put("reasons", f.reasons());
            m.put("detail", "the baseline analysis failed (" + f.kind() + "): " + Evaluator.firstLine(f.reasons().get(0))
                    + "; nothing was compared");
            return Optional.empty();
        }
        WarningGraph graph = InitialWarningGraph.build(candidates.runId(), checker.id(), completed, source);
        DeclarationAnchors anchors = new DeclarationAnchors(source);
        List<ObservedFinding> findings = new ArrayList<>();
        List<Object> json = new ArrayList<>();
        for (WarningNode w : graph.warningNodes()) {
            ObservedFinding f = Evaluator.observe(w.id(), w.diagnostic(), w.targetPath(), anchors);
            findings.add(f);
            Map<String, Object> fm = FindingJson.finding(f);
            fm.put("targetPath", w.targetPath().orElse(null));
            json.add(fm);
        }
        Map<String, Object> identity = tools.of(run);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("state", baseState);
        r.put("snapshotTreeSha256", snapshotHash);
        r.put("sourceTreeSha256", EvaluationFiles.treeHash(source));
        r.put("toolIdentity", identity);
        r.put("findingCount", findings.size());
        r.put("findings", json);
        write(tmp, "baseline/result.json", r);
        bm.put("status", "COMPLETED");
        bm.put("findingCount", findings.size());
        bm.put("toolIdentity", identity);
        out.println("  baseline:   " + findings.size() + " finding(s) in state " + baseState);
        return Optional.of(new Baseline(findings, Evaluator.parse(tmp.resolve("baseline/result.json"))
                .get("toolIdentity") instanceof Map<?, ?> t ? t : identity));
    }

    private Map<String, Object> checkJson(CheckerRun run, Path tmp) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("commands", Evaluator.commands(run, tmp));
        m.put("coverage", Evaluator.coverage(run));
        m.put("details", run.details());
        m.put("toolIdentity", tools.of(run));
        return m;
    }

    private AnalysisRequest request(Path source, Path runDirectory) {
        AnalysisConfig c = config.analysis();
        List<Path> classpath = c.classpath().stream()
                .map(e -> e.relativeToTarget() ? source.resolve(e.path()) : e.path()).toList();
        return new AnalysisRequest(source, c.sourceRoots(), classpath, c.checkerJavaHome(), c.javacRelease(),
                runDirectory, c.timeout());
    }

    private void requireSnapshot(String hash, String when) throws IOException {
        if (!hash.equals(EvaluationFiles.treeHash(candidates.snapshot()))) {
            throw new IOException("the run snapshot " + candidates.snapshot() + " changed while it was " + when);
        }
    }

    private List<Object> packagesJson() {
        List<Object> list = new ArrayList<>();
        for (Candidates.Candidate c : candidates.candidates()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", c.id());
            p.put("packageKey", c.packageKey());
            p.put("warningIds", c.warningIds());
            p.put("origins", c.origins().stream().map(Candidates.Origin::group).toList());
            p.put("edits", c.edits().stream().map(AnnotationEdit::canonical).toList());
            list.add(p);
        }
        return list;
    }

    private static Map<String, Object> unionEditJson(UnionEdit u, AnnotationApplier.EditResult er) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("canonical", u.edit().canonical());
        m.put("edit", Candidates.editFields(u.edit()));
        m.put("packages", u.packages().stream().map(Candidates.Candidate::id).toList());
        m.put("origins", origins(u));
        m.put("applier", er == null ? null : Map.of("outcome", er.outcome().name(), "detail", er.detail()));
        return m;
    }

    private static List<Object> origins(UnionEdit u) {
        List<Object> list = new ArrayList<>();
        for (Candidates.Candidate c : u.packages()) {
            for (Candidates.Origin o : c.origins()) {
                list.add(Map.of("package", c.id(), "warningId", o.warningId(), "group", o.group()));
            }
        }
        return list;
    }

    private static Map<String, Object> conflict(UnionEdit u, String kind, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("edit", u.edit().canonical());
        m.put("detail", detail);
        m.put("packages", u.packages().stream().map(Candidates.Candidate::id).toList());
        m.put("origins", origins(u));
        return m;
    }

    private Map<String, Object> findingsFile(String id, List<Object> findings) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", SCHEMA_VERSION);
        m.put("state", id);
        m.put("round", round);
        m.put("complete", true);
        m.put("count", findings.size());
        m.put("findings", findings);
        return m;
    }

    private String warningsText(String id, List<String> lines, String summary) {
        StringBuilder b = new StringBuilder();
        b.append("# GeneralAnnotator warnings of a combined round state\n");
        b.append("# state:   ").append(id).append(" (round ").append(round).append(": every validated package applied "
                + "together to ").append(baseState).append(")\n");
        b.append("# checker: ").append(config.analysis().checkerId()).append(", source roots ")
                .append(config.analysis().sourceRoots()).append(" only\n");
        b.append("# status:  COMPLETED (complete list)\n");
        b.append("# count:   ").append(lines.size()).append('\n');
        b.append("# summary: ").append(summary).append('\n');
        b.append("# format:  ID  path:line:column: severity: [checker] message  [comparison status]\n");
        lines.forEach(l -> b.append(l).append('\n'));
        return b.toString();
    }

    private Map<String, Object> nextRunReport(String nextRunId, Path dest, int findings) {
        AnalysisConfig c = config.analysis();
        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put("checker", c.checkerId());
        conf.put("checkerHome", c.checkerHome().toString());
        conf.put("targetProject", dest.resolve("workspace").resolve("target").toString());
        conf.put("sourceRoots", c.sourceRoots().stream().map(Path::toString).toList());
        conf.put("classpath", c.classpath().stream().map(e -> e.relativeToTarget()
                ? dest.resolve("workspace").resolve("target").resolve(e.path()).toString() : e.path().toString())
                .toList());
        conf.put("checkerJavaHome", c.checkerJavaHome().toString());
        conf.put("javacRelease", c.javacRelease().isPresent() ? c.javacRelease().getAsInt() : null);
        conf.put("timeoutSeconds", c.timeout().toSeconds());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", 1);
        m.put("runId", nextRunId);
        m.put("kind", MODEL);
        m.put("status", "COMPLETED");
        m.put("derivedFrom", Map.of("runId", candidates.runId(), "runDirectory", candidates.runDir().toString(),
                "submissionId", candidates.submissionId(), "round", round, "state", dest.getFileName().toString()));
        m.put("configuration", conf);
        m.put("isolation", Map.of("workingCopy", dest.resolve("workspace").resolve("target").toString(),
                "mechanism", "explicit file copy of the run snapshot with the round's edits applied"));
        m.put("warningGraph", Map.of("created", true, "file", WarningGraphJson.FILE_NAME, "nodeCount", findings,
                "edgeCount", 0));
        return m;
    }

    private static void write(Path tmp, String name, Object json) throws IOException {
        Path f = tmp.resolve(name);
        Files.createDirectories(f.getParent());
        Files.writeString(f, Json.write(json), StandardCharsets.UTF_8);
    }

    private String stampOf(Path rounds, String id) {
        String stamp = STAMP.format(clock.instant());
        String name = stamp;
        for (int i = 2; Files.exists(rounds.resolve(id + "-failed-" + name), LinkOption.NOFOLLOW_LINKS); i++) {
            name = stamp + "-" + i;
        }
        return name;
    }

    /**
     * Writes round.json with paths of the temporary directory moved to {@code dest}, rewrites the other JSON
     * files the same way, and renames the directory atomically (never over an existing one).
     */
    private Result publish(Path tmp, Path dest, Map<String, Object> m, Status status) throws IOException {
        for (String name : List.of(FINDINGS, COMPARISON, "baseline/result.json")) {
            Path f = tmp.resolve(name);
            if (Files.isRegularFile(f)) {
                Files.writeString(f, Json.write(PackageReuse.normalize(Evaluator.parse(f), tmp, dest.toString())),
                        StandardCharsets.UTF_8);
            }
        }
        Map<?, ?> record = PackageReuse.normalize(new TreeMap<>(Map.of("r", m)), tmp, dest.toString());
        Files.writeString(tmp.resolve(RECORD), Json.write(record.get("r")), StandardCharsets.UTF_8);
        if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(dest + " already exists; it is never overwritten");
        }
        Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
        return new Result(status, dest, false, Evaluator.parse(dest.resolve(RECORD)));
    }
}
