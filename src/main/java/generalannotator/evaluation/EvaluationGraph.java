package generalannotator.evaluation;

import generalannotator.graph.AnnotationNode;
import generalannotator.graph.GraphEdge;
import generalannotator.graph.GraphNode;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the expanded graph of an evaluation from its baseline and attempt records. It is a pure
 * function of those records, rebuilt after every invocation, so resuming never duplicates nodes or
 * edges.
 *
 * <ul>
 *   <li>Baseline warnings: root nodes of state {@code S0}, distance 0, kept whether or not anything
 *       resolves them.
 *   <li>One annotation node per candidate edit, with its outcome ({@link AttemptOutcome}), so a node
 *       without edges always says why: unevaluated, interrupted, no change, no-op, rejected, failed, not
 *       comparable or ambiguous.
 *   <li>Only a trustworthy comparison (COMPLETED or AMBIGUOUS) adds edges: {@code W -> A}
 *       ({@value GraphEdge#RESOLVED_BY}) for every resolved baseline warning W, whether or not A was
 *       proposed for W; and for every introduced finding a new warning node {@code A-N<k>} of state
 *       {@code S0+A} with distance 1, plus {@code A -> N} ({@value GraphEdge#INTRODUCES}). Introduced
 *       findings of different attempts are always distinct nodes, even if they look identical.
 *   <li>Proposal associations stay on the annotation node; they are never edges.
 * </ul>
 */
final class EvaluationGraph {

    private EvaluationGraph() {}

    static WarningGraph build(Candidates candidates, List<WarningNode> baseline, Map<String, Map<?, ?>> records,
            Set<String> interrupted, Map<String, String> originalToBaseline, String checkerId) {
        List<GraphNode> nodes = new ArrayList<>(baseline);
        List<GraphEdge> edges = new ArrayList<>();
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
                    for (Object o : (List<?>) r.get("introduced")) {
                        Map<?, ?> im = (Map<?, ?>) o;
                        String id = (String) im.get("nodeId");
                        introduced.add(new WarningNode(id, FindingJson.diagnostic((Map<?, ?>) im.get("diagnostic")),
                                Optional.ofNullable((String) im.get("targetPath")), false, 1, resultState));
                        edges.add(new GraphEdge(c.id(), id, GraphEdge.INTRODUCES));
                    }
                }
            }
            List<AnnotationNode.Proposal> proposals = c.associations().stream()
                    .map(a -> new AnnotationNode.Proposal(a.warningId(), a.group(), a.answerEditIndex(),
                            Optional.ofNullable(originalToBaseline.get(a.warningId()))))
                    .toList();
            nodes.add(new AnnotationNode(c.id(), c.edit().canonical(), Candidates.editFields(c.edit()),
                    Evaluator.BASELINE_STATE, resultState, outcome, detail, proposals, counts));
            nodes.addAll(introduced);
        }
        return new WarningGraph(candidates.runId(), checkerId, nodes, edges);
    }
}
