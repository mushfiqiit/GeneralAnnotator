package generalannotator.evaluation;

import generalannotator.evaluation.matching.WarningMatcher;
import generalannotator.graph.WarningGraphJson;
import generalannotator.graph.WarningIds;
import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Migrates a run written before the warning registry ({@code warning-ids migrate <run>}): afterwards every public
 * warning ID names one warning in every report, comparison and graph of the run.
 *
 * <ol>
 *   <li><b>Plan</b> (nothing is written): the run's baseline and the combined round results are registered first,
 *       so their report IDs are kept. Each evaluation is then registered in pipeline order (named evaluations
 *       first, then superseded ones by round and name); a warning it introduced whose recorded ID already names
 *       another warning is a distinct occurrence and gets a fresh ID of its round, after every ID of that round in
 *       any artifact. Legacy warnings are identified by artifact namespace (evaluation directory), state and
 *       local finding, never by bare ID.
 *   <li><b>Back up</b> every derived file that changes into {@code migrations/<id>/backup/} (raw checker output,
 *       snapshots and combined round results are never changed).
 *   <li><b>Apply</b> the renames to the evaluations' attempt records, comparisons, graphs and summaries (stale
 *       renderings are removed), save the registry, and rebuild every propagation round's extended graph from the
 *       saved artifacts in round order ({@link generalannotator.propagation.PropagationStage#rebuildGraph} through
 *       the {@code rebuilder}); propagation stages in pipeline.json record the new graph model.
 *   <li><b>Validate</b> with {@link WarningIntegrity}; on any problem every changed file is restored from the
 *       backup and the run is left as it was.
 * </ol>
 * The report ({@code migrations/<id>/migration.json}) lists every rename with its evidence, the ambiguities left
 * unresolved (kept as separate warnings), and the integrity result.
 */
public final class RegistryMigration {

    /** Rebuilds one propagation stage's extended graph from saved artifacts. */
    @FunctionalInterface
    public interface Rebuilder {
        /** @throws Superseded if the stage's input no longer resolves to what it was computed on */
        void rebuild(Path stageDir) throws IOException;
    }

    /** A propagation stage that cannot be rebuilt against the run's current input (a superseded result). */
    public static final class Superseded extends IOException {
        private static final long serialVersionUID = 1L;

        public Superseded(String message) {
            super(message);
        }
    }

    public record Result(boolean migrated, boolean ok, Path report, Map<String, Map<String, String>> renames,
            List<String> problems, List<String> ambiguities) {}

    private RegistryMigration() {}

    public static Result migrate(Path runDir, WarningMatcher matcher, Clock clock, PrintStream out, Rebuilder rebuilder)
            throws IOException {
        Path dir = runDir.toAbsolutePath().normalize();
        if (Files.isRegularFile(dir.resolve(WarningRegistry.FILE))) {
            WarningIntegrity.Report check = WarningIntegrity.check(dir);
            if (check.ok()) {
                out.println("  already consistent: " + dir.resolve(WarningRegistry.FILE) + " and every report and graph "
                        + "agree; nothing to migrate");
                return new Result(false, true, null, Map.of(), List.of(), List.of());
            }
        }

        // 1. Plan.
        WarningRegistry reg = WarningRegistry.empty(dir);
        reg.registerBaseline();
        for (Path round : WarningRegistry.roundsInOrder(dir)) {
            reg.registerRound(round);
        }
        Map<Integer, Integer> next = new TreeMap<>();
        for (String id : allIds(dir)) {
            for (int r = 1; r <= 99; r++) {
                int round = r;
                WarningIds.sequence(id, r).ifPresent(s -> next.merge(round, s, Math::max));
            }
        }
        Map<String, Map<String, String>> renames = new LinkedHashMap<>();
        List<String> evidence = new ArrayList<>();
        for (Path evaluation : WarningRegistry.evaluationsInOrder(dir)) {
            Map<String, String> map = new TreeMap<>();
            Set<String> baselineIds = baselineIds(evaluation);
            for (Map<?, ?> iw : introduced(evaluation)) {
                String id = String.valueOf(iw.get("warningId"));
                int round = ((Number) iw.get("round")).intValue();
                if (reg.warning(id).isPresent() && !map.containsKey(id)) {
                    if (baselineIds.contains(id)) {
                        throw new IOException(evaluation + " uses " + id + " both for its baseline and for an introduced "
                                + "warning; it cannot be migrated automatically");
                    }
                    int seq = next.merge(round, 1, Integer::sum);
                    String fresh = WarningIds.introduced(round, seq);
                    map.put(id, fresh);
                    evidence.add(reg.artifact(evaluation) + ": " + id + " -> " + fresh + " (introduced in state "
                            + iw.get("state") + "; " + id + " already names " + reg.warning(id).get().site().describe()
                            + ", first in " + reg.warning(id).get().first() + ")");
                }
            }
            reg.registerEvaluation(evaluation, map);
            if (!map.isEmpty()) {
                renames.put(reg.artifact(evaluation), map);
            }
        }
        out.println("  plan:       " + reg.warnings().size() + " warning(s), " + reg.occurrences().size()
                + " occurrence(s); " + renames.values().stream().mapToInt(Map::size).sum() + " independent-evaluation "
                + "occurrence(s) renamed in " + renames.size() + " evaluation(s); combined-report IDs kept");
        evidence.forEach(e -> out.println("    " + e));

        // 2. Back up.
        String id = "warning-registry-" + clock.instant().toString().replace(":", "").replace(".", "");
        Path migration = dir.resolve("migrations").resolve(id);
        Path backup = migration.resolve("backup");
        List<Path> touched = new ArrayList<>();
        for (String artifact : renames.keySet()) {
            Path evaluation = dir.resolve(artifact);
            touched.addAll(evaluationFiles(evaluation));
        }
        List<Path> stages = propagationStages(dir);
        for (Path stage : stages) {
            for (String f : List.of(Evaluator.GRAPH, "propagation.json", "summary.txt", "evaluation-graph.dot",
                    "evaluation-graph.svg")) {
                touched.add(stage.resolve(f));
            }
        }
        for (String f : List.of("pipeline.json", WarningRegistry.FILE)) {
            touched.add(dir.resolve(f));
        }
        Set<Path> existed = new LinkedHashSet<>();
        for (Path f : touched) {
            if (Files.isRegularFile(f)) {
                existed.add(f);
                Path copy = backup.resolve(dir.relativize(f).toString());
                Files.createDirectories(copy.getParent());
                Files.copy(f, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        out.println("  backup:     " + existed.size() + " derived file(s) in " + backup);

        // 3. Apply, 4. validate (restoring on failure).
        List<String> problems = new ArrayList<>();
        List<String> superseded = new ArrayList<>();
        try {
            for (Map.Entry<String, Map<String, String>> e : renames.entrySet()) {
                Path evaluation = dir.resolve(e.getKey());
                for (Path f : evaluationFiles(evaluation)) {
                    if (f.getFileName().toString().endsWith(".dot") || f.getFileName().toString().endsWith(".svg")) {
                        Files.deleteIfExists(f);
                    } else if (Files.isRegularFile(f)) {
                        Object json = JsonParser.parse(Files.readString(f, StandardCharsets.UTF_8));
                        EvaluationFiles.writeAtomically(f, Json.write(rename(json, e.getValue())));
                    }
                }
            }
            reg.save();
            List<Path> named = namedStages(dir);
            for (Path stage : stages) {
                out.println("  rebuild:    " + dir.relativize(stage));
                try {
                    rebuilder.rebuild(stage);
                } catch (Superseded e) {
                    if (named.contains(stage)) {
                        throw new IOException(dir.relativize(stage) + " is named by pipeline.json but cannot be "
                                + "rebuilt: " + e.getMessage());
                    }
                    // a superseded stage whose input no longer resolves: its legacy graph is kept, marked as such
                    for (String f : List.of(Evaluator.GRAPH, "evaluation-graph.dot", "evaluation-graph.svg")) {
                        Path g = stage.resolve(f);
                        if (Files.isRegularFile(g)) {
                            Files.move(g, stage.resolve(f.replace("evaluation-graph.", "evaluation-graph.legacy.")),
                                    StandardCopyOption.REPLACE_EXISTING);
                            touched.add(stage.resolve(f.replace("evaluation-graph.", "evaluation-graph.legacy.")));
                        }
                    }
                    superseded.add(dir.relativize(stage) + ": not rebuilt (" + e.getMessage() + "); its legacy graph "
                            + "is kept as evaluation-graph.legacy.json and is not part of the run's identities");
                }
            }
            pipelineModels(dir);
            WarningIntegrity.Report check = WarningIntegrity.check(dir);
            problems.addAll(check.problems());
        } catch (IOException | RuntimeException e) {
            problems.add("the migration failed: " + e.getMessage());
        }
        if (!problems.isEmpty()) {
            for (Path f : touched) {
                Path copy = backup.resolve(dir.relativize(f).toString());
                if (existed.contains(f)) {
                    Files.createDirectories(f.getParent());
                    Files.copy(copy, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                } else {
                    Files.deleteIfExists(f);
                }
            }
            out.println("  restored:   every changed file, from " + backup);
        }

        List<String> ambiguities = ambiguities(dir);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("id", id);
        report.put("model", WarningRegistry.MODEL);
        report.put("graphModel", HistoryGraph.MODEL);
        report.put("migratedAt", clock.instant().toString());
        report.put("ok", problems.isEmpty());
        report.put("policy", "combined-report IDs are kept; an independent-evaluation occurrence whose recorded ID "
                + "names another warning gets a fresh ID of its round; legacy warnings are identified by artifact "
                + "namespace, state and local finding, never by bare ID");
        report.put("renames", renames);
        report.put("evidence", evidence);
        report.put("ambiguities", ambiguities);
        report.put("superseded", superseded);
        report.put("problems", problems);
        report.put("backup", dir.relativize(backup).toString());
        report.put("preserved", "raw checker output, logs, snapshots, workspaces and combined round results");
        Files.createDirectories(migration);
        Path reportFile = migration.resolve("migration.json");
        EvaluationFiles.writeAtomically(reportFile, Json.write(report));
        return new Result(true, problems.isEmpty(), reportFile, renames, problems, ambiguities);
    }

    /** Every warning ID recorded by any artifact of the run (for fresh allocation beyond all of them). */
    private static Set<String> allIds(Path dir) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        for (Path round : WarningRegistry.roundsInOrder(dir)) {
            Path f = round.resolve(RoundApplication.FINDINGS);
            if (Files.isRegularFile(f)) {
                for (Object o : WarningRegistry.list(WarningRegistry.parse(f).get("findings"))) {
                    ids.add(String.valueOf(((Map<?, ?>) o).get("warningId")));
                }
            }
        }
        for (Path evaluation : WarningRegistry.evaluationsInOrder(dir)) {
            introduced(evaluation).forEach(iw -> ids.add(String.valueOf(iw.get("warningId"))));
            Path graph = evaluation.resolve(Evaluator.GRAPH);
            if (Files.isRegularFile(graph)) {
                WarningGraphJson.load(graph).warningNodes().forEach(w -> ids.add(w.id()));
            }
        }
        return ids;
    }

    /** The introduced warnings recorded by an evaluation's compared attempts, with their result states. */
    private static List<Map<?, ?>> introduced(Path evaluation) throws IOException {
        List<Map<?, ?>> out = new ArrayList<>();
        Path attempts = evaluation.resolve("attempts");
        if (!Files.isDirectory(attempts)) {
            return out;
        }
        List<Path> dirs;
        try (Stream<Path> s = Files.list(attempts)) {
            dirs = s.filter(p -> Files.isRegularFile(p.resolve("comparison.json"))
                    && Files.isRegularFile(p.resolve("attempt.json"))).sorted().toList();
        }
        for (Path d : dirs) {
            Map<?, ?> a = WarningRegistry.parse(d.resolve("attempt.json"));
            if (!AttemptOutcome.valueOf(String.valueOf(a.get("outcome"))).compared()) {
                continue;
            }
            for (Object o : WarningRegistry.list(WarningRegistry.parse(d.resolve("comparison.json"))
                    .get("introducedWarnings"))) {
                Map<String, Object> m = new LinkedHashMap<>();
                ((Map<?, ?>) o).forEach((k, v) -> m.put(String.valueOf(k), v));
                m.put("state", a.get("resultState"));
                out.add(m);
            }
        }
        return out;
    }

    private static Set<String> baselineIds(Path evaluation) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        Path b = evaluation.resolve("baseline").resolve("result.json");
        if (Files.isRegularFile(b)) {
            for (Object o : WarningRegistry.list(WarningRegistry.parse(b).get("findings"))) {
                ids.add(String.valueOf(((Map<?, ?>) o).get("id")));
            }
        }
        return ids;
    }

    /** The derived files of an evaluation that carry its introduced IDs (records, comparisons, graph, renderings). */
    private static List<Path> evaluationFiles(Path evaluation) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String f : List.of(Evaluator.GRAPH, "summary.json", "evaluation-graph.dot", "evaluation-graph.svg")) {
            files.add(evaluation.resolve(f));
        }
        Path attempts = evaluation.resolve("attempts");
        if (Files.isDirectory(attempts)) {
            try (Stream<Path> s = Files.list(attempts)) {
                for (Path d : s.filter(p -> Files.isRegularFile(p.resolve("attempt.json"))).sorted().toList()) {
                    files.add(d.resolve("attempt.json"));
                    files.add(d.resolve("comparison.json"));
                }
            }
        }
        return files;
    }

    /** Replaces every string value equal to a renamed ID (the evaluation's own namespace only). */
    private static Object rename(Object json, Map<String, String> map) {
        if (json instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), rename(v, map)));
            return copy;
        }
        if (json instanceof List<?> l) {
            List<Object> copy = new ArrayList<>();
            l.forEach(v -> copy.add(rename(v, map)));
            return copy;
        }
        if (json instanceof String s && map.containsKey(s)) {
            return map.get(s);
        }
        return json;
    }

    private static List<Path> namedStages(Path dir) throws IOException {
        return WarningRegistry.namedPropagationDirs(dir);
    }

    private static List<Path> propagationStages(Path dir) throws IOException {
        return WarningRegistry.propagationDirs(dir);
    }

    private static void pipelineModels(Path dir) throws IOException {
        Path file = dir.resolve("pipeline.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) mutable(JsonParser.parse(Files.readString(file,
                StandardCharsets.UTF_8)));
        if (state.get("stages") instanceof Map<?, ?> stages) {
            stages.forEach((k, v) -> {
                String name = String.valueOf(k);
                if ((name.equals("propagation") || name.matches("propagation-r\\d+")) && v instanceof Map<?, ?> m
                        && m.get("graphModel") != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> st = (Map<String, Object>) m;
                    st.put("graphModel", HistoryGraph.MODEL);
                }
            });
        }
        EvaluationFiles.writeAtomically(file, Json.write(state));
    }

    private static Object mutable(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), mutable(v)));
            return copy;
        }
        if (value instanceof List<?> l) {
            List<Object> copy = new ArrayList<>();
            l.forEach(v -> copy.add(mutable(v)));
            return copy;
        }
        return value;
    }

    /** Ambiguities left as separate warnings: ambiguous combined findings and undecided graph correspondences. */
    private static List<String> ambiguities(Path dir) throws IOException {
        List<String> out = new ArrayList<>();
        for (Path round : WarningRegistry.roundsInOrder(dir)) {
            Path f = round.resolve(RoundApplication.FINDINGS);
            if (Files.isRegularFile(f)) {
                for (Object o : WarningRegistry.list(WarningRegistry.parse(f).get("findings"))) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    if ("AMBIGUOUS".equals(m.get("status"))) {
                        out.add(dir.relativize(round) + ": " + m.get("warningId") + " matched ambiguously in the combined "
                                + "comparison; kept as its own warning");
                    }
                }
            }
        }
        for (Path stage : propagationStages(dir)) {
            Map<?, ?> rec = WarningRegistry.parse(stage.resolve("propagation.json"));
            Map<?, ?> history = rec.get("history") instanceof Map<?, ?> h ? h : Map.of();
            for (Object o : WarningRegistry.list(history.get("inputWarnings"))) {
                Map<?, ?> w = (Map<?, ?>) o;
                if ("ambiguous-correspondence".equals(w.get("relation"))) {
                    out.add(dir.relativize(stage) + ": " + w.get("reportWarning") + " has equivalent warnings of several "
                            + "packages; no correspondence recorded");
                }
            }
        }
        return out;
    }
}
