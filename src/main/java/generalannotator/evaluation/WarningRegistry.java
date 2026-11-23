package generalannotator.evaluation;

import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.graph.WarningIds;
import generalannotator.graph.WarningNode;
import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The run-scoped authority for public warning IDs ({@value #FILE} in the run directory). Within one run a public
 * ID names exactly one warning, in every report, comparison and graph.
 *
 * <ul>
 *   <li><b>Warnings</b> ({@code ID -> canonical record}): the ID, the round it belongs to and its first
 *       occurrence (artifact, state and diagnostic identity: path, line, column, message, declaration anchor).
 *   <li><b>Occurrences</b> ({@code occurrence -> ID}): every verified sighting of a warning in a saved artifact,
 *       keyed by the artifact's run-relative directory and its local finding ID ({@code rounds/round2-...#R004},
 *       {@code evaluations/<id>/attempts/P0002#R017}). An occurrence is either the warning's {@code FIRST}, which
 *       allocated its ID, or {@code PERSISTING}: paired with an earlier occurrence of the same ID by the semantic
 *       {@link generalannotator.evaluation.matching.WarningMatcher} in the artifact's own comparison (never by
 *       line or message alone). Ambiguous matches are not persisting; they get their own IDs.
 *   <li><b>Correspondences</b>: verified links between distinct occurrences that keep distinct IDs, for example a
 *       combined-state warning and the equivalent warning a package introduced in its own evaluation. They are
 *       metadata; they never merge IDs and never become graph edges.
 *   <li><b>Allocation</b>: one sequence per round ({@link WarningIds}) shared by the independent evaluations and
 *       the combined application of that round; the next ID follows every ID of the round recorded anywhere
 *       else in the run, and exhaustion fails instead of colliding.
 * </ul>
 *
 * Registration is idempotent (the same occurrence with the same ID is a no-op) and refuses anything that would
 * give one ID to two warnings, or two IDs to one occurrence ({@link Conflict}). The file is written atomically
 * under its own lock ({@code .warning-registry/lock}); a missing registry is rebuilt deterministically from the
 * saved artifacts, and a legacy run whose artifacts reuse IDs is refused until it is migrated
 * ({@code warning-ids migrate}).
 */
public final class WarningRegistry {

    public static final String FILE = "warning-registry.json";
    public static final String MODEL = "warning-registry-1";
    static final String LOCK_DIR = ".warning-registry";

    /** How an occurrence got its ID. */
    public enum Relation { FIRST, PERSISTING }

    /** The diagnostic identity of an occurrence. */
    public record Site(String path, int line, int column, String message, String declaration, String problem) {

        /** A short human-readable form. */
        public String describe() {
            return path + ":" + line + ":" + column + " " + message + (declaration == null ? "" : " [" + declaration
                    + "]");
        }
    }

    /** A verified sighting of a warning. */
    public record Occurrence(String key, String warningId, String artifact, String state, String localId,
            Relation relation, Site site, String evidence) {}

    /** A public warning: its ID, round and first occurrence. */
    public record Warning(String id, int round, String first, String kind, Site site) {}

    /** A verified link between two distinct warnings (no shared ID, no edge). */
    public record Correspondence(String warningId, String correspondsTo, String evidence) {}

    /** Registration would break the one-ID-one-warning invariant. */
    public static final class Conflict extends IOException {
        private static final long serialVersionUID = 1L;

        public Conflict(String message) {
            super(message);
        }
    }

    private final Path runDir;
    private final Map<String, Warning> warnings = new TreeMap<>();
    private final Map<String, Occurrence> occurrences = new TreeMap<>();
    private final Map<Integer, Integer> counters = new TreeMap<>();
    private final Map<String, Correspondence> correspondences = new TreeMap<>();

    private WarningRegistry(Path runDir) {
        this.runDir = runDir.toAbsolutePath().normalize();
    }

    /** An empty registry for {@code runDir} (nothing is read or written). */
    public static WarningRegistry empty(Path runDir) {
        return new WarningRegistry(runDir);
    }

    // ------------------------------------------------------------------------------------------ access

    public Map<String, Warning> warnings() {
        return java.util.Collections.unmodifiableMap(warnings);
    }

    public Map<String, Occurrence> occurrences() {
        return java.util.Collections.unmodifiableMap(occurrences);
    }

    public Map<Integer, Integer> counters() {
        return java.util.Collections.unmodifiableMap(counters);
    }

    public List<Correspondence> correspondences() {
        return List.copyOf(correspondences.values());
    }

    public Optional<Warning> warning(String id) {
        return Optional.ofNullable(warnings.get(id));
    }

    /** The occurrences of {@code id}, in key order. */
    public List<Occurrence> occurrencesOf(String id) {
        return occurrences.values().stream().filter(o -> o.warningId().equals(id)).toList();
    }

    /** The run-relative artifact name of {@code dir} ({@code .} for the run directory itself). */
    public String artifact(Path dir) {
        Path d = dir.toAbsolutePath().normalize();
        if (d.equals(runDir)) {
            return ".";
        }
        return d.startsWith(runDir) ? runDir.relativize(d).toString().replace('\\', '/') : d.toString();
    }

    /** Every ID registered by an artifact other than {@code artifactDir} and the artifacts beneath it. */
    public Set<String> idsOutside(Path artifactDir) {
        String a = artifact(artifactDir);
        Set<String> owned = new java.util.HashSet<>();
        Set<String> outside = new TreeSet<>();
        for (Occurrence o : occurrences.values()) {
            boolean inside = o.artifact().equals(a) || o.artifact().startsWith(a + "/");
            if (inside && o.relation() == Relation.FIRST) {
                owned.add(o.warningId());
            }
        }
        for (Warning w : warnings.values()) {
            if (!owned.contains(w.id())) {
                outside.add(w.id());
            }
        }
        return outside;
    }

    /**
     * The round's shared allocator for an artifact: it never returns an ID registered elsewhere in the run, or in
     * {@code alsoTaken}, and continues after the highest such ID of the round.
     */
    public WarningIds.Allocator allocator(int round, Path artifactDir, Set<String> alsoTaken) {
        Set<String> taken = new LinkedHashSet<>(idsOutside(artifactDir));
        taken.addAll(alsoTaken);
        int last = 0;
        for (String id : taken) {
            last = Math.max(last, WarningIds.sequence(id, round).orElse(0));
        }
        return new WarningIds.Allocator(round, taken, last);
    }

    // ------------------------------------------------------------------------------------------ registration

    /** Registers one occurrence; idempotent, and refuses anything that would break the invariant. */
    public void register(Occurrence o, int round, String kind) throws Conflict {
        Occurrence existing = occurrences.get(o.key());
        if (existing != null) {
            if (!existing.warningId().equals(o.warningId())) {
                throw new Conflict("occurrence " + o.key() + " is already " + existing.warningId() + "; it cannot also "
                        + "be " + o.warningId());
            }
            return;
        }
        Warning w = warnings.get(o.warningId());
        if (o.relation() == Relation.FIRST) {
            if (w != null) {
                throw new Conflict("public ID " + o.warningId() + " already identifies " + w.site().describe()
                        + " (first seen in " + w.first() + "); " + o.key() + " (" + o.site().describe() + ", state "
                        + o.state() + ") is a different occurrence and needs its own ID");
            }
            if (round == 0 ? !o.warningId().matches("W\\d{4,}") : WarningIds.sequence(o.warningId(), round).isEmpty()) {
                throw new Conflict(o.warningId() + " is not a round-" + round + " warning ID (" + o.key() + ")");
            }
            warnings.put(o.warningId(), new Warning(o.warningId(), round, o.key(), kind, o.site()));
            WarningIds.sequence(o.warningId(), round).ifPresent(s -> counters.merge(round, s, Math::max));
        } else if (w == null) {
            throw new Conflict(o.key() + " is recorded as persisting warning " + o.warningId() + ", which no earlier "
                    + "occurrence in the run introduced");
        }
        occurrences.put(o.key(), o);
    }

    /** Records a verified correspondence between two distinct warnings (metadata only). */
    public void correspond(String warningId, String correspondsTo, String evidence) throws Conflict {
        if (!warnings.containsKey(warningId) || !warnings.containsKey(correspondsTo)) {
            throw new Conflict("a correspondence names an unregistered warning (" + warningId + ", " + correspondsTo
                    + ")");
        }
        correspondences.put(warningId + "->" + correspondsTo, new Correspondence(warningId, correspondsTo, evidence));
    }

    /** The run's original warnings ({@code warning-graph.json}), state {@code S0}. */
    public void registerBaseline() throws IOException {
        Path graph = runDir.resolve(WarningGraphJson.FILE_NAME);
        if (!Files.isRegularFile(graph)) {
            return;
        }
        WarningGraph g = WarningGraphJson.load(graph);
        for (WarningNode n : g.warningNodes()) {
            if (!n.root()) {
                continue;
            }
            register(new Occurrence(".#" + n.id(), n.id(), ".", Evaluator.BASELINE_STATE, n.id(), Relation.FIRST,
                    site(n), "the run's baseline analysis"), 0, "baseline");
        }
    }

    /**
     * An evaluation directory: its baseline findings (persisting: they carry the evaluated state's IDs) and, for
     * every compared attempt, its introduced warnings ({@code FIRST}, with the IDs the attempt recorded) and its
     * persisting ones. {@code renames} replaces recorded IDs (migration only).
     */
    public void registerEvaluation(Path evaluationDir, Map<String, String> renames) throws IOException {
        String a = artifact(evaluationDir);
        Path baseline = evaluationDir.resolve("baseline").resolve("result.json");
        if (!Files.isRegularFile(baseline)) {
            return;
        }
        Map<?, ?> b = parse(baseline);
        String baseState = String.valueOf(b.get("state"));
        String matcher = "unknown";
        for (Object o : list(b.get("findings"))) {
            Map<?, ?> f = (Map<?, ?>) o;
            String id = String.valueOf(f.get("id"));
            Occurrence occ = new Occurrence(a + "/baseline#" + id, id, a + "/baseline", baseState, id,
                    warnings.containsKey(id) ? Relation.PERSISTING : Relation.FIRST, site(f),
                    "the evaluation's baseline, matched to the evaluated state's warnings");
            register(occ, inferredRound(id), "inherited");
        }
        Path attempts = evaluationDir.resolve("attempts");
        if (!Files.isDirectory(attempts)) {
            return;
        }
        List<Path> dirs;
        try (Stream<Path> s = Files.list(attempts)) {
            dirs = s.filter(p -> Files.isRegularFile(p.resolve("attempt.json"))
                    && Files.isRegularFile(p.resolve("comparison.json"))).sorted().toList();
        }
        for (Path dir : dirs) {
            Map<?, ?> attempt = parse(dir.resolve("attempt.json"));
            if (!AttemptOutcome.valueOf(String.valueOf(attempt.get("outcome"))).compared()) {
                continue;
            }
            Map<?, ?> c = parse(dir.resolve("comparison.json"));
            String state = String.valueOf(attempt.get("resultState"));
            String art = a + "/attempts/" + dir.getFileName();
            Map<String, Map<?, ?>> results = new LinkedHashMap<>();
            for (Object o : list(c.get("resultFindings"))) {
                results.put(String.valueOf(((Map<?, ?>) o).get("id")), (Map<?, ?>) o);
            }
            matcher = String.valueOf(c.get("matcher") instanceof Map<?, ?> m ? m.get("id") : c.get("matcher"));
            for (Object o : list(c.get("introducedWarnings"))) {
                Map<?, ?> iw = (Map<?, ?>) o;
                String recorded = String.valueOf(iw.get("warningId"));
                String id = renames.getOrDefault(recorded, recorded);
                String rid = String.valueOf(iw.get("resultId"));
                register(new Occurrence(art + "#" + rid, id, art, state, rid, Relation.FIRST,
                        site(results.getOrDefault(rid, Map.of())), "introduced in the package's own evaluation"),
                        ((Number) iw.get("round")).intValue(), "evaluation");
            }
            for (Object o : list(c.get("persisting"))) {
                Map<?, ?> p = (Map<?, ?>) o;
                String id = String.valueOf(p.get("baseline"));
                String rid = String.valueOf(p.get("result"));
                register(new Occurrence(art + "#" + rid, id, art, state, rid, Relation.PERSISTING,
                        site(results.getOrDefault(rid, Map.of())), "paired with baseline warning " + id + " by the "
                                + "matcher (" + matcher + ")"), inferredRound(id), "evaluation");
            }
        }
    }

    /**
     * A completed combined round result: its persisting findings (the matcher paired them with the base state's
     * warning of the same ID), its introduced and its ambiguous ones (each a new warning with its own ID).
     */
    public void registerRound(Path roundDir) throws IOException {
        Path record = roundDir.resolve(RoundApplication.RECORD);
        Path findings = roundDir.resolve(RoundApplication.FINDINGS);
        if (!Files.isRegularFile(record) || !Files.isRegularFile(findings)) {
            return;
        }
        Map<?, ?> r = parse(record);
        if (!"COMPLETED".equals(r.get("status"))) {
            return;
        }
        String a = artifact(roundDir);
        String state = String.valueOf(r.get("roundResultId"));
        int round = ((Number) r.get("round")).intValue();
        Path baseline = roundDir.resolve("baseline").resolve("result.json");
        if (Files.isRegularFile(baseline)) {
            // the round's own baseline of its base state (round 1): its IDs are the base state's
            Map<?, ?> b = parse(baseline);
            for (Object o : list(b.get("findings"))) {
                Map<?, ?> f = (Map<?, ?>) o;
                String id = String.valueOf(f.get("id"));
                register(new Occurrence(a + "/baseline#" + id, id, a + "/baseline", String.valueOf(r.get("baseState")),
                        id, warnings.containsKey(id) ? Relation.PERSISTING : Relation.FIRST, site(f),
                        "the round's baseline of its base state"), inferredRound(id), "baseline");
            }
        }
        String matcher = String.valueOf(r.get("matcher") instanceof Map<?, ?> m ? m.get("id") : r.get("matcher"));
        for (Object o : list(parse(findings).get("findings"))) {
            Map<?, ?> f = (Map<?, ?>) o;
            String id = String.valueOf(f.get("warningId"));
            String rid = String.valueOf(f.get("resultId"));
            String status = String.valueOf(f.get("status"));
            boolean persisting = status.equals("PERSISTING");
            register(new Occurrence(a + "#" + rid, id, a, state, rid, persisting ? Relation.PERSISTING
                    : Relation.FIRST, site(f), persisting ? "the combined comparison (" + matcher + ") paired it with "
                            + "base warning " + f.get("baselineWarningId") + " of " + r.get("baseState")
                            : status.equals("AMBIGUOUS") ? "an ambiguous match in the combined comparison; kept as a "
                                    + "separate warning" : "introduced in the combined state"),
                    persisting ? inferredRound(id) : round, "combined");
        }
    }

    // ------------------------------------------------------------------------------------------ persistence

    /** Loads {@value #FILE}; empty if absent. */
    public static WarningRegistry load(Path runDir) throws IOException {
        WarningRegistry reg = new WarningRegistry(runDir);
        Path file = runDir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return reg;
        }
        Map<?, ?> m = parse(file);
        if (!MODEL.equals(m.get("model"))) {
            throw new IOException(file + " has model " + m.get("model") + ", not " + MODEL);
        }
        for (Object o : list(m.get("warnings"))) {
            Map<?, ?> w = (Map<?, ?>) o;
            reg.warnings.put(String.valueOf(w.get("id")), new Warning(String.valueOf(w.get("id")),
                    ((Number) w.get("round")).intValue(), String.valueOf(w.get("first")), String.valueOf(w.get("kind")),
                    site((Map<?, ?>) w.get("site"), true)));
        }
        for (Object o : list(m.get("occurrences"))) {
            Map<?, ?> x = (Map<?, ?>) o;
            reg.occurrences.put(String.valueOf(x.get("key")), new Occurrence(String.valueOf(x.get("key")),
                    String.valueOf(x.get("warningId")), String.valueOf(x.get("artifact")), String.valueOf(x.get("state")),
                    String.valueOf(x.get("localId")), Relation.valueOf(String.valueOf(x.get("relation"))),
                    site((Map<?, ?>) x.get("site"), true), String.valueOf(x.get("evidence"))));
        }
        if (m.get("counters") instanceof Map<?, ?> c) {
            c.forEach((k, v) -> reg.counters.put(Integer.parseInt(String.valueOf(k)), ((Number) v).intValue()));
        }
        for (Object o : list(m.get("correspondences"))) {
            Map<?, ?> x = (Map<?, ?>) o;
            Correspondence cr = new Correspondence(String.valueOf(x.get("warningId")),
                    String.valueOf(x.get("correspondsTo")), String.valueOf(x.get("evidence")));
            reg.correspondences.put(cr.warningId() + "->" + cr.correspondsTo(), cr);
        }
        return reg;
    }

    /** Writes {@value #FILE} atomically. */
    public void save() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", 1);
        m.put("model", MODEL);
        m.put("idScheme", WarningIds.SCHEME);
        m.put("note", "The run's authority for public warning IDs: one ID names one warning in every report, "
                + "comparison and graph of this run. Occurrences are verified sightings (FIRST allocated the ID; "
                + "PERSISTING was paired with it by the semantic matcher). Correspondences link distinct warnings "
                + "without merging them.");
        List<Object> ws = new ArrayList<>();
        for (Warning w : warnings.values()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", w.id());
            x.put("round", w.round());
            x.put("kind", w.kind());
            x.put("first", w.first());
            x.put("site", siteJson(w.site()));
            ws.add(x);
        }
        m.put("warnings", ws);
        List<Object> os = new ArrayList<>();
        for (Occurrence o : occurrences.values()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("key", o.key());
            x.put("warningId", o.warningId());
            x.put("artifact", o.artifact());
            x.put("state", o.state());
            x.put("localId", o.localId());
            x.put("relation", o.relation().name());
            x.put("site", siteJson(o.site()));
            x.put("evidence", o.evidence());
            os.add(x);
        }
        m.put("occurrences", os);
        Map<String, Object> c = new LinkedHashMap<>();
        counters.forEach((k, v) -> c.put(String.valueOf(k), v));
        m.put("counters", c);
        List<Object> cs = new ArrayList<>();
        for (Correspondence cr : correspondences.values()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("warningId", cr.warningId());
            x.put("correspondsTo", cr.correspondsTo());
            x.put("evidence", cr.evidence());
            cs.add(x);
        }
        m.put("correspondences", cs);
        EvaluationFiles.writeAtomically(runDir.resolve(FILE), Json.write(m));
    }

    /** A change to the registry under its lock. */
    @FunctionalInterface
    public interface Change {
        void apply(WarningRegistry registry) throws IOException;
    }

    /**
     * Loads the registry under its lock (rebuilding a missing one from the saved artifacts), applies {@code change}
     * and saves it. Nothing is saved if anything fails.
     */
    @SuppressWarnings("try")
    public static WarningRegistry update(Path runDir, Change change) throws IOException {
        try (EvaluationFiles.Lock lock = EvaluationFiles.lock(runDir.resolve(LOCK_DIR), "pid "
                + ProcessHandle.current().pid())) {
            WarningRegistry reg = Files.isRegularFile(runDir.resolve(FILE)) ? load(runDir) : rebuild(runDir);
            change.apply(reg);
            reg.save();
            return reg;
        } catch (EvaluationFiles.BusyException e) {
            throw new IOException(e.getMessage());
        }
    }

    /** The registry as it is, or rebuilt from the artifacts if it does not exist yet (nothing is written). */
    public static WarningRegistry current(Path runDir) throws IOException {
        return Files.isRegularFile(runDir.resolve(FILE)) ? load(runDir) : rebuild(runDir);
    }

    /**
     * Rebuilds the registry from the saved artifacts, deterministically: the run's baseline, the combined round
     * results (those named by {@code pipeline.json} first, by round), then the evaluations (named ones first).
     * A legacy run whose artifacts give one ID to different warnings is refused with the migration command.
     */
    public static WarningRegistry rebuild(Path runDir) throws IOException {
        WarningRegistry reg = new WarningRegistry(runDir);
        try {
            reg.registerBaseline();
            for (Path round : roundsInOrder(runDir)) {
                reg.registerRound(round);
            }
            for (Path evaluation : evaluationsInOrder(runDir)) {
                reg.registerEvaluation(evaluation, Map.of());
            }
            for (Path stage : propagationDirs(runDir)) {
                Path record = stage.resolve("propagation.json");
                Map<?, ?> history = parse(record).get("history") instanceof Map<?, ?> h ? h : Map.of();
                for (Object o : list(history.get("correspondences"))) {
                    Map<?, ?> c = (Map<?, ?>) o;
                    reg.correspond(String.valueOf(c.get("reportWarning")), String.valueOf(c.get("correspondsTo")),
                            String.valueOf(c.get("evidence")));
                }
            }
        } catch (Conflict e) {
            throw new Conflict("the saved artifacts of " + runDir + " use one warning ID for different warnings ("
                    + e.getMessage() + "). This run was written before the warning registry existed; migrate it with "
                    + "`GeneralAnnotator warning-ids migrate " + runDir + "` (derived artifacts are backed up; raw "
                    + "checker output and snapshots are kept).");
        }
        return reg;
    }

    /** Propagation stage directories named by pipeline.json, in round order. */
    public static List<Path> namedPropagationDirs(Path runDir) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String d : pipelineArtifacts(runDir, "directory")) {
            Path p = runDir.resolve(d).normalize();
            if (Files.isRegularFile(p.resolve("propagation.json")) && !out.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Every propagation stage directory: those named by pipeline.json in round order, then the others by round and name. */
    public static List<Path> propagationDirs(Path runDir) throws IOException {
        List<Path> out = new ArrayList<>(namedPropagationDirs(runDir));
        Path area = runDir.resolve("propagation");
        if (Files.isDirectory(area)) {
            List<Path> others;
            try (Stream<Path> s = Files.list(area)) {
                others = new ArrayList<>(s.filter(p -> Files.isRegularFile(p.resolve("propagation.json"))
                        && !out.contains(p)).toList());
            }
            others.sort(java.util.Comparator.comparingInt((Path p) -> {
                try {
                    return ((Number) parse(p.resolve("propagation.json")).get("round")).intValue();
                } catch (IOException | RuntimeException e) {
                    return Integer.MAX_VALUE;
                }
            }).thenComparing(Path::toString));
            out.addAll(others);
        }
        return out;
    }

    /** Combined round results: those named in pipeline.json in round order, then the others by round and name. */
    static List<Path> roundsInOrder(Path runDir) throws IOException {
        List<Path> named = new ArrayList<>();
        for (String d : pipelineArtifacts(runDir, "combined")) {
            Path p = runDir.resolve(d).normalize();
            if (Files.isDirectory(p) && !named.contains(p)) {
                named.add(p);
            }
        }
        List<Path> all = new ArrayList<>(named);
        Path rounds = runDir.resolve(RoundApplication.DIR);
        if (Files.isDirectory(rounds)) {
            List<Path> others;
            try (Stream<Path> s = Files.list(rounds)) {
                others = s.filter(p -> Files.isRegularFile(p.resolve(RoundApplication.RECORD))
                        && !p.getFileName().toString().startsWith(".") && !named.contains(p)).toList();
            }
            List<Path> sorted = new ArrayList<>(others);
            sorted.sort(java.util.Comparator.comparingInt((Path p) -> roundOf(p)).thenComparing(Path::toString));
            all.addAll(sorted);
        }
        return all;
    }

    /** Evaluation directories: those named in pipeline.json in stage order, then the others by round and name. */
    static List<Path> evaluationsInOrder(Path runDir) throws IOException {
        List<Path> named = new ArrayList<>();
        for (String d : pipelineArtifacts(runDir, "evaluations")) {
            Path p = runDir.resolve(d).normalize();
            if (Files.isDirectory(p) && !named.contains(p)) {
                named.add(p);
            }
        }
        List<Path> all = new ArrayList<>(named);
        Path evaluations = runDir.resolve(Evaluator.DIR);
        if (Files.isDirectory(evaluations)) {
            List<Path> others;
            try (Stream<Path> s = Files.list(evaluations)) {
                others = s.filter(p -> Files.isRegularFile(p.resolve("baseline").resolve("result.json"))
                        && !named.contains(p)).toList();
            }
            List<Path> sorted = new ArrayList<>(others);
            sorted.sort(java.util.Comparator.comparingInt((Path p) -> evaluationRound(p)).thenComparing(Path::toString));
            all.addAll(sorted);
        }
        return all;
    }

    /**
     * Artifact directories named by pipeline.json, in stage order: {@code combined} gives the round-1 combined
     * result then every propagation round's; {@code evaluations} the round-1 evaluation then every propagation
     * round's.
     */
    static List<String> pipelineArtifacts(Path runDir, String kind) throws IOException {
        Path file = runDir.resolve("pipeline.json");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        Map<?, ?> stages = parse(file).get("stages") instanceof Map<?, ?> m ? m : Map.of();
        List<String> out = new ArrayList<>();
        Map<?, ?> first = stages.get(kind.equals("combined") ? "combined" : "evaluation") instanceof Map<?, ?> m ? m
                : Map.of();
        if (first.get("artifacts") instanceof Map<?, ?> a && a.get("directory") instanceof String d) {
            out.add(d);
        }
        List<String> propagation = new ArrayList<>();
        stages.keySet().forEach(k -> {
            String s = String.valueOf(k);
            if (s.equals("propagation") || s.matches("propagation-r\\d+")) {
                propagation.add(s);
            }
        });
        propagation.sort(java.util.Comparator.comparingInt(s -> s.equals("propagation") ? 2
                : Integer.parseInt(s.substring("propagation-r".length()))));
        for (String s : propagation) {
            if (stages.get(s) instanceof Map<?, ?> st && st.get("artifacts") instanceof Map<?, ?> a
                    && a.get(kind) instanceof String d) {
                out.add(d);
            }
        }
        return out;
    }

    private static int roundOf(Path roundDir) {
        try {
            return ((Number) parse(roundDir.resolve(RoundApplication.RECORD)).get("round")).intValue();
        } catch (IOException | RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static int evaluationRound(Path dir) {
        try {
            Path summary = dir.resolve("summary.json");
            if (Files.isRegularFile(summary)) {
                return ((Number) parse(summary).get("round")).intValue();
            }
        } catch (IOException | RuntimeException e) {
            // fall through
        }
        return Integer.MAX_VALUE;
    }

    /** The round of an ID whose round is not recorded with it: its registered round, else read from its form. */
    private int inferredRound(String id) {
        Warning w = warnings.get(id);
        if (w != null) {
            return w.round();
        }
        if (id.matches("W0\\d{3}")) {
            return 0;
        }
        for (int r = 1; r < 1000; r++) {
            if (WarningIds.sequence(id, r).isPresent()) {
                return r;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------------------------------ helpers

    static Site site(WarningNode n) {
        return new Site(n.targetPath().orElse(n.diagnostic().sourcePath()), n.diagnostic().line(),
                n.diagnostic().column().orElse(0), firstLine(n.diagnostic().message()), null, null);
    }

    /** The site of a saved finding ({@code path}, {@code anchor}, {@code diagnostic}). */
    static Site site(Map<?, ?> f) {
        Map<?, ?> d = f.get("diagnostic") instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> anchor = f.get("anchor") instanceof Map<?, ?> m ? m : Map.of();
        Object path = f.get("path") != null ? f.get("path") : f.get("targetPath");
        return new Site(path == null ? String.valueOf(d.get("sourcePath")) : String.valueOf(path),
                d.get("line") instanceof Number n ? n.intValue() : 0, d.get("column") instanceof Number n
                        ? n.intValue() : 0, firstLine(String.valueOf(d.get("message"))),
                anchor.get("declaration") == null ? null : String.valueOf(anchor.get("declaration")),
                anchor.get("problem") == null ? null : String.valueOf(anchor.get("problem")));
    }

    private static Site site(Map<?, ?> s, @SuppressWarnings("unused") boolean saved) {
        return new Site(String.valueOf(s.get("path")), ((Number) s.get("line")).intValue(),
                ((Number) s.get("column")).intValue(), String.valueOf(s.get("message")),
                s.get("declaration") == null ? null : String.valueOf(s.get("declaration")),
                s.get("problem") == null ? null : String.valueOf(s.get("problem")));
    }

    private static Map<String, Object> siteJson(Site s) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("path", s.path());
        x.put("line", s.line());
        x.put("column", s.column());
        x.put("message", s.message());
        x.put("declaration", s.declaration());
        x.put("problem", s.problem());
        return x;
    }

    static String firstLine(String text) {
        return text == null ? "" : text.lines().findFirst().orElse("").strip();
    }

    public static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    public static Map<?, ?> parse(Path file) throws IOException {
        try {
            return (Map<?, ?>) JsonParser.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException(file + " is not valid JSON: " + e.getMessage());
        }
    }

    /** The registered sequence of {@code round}, if any. */
    public OptionalInt counter(int round) {
        Integer c = counters.get(round);
        return c == null ? OptionalInt.empty() : OptionalInt.of(c);
    }
}
