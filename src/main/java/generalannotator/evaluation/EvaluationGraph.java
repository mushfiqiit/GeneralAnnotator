package generalannotator.evaluation;

import generalannotator.graph.AnnotationNode;
import generalannotator.graph.GraphEdge;
import generalannotator.graph.GraphNode;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Builds the expanded graph of an evaluation from its baseline and attempt records. It is a pure
 * function of those records, rebuilt after every invocation, so resuming never duplicates nodes or
 * edges.
 *
 * <ul>
 *   <li>Baseline warnings: root nodes of state {@code S0}, round 0, distance 0, kept whether or not
 *       anything resolves them.
 *   <li>One annotation node per unique package (one complete edit set, however many answers proposed
 *       it), holding every edit with its applier outcome, every originating answer, and the package's
 *       outcome ({@link AttemptOutcome}), so a node without edges always says why: unevaluated,
 *       interrupted, no change, no-op, rejected, failed, not comparable or ambiguous. Edits of a package
 *       never get nodes of their own.
 *   <li>Only a trustworthy comparison (COMPLETED or AMBIGUOUS) adds edges: {@code W -> A}
 *       ({@value GraphEdge#RESOLVED_BY}) for every baseline warning W resolved by the whole package, whether
 *       or not A was proposed for W; and for every introduced finding a new warning node of state {@code S0+A}, distance
 *       1 and the evaluation's round, plus {@code A -> N} ({@value GraphEdge#INTRODUCES}). Its ID was
 *       allocated from the round's sequence when the attempt was recorded ({@code W1001, W1002, ...} for
 *       round 1, see {@link generalannotator.graph.WarningIds}). Introduced findings of different attempts
 *       are always distinct nodes, even if they look identical.
 *   <li>The originating answers stay on the annotation node (with their edit associations); they are
 *       never edges. Edges are deduplicated, and a package's introduced warnings exist once, however many
 *       answers proposed it.
 * </ul>
 */
final class EvaluationGraph {

    private EvaluationGraph() {}

    static WarningGraph build(Candidates candidates, List<WarningNode> baseline, Map<String, Map<?, ?>> records,
            Set<String> interrupted, Map<String, String> originalToBaseline, String checkerId, int round) {
        return build(candidates, baseline, records, interrupted, originalToBaseline, checkerId, round,
                Evaluator.BASELINE_STATE, 1);
    }

    /**
     * @param baselineState the name of the state every package was applied to
     * @param introducedDistance the graph distance of introduced warnings (1 above an initial baseline)
     */
    static WarningGraph build(Candidates candidates, List<WarningNode> baseline, Map<String, Map<?, ?>> records,
            Set<String> interrupted, Map<String, String> originalToBaseline, String checkerId, int round,
            String baselineState, int introducedDistance) {
        List<GraphNode> nodes = new ArrayList<>(baseline);
        Set<GraphEdge> edges = new LinkedHashSet<>();
        for (Candidates.Candidate c : candidates.candidates()) {
            Map<?, ?> r = records.get(c.id());
            String outcome;
            String detail;
            Optional<String> resultState = Optional.empty();
            Optional<AnnotationNode.Counts> counts = Optional.empty();
            List<WarningNode> introduced = new ArrayList<>();
            if (r == null) {
                outcome = interrupted.contains(c.id()) ? AttemptOutcome.INTERRUPTED.name()
                        : AttemptOutcome.UNEVALUATED.name();
                detail = interrupted.contains(c.id()) ? "an earlier attempt was interrupted; rerun the evaluation"
                        : "not evaluated yet";
            } else {
                outcome = (String) r.get("outcome");
                detail = (String) r.get("detail");
                if (r.get("resultState") instanceof String s) {
                    resultState = Optional.of(s);
                }
                if (AttemptOutcome.valueOf(outcome).compared()) {
                    counts = Evaluator.counts(r.get("counts"));
                    for (Object w : (List<?>) r.get("resolved")) {
                        edges.add(new GraphEdge((String) w, c.id(), GraphEdge.RESOLVED_BY));
                    }
                    for (Map<?, ?> im : Evaluator.introduced(r)) {
                        String id = (String) im.get("warningId");
                        introduced.add(new WarningNode(id, FindingJson.diagnostic((Map<?, ?>) im.get("diagnostic")),
                                Optional.ofNullable((String) im.get("targetPath")), false, introducedDistance, resultState,
                                OptionalInt.of(Math.toIntExact((Long) im.get("round")))));
                        edges.add(new GraphEdge(c.id(), id, GraphEdge.INTRODUCES));
                    }
                }
            }
            List<AnnotationNode.PackageEdit> edits = new ArrayList<>();
            List<?> recorded = r != null && r.get("edits") instanceof List<?> l ? l : List.of();
            for (int i = 0; i < c.edits().size(); i++) {
                Map<?, ?> applier = i < recorded.size() && ((Map<?, ?>) recorded.get(i)).get("applier") instanceof Map<?, ?> a
                        ? a : Map.of();
                edits.add(new AnnotationNode.PackageEdit(c.edits().get(i).canonical(),
                        Candidates.editFields(c.edits().get(i)), Optional.ofNullable((String) applier.get("outcome")),
                        Optional.ofNullable((String) applier.get("detail"))));
            }
            List<AnnotationNode.Proposal> proposals = new ArrayList<>();
            for (Candidates.Origin o : c.origins()) {
                proposals.add(new AnnotationNode.Proposal(o.warningId(), o.group(),
                        o.edits().stream().map(Candidates.EditAssociation::answerEditIndices).toList(),
                        Optional.ofNullable(originalToBaseline.get(o.warningId()))));
            }
            nodes.add(new AnnotationNode(c.id(), c.canonical(), edits, baselineState, resultState, outcome,
                    detail, proposals, counts, OptionalInt.of(round)));
            nodes.addAll(introduced);
        }
        return new WarningGraph(candidates.runId(), checkerId, nodes, List.copyOf(edges));
    }
}
