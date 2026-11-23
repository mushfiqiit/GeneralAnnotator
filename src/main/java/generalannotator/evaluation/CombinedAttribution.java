package generalannotator.evaluation;

import generalannotator.checker.AnalysisRequest;
import generalannotator.checker.Checker;
import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.checker.CheckerOutcome.ExecutionFailure;
import generalannotator.checker.CheckerRun;
import generalannotator.config.AnalysisConfig;
import generalannotator.edits.AnnotationApplier;
import generalannotator.edits.AnnotationEdit;
import generalannotator.edits.AnnotationPosition;
import generalannotator.edits.EditAction;
import generalannotator.edits.MemberRef;
import generalannotator.edits.TypeUsePath;
import generalannotator.evaluation.matching.DeclarationAnchors;
import generalannotator.evaluation.matching.ObservedFinding;
import generalannotator.evaluation.matching.WarningMatcher;
import generalannotator.graph.InitialWarningGraph;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningNode;
import generalannotator.isolation.SnapshotCopier;
import generalannotator.report.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Evidence-backed attribution of the warnings a combined round introduced ({@code attribute <run>}): which included
 * packages contribute to a combined warning in that combined context.
 *
 * <ol>
 *   <li><b>Targets:</b> the combined result's {@code INTRODUCED} findings (absent from the round's baseline by its
 *       own comparison, re-checked here). {@code AMBIGUOUS} findings are not attributed.
 *   <li><b>Candidates</b> from saved evidence only: an included package whose own completed evaluation, on the same
 *       base state and with the same package key, introduced a warning the {@link WarningMatcher} pairs with the
 *       target (declaration anchor and site; never a bare ID or message). Discovery is limited to saved
 *       evaluations: a warning without a candidate stays unattributed, which is not a claim that no package
 *       contributes (an effect of the combination only, for example).
 *   <li><b>Leave one out:</b> the counterfactual state for candidate {@code c} is the round's base snapshot with the
 *       union of every other included package's edits, so an edit {@code c} shares with another package stays.
 *       If that is the complete edit set, or the applied source equals the combined workspace, excluding {@code c}
 *       changes nothing: {@code INCONCLUSIVE}. The state is the base state itself (no edit left: the evaluation's
 *       baseline is reused), exactly one package's saved attempt (its result is reused), or else checked locally
 *       with the real applier and the full checker configuration, cached under
 *       {@code attributions/counterfactuals/<key>} (key: base snapshot and baseline, effective edit set, checker
 *       configuration, matcher, {@value #MODEL}).
 *   <li><b>Verification:</b> the complete counterfactual result is compared with the recorded combined result by the
 *       matcher. The target absent without {@code c} (and present with it, as recorded) is {@code VERIFIED}: {@code c}
 *       contributes in that combined context, which is not proof that it alone causes the warning; several packages
 *       may be verified. Still present: {@code NOT_A_CONTRIBUTOR}. An undecided match: {@code AMBIGUOUS}. A
 *       compilation failure, timeout, rejected edit, incomplete or incomparable (different tool) result:
 *       {@code FAILED}. None of these establishes absence or attribution.
 * </ol>
 * The record ({@value #RECORD} in {@code attributions/<round result>-<key>/}) lists every target, candidate and
 * counterfactual with its evidence; {@link HistoryGraph} draws {@code package -> warning} edges only for
 * {@code VERIFIED} contributions it finds there. Nothing in the run's snapshots, round results or checker output is
 * changed, and no warning ID is allocated.
 */
public final class CombinedAttribution {

    public static final String DIR = "attributions";
    public static final String RECORD = "attribution.json";
    public static final String MODEL = "leave-one-out-1";

    /** One candidate's verification outcome. */
    public enum Status { VERIFIED, NOT_A_CONTRIBUTOR, INCONCLUSIVE, AMBIGUOUS, FAILED }

    /** A counterfactual state's complete findings, or why there are none. */
    private record Counterfactual(String key, String source, Optional<List<ObservedFinding>> findings, String detail,
            boolean unchanged) {}

    private final Path runDir;
    private final Path roundDir;
    private final Path evaluationDir;
    private final Optional<EvaluationConfig> config;
    private final Optional<Checker> checker;
    private final AnnotationApplier applier;
    private final WarningMatcher matcher;
    private final Clock clock;
    private final PrintStream out;
    private final ToolIdentity tools = new ToolIdentity();

    /**
     * @param config the checker configuration for local counterfactual checks; empty: saved evidence only (states
     *     that would need a local run are {@code INCONCLUSIVE})
     */
    public CombinedAttribution(Path runDir, Path roundDir, Path evaluationDir, Optional<EvaluationConfig> config,
            Optional<Checker> checker, AnnotationApplier applier, WarningMatcher matcher, Clock clock, PrintStream out) {
        this.runDir = runDir.toAbsolutePath().normalize();
        this.roundDir = roundDir.toAbsolutePath().normalize();
        this.evaluationDir = evaluationDir.toAbsolutePath().normalize();
        this.config = config;
        this.checker = checker;
        this.applier = applier;
        this.matcher = matcher;
        this.clock = clock;
        this.out = out;
    }

    /** Attributes the round's introduced warnings and writes the record; returns its directory. */
    public Path run() throws IOException {
        Map<?, ?> round = WarningRegistry.parse(roundDir.resolve(RoundApplication.RECORD));
        if (!"COMPLETED".equals(round.get("status"))) {
            throw new IOException(roundDir + " is " + round.get("status") + ", not a completed combined result");
        }
        String state = String.valueOf(round.get("roundResultId"));
        String baseState = String.valueOf(round.get("baseState"));
        Path base = baseState.equals(Evaluator.BASELINE_STATE) ? runDir.resolve("workspace").resolve("target")
                : runDir.resolve(RoundApplication.DIR).resolve(baseState).resolve("workspace").resolve("target");
        Map<?, ?> key = round.get("key") instanceof Map<?, ?> k ? k : Map.of();
        String baseHash = EvaluationFiles.treeHash(base);
        if (!baseHash.equals(key.get("snapshotTreeSha256"))) {
            throw new IOException("the base snapshot " + base + " of " + roundDir + " no longer has its recorded tree "
                    + "hash; the round cannot be reconstructed");
        }
        Map<?, ?> baselineRecord = WarningRegistry.parse(evaluationDir.resolve("baseline").resolve("result.json"));
        if (!baseState.equals(baselineRecord.get("state"))) {
            throw new IOException("the evaluation " + evaluationDir + " evaluated state " + baselineRecord.get("state")
                    + ", not the round's base state " + baseState);
        }
        List<ObservedFinding> baseline = new ArrayList<>();
        for (Object o : WarningRegistry.list(baselineRecord.get("findings"))) {
            baseline.add(FindingJson.finding((Map<?, ?>) o));
        }
        Map<?, ?> baseTools = baselineRecord.get("toolIdentity") instanceof Map<?, ?> t ? t : Map.of();
        String combinedTree = round.get("workspace") instanceof Map<?, ?> w ? String.valueOf(w.get("treeSha256")) : "";

        // The included packages and their complete edit sets.
        Map<String, Map<?, ?>> saved = new LinkedHashMap<>();
        for (Object o : WarningRegistry.list(WarningRegistry.parse(evaluationDir.resolve(Candidates.FILE))
                .get("candidates"))) {
            saved.put(String.valueOf(((Map<?, ?>) o).get("id")), (Map<?, ?>) o);
        }
        Map<String, List<AnnotationEdit>> packages = new LinkedHashMap<>();
        Map<String, String> packageKeys = new LinkedHashMap<>();
        for (Object o : WarningRegistry.list(round.get("packages"))) {
            Map<?, ?> p = (Map<?, ?>) o;
            String id = String.valueOf(p.get("id"));
            Map<?, ?> c = saved.get(id);
            if (c == null || !String.valueOf(p.get("packageKey")).equals(c.get("packageKey"))) {
                throw new IOException("package " + id + " of " + roundDir + " is not the package of that key in "
                        + evaluationDir.resolve(Candidates.FILE));
            }
            List<AnnotationEdit> edits = new ArrayList<>();
            for (Object e : WarningRegistry.list(c.get("edits"))) {
                edits.add(edit((Map<?, ?>) ((Map<?, ?>) e).get("edit")));
            }
            packages.put(id, edits);
            packageKeys.put(id, String.valueOf(p.get("packageKey")));
        }
        TreeSet<String> all = new TreeSet<>();
        packages.values().forEach(es -> es.forEach(e -> all.add(Candidates.editKey(e))));

        // The combined findings and the targets.
        List<ObservedFinding> combined = new ArrayList<>();
        Map<String, Map<?, ?>> byId = new LinkedHashMap<>();
        for (Object o : WarningRegistry.list(WarningRegistry.parse(roundDir.resolve(RoundApplication.FINDINGS))
                .get("findings"))) {
            Map<?, ?> f = (Map<?, ?>) o;
            String id = String.valueOf(f.get("warningId"));
            combined.add(observed(id, f));
            byId.put(id, f);
        }

        // Candidate discovery from each package's own saved evaluation.
        Map<String, List<Map<String, Object>>> candidates = new LinkedHashMap<>();
        for (String p : packages.keySet()) {
            Path attempt = evaluationDir.resolve("attempts").resolve(p);
            if (!Files.isRegularFile(attempt.resolve("attempt.json"))
                    || !Files.isRegularFile(attempt.resolve("comparison.json"))) {
                continue;
            }
            Map<?, ?> a = WarningRegistry.parse(attempt.resolve("attempt.json"));
            if (!AttemptOutcome.valueOf(String.valueOf(a.get("outcome"))).compared()
                    || !packageKeys.get(p).equals(a.get("packageKey")) || !baseState.equals(a.get("baselineState"))) {
                continue;
            }
            Map<?, ?> c = WarningRegistry.parse(attempt.resolve("comparison.json"));
            Map<String, Map<?, ?>> results = new LinkedHashMap<>();
            for (Object o : WarningRegistry.list(c.get("resultFindings"))) {
                results.put(String.valueOf(((Map<?, ?>) o).get("id")), (Map<?, ?>) o);
            }
            List<ObservedFinding> own = new ArrayList<>();
            for (Object o : WarningRegistry.list(c.get("introducedWarnings"))) {
                Map<?, ?> iw = (Map<?, ?>) o;
                Map<?, ?> r = results.get(String.valueOf(iw.get("resultId")));
                if (r != null) {
                    ObservedFinding f = FindingJson.finding(r);
                    own.add(new ObservedFinding(String.valueOf(iw.get("warningId")), f.diagnostic(), f.path(),
                            f.anchor()));
                }
            }
            List<ObservedFinding> targets = combined.stream()
                    .filter(f -> "INTRODUCED".equals(byId.get(f.id()).get("status"))).toList();
            WarningMatcher.Comparison cmp = matcher.compare(own, targets);
            for (WarningMatcher.Pair pair : cmp.persisting()) {
                Map<String, Object> cand = new LinkedHashMap<>();
                cand.put("package", p);
                cand.put("packageKey", packageKeys.get(p));
                cand.put("discoveredBy", pair.baselineId());
                cand.put("discovery", "introduced " + pair.baselineId() + " in its own evaluation (state "
                        + a.get("resultState") + "), which the matcher pairs with this warning");
                candidates.computeIfAbsent(pair.resultId(), k -> new ArrayList<>()).add(cand);
            }
            for (WarningMatcher.Ambiguity amb : cmp.ambiguous()) {
                for (String r : amb.resultIds()) {
                    Map<String, Object> cand = new LinkedHashMap<>();
                    cand.put("package", p);
                    cand.put("packageKey", packageKeys.get(p));
                    cand.put("discoveredBy", String.join(", ", amb.baselineIds()));
                    cand.put("discovery", "an undecided match with " + amb.baselineIds() + " of its own evaluation ("
                            + amb.reason() + "); verified like any candidate");
                    candidates.computeIfAbsent(r, k -> new ArrayList<>()).add(cand);
                }
            }
        }

        // One counterfactual per candidate package (shared by every target it is a candidate for).
        Map<String, Counterfactual> without = new TreeMap<>();
        for (List<Map<String, Object>> cs : candidates.values()) {
            for (Map<String, Object> c : cs) {
                String p = String.valueOf(c.get("package"));
                if (!without.containsKey(p)) {
                    without.put(p, counterfactual(p, packages, all, base, baseHash, baseline, baseTools, combinedTree,
                            baseState));
                }
            }
        }

        // Verification.
        List<Object> warnings = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Integer> candidateCounts = new TreeMap<>();
        for (ObservedFinding w : combined) {
            Map<?, ?> f = byId.get(w.id());
            String status = String.valueOf(f.get("status"));
            if (status.equals("PERSISTING")) {
                continue;
            }
            Map<String, Object> wm = new LinkedHashMap<>();
            wm.put("warningId", w.id());
            wm.put("site", w.path() + ":" + w.diagnostic().line() + " " + WarningRegistry.firstLine(
                    w.diagnostic().message()));
            wm.put("combinedStatus", status);
            List<Object> verdicts = new ArrayList<>();
            List<String> contributors = new ArrayList<>();
            String outcome;
            String reason;
            WarningMatcher.Comparison inBase = matcher.compare(baseline, List.of(w));
            if (status.equals("AMBIGUOUS")) {
                outcome = "AMBIGUOUS";
                reason = "the combined comparison matched it ambiguously with the base state; it is not attributed";
            } else if (!inBase.persisting().isEmpty() || !inBase.ambiguous().isEmpty()) {
                outcome = "NOT_ATTRIBUTABLE";
                reason = "the matcher finds it (or an undecided equivalent) in the round's base state";
            } else if (!candidates.containsKey(w.id())) {
                outcome = "NO_CANDIDATE";
                reason = "no included package introduced an equivalent warning in its own saved evaluation; it may "
                        + "arise only in combination, and candidate discovery covers saved evaluations only, so no "
                        + "contributor is claimed";
            } else {
                for (Map<String, Object> c : candidates.get(w.id())) {
                    String p = String.valueOf(c.get("package"));
                    Counterfactual cf = without.get(p);
                    Map<String, Object> v = new LinkedHashMap<>(c);
                    v.put("counterfactual", cf.key());
                    v.put("counterfactualState", cf.source());
                    Status s;
                    String evidence;
                    if (cf.unchanged()) {
                        s = Status.INCONCLUSIVE;
                        evidence = cf.detail();
                    } else if (cf.findings().isEmpty()) {
                        s = cf.detail().startsWith("not checked") ? Status.INCONCLUSIVE : Status.FAILED;
                        evidence = cf.detail();
                    } else {
                        WarningMatcher.Comparison cmp = matcher.compare(List.of(w), cf.findings().get());
                        if (!cmp.ambiguous().isEmpty()) {
                            s = Status.AMBIGUOUS;
                            evidence = "undecided match without " + p + ": " + cmp.ambiguous().get(0).reason();
                        } else if (!cmp.persisting().isEmpty()) {
                            s = Status.NOT_A_CONTRIBUTOR;
                            evidence = "still reported without " + p + " (as " + cmp.persisting().get(0).resultId()
                                    + " in " + cf.source() + ")";
                        } else {
                            s = Status.VERIFIED;
                            evidence = "reported in " + state + " (with " + p + ") and absent from the complete "
                                    + "result without " + p + " (" + cf.source() + "), compared by " + matcher.id();
                            contributors.add(p);
                        }
                    }
                    v.put("status", s.name());
                    v.put("evidence", evidence);
                    candidateCounts.merge(s.name(), 1, Integer::sum);
                    verdicts.add(v);
                }
                if (!contributors.isEmpty()) {
                    outcome = "ATTRIBUTED";
                    reason = contributors.size() == 1 ? "one verified contributor in this combined context"
                            : contributors.size() + " verified contributors in this combined context (each necessary "
                                    + "here; none is claimed to be the unique cause)";
                } else {
                    outcome = "UNATTRIBUTED";
                    reason = "no candidate could be verified: " + String.join("; ", verdicts.stream()
                            .map(o -> ((Map<?, ?>) o).get("package") + " " + ((Map<?, ?>) o).get("status")).toList());
                }
            }
            wm.put("status", outcome);
            wm.put("reason", reason);
            wm.put("contributors", contributors);
            wm.put("candidates", verdicts);
            counts.merge(outcome, 1, Integer::sum);
            warnings.add(wm);
        }

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("schemaVersion", 1);
        record.put("model", MODEL);
        record.put("roundResult", rel(roundDir));
        record.put("roundResultId", state);
        record.put("round", round.get("round"));
        record.put("baseState", baseState);
        record.put("evaluation", rel(evaluationDir));
        record.put("identity", identity(roundDir, evaluationDir));
        record.put("matcher", matcher.id());
        record.put("configuration", config.map(EvaluationConfig::fingerprint).orElse(null));
        record.put("mode", config.isPresent() ? "local checks where saved evidence is not the required state"
                : "saved evidence only (states needing a local check are INCONCLUSIVE)");
        record.put("counts", counts);
        record.put("candidateCounts", candidateCounts);
        List<Object> cfs = new ArrayList<>();
        without.forEach((p, cf) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("without", p);
            m.put("key", cf.key());
            m.put("state", cf.source());
            m.put("complete", cf.findings().isPresent());
            m.put("unchanged", cf.unchanged());
            m.put("detail", cf.detail());
            cfs.add(m);
        });
        record.put("counterfactuals", cfs);
        record.put("warnings", warnings);
        record.put("note", "A VERIFIED package contributes to the warning in this combined context: the warning is "
                + "reported with every included package and absent from the complete result without that one (shared "
                + "edits kept). It is not proof that the package alone causes it. Candidates come from saved "
                + "independent evaluations only; NO_CANDIDATE and UNATTRIBUTED warnings get no edge.");
        String recordKey = EvaluationFiles.sha256(MODEL + "\n" + record.get("identity") + "\n" + matcher.id() + "\n"
                + Json.write(record.get("configuration") == null ? "saved-only" : record.get("configuration")))
                .substring(0, 12);
        Path dir = runDir.resolve(DIR).resolve(state + "-" + recordKey);
        EvaluationFiles.writeAtomically(dir.resolve(RECORD), Json.write(record));
        return dir;
    }

    /** The identity an attribution is valid for: the combined result and the evaluation it used. */
    public static String identity(Path roundDir, Path evaluationDir) throws IOException {
        return EvaluationFiles.sha256(EvaluationFiles.hashPath(roundDir.resolve(RoundApplication.RECORD)) + "\n"
                + EvaluationFiles.hashPath(roundDir.resolve(RoundApplication.FINDINGS)) + "\n"
                + EvaluationFiles.hashPath(evaluationDir.resolve("baseline").resolve("result.json")) + "\n"
                + EvaluationFiles.hashPath(evaluationDir.resolve(Candidates.FILE)));
    }

    /** The state without package {@code p}: reused saved evidence where it is exactly that state, else a local run. */
    private Counterfactual counterfactual(String p, Map<String, List<AnnotationEdit>> packages, TreeSet<String> all,
            Path base, String baseHash, List<ObservedFinding> baseline, Map<?, ?> baseTools, String combinedTree,
            String baseState) throws IOException {
        Map<String, AnnotationEdit> remaining = new TreeMap<>();
        packages.forEach((q, es) -> {
            if (!q.equals(p)) {
                es.forEach(e -> remaining.put(Candidates.editKey(e), e));
            }
        });
        String editSet = String.join("\n--\n", remaining.keySet());
        String key = EvaluationFiles.sha256(MODEL + "\n" + baseHash + "\n" + EvaluationFiles.sha256(Json.write(
                baseline.stream().map(FindingJson::finding).toList())) + "\n" + editSet + "\n"
                + Json.write(config.map(EvaluationConfig::fingerprint).orElse(Map.of())) + "\n" + matcher.id())
                .substring(0, 16);
        if (remaining.keySet().equals(all)) {
            return new Counterfactual(key, "the complete edit set", Optional.empty(), "every edit of " + p + " is also an "
                    + "edit of another included package (shared ownership kept), so excluding it changes no source",
                    true);
        }
        if (remaining.isEmpty()) {
            return new Counterfactual(key, "the base state " + baseState + " (the evaluation's baseline, reused)",
                    Optional.of(baseline), "reused", false);
        }
        for (Map.Entry<String, List<AnnotationEdit>> q : packages.entrySet()) {
            TreeSet<String> own = new TreeSet<>();
            q.getValue().forEach(e -> own.add(Candidates.editKey(e)));
            Path attempt = evaluationDir.resolve("attempts").resolve(q.getKey());
            if (!q.getKey().equals(p) && own.equals(remaining.keySet())
                    && Files.isRegularFile(attempt.resolve("comparison.json"))) {
                Map<?, ?> a = WarningRegistry.parse(attempt.resolve("attempt.json"));
                if (AttemptOutcome.valueOf(String.valueOf(a.get("outcome"))).compared()
                        && baseState.equals(a.get("baselineState"))) {
                    List<ObservedFinding> fs = new ArrayList<>();
                    for (Object o : WarningRegistry.list(WarningRegistry.parse(attempt.resolve("comparison.json"))
                            .get("resultFindings"))) {
                        fs.add(FindingJson.finding((Map<?, ?>) o));
                    }
                    return new Counterfactual(key, "package " + q.getKey() + "'s own evaluation (state "
                            + a.get("resultState") + ", reused: exactly the remaining edits)", Optional.of(fs), "reused",
                            false);
                }
            }
        }
        Path dir = runDir.resolve(DIR).resolve("counterfactuals").resolve(key);
        if (config.isEmpty()) {
            // saved evidence only: a completed, comparable local check of exactly this state (any configuration
            // with the baseline's tool identity) is evidence; nothing new is run
            Optional<Path> saved = savedCheck(baseHash, new ArrayList<>(remaining.keySet()), baseTools);
            if (saved.isPresent()) {
                dir = saved.get();
            }
        }
        String where = "a local check without " + p + " (" + rel(dir) + ")";
        Path result = dir.resolve("result.json");
        boolean retry = Files.isRegularFile(result) && "FAILED".equals(WarningRegistry.parse(result).get("status"))
                && config.isPresent() && checker.isPresent();
        if (!Files.isRegularFile(result) || retry) {
            // a failed check (timeout, compilation failure, ...) is evidence of nothing: it is checked again
            if (config.isEmpty() || checker.isEmpty()) {
                return new Counterfactual(key, where, Optional.empty(), "not checked: the state without " + p + " is no "
                        + "saved evaluation, and saved-evidence-only mode runs no checker", false);
            }
            check(dir, base, baseHash, new ArrayList<>(remaining.values()), baseTools, combinedTree, p);
        }
        Map<?, ?> r = WarningRegistry.parse(result);
        if (Boolean.TRUE.equals(r.get("unchanged"))) {
            return new Counterfactual(key, where, Optional.empty(), String.valueOf(r.get("detail")), true);
        }
        if (!"COMPLETED".equals(r.get("status"))) {
            return new Counterfactual(key, where, Optional.empty(), r.get("status") + ": " + r.get("detail"), false);
        }
        List<ObservedFinding> fs = new ArrayList<>();
        for (Object o : WarningRegistry.list(r.get("findings"))) {
            fs.add(FindingJson.finding((Map<?, ?>) o));
        }
        return new Counterfactual(key, where, Optional.of(fs), "checked", false);
    }

    /** A completed local check of {@code edits} on the base, comparable with the baseline's tools, if one exists. */
    private Optional<Path> savedCheck(String baseHash, List<String> edits, Map<?, ?> baseTools) throws IOException {
        Path area = runDir.resolve(DIR).resolve("counterfactuals");
        if (!Files.isDirectory(area)) {
            return Optional.empty();
        }
        List<Path> dirs;
        try (Stream<Path> s = Files.list(area)) {
            dirs = s.filter(d -> Files.isRegularFile(d.resolve("result.json"))).sorted().toList();
        }
        for (Path d : dirs) {
            Map<?, ?> r = WarningRegistry.parse(d.resolve("result.json"));
            if ("COMPLETED".equals(r.get("status")) && baseHash.equals(r.get("baseSnapshotTreeSha256"))
                    && edits.equals(r.get("edits")) && matcher.id().equals(r.get("matcher"))
                    && r.get("toolIdentity") instanceof Map<?, ?> t && ToolIdentity.differences(baseTools, t).isEmpty()) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    /** Applies {@code edits} to a fresh copy of {@code base} and checks it once; writes {@code result.json}. */
    private void check(Path dir, Path base, String baseHash, List<AnnotationEdit> edits, Map<?, ?> baseTools,
            String combinedTree, String p) throws IOException {
        Path tmp = dir.resolveSibling(".tmp-" + dir.getFileName() + "-" + ProcessHandle.current().pid());
        EvaluationFiles.deleteTree(tmp);
        Files.createDirectories(tmp);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", 1);
        m.put("model", MODEL);
        m.put("without", p);
        m.put("baseSnapshot", base.toString());
        m.put("baseSnapshotTreeSha256", baseHash);
        m.put("configuration", config.get().fingerprint());
        m.put("matcher", matcher.id());
        m.put("edits", edits.stream().map(Candidates::editKey).toList());
        m.put("createdAt", clock.instant().toString());
        Path source = tmp.resolve("source");
        SnapshotCopier.copy(base, source);
        AnnotationApplier.ApplyResult applied = applier.apply(source, edits);
        List<String> rejected = new ArrayList<>();
        for (int i = 0; i < edits.size(); i++) {
            if (applied.results().get(i).outcome() == AnnotationApplier.Outcome.REJECTED) {
                rejected.add(Candidates.editKey(edits.get(i)) + ": " + applied.results().get(i).detail());
            }
        }
        String tree = EvaluationFiles.treeHash(source);
        m.put("sourceTreeSha256", tree);
        if (!rejected.isEmpty()) {
            m.put("status", "FAILED");
            m.put("detail", "edit(s) could not be applied: " + rejected);
        } else if (tree.equals(combinedTree)) {
            m.put("status", "UNCHANGED");
            m.put("unchanged", true);
            m.put("detail", "excluding " + p + " leaves exactly the combined source (its edits are no-ops there)");
        } else {
            out.println("  checking:   the state without " + p + " (" + source + ")");
            CheckerRun run = checker.get().analyze(request(source, tmp.resolve("check")));
            if (run.outcome() instanceof ExecutionFailure f) {
                m.put("status", "FAILED");
                m.put("detail", f.kind() + ": " + Evaluator.firstLine(f.reasons().get(0)) + " (incomplete; no evidence "
                        + "of absence)");
            } else {
                CompletedAnalysis completed = (CompletedAnalysis) run.outcome();
                Map<String, Object> identity = tools.of(run);
                List<String> differences = ToolIdentity.differences(baseTools, identity);
                m.put("toolIdentity", identity);
                if (!differences.isEmpty()) {
                    m.put("status", "FAILED");
                    m.put("detail", "analyzed with a different " + String.join(", ", differences) + " than the round's "
                            + "baseline; not comparable");
                } else {
                    WarningGraph sorted = InitialWarningGraph.build("counterfactual", checker.get().id(), completed,
                            source);
                    DeclarationAnchors anchors = new DeclarationAnchors(source);
                    List<Object> findings = new ArrayList<>();
                    int n = 0;
                    for (WarningNode w : sorted.warningNodes()) {
                        findings.add(FindingJson.finding(Evaluator.observe(String.format("C%03d", ++n), w.diagnostic(),
                                w.targetPath(), anchors)));
                    }
                    m.put("status", "COMPLETED");
                    m.put("detail", n + " finding(s)");
                    m.put("findings", findings);
                }
            }
        }
        if (!baseHash.equals(EvaluationFiles.treeHash(base))) {
            throw new IOException("the base snapshot " + base + " changed during the counterfactual check");
        }
        EvaluationFiles.writeAtomically(tmp.resolve("result.json"), Json.write(m));
        Files.createDirectories(dir.getParent());
        EvaluationFiles.deleteTree(dir);
        Files.move(tmp, dir);
    }

    private AnalysisRequest request(Path source, Path runDirectory) {
        AnalysisConfig c = config.get().analysis();
        List<Path> classpath = c.classpath().stream()
                .map(e -> e.relativeToTarget() ? source.resolve(e.path()) : e.path()).toList();
        return new AnalysisRequest(source, c.sourceRoots(), classpath, c.checkerJavaHome(), c.javacRelease(),
                runDirectory, c.timeout());
    }

    /** An edit from its saved fields ({@link Candidates#editFields}). */
    static AnnotationEdit edit(Map<?, ?> f) {
        String position = String.valueOf(f.get("position"));
        AnnotationPosition pos;
        if (position.equals("FIELD")) {
            pos = AnnotationPosition.field();
        } else if (position.equals("RETURN")) {
            pos = AnnotationPosition.returnType();
        } else if (position.startsWith("PARAMETER[")) {
            pos = AnnotationPosition.parameter(Integer.parseInt(position.substring(10, position.length() - 1)));
        } else if (position.startsWith("TYPE_USE ")) {
            pos = AnnotationPosition.of(TypeUsePath.parse(position.substring(9)));
        } else {
            throw new IllegalArgumentException("unknown position " + position);
        }
        return new AnnotationEdit(String.valueOf(f.get("sourceRoot")), String.valueOf(f.get("file")),
                String.valueOf(f.get("declaringClass")), MemberRef.parse(String.valueOf(f.get("member"))), pos,
                EditAction.valueOf(String.valueOf(f.get("action"))), String.valueOf(f.get("annotation")));
    }

    private static ObservedFinding observed(String id, Map<?, ?> f) {
        Map<?, ?> a = (Map<?, ?>) f.get("anchor");
        Object problem = a.get("problem");
        return new ObservedFinding(id, FindingJson.diagnostic((Map<?, ?>) f.get("diagnostic")),
                String.valueOf(f.get("path")), new ObservedFinding.Anchor(String.valueOf(a.get("declaration")),
                ((Number) a.get("line")).intValue(), problem == null ? "" : String.valueOf(problem)));
    }

    private String rel(Path p) {
        Path a = p.toAbsolutePath().normalize();
        return a.startsWith(runDir) ? runDir.relativize(a).toString() : a.toString();
    }

    /** The saved attribution valid for {@code roundDir} and {@code evaluationDir} now, if exactly one exists. */
    public static Optional<Path> find(Path runDir, Path roundDir, Path evaluationDir) throws IOException {
        Path area = runDir.resolve(DIR);
        if (!Files.isDirectory(area) || !Files.isRegularFile(roundDir.resolve(RoundApplication.RECORD))) {
            return Optional.empty();
        }
        String identity = identity(roundDir, evaluationDir);
        String state = String.valueOf(WarningRegistry.parse(roundDir.resolve(RoundApplication.RECORD))
                .get("roundResultId"));
        List<Path> found = new ArrayList<>();
        try (Stream<Path> s = Files.list(area)) {
            for (Path d : s.filter(d -> d.getFileName().toString().startsWith(state + "-")).sorted().toList()) {
                Path r = d.resolve(RECORD);
                if (Files.isRegularFile(r) && identity.equals(WarningRegistry.parse(r).get("identity"))
                        && MODEL.equals(WarningRegistry.parse(r).get("model"))) {
                    found.add(d);
                }
            }
        }
        // prefer a record with local checks over a saved-evidence-only one; several of either kind: the first
        return found.stream().filter(d -> {
            try {
                return WarningRegistry.parse(d.resolve(RECORD)).get("configuration") != null;
            } catch (IOException e) {
                return false;
            }
        }).findFirst().or(() -> found.stream().findFirst());
    }
}
