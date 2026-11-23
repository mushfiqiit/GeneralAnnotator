package generalannotator.evaluation;

import generalannotator.evaluation.matching.ObservedFinding;
import generalannotator.evaluation.matching.WarningMatcher;
import generalannotator.graph.AnnotationNode;
import generalannotator.graph.GraphEdge;
import generalannotator.graph.GraphNode;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.graph.WarningNode;
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
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds an extended (multi-round) graph: an earlier evaluation graph, kept whole, plus a later stage's
 * evaluation layer whose packages were evaluated on a combined round result. A pure function of saved
 * artifacts (earlier evaluation, combined round result, later evaluation), so rebuilding gives identical output.
 *
 * <p>The result has the two node kinds and two edge kinds of every evaluation graph and nothing else: warning
 * nodes, annotation package nodes, {@code W -> A} ({@value GraphEdge#RESOLVED_BY}) and {@code A -> W} ({@value
 * GraphEdge#INTRODUCES}). Every edge comes from one successful, complete, independent package evaluation against
 * its recorded source state: the earlier graph's edges from the earlier evaluation, the new edges from the later
 * evaluation. The combined round result adds no edge and no node of its own; it is the authoritative list of the
 * input state's warnings, saved separately, and is used here only to identify those warnings.
 *
 * <h2>Identity</h2>
 * Public warning IDs come from the run's {@link WarningRegistry}: one ID names one warning in every report and
 * graph. Every input warning (a combined-result finding) therefore appears under its report ID:
 * <ul>
 *   <li><b>Persisting:</b> the combined result's comparison paired it with the base warning of the same ID; it is
 *       that node (the matcher re-verifies the pairing against the earlier evaluation's baseline, and the
 *       relation records whether it did).
 *   <li><b>Introduced in the combined state:</b> a distinct occurrence in another state than any package's own
 *       evaluation, so it is its own node with its own ID and no incoming edge. If exactly one applied package
 *       introduced an equivalent warning in its own completed evaluation on the same base state (verified by
 *       package key and the matcher, with nothing ambiguous), the correspondence is recorded as metadata (and in
 *       the registry); it never becomes an edge or a shared ID. Otherwise nothing is recorded.
 *   <li>An earlier node with the same ID but another warning is refused: the run predates the registry and must be
 *       migrated ({@code warning-ids migrate}).
 * </ul>
 * The layer's nodes and edges are then added unchanged; its introduced warnings keep their registry IDs. Every
 * new edge carries its provenance (the evaluation, source and result states, the matcher, the report warning) and
 * its claim: the outcome of that package's own independent evaluation only, not a statement about any later
 * combined state. {@code reportToGraph} is the identity map, kept for readers of earlier records.
 */
public final class HistoryGraph {

    /** The graph model, recorded with the graph; a different model means the saved graph is stale. */
    public static final String MODEL = "warning-annotation-history-3";

    public record Built(WarningGraph graph, Map<String, Object> record) {}

    private record Candidate(String packageId, String warningId, String state) {}

    private HistoryGraph() {}

    /**
     * @param earlierGraph the earlier evaluation graph ({@code evaluation-graph.json} in its evaluation directory)
     * @param roundResult the combined round result directory the input state is (required with an earlier graph)
     * @param runDir the run directory, for relative references
     * @param layer the later evaluation's graph (input warnings in state {@code inputState})
     * @param layerEvaluation the later evaluation's directory
     * @param inputState the input state's ID
     * @param inputRound the input state's round
     */
    public static Built build(Optional<Path> earlierGraph, Optional<Path> roundResult, Path runDir, WarningGraph layer,
            Path layerEvaluation, String inputState, int inputRound, WarningMatcher matcher) throws IOException {
        return build(earlierGraph, earlierGraph.map(Path::getParent), roundResult, runDir, layer, layerEvaluation,
                inputState, inputRound, matcher);
    }

    /**
     * Like the other overload, for an earlier graph that is itself an extended graph (round 3 and later).
     *
     * @param earlierEvaluation the evaluation whose packages the combined round result applied (its {@code
     *     baseline/} and {@code attempts/} are the evidence); by default the earlier graph's own directory
     */
    public static Built build(Optional<Path> earlierGraph, Optional<Path> earlierEvaluation,
            Optional<Path> roundResult, Path runDir, WarningGraph layer, Path layerEvaluation, String inputState,
            int inputRound, WarningMatcher matcher) throws IOException {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        Map<String, GraphEdge> edges = new LinkedHashMap<>();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("model", MODEL);
        List<WarningNode> inputs = layer.warningNodes().stream()
                .filter(w -> w.state().equals(Optional.of(inputState))).toList();
        Set<String> inputIds = new LinkedHashSet<>();
        inputs.forEach(w -> inputIds.add(w.id()));
        Map<String, String> toGraph = new LinkedHashMap<>();
        Map<String, Map<String, Object>> identities = new LinkedHashMap<>();
        Map<String, Integer> counts = new TreeMap<>();
        List<String> notes = new ArrayList<>();
        List<Object> correspondences = new ArrayList<>();
        Map<String, Map<?, ?>> attribution = new LinkedHashMap<>();
        String attributionRef = null;
        String attributionMatcher = null;
        int attributed = 0;
        String roundRef = null;

        if (earlierGraph.isEmpty()) {
            for (WarningNode w : inputs) {
                nodes.put(w.id(), w);
                toGraph.put(w.id(), w.id());
                identities.put(w.id(), identity(w.id(), w.id(), "input", Map.of("note", "no earlier graph")));
                counts.merge("input", 1, Integer::sum);
            }
        } else {
            if (roundResult.isEmpty()) {
                throw new IOException("an earlier graph is extended only from a combined round result");
            }
            Path history = earlierGraph.get();
            WarningGraph earlier = WarningGraphJson.load(history);
            earlier.nodes().forEach(n -> nodes.put(n.id(), n));
            earlier.edges().forEach(e -> edges.putIfAbsent(e.key(), e));
            record.put("earlierGraph", rel(runDir, history));
            Path evaluationDir = earlierEvaluation.orElse(history.getParent());
            record.put("earlierEvaluation", rel(runDir, evaluationDir));
            Path roundDir = roundResult.get();
            roundRef = rel(runDir, roundDir);
            Map<?, ?> round = parse(roundDir.resolve(RoundApplication.RECORD));
            // verified contributions in the combined context, only from a saved attribution valid for this round
            Optional<Path> attributionDir = CombinedAttribution.find(runDir, roundDir, evaluationDir);
            if (attributionDir.isPresent()) {
                Map<?, ?> a = parse(attributionDir.get().resolve(CombinedAttribution.RECORD));
                attributionRef = rel(runDir, attributionDir.get().resolve(CombinedAttribution.RECORD));
                attributionMatcher = String.valueOf(a.get("matcher"));
                for (Object o : list(a.get("warnings"))) {
                    attribution.put(String.valueOf(((Map<?, ?>) o).get("warningId")), (Map<?, ?>) o);
                }
            }
            Map<?, ?> findings = parse(roundDir.resolve(RoundApplication.FINDINGS));
            String baseState = String.valueOf(round.get("baseState"));
            Map<String, Map<?, ?>> byId = new LinkedHashMap<>();
            for (Object o : list(findings.get("findings"))) {
                Map<?, ?> f = (Map<?, ?>) o;
                byId.put(String.valueOf(f.get("warningId")), f);
            }
            if (!byId.keySet().equals(inputIds)) {
                throw new IOException("the warnings of " + roundDir + " (" + byId.keySet() + ") are not the input "
                        + "warnings of the evaluation " + layerEvaluation + " (" + inputIds + ")");
            }
            Map<String, ObservedFinding> base = new LinkedHashMap<>();
            for (Object o : list(parse(evaluationDir.resolve("baseline").resolve("result.json")).get("findings"))) {
                ObservedFinding f = FindingJson.finding((Map<?, ?>) o);
                base.put(f.id(), f);
            }
            List<String> packages = new ArrayList<>();
            Map<String, String> packageKeys = new LinkedHashMap<>();
            for (Object o : list(round.get("packages"))) {
                Map<?, ?> p = (Map<?, ?>) o;
                packages.add(String.valueOf(p.get("id")));
                packageKeys.put(String.valueOf(p.get("id")), String.valueOf(p.get("packageKey")));
            }

            // Introduced warnings of each applied package's own evaluation, verified against the earlier graph.
            List<ObservedFinding> introducedHere = new ArrayList<>();
            byId.forEach((id, f) -> {
                if ("INTRODUCED".equals(f.get("status"))) {
                    introducedHere.add(observed(id, f));
                }
            });
            Map<String, List<Candidate>> candidates = new LinkedHashMap<>();
            Map<String, List<String>> ambiguous = new LinkedHashMap<>();
            for (String p : packages) {
                if (!(nodes.get(p) instanceof AnnotationNode)) {
                    notes.add("package " + p + " of " + roundRef + " has no annotation node in the earlier graph; "
                            + "not used for linking");
                    continue;
                }
                Path attempt = evaluationDir.resolve("attempts").resolve(p);
                if (!Files.isRegularFile(attempt.resolve("attempt.json"))
                        || !Files.isRegularFile(attempt.resolve("comparison.json"))) {
                    notes.add(p + ": no saved attempt and comparison in " + rel(runDir, evaluationDir)
                            + "; not used for linking");
                    continue;
                }
                Map<?, ?> a = parse(attempt.resolve("attempt.json"));
                if (!AttemptOutcome.valueOf(String.valueOf(a.get("outcome"))).compared()
                        || !packageKeys.get(p).equals(a.get("packageKey"))
                        || !baseState.equals(a.get("baselineState"))) {
                    notes.add(p + ": its own evaluation is " + a.get("outcome") + " for package key "
                            + a.get("packageKey") + " on state " + a.get("baselineState") + " (the round applied key "
                            + packageKeys.get(p) + " to " + baseState + "); not used for linking");
                    continue;
                }
                String resultState = String.valueOf(a.get("resultState"));
                Map<?, ?> c = parse(attempt.resolve("comparison.json"));
                Map<String, Map<?, ?>> results = new LinkedHashMap<>();
                for (Object o : list(c.get("resultFindings"))) {
                    results.put(String.valueOf(((Map<?, ?>) o).get("id")), (Map<?, ?>) o);
                }
                List<ObservedFinding> own = new ArrayList<>();
                for (Object o : list(c.get("introducedWarnings"))) {
                    Map<?, ?> iw = (Map<?, ?>) o;
                    String wid = String.valueOf(iw.get("warningId"));
                    Map<?, ?> r = results.get(String.valueOf(iw.get("resultId")));
                    if (r == null || !(nodes.get(wid) instanceof WarningNode n) || n.root()
                            || !n.state().equals(Optional.of(resultState))
                            || !edges.containsKey(new GraphEdge(p, wid, GraphEdge.INTRODUCES).key())) {
                        notes.add(p + ": introduced warning " + wid + " is not a node of state " + resultState
                                + " introduced by " + p + " in the earlier graph; not used for linking");
                        continue;
                    }
                    ObservedFinding f = FindingJson.finding(r);
                    own.add(new ObservedFinding(wid, f.diagnostic(), f.path(), f.anchor()));
                }
                if (own.isEmpty()) {
                    continue;
                }
                WarningMatcher.Comparison cmp = matcher.compare(own, introducedHere);
                for (WarningMatcher.Pair pair : cmp.persisting()) {
                    candidates.computeIfAbsent(pair.resultId(), k -> new ArrayList<>())
                            .add(new Candidate(p, pair.baselineId(), resultState));
                }
                for (WarningMatcher.Ambiguity amb : cmp.ambiguous()) {
                    amb.resultIds().forEach(r -> ambiguous.computeIfAbsent(r, k -> new ArrayList<>())
                            .add(p + " " + amb.baselineIds() + ": " + amb.reason()));
                }
            }

            for (WarningNode w : inputs) {
                Map<?, ?> f = byId.get(w.id());
                Map<String, String> prov = new LinkedHashMap<>();
                prov.put("reportWarning", w.id());
                prov.put("inputState", inputState);
                prov.put("roundResult", roundRef);
                prov.put("matcher", matcher.id());
                if ("PERSISTING".equals(f.get("status"))) {
                    String b = String.valueOf(f.get("baselineWarningId"));
                    if (!b.equals(w.id())) {
                        throw new IOException("the combined result " + roundRef + " records " + w.id() + " as persisting "
                                + "from " + b + "; a persisting warning keeps its public ID, so these artifacts predate "
                                + "the warning registry (migrate the run with `warning-ids migrate`)");
                    }
                    boolean verified = nodes.get(b) instanceof WarningNode && base.containsKey(b)
                            && matcher.compare(List.of(base.get(b)), List.of(observed(w.id(), f))).persisting()
                                    .size() == 1;
                    prov.put("baseState", baseState);
                    if (nodes.get(b) instanceof WarningNode) {
                        prov.put("evidence", verified ? "the combined result's comparison and the matcher pair it with "
                                + "base warning " + b + " (" + rel(runDir, evaluationDir.resolve("baseline")) + ")"
                                : "the combined result's comparison pairs it with base warning " + b);
                        place(w, b, verified ? "persisting-from-base" : "persisting-per-combined-comparison", prov,
                                toGraph, identities, counts);
                    } else {
                        prov.put("note", "persisting from " + b + ", which the earlier graph does not show");
                        own(w, "persisting-not-in-earlier-graph", prov, nodes, toGraph, identities, counts);
                    }
                    continue;
                }
                if (nodes.containsKey(w.id())) {
                    throw new IOException("the earlier graph already uses " + w.id() + " for another warning ("
                            + describe(nodes.get(w.id())) + ") than " + roundRef + "'s " + w.id() + " ("
                            + w.diagnostic().message().lines().findFirst().orElse("") + " at line "
                            + w.diagnostic().line() + "); one public ID must name one warning, so migrate the run with "
                            + "`warning-ids migrate`");
                }
                List<Candidate> cs = candidates.getOrDefault(w.id(), List.of());
                if (cs.size() == 1 && !ambiguous.containsKey(w.id())) {
                    Candidate c = cs.get(0);
                    prov.put("correspondsTo", c.warningId());
                    prov.put("package", c.packageId());
                    prov.put("packageState", c.state());
                    prov.put("packageEvaluation", rel(runDir, evaluationDir));
                    prov.put("evidence", "the matcher pairs it with " + c.warningId() + ", which only package "
                            + c.packageId() + " (applied in " + inputState + ") introduced in its own completed "
                            + "evaluation on " + baseState + " (state " + c.state() + "); a distinct occurrence in "
                            + "another state, so it keeps its own ID and no edge is drawn");
                    Map<String, Object> corr = new LinkedHashMap<>();
                    corr.put("reportWarning", w.id());
                    corr.put("correspondsTo", c.warningId());
                    corr.put("package", c.packageId());
                    corr.put("evidence", prov.get("evidence"));
                    correspondences.add(corr);
                    own(w, "corresponds-to-package-warning", prov, nodes, toGraph, identities, counts);
                } else {
                    boolean none = cs.isEmpty() && !ambiguous.containsKey(w.id());
                    if (!cs.isEmpty()) {
                        prov.put("candidates", String.join(", ", cs.stream()
                                .map(c -> c.warningId() + " (" + c.packageId() + ", " + c.state() + ")").toList()));
                    }
                    if (ambiguous.containsKey(w.id())) {
                        prov.put("ambiguity", String.join("; ", ambiguous.get(w.id())));
                    }
                    prov.put("note", none ? "no applied package introduced an equivalent warning in its own evaluation; "
                            + "its origin in the combination is not established by any individual evaluation"
                            : "equivalent warnings of several packages, or an undecided group; no correspondence is "
                                    + "recorded");
                    own(w, none ? "introduced-in-combined-state" : "ambiguous-correspondence", prov, nodes, toGraph,
                            identities, counts);
                }
                Map<?, ?> att = attribution.get(w.id());
                if (att == null) {
                    identities.get(w.id()).put("attribution", attributionRef == null ? "no saved attribution for "
                            + roundRef + " (run `attribute`); no incoming edge is claimed" : "not a target of "
                            + attributionRef);
                    continue;
                }
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("status", att.get("status"));
                summary.put("reason", att.get("reason"));
                summary.put("contributors", att.get("contributors"));
                summary.put("record", attributionRef);
                identities.get(w.id()).put("attribution", summary);
                for (Object o : list(att.get("candidates"))) {
                    Map<?, ?> v = (Map<?, ?>) o;
                    String pkg = String.valueOf(v.get("package"));
                    if (!"VERIFIED".equals(v.get("status"))) {
                        continue;
                    }
                    if (!(nodes.get(pkg) instanceof AnnotationNode an) || !an.baselineState().equals(baseState)) {
                        notes.add(w.id() + ": verified contributor " + pkg + " is not an annotation package of state "
                                + baseState + " in the earlier graph; no edge");
                        continue;
                    }
                    Map<String, String> ep = new LinkedHashMap<>();
                    ep.put("basis", "combined-context contribution (leave one out)");
                    ep.put("claim", pkg + " contributes to " + w.id() + " in " + inputState + ": reported with every "
                            + "included package and absent from the complete result without " + pkg + "; not proof that "
                            + pkg + " alone causes it");
                    ep.put("combinedState", inputState);
                    ep.put("sourceState", baseState);
                    ep.put("counterfactualState", String.valueOf(v.get("counterfactualState")));
                    ep.put("counterfactual", String.valueOf(v.get("counterfactual")));
                    ep.put("packageKey", String.valueOf(v.get("packageKey")));
                    ep.put("matcher", attributionMatcher);
                    ep.put("attribution", attributionRef);
                    ep.put("reportWarning", w.id());
                    ep.put("evidence", String.valueOf(v.get("evidence")));
                    putEdge(edges, new GraphEdge(pkg, w.id(), GraphEdge.INTRODUCES, ep));
                    attributed++;
                }
            }
        }

        // The layer: its packages and introduced warnings, input IDs translated.
        String evaluationRef = rel(runDir, layerEvaluation);
        Map<String, String> resultStates = new LinkedHashMap<>();
        for (GraphNode n : layer.nodes()) {
            if (n instanceof WarningNode w && inputIds.contains(w.id())) {
                continue;
            }
            GraphNode added = n;
            if (n instanceof AnnotationNode a) {
                a.resultState().ifPresent(s -> resultStates.put(a.id(), s));
                List<AnnotationNode.Proposal> proposals = a.proposals().stream()
                        .map(p -> new AnnotationNode.Proposal(p.warningId(), p.group(), p.answerEditIndices(),
                                p.baselineWarningId().map(b -> toGraph.getOrDefault(b, b))))
                        .toList();
                added = new AnnotationNode(a.id(), a.canonical(), a.edits(), a.baselineState(), a.resultState(),
                        a.outcome(), a.detail(), proposals, a.counts(), a.round());
            }
            if (nodes.containsKey(n.id())) {
                throw new IOException("node ID " + n.id() + " of " + layerEvaluation + " is already used by the earlier "
                        + "history; IDs are never renamed, so the graphs cannot be combined");
            }
            nodes.put(n.id(), added);
        }
        for (GraphEdge e : layer.edges()) {
            String annotation = e.kind().equals(GraphEdge.RESOLVED_BY) ? e.target() : e.source();
            Map<String, String> prov = new LinkedHashMap<>();
            prov.put("evaluation", evaluationRef);
            prov.put("claim", "the outcome of this package's own independent evaluation on " + inputState + " only; "
                    + "later rounds may still report the warning");
            prov.put("sourceState", inputState);
            prov.put("resultState", resultStates.getOrDefault(annotation, ""));
            prov.put("matcher", matcher.id());
            if (inputIds.contains(e.source())) {
                prov.put("reportWarning", e.source());
            }
            putEdge(edges, new GraphEdge(toGraph.getOrDefault(e.source(), e.source()),
                    toGraph.getOrDefault(e.target(), e.target()), e.kind(), prov));
        }
        WarningGraph graph = new WarningGraph(layer.runId(), layer.checkerId(), List.copyOf(nodes.values()),
                List.copyOf(edges.values()));
        record.put("inputState", inputState);
        record.put("roundResult", roundRef);
        record.put("counts", counts);
        Map<String, String> reportToGraph = new LinkedHashMap<>();
        identities.forEach((k, v) -> reportToGraph.put(k, String.valueOf(v.get("graphNode"))));
        record.put("reportToGraph", reportToGraph);
        record.put("correspondences", correspondences);
        record.put("attribution", attributionRef);
        record.put("attributedEdges", attributed);
        record.put("inputWarnings", List.copyOf(identities.values()));
        record.put("notes", notes);
        record.put("nodes", graph.nodes().size());
        record.put("edges", graph.edges().size());
        record.put("note", "Earlier nodes and edges are kept unchanged. Every edge comes from an independent package "
                + "evaluation and records only that evaluation's outcome; the combined result adds none. Every input "
                + "warning appears under its report ID (reportToGraph is the identity): a persisting one on the node of "
                + "that ID, an introduced one as its own node, with any verified correspondence to a package's "
                + "introduced warning recorded as metadata, never as an edge or a shared ID.");
        return new Built(graph, record);
    }

    private static void place(WarningNode w, String graphId, String relation, Map<String, String> prov,
            Map<String, String> toGraph, Map<String, Map<String, Object>> identities, Map<String, Integer> counts)
            throws IOException {
        if (toGraph.containsValue(graphId)) {
            throw new IOException("two combined-result warnings would be linked to the same graph node " + graphId);
        }
        toGraph.put(w.id(), graphId);
        identities.put(w.id(), identity(w.id(), graphId, relation, prov));
        counts.merge(relation, 1, Integer::sum);
    }

    /** An input warning that is its own node, under its report ID. */
    private static void own(WarningNode w, String relation, Map<String, String> prov, Map<String, GraphNode> nodes,
            Map<String, String> toGraph, Map<String, Map<String, Object>> identities, Map<String, Integer> counts) {
        nodes.put(w.id(), w);
        toGraph.put(w.id(), w.id());
        identities.put(w.id(), identity(w.id(), w.id(), relation, prov));
        counts.merge(relation, 1, Integer::sum);
    }

    private static String describe(GraphNode n) {
        return n instanceof WarningNode w ? w.diagnostic().message().lines().findFirst().orElse("") + " at line "
                + w.diagnostic().line() + w.state().map(s -> " in state " + s).orElse("") : "annotation package";
    }

    private static Map<String, Object> identity(String input, String graph, String relation, Map<String, ?> prov) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reportWarning", input);
        m.put("graphNode", graph);
        m.put("relation", relation);
        m.put("provenance", prov);
        return m;
    }

    /** Keeps the first edge with a given source, target and kind (earlier edges win). */
    private static void putEdge(Map<String, GraphEdge> edges, GraphEdge e) {
        edges.putIfAbsent(e.key(), e);
    }

    private static ObservedFinding observed(String id, Map<?, ?> f) {
        Map<?, ?> a = (Map<?, ?>) f.get("anchor");
        Object problem = a.get("problem");
        return new ObservedFinding(id, FindingJson.diagnostic((Map<?, ?>) f.get("diagnostic")),
                String.valueOf(f.get("path")), new ObservedFinding.Anchor(String.valueOf(a.get("declaration")),
                ((Number) a.get("line")).intValue(), problem == null ? "" : String.valueOf(problem)));
    }

    private static String rel(Path runDir, Path p) {
        Path a = runDir.toAbsolutePath().normalize();
        Path b = p.toAbsolutePath().normalize();
        return b.startsWith(a) ? a.relativize(b).toString() : b.toString();
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static Map<?, ?> parse(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException(file + " is missing");
        }
        try {
            return (Map<?, ?>) JsonParser.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException(file + " is not valid JSON: " + e.getMessage());
        }
    }
}
