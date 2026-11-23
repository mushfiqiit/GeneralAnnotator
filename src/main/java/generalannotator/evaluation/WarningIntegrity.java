package generalannotator.evaluation;

import generalannotator.graph.AnnotationNode;
import generalannotator.graph.GraphEdge;
import generalannotator.graph.GraphNode;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.graph.WarningIds;
import generalannotator.graph.WarningNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Checks that a run's public warning IDs are consistent everywhere ({@code warning-ids check}):
 * <ul>
 *   <li><b>Registry:</b> one {@code FIRST} occurrence per ID, every occurrence's ID registered, ID forms matching
 *       their rounds, counters covering every allocated sequence, correspondences between registered IDs.
 *   <li><b>Artifacts:</b> a deterministic rebuild from the saved artifacts succeeds (no ID names two warnings) and
 *       equals the saved registry (no occurrence missing or reassigned).
 *   <li><b>Reports:</b> each completed round's {@code findings.json}, {@code warnings.txt} and warning graph list the
 *       same IDs.
 *   <li><b>Graphs</b> (every {@code evaluation-graph.json} under evaluations/ and propagation/): every warning node
 *       is the registry's warning of that ID in that state at that site; every edge joins existing nodes in a causal
 *       direction ({@code W -> A} resolved-by, {@code A -> W} introduces), without duplicates; package proposals
 *       name registered warnings; propagation records map every report ID to itself.
 *   <li><b>Pipeline:</b> every artifact path in {@code pipeline.json} exists.
 * </ul>
 */
public final class WarningIntegrity {

    /** The outcome: problems (the run is inconsistent), notes (informational) and counts. */
    public record Report(List<String> problems, List<String> notes, Map<String, Integer> counts) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private WarningIntegrity() {}

    public static Report check(Path runDir) throws IOException {
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Path dir = runDir.toAbsolutePath().normalize();
        WarningRegistry saved = null;
        if (!Files.isRegularFile(dir.resolve(WarningRegistry.FILE))) {
            problems.add("no " + WarningRegistry.FILE + ": the run has no warning registry (run `warning-ids migrate "
                    + dir + "`)");
        } else {
            saved = WarningRegistry.load(dir);
            registry(saved, problems);
            counts.put("warnings", saved.warnings().size());
            counts.put("occurrences", saved.occurrences().size());
            counts.put("correspondences", saved.correspondences().size());
        }
        WarningRegistry rebuilt = null;
        try {
            rebuilt = WarningRegistry.rebuild(dir);
        } catch (WarningRegistry.Conflict e) {
            problems.add("the saved artifacts give one public ID to different warnings: " + e.getMessage());
        }
        if (saved != null && rebuilt != null) {
            for (WarningRegistry.Occurrence o : rebuilt.occurrences().values()) {
                WarningRegistry.Occurrence s = saved.occurrences().get(o.key());
                if (s == null) {
                    problems.add("occurrence " + o.key() + " (" + o.warningId() + ") of the saved artifacts is not in "
                            + "the registry");
                } else if (!s.warningId().equals(o.warningId())) {
                    problems.add("occurrence " + o.key() + " is " + o.warningId() + " in its artifact but "
                            + s.warningId() + " in the registry");
                }
            }
            for (String key : saved.occurrences().keySet()) {
                if (!rebuilt.occurrences().containsKey(key)) {
                    problems.add("the registry records occurrence " + key + ", which no saved artifact contains");
                }
            }
        }
        WarningRegistry reference = saved != null ? saved : rebuilt;
        rounds(dir, reference, problems, counts);
        graphs(dir, reference, problems, notes, counts);
        pipeline(dir, problems);
        return new Report(List.copyOf(problems), List.copyOf(notes), counts);
    }

    private static void registry(WarningRegistry r, List<String> problems) {
        Map<String, Integer> firsts = new LinkedHashMap<>();
        for (WarningRegistry.Occurrence o : r.occurrences().values()) {
            if (r.warning(o.warningId()).isEmpty()) {
                problems.add("registry occurrence " + o.key() + " names unregistered " + o.warningId());
            }
            if (o.relation() == WarningRegistry.Relation.FIRST) {
                firsts.merge(o.warningId(), 1, Integer::sum);
            }
        }
        Map<Integer, Integer> max = new LinkedHashMap<>();
        for (WarningRegistry.Warning w : r.warnings().values()) {
            Integer n = firsts.get(w.id());
            if (n == null || n != 1) {
                problems.add("registry warning " + w.id() + " has " + (n == null ? 0 : n) + " FIRST occurrences, not 1");
            }
            WarningRegistry.Occurrence first = r.occurrences().get(w.first());
            if (first == null || !first.warningId().equals(w.id()) || first.relation() != WarningRegistry.Relation.FIRST) {
                problems.add("registry warning " + w.id() + " names first occurrence " + w.first() + ", which is not its "
                        + "FIRST occurrence");
            }
            if (w.round() == 0 ? !w.id().matches("W\\d{4,}") : WarningIds.sequence(w.id(), w.round()).isEmpty()) {
                problems.add("registry warning " + w.id() + " is not a round-" + w.round() + " ID");
            }
            WarningIds.sequence(w.id(), w.round()).ifPresent(s -> max.merge(w.round(), s, Math::max));
        }
        max.forEach((round, seq) -> {
            int c = r.counter(round).orElse(0);
            if (c < seq || c > WarningIds.MAX_PER_ROUND) {
                problems.add("registry counter of round " + round + " is " + c + ", but sequence " + seq
                        + " is allocated");
            }
        });
        for (WarningRegistry.Correspondence c : r.correspondences()) {
            if (r.warning(c.warningId()).isEmpty() || r.warning(c.correspondsTo()).isEmpty()) {
                problems.add("correspondence " + c.warningId() + " -> " + c.correspondsTo() + " names an unregistered "
                        + "warning");
            } else if (c.warningId().equals(c.correspondsTo())) {
                problems.add("correspondence " + c.warningId() + " links a warning to itself");
            }
        }
    }

    private static void rounds(Path dir, WarningRegistry r, List<String> problems, Map<String, Integer> counts)
            throws IOException {
        int n = 0;
        for (Path round : WarningRegistry.roundsInOrder(dir)) {
            Map<?, ?> record = WarningRegistry.parse(round.resolve(RoundApplication.RECORD));
            if (!"COMPLETED".equals(record.get("status"))) {
                continue;
            }
            n++;
            String rel = dir.relativize(round).toString();
            List<String> ids = new ArrayList<>();
            for (Object o : WarningRegistry.list(WarningRegistry.parse(round.resolve(RoundApplication.FINDINGS))
                    .get("findings"))) {
                Map<?, ?> f = (Map<?, ?>) o;
                String id = String.valueOf(f.get("warningId"));
                ids.add(id);
                WarningRegistry.Occurrence occ = r == null ? null
                        : r.occurrences().get(rel + "#" + f.get("resultId"));
                if (r != null && (occ == null || !occ.warningId().equals(id))) {
                    problems.add(rel + "/findings.json lists " + id + " (" + f.get("resultId") + "), which the registry "
                            + (occ == null ? "does not record" : "records as " + occ.warningId()));
                }
            }
            Path text = round.resolve("warnings.txt");
            if (Files.isRegularFile(text)) {
                List<String> listed = Files.readAllLines(text, StandardCharsets.UTF_8).stream()
                        .filter(l -> l.matches("W\\d+\\s.*")).map(l -> l.split("\\s+")[0]).toList();
                if (!listed.equals(ids)) {
                    problems.add(rel + "/warnings.txt lists " + listed + ", but findings.json " + ids);
                }
            }
            Path graph = round.resolve(WarningGraphJson.FILE_NAME);
            if (Files.isRegularFile(graph)) {
                Set<String> nodes = new HashSet<>();
                WarningGraphJson.load(graph).warningNodes().forEach(w -> nodes.add(w.id()));
                if (!nodes.equals(new HashSet<>(ids))) {
                    problems.add(rel + "/" + WarningGraphJson.FILE_NAME + " has warnings " + nodes + ", but findings.json "
                            + ids);
                }
            }
        }
        counts.put("rounds", n);
    }

    private static void graphs(Path dir, WarningRegistry r, List<String> problems, List<String> notes,
            Map<String, Integer> counts) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String area : List.of(Evaluator.DIR, "propagation")) {
            Path a = dir.resolve(area);
            if (!Files.isDirectory(a)) {
                continue;
            }
            try (Stream<Path> s = Files.list(a)) {
                s.map(p -> p.resolve(Evaluator.GRAPH)).filter(Files::isRegularFile).sorted().forEach(files::add);
            }
        }
        int nodes = 0;
        int edges = 0;
        for (Path file : files) {
            String rel = dir.relativize(file).toString();
            WarningGraph g;
            try {
                g = WarningGraphJson.load(file);
            } catch (RuntimeException | IOException e) {
                problems.add(rel + " cannot be read: " + e.getMessage());
                continue;
            }
            Map<String, GraphNode> byId = new LinkedHashMap<>();
            for (GraphNode n : g.nodes()) {
                if (byId.put(n.id(), n) != null) {
                    problems.add(rel + " has two nodes " + n.id());
                }
            }
            for (WarningNode w : g.warningNodes()) {
                nodes++;
                if (r == null) {
                    continue;
                }
                String state = w.state().orElse(Evaluator.BASELINE_STATE);
                if (r.warning(w.id()).isEmpty()) {
                    problems.add(rel + ": warning node " + w.id() + " is not registered");
                    continue;
                }
                boolean same = r.occurrencesOf(w.id()).stream().anyMatch(o -> o.state().equals(state)
                        && o.site().line() == w.diagnostic().line()
                        && o.site().message().equals(WarningRegistry.firstLine(w.diagnostic().message())));
                if (!same) {
                    WarningRegistry.Warning reg = r.warning(w.id()).get();
                    problems.add(rel + ": node " + w.id() + " (state " + state + ", line " + w.diagnostic().line() + ": "
                            + WarningRegistry.firstLine(w.diagnostic().message()) + ") is not the registry's " + w.id()
                            + " (" + reg.site().describe() + ", first in " + reg.first() + ")");
                }
            }
            Set<String> keys = new HashSet<>();
            for (GraphEdge e : g.edges()) {
                edges++;
                GraphNode s = byId.get(e.source());
                GraphNode t = byId.get(e.target());
                if (s == null || t == null) {
                    problems.add(rel + ": dangling edge " + e.source() + " -" + e.kind() + "-> " + e.target());
                    continue;
                }
                boolean causal = (e.kind().equals(GraphEdge.RESOLVED_BY) && s instanceof WarningNode
                        && t instanceof AnnotationNode) || (e.kind().equals(GraphEdge.INTRODUCES)
                                && s instanceof AnnotationNode && t instanceof WarningNode);
                if (!causal) {
                    problems.add(rel + ": edge " + e.source() + " -" + e.kind() + "-> " + e.target()
                            + " is not a causal edge");
                }
                if (!keys.add(e.key())) {
                    problems.add(rel + ": duplicate edge " + e.key());
                }
                String attribution = e.provenance().get("attribution");
                if (attribution != null) {
                    attributionEdge(dir, rel, e, attribution, problems);
                }
            }
            for (GraphNode n : g.nodes()) {
                if (n instanceof AnnotationNode a && r != null) {
                    for (AnnotationNode.Proposal p : a.proposals()) {
                        if (p.baselineWarningId().isPresent() && r.warning(p.baselineWarningId().get()).isEmpty()) {
                            problems.add(rel + ": package " + a.id() + " names unregistered warning "
                                    + p.baselineWarningId().get());
                        }
                    }
                }
            }
            Path record = file.resolveSibling("propagation.json");
            if (Files.isRegularFile(record)) {
                Map<?, ?> rec = WarningRegistry.parse(record);
                Map<?, ?> history = rec.get("history") instanceof Map<?, ?> h ? h : Map.of();
                if (!HistoryGraph.MODEL.equals(rec.get("graphModel"))) {
                    problems.add(dir.relativize(record) + " records graph model " + rec.get("graphModel") + ", not "
                            + HistoryGraph.MODEL + " (rebuild it)");
                }
                if (history.get("reportToGraph") instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> {
                        if (!k.equals(v)) {
                            problems.add(dir.relativize(record) + " maps report warning " + k + " to graph node " + v
                                    + "; a report ID must be its graph ID");
                        }
                    });
                }
            }
        }
        counts.put("graphs", files.size());
        counts.put("graphWarningNodes", nodes);
        counts.put("graphEdges", edges);
        if (files.isEmpty()) {
            notes.add("no evaluation graph to check");
        }
    }

    /** An attribution edge is backed by a current record that lists its package as a verified contributor. */
    private static void attributionEdge(Path dir, String rel, GraphEdge e, String attribution, List<String> problems)
            throws IOException {
        Path record = dir.resolve(attribution);
        String edge = rel + ": attribution edge " + e.source() + " -> " + e.target();
        if (!Files.isRegularFile(record)) {
            problems.add(edge + " names missing record " + attribution);
            return;
        }
        Map<?, ?> r = WarningRegistry.parse(record);
        Path round = dir.resolve(String.valueOf(r.get("roundResult")));
        Path evaluation = dir.resolve(String.valueOf(r.get("evaluation")));
        if (!CombinedAttribution.MODEL.equals(r.get("model")) || !Files.isDirectory(round)
                || !Files.isDirectory(evaluation)
                || !CombinedAttribution.identity(round, evaluation).equals(r.get("identity"))) {
            problems.add(edge + " rests on " + attribution + ", which no longer matches its round result and "
                    + "evaluation");
            return;
        }
        boolean verified = WarningRegistry.list(r.get("warnings")).stream().map(o -> (Map<?, ?>) o)
                .filter(w -> e.target().equals(w.get("warningId")))
                .flatMap(w -> WarningRegistry.list(w.get("candidates")).stream().map(o -> (Map<?, ?>) o))
                .anyMatch(c -> e.source().equals(c.get("package")) && "VERIFIED".equals(c.get("status")));
        if (!verified) {
            problems.add(edge + " is not a VERIFIED contribution in " + attribution);
        }
    }

    private static void pipeline(Path dir, List<String> problems) throws IOException {
        Path file = dir.resolve("pipeline.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        Map<?, ?> stages = WarningRegistry.parse(file).get("stages") instanceof Map<?, ?> m ? m : Map.of();
        stages.forEach((name, stage) -> {
            if (stage instanceof Map<?, ?> st && st.get("artifacts") instanceof Map<?, ?> a) {
                a.forEach((k, v) -> {
                    if (v instanceof String path && !path.isEmpty() && !Files.exists(dir.resolve(path))) {
                        problems.add("pipeline.json stage " + name + " names missing artifact " + k + " = " + path);
                    }
                });
            }
        });
    }
}
