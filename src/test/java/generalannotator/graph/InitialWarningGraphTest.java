package generalannotator.graph;

import static generalannotator.graph.GraphFixtures.ROOT;
import static generalannotator.graph.GraphFixtures.completed;
import static generalannotator.graph.GraphFixtures.findings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.diagnostics.Diagnostic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class InitialWarningGraphTest {

    private static WarningGraph build(List<Diagnostic> findings) {
        return InitialWarningGraph.build("run-1", "nullaway", completed(findings), ROOT);
    }

    @Test
    void oneRootNodeAtDistanceZeroPerFindingAndNoEdges() {
        WarningGraph graph = build(findings());

        assertEquals("run-1", graph.runId());
        assertEquals("nullaway", graph.checkerId());
        assertEquals(4, graph.nodes().size());
        assertEquals(4, graph.warningNodes().size());
        assertTrue(graph.edges().isEmpty());
        for (WarningNode node : graph.warningNodes()) {
            assertTrue(node.root());
            assertEquals(0, node.distance());
            assertEquals("warning", node.kind());
        }
        List<String> ids = graph.nodes().stream().map(GraphNode::id).toList();
        assertEquals(List.of("W0001", "W0002", "W0003", "W0004"), ids);
        assertEquals(ids.size(), new HashSet<>(ids).size(), "IDs are unique");
    }

    @Test
    void idsFollowPathLineColumnOrderAndKeepFullDiagnostic() {
        WarningGraph graph = build(findings());
        List<WarningNode> nodes = graph.warningNodes();

        assertEquals(Optional.of("src/p/EventBus.java"), nodes.get(0).targetPath());
        assertEquals(46, nodes.get(0).diagnostic().line());
        assertEquals(119, nodes.get(1).diagnostic().line());
        assertEquals(Optional.of("src/p/Queue.java"), nodes.get(2).targetPath());
        assertEquals(20, nodes.get(2).diagnostic().line());
        assertEquals(43, nodes.get(3).diagnostic().line());
        // The node holds the complete diagnostic, so its ID maps to file, location, checker, severity, message.
        assertEquals(findings().get(2), nodes.get(2).diagnostic());
        assertEquals(nodes.get(2), graph.node("W0003").orElseThrow());
    }

    @Test
    void idAssignmentDoesNotDependOnOutputOrder() {
        WarningGraph expected = build(findings());
        Random random = new Random(42);
        for (int i = 0; i < 10; i++) {
            List<Diagnostic> shuffled = new ArrayList<>(findings());
            Collections.shuffle(shuffled, random);
            assertEquals(expected, build(shuffled));
        }
    }

    @Test
    void identicalDuplicateFindingsGetDistinctIds() {
        Diagnostic d = findings().getFirst();
        WarningGraph graph = build(List.of(d, d));
        assertEquals(List.of("W0001", "W0002"), graph.nodes().stream().map(GraphNode::id).toList());
    }

    @Test
    void successfulRunWithoutFindingsGivesEmptyGraph() {
        WarningGraph graph = build(List.of());
        assertTrue(graph.nodes().isEmpty());
        assertTrue(graph.edges().isEmpty());
    }

    @Test
    void idFormatGrowsBeyondFourDigits() {
        assertEquals("W0001", InitialWarningGraph.warningId(1));
        assertEquals("W9999", InitialWarningGraph.warningId(9999));
        assertEquals("W10000", InitialWarningGraph.warningId(10000));
    }

    @Test
    void graphInvariantsAreEnforced() {
        WarningNode a = build(findings()).warningNodes().get(0);
        assertThrows(IllegalArgumentException.class,
                () -> new WarningGraph("r", "nullaway", List.of(a, a), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new WarningGraph("r", "nullaway", List.of(a), List.of(new GraphEdge("W0001", "W9", "k"))));
        assertThrows(IllegalArgumentException.class,
                () -> new WarningNode("W1", a.diagnostic(), Optional.empty(), true, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new WarningNode("W1", a.diagnostic(), Optional.empty(), false, -1));
    }
}
