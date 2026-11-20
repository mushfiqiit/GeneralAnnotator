package generalannotator.evaluation;

import generalannotator.graph.AnnotationNode;
import generalannotator.graph.GraphEdge;
import generalannotator.graph.GraphNode;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.graph.WarningIds;
import generalannotator.graph.WarningNode;
import generalannotator.inference.SubmissionPreparer;
import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import generalannotator.visualization.DotExporter;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Converts an evaluation directory written before warning IDs recorded the inference round
 * (introduced warnings named {@code A0003-N1}, records of schema version 1, graph schema version 2)
 * to round-sequenced IDs ({@link WarningIds}). Nothing is rerun. Such evaluations evaluated each edit
 * alone; the migration keeps that meaning (records become schema version {@value
 * Evaluator#EDIT_LEVEL_RECORD_SCHEMA_VERSION}, one edit per annotation node, {@code candidateModel}
 * marked historical) and never turns them into package results. Package evaluations live in their own
 * directories and never read them.
 *
 * <ol>
 *   <li><b>Round.</b> Taken from {@code evaluation.json} if recorded. Otherwise it is established as
 *       round 1 only if the evaluation started from the run snapshot ({@code S0}) and every candidate was
 *       proposed for an initial warning of the run (a root node of its {@code warning-graph.json}): such
 *       proposals are the first inference round by definition. Anything else is refused as ambiguous.
 *   <li><b>Plan.</b> Introduced warnings are renumbered in candidate order and, within an attempt, in their
 *       recorded order ({@code A0001-N1} before {@code A0001-N2} before {@code A0002-N1}), which is the
 *       order a fresh evaluation allocates them in. Baseline IDs, annotation IDs and graph semantics stay
 *       unchanged. Overflow (more than {@value WarningIds#MAX_PER_ROUND}), a collision with an existing
 *       ID, unexpected legacy IDs, or a graph that disagrees with the attempt records are refused.
 *   <li><b>Backup.</b> Every file that will change ({@code evaluation.json}, {@code summary.json}, the
 *       graph with its DOT and SVG, and each attempt's {@code attempt.json} and {@code comparison.json}) is
 *       copied to {@code warning-id-migration-backup-<time>/} and verified by hash before anything is
 *       rewritten.
 *   <li><b>Rewrite.</b> The plan is saved in {@value #MARKER}, then each file is rewritten atomically:
 *       attempt records get {@code warningId} and {@code round} per introduced warning, comparisons get an
 *       {@code introducedWarnings} mapping, the graph is renamed (nodes and edge endpoints) and written as
 *       schema version 3 with rounds, the DOT file is regenerated, the stale SVG is removed (rerun
 *       {@code visualize}), and {@code summary.json} and finally {@code evaluation.json} record the round
 *       and the migration. Each step is idempotent, so an interrupted migration completes when it is run
 *       again; {@code evaluate-annotations} refuses the directory while the marker exists.
 * </ol>
 */
public final class WarningIdMigration {

    public static final String MARKER = ".warning-id-migration.json";
    public static final String BACKUP_PREFIX = "warning-id-migration-backup-";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private WarningIdMigration() {}

    /** How a migration ended. */
    public enum Status {
        /** The directory was converted (or an interrupted conversion was completed). */
        MIGRATED,
        /** The directory already uses round-sequenced IDs; nothing was changed. */
        CURRENT,
        /** The conversion is ambiguous or unsafe; nothing was changed. */
        REFUSED,
        /** Another process is writing the directory. */
        BUSY
    }

    /**
     * The result.
     *
     * @param renames old ID to new ID, in allocation order
     */
    public record Result(Status status, String message, Optional<Path> backup, Map<String, String> renames) {}

    /** The plan of one migration, saved in the marker so an interrupted run can finish it. */
    private record Plan(int round, String roundSource, String backup, Map<String, String> renames, String startedAt) {}

    @SuppressWarnings("try")
    public static Result migrate(Path evaluationDir, Clock clock) throws IOException {
        Path dir = evaluationDir.toAbsolutePath().normalize();
        if (!Files.isRegularFile(dir.resolve(Evaluator.RECORD)) || !Files.isRegularFile(dir.resolve(Candidates.FILE))) {
            return refused(dir + " is not an evaluation directory (runs/<run>/evaluations/<id>)");
        }
        try (EvaluationFiles.Lock lock = EvaluationFiles.lock(dir, "migrate-warning-ids, pid "
                + ProcessHandle.current().pid() + " since " + clock.instant())) {
            Path marker = dir.resolve(MARKER);
            if (Files.exists(marker)) {
                Plan plan = readPlan(marker);
                apply(dir, plan, clock);
                Files.delete(marker);
                return new Result(Status.MIGRATED, "completed an interrupted migration", Optional.of(dir.resolve(
                        plan.backup())), plan.renames());
            }
            return planAndApply(dir, clock);
        } catch (EvaluationFiles.BusyException e) {
            return new Result(Status.BUSY, e.getMessage(), Optional.empty(), Map.of());
        }
    }

    private static Result planAndApply(Path dir, Clock clock) throws IOException {
        Map<?, ?> evaluation = parse(dir.resolve(Evaluator.RECORD));
        Map<?, ?> candidates = parse(dir.resolve(Candidates.FILE));
        List<String> candidateIds = new ArrayList<>();
        for (Object o : list(candidates.get("candidates"))) {
            candidateIds.add((String) ((Map<?, ?>) o).get("id"));
        }
        Map<String, Map<?, ?>> records = new LinkedHashMap<>();
        Set<Object> versions = new LinkedHashSet<>();
        for (String id : candidateIds) {
            Path f = dir.resolve("attempts").resolve(id).resolve(Evaluator.ATTEMPT);
            if (Files.exists(f)) {
                Map<?, ?> r = parse(f);
                records.put(id, r);
                versions.add(r.get("schemaVersion"));
            }
        }
        Path graphFile = dir.resolve(Evaluator.GRAPH);
        Long graphVersion = Files.exists(graphFile) ? (Long) parse(graphFile).get("schemaVersion") : null;
        boolean recordedRound = evaluation.get("round") instanceof Long;
        boolean legacy = !recordedRound || versions.contains(1L)
                || Long.valueOf(WarningGraphJson.LEGACY_EXPANDED_SCHEMA_VERSION).equals(graphVersion);
        if (!legacy) {
            return new Result(Status.CURRENT, dir + " already uses round-sequenced warning IDs (round "
                    + evaluation.get("round") + "); nothing was changed", Optional.empty(), Map.of());
        }
        if (versions.contains((long) Evaluator.EDIT_LEVEL_RECORD_SCHEMA_VERSION) && versions.contains(1L)) {
            return refused("the attempt records mix old and new formats and no migration marker explains it");
        }

        int round;
        String roundSource;
        if (recordedRound) {
            round = Math.toIntExact((Long) evaluation.get("round"));
            roundSource = "recorded in " + Evaluator.RECORD;
        } else {
            Optional<String> problem = roundOneProblem(dir, evaluation, candidates);
            if (problem.isPresent()) {
                return refused("the inference round cannot be established: " + problem.get()
                        + "; nothing was changed");
            }
            round = 1;
            roundSource = "established by migrate-warning-ids: the evaluation started from the run snapshot "
                    + Evaluator.BASELINE_STATE + " and every candidate was proposed for an initial warning of the run, "
                    + "so its proposals are the first inference round";
        }

        Set<String> taken = new LinkedHashSet<>();
        for (Object o : list(parse(dir.resolve("baseline").resolve("result.json")).get("findings"))) {
            taken.add((String) ((Map<?, ?>) o).get("id"));
        }
        WarningIds.Allocator ids = new WarningIds.Allocator(round, taken, 0);
        Map<String, String> renames = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Map<?, ?>> e : records.entrySet()) {
                int k = 0;
                for (Map<?, ?> im : Evaluator.introduced(e.getValue())) {
                    String old = (String) im.get("nodeId");
                    String expected = e.getKey() + "-N" + (++k);
                    if (!expected.equals(old)) {
                        return refused("attempt " + e.getKey() + " records introduced warning " + old + ", expected "
                                + expected + "; the old IDs are not as written by evaluate-annotations");
                    }
                    renames.put(old, ids.next());
                }
            }
        } catch (WarningIds.AllocationException e) {
            return refused(e.getMessage());
        }
        if (graphVersion != null) {
            WarningGraph graph = WarningGraphJson.load(graphFile);
            Set<String> inGraph = new LinkedHashSet<>();
            for (WarningNode w : graph.warningNodes()) {
                if (!w.root()) {
                    inGraph.add(w.id());
                }
            }
            if (!inGraph.equals(renames.keySet())) {
                return refused(graphFile + " has introduced warnings " + inGraph + ", but the attempt records have "
                        + renames.keySet() + "; refusing to guess which is right");
            }
        }

        Path backup = dir.resolve(BACKUP_PREFIX + STAMP.format(clock.instant()));
        for (int n = 2; Files.exists(backup, LinkOption.NOFOLLOW_LINKS); n++) {
            backup = dir.resolve(BACKUP_PREFIX + STAMP.format(clock.instant()) + "-" + n);
        }
        List<String> files = new ArrayList<>(List.of(Evaluator.RECORD, Evaluator.SUMMARY, Evaluator.GRAPH,
                dotName(), svgName()));
        for (String id : records.keySet()) {
            files.add("attempts/" + id + "/" + Evaluator.ATTEMPT);
            files.add("attempts/" + id + "/" + Evaluator.COMPARISON);
        }
        for (String f : files) {
            Path from = dir.resolve(f);
            if (Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)) {
                Path to = backup.resolve(f);
                Files.createDirectories(to.getParent());
                Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
                if (!sha(from).equals(sha(to))) {
                    throw new IOException("the backup copy of " + from + " does not match");
                }
            }
        }
        Plan plan = new Plan(round, roundSource, dir.relativize(backup).toString(), renames, clock.instant().toString());
        EvaluationFiles.writeAtomically(dir.resolve(MARKER), Json.write(planJson(plan)));
        apply(dir, plan, clock);
        Files.delete(dir.resolve(MARKER));
        return new Result(Status.MIGRATED, renames.size() + " introduced warning(s) renamed for round " + round,
                Optional.of(backup), renames);
    }

    /** Why the round cannot be established as 1, or empty if it can. */
    private static Optional<String> roundOneProblem(Path dir, Map<?, ?> evaluation, Map<?, ?> candidates)
            throws IOException {
        if (!Evaluator.BASELINE_STATE.equals(evaluation.get("baselineState"))) {
            return Optional.of("the baseline state is " + evaluation.get("baselineState") + ", not "
                    + Evaluator.BASELINE_STATE);
        }
        Path runDir = dir.getParent() == null ? null : dir.getParent().getParent();
        Path runGraph = runDir == null ? null : runDir.resolve(WarningGraphJson.FILE_NAME);
        if (runGraph == null || !Files.isRegularFile(runGraph)) {
            return Optional.of("the run's " + WarningGraphJson.FILE_NAME + " is not next to the evaluation");
        }
        WarningGraph initial;
        try {
            initial = WarningGraphJson.load(runGraph);
        } catch (IllegalArgumentException e) {
            return Optional.of(runGraph + " is not a valid warning graph: " + e.getMessage());
        }
        Set<String> roots = new LinkedHashSet<>();
        for (GraphNode n : initial.nodes()) {
            if (n instanceof WarningNode w && w.root() && w.distance() == 0 && w.state().isEmpty()) {
                roots.add(w.id());
            } else {
                return Optional.of(runGraph + " is not an initial warning graph (node " + n.id() + ")");
            }
        }
        for (Object o : list(candidates.get("candidates"))) {
            Map<?, ?> c = (Map<?, ?>) o;
            for (Object a : list(c.get("associations"))) {
                Object warning = ((Map<?, ?>) a).get("warningId");
                if (!roots.contains(warning)) {
                    return Optional.of("candidate " + c.get("id") + " was proposed for " + warning + ", which is not an "
                            + "initial warning of the run");
                }
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------------------------------------
    // Rewriting (idempotent)

    private static void apply(Path dir, Plan plan, Clock clock) throws IOException {
        Map<String, String> renames = plan.renames();
        Map<?, ?> candidates = parse(dir.resolve(Candidates.FILE));
        for (Object o : list(candidates.get("candidates"))) {
            String id = (String) ((Map<?, ?>) o).get("id");
            Path attemptFile = dir.resolve("attempts").resolve(id).resolve(Evaluator.ATTEMPT);
            if (!Files.exists(attemptFile)) {
                continue;
            }
            Map<?, ?> record = parse(attemptFile);
            if (Long.valueOf(1).equals(record.get("schemaVersion"))) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : record.entrySet()) {
                    String key = (String) e.getKey();
                    if (key.equals("schemaVersion")) {
                        m.put(key, Evaluator.EDIT_LEVEL_RECORD_SCHEMA_VERSION);
                    } else if (key.equals("introduced") && e.getValue() instanceof List<?> l) {
                        List<Object> renamed = new ArrayList<>();
                        for (Object x : l) {
                            Map<?, ?> im = (Map<?, ?>) x;
                            Map<String, Object> nm = new LinkedHashMap<>();
                            nm.put("warningId", rename(renames, (String) im.get("nodeId")));
                            nm.put("round", plan.round());
                            im.forEach((k, v) -> {
                                if (!"nodeId".equals(k)) {
                                    nm.put((String) k, v);
                                }
                            });
                            renamed.add(nm);
                        }
                        m.put(key, renamed);
                    } else {
                        m.put(key, e.getValue());
                    }
                    if (key.equals("evaluationId")) {
                        m.put("round", plan.round());
                    }
                }
                EvaluationFiles.writeAtomically(attemptFile, Json.write(m));
                record = m;
            }
            Path comparisonFile = attemptFile.resolveSibling(Evaluator.COMPARISON);
            if (Files.exists(comparisonFile)) {
                Map<?, ?> comparison = parse(comparisonFile);
                if (!comparison.containsKey("introducedWarnings")) {
                    List<Object> mapping = new ArrayList<>();
                    for (Map<?, ?> im : Evaluator.introduced(record)) {
                        mapping.add(Evaluator.introducedMapping((String) im.get("resultId"), (String) im.get("warningId"),
                                plan.round()));
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    comparison.forEach((k, v) -> {
                        m.put((String) k, v);
                        if ("introduced".equals(k)) {
                            m.put("introducedWarnings", mapping);
                        }
                    });
                    m.putIfAbsent("introducedWarnings", mapping);
                    EvaluationFiles.writeAtomically(comparisonFile, Json.write(m));
                }
            }
        }

        Path graphFile = dir.resolve(Evaluator.GRAPH);
        if (Files.exists(graphFile) && Long.valueOf(WarningGraphJson.LEGACY_EXPANDED_SCHEMA_VERSION)
                .equals(parse(graphFile).get("schemaVersion"))) {
            WarningGraph legacy = WarningGraphJson.load(graphFile);
            WarningGraph renamed = renameGraph(legacy, renames, plan.round());
            EvaluationFiles.writeAtomically(graphFile, WarningGraphJson.write(renamed));
            if (Files.exists(dir.resolve(dotName()))) {
                EvaluationFiles.writeAtomically(dir.resolve(dotName()), DotExporter.toDot(renamed));
            }
        }
        Files.deleteIfExists(dir.resolve(svgName()));

        Path summaryFile = dir.resolve(Evaluator.SUMMARY);
        if (Files.exists(summaryFile)) {
            Map<?, ?> summary = parse(summaryFile);
            if (!summary.containsKey("round")) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : summary.entrySet()) {
                    String key = (String) e.getKey();
                    if (key.equals("schemaVersion")) {
                        m.put(key, Evaluator.EDIT_LEVEL_RECORD_SCHEMA_VERSION);
                    } else if (key.equals("candidates") && e.getValue() instanceof List<?> l) {
                        List<Object> cs = new ArrayList<>();
                        for (Object x : l) {
                            Map<String, Object> c = new LinkedHashMap<>();
                            ((Map<?, ?>) x).forEach((k, v) -> c.put((String) k, v));
                            if (c.get("introduced") instanceof List<?> ids) {
                                c.put("introduced", ids.stream().map(i -> rename(renames, (String) i)).toList());
                            }
                            cs.add(c);
                        }
                        m.put(key, cs);
                    } else {
                        m.put(key, e.getValue());
                    }
                    if (key.equals("evaluationId")) {
                        m.put("round", plan.round());
                        Map<String, Object> seq = new LinkedHashMap<>();
                        seq.put("scheme", WarningIds.SCHEME);
                        seq.put("round", plan.round());
                        seq.put("lastSequence", renames.size());
                        seq.put("next", renames.size() < WarningIds.MAX_PER_ROUND
                                ? WarningIds.introduced(plan.round(), renames.size() + 1) : null);
                        m.put("warningIdSequence", seq);
                    }
                }
                EvaluationFiles.writeAtomically(summaryFile, Json.write(m));
            }
        }

        Path recordFile = dir.resolve(Evaluator.RECORD);
        Map<?, ?> evaluation = parse(recordFile);
        if (!(evaluation.get("round") instanceof Long) || !evaluation.containsKey("warningIdScheme")) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : evaluation.entrySet()) {
                String key = (String) e.getKey();
                m.put(key, key.equals("schemaVersion") ? Evaluator.EDIT_LEVEL_RECORD_SCHEMA_VERSION : e.getValue());
                if (key.equals("createdAt")) {
                    m.put("round", plan.round());
                    m.put("warningIdScheme", WarningIds.SCHEME);
                    m.put("candidateModel", "single-edit (historical; each edit evaluated alone)");
                    m.put("roundSource", plan.roundSource());
                }
            }
            Map<String, Object> migration = new LinkedHashMap<>();
            migration.put("tool", "migrate-warning-ids");
            migration.put("startedAt", plan.startedAt());
            migration.put("finishedAt", clock.instant().toString());
            migration.put("from", "introduced warnings named <candidate>-N<k> (records schema 1, graph schema 2)");
            migration.put("to", WarningIds.SCHEME + " (records schema " + Evaluator.EDIT_LEVEL_RECORD_SCHEMA_VERSION + ", graph "
                    + "schema " + WarningGraphJson.EXPANDED_SCHEMA_VERSION + ")");
            migration.put("backup", plan.backup());
            migration.put("renames", plan.renames());
            m.put("migrations", List.of(migration));
            EvaluationFiles.writeAtomically(recordFile, Json.write(m));
        }
    }

    /** The legacy graph with introduced warnings renamed, rounds set, and edge endpoints updated. */
    static WarningGraph renameGraph(WarningGraph legacy, Map<String, String> renames, int round) {
        List<GraphNode> nodes = new ArrayList<>();
        for (GraphNode n : legacy.nodes()) {
            nodes.add(switch (n) {
                case WarningNode w -> w.root()
                        ? new WarningNode(w.id(), w.diagnostic(), w.targetPath(), true, 0, w.state(), OptionalInt.of(0))
                        : new WarningNode(rename(renames, w.id()), w.diagnostic(), w.targetPath(), false, w.distance(),
                                w.state(), OptionalInt.of(round));
                case AnnotationNode a -> new AnnotationNode(a.id(), a.canonical(), a.edits(), a.baselineState(),
                        a.resultState(), a.outcome(), a.detail(), a.proposals(), a.counts(), OptionalInt.of(round));
            });
        }
        List<GraphEdge> edges = new ArrayList<>();
        for (GraphEdge e : legacy.edges()) {
            edges.add(new GraphEdge(renames.getOrDefault(e.source(), e.source()),
                    renames.getOrDefault(e.target(), e.target()), e.kind()));
        }
        return new WarningGraph(legacy.runId(), legacy.checkerId(), nodes, edges);
    }

    private static String rename(Map<String, String> renames, String old) {
        String id = renames.get(old);
        if (id == null) {
            throw new IllegalStateException("no new ID planned for " + old);
        }
        return id;
    }

    private static Map<String, Object> planJson(Plan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("round", p.round());
        m.put("roundSource", p.roundSource());
        m.put("backup", p.backup());
        m.put("renames", p.renames());
        m.put("startedAt", p.startedAt());
        return m;
    }

    private static Plan readPlan(Path marker) throws IOException {
        Map<?, ?> m = parse(marker);
        Map<String, String> renames = new LinkedHashMap<>();
        ((Map<?, ?>) m.get("renames")).forEach((k, v) -> renames.put((String) k, (String) v));
        return new Plan(Math.toIntExact((Long) m.get("round")), (String) m.get("roundSource"), (String) m.get("backup"),
                renames, (String) m.get("startedAt"));
    }

    private static String dotName() {
        return Evaluator.GRAPH.replace(".json", ".dot");
    }

    private static String svgName() {
        return Evaluator.GRAPH.replace(".json", ".svg");
    }

    private static Result refused(String message) {
        return new Result(Status.REFUSED, message, Optional.empty(), Map.of());
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static String sha(Path f) throws IOException {
        return SubmissionPreparer.sha256(Files.readAllBytes(f));
    }

    private static Map<?, ?> parse(Path file) throws IOException {
        try {
            return (Map<?, ?>) JsonParser.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException(file + " is not valid JSON: " + e.getMessage());
        }
    }
}
