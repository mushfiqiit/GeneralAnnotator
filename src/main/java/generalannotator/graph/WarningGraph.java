package generalannotator.graph;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Warning graph of one analysis run: nodes and directed edges.
 *
 * <p>Invariants checked on construction: node IDs are unique, and every edge refers to existing
 * nodes. Visualization is out of scope; this is a plain data model.
 *
 * @param runId the run that produced the graph (the run directory name)
 * @param checkerId the checker whose findings the warning nodes are
 * @param nodes nodes in ID order
 * @param edges edges (none in an initial graph)
 */
public record WarningGraph(String runId, String checkerId, List<GraphNode> nodes, List<GraphEdge> edges) {

    public WarningGraph {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(checkerId, "checkerId");
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        Set<String> ids = new HashSet<>();
        for (GraphNode node : nodes) {
            if (!ids.add(node.id())) {
                throw new IllegalArgumentException("duplicate node id " + node.id());
            }
        }
        for (GraphEdge edge : edges) {
            if (!ids.contains(edge.source()) || !ids.contains(edge.target())) {
                throw new IllegalArgumentException("edge " + edge + " refers to a missing node");
            }
        }
    }

    /** The warning nodes, in node order. */
    public List<WarningNode> warningNodes() {
        return nodes.stream()
                .filter(WarningNode.class::isInstance)
                .map(WarningNode.class::cast)
                .toList();
    }

    public Optional<GraphNode> node(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst();
    }
}
