package generalannotator.visualization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.graph.AnnotationNode;
import generalannotator.graph.GraphEdge;
import generalannotator.graph.GraphNode;
import generalannotator.graph.WarningGraph;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DotExporterTest {

    private static final Pattern NODE_LINE = Pattern.compile("(?m)^  \"(W\\d{4})\" \\[id=\"\\1\", label=\"([^\"]*)\"");

    @Test
    void everyNodeIncludingIsolatedOnesIsDrawnAndLabeledWithItsExistingId() {
        String dot = DotExporter.toDot(VisualizationFixtures.threeIsolated());

        Matcher m = NODE_LINE.matcher(dot);
        List<String> labels = m.results().map(r -> r.group(1) + "=" + r.group(2)).toList();
        assertEquals(List.of("W0001=W0001", "W0002=W0002", "W0003=W0003"), labels);
        assertTrue(dot.contains("sortv=1];") && dot.contains("sortv=3];"), "node order is preserved for packing");
    }

    @Test
    void noEdgesAreInventedAndDisconnectedNodesArePacked() {
        String dot = DotExporter.toDot(VisualizationFixtures.threeIsolated());
        assertEquals(0, Pattern.compile("(?m)^  \"[^\"]+\" -> ").matcher(dot).results().count(), dot);
        assertFalse(dot.contains("style=invis"), "no invisible layout edges or nodes");
        assertTrue(dot.contains("graph [layout=osage, fontname=\"Helvetica\", labelloc=t, fontsize=14, pack=true, "
                + "packmode=\"array_u\""), dot);
    }

    @Test
    void existingEdgesAreDrawnSolid() {
        WarningGraph g = VisualizationFixtures.threeIsolated();
        List<GraphNode> nodes = new java.util.ArrayList<>(g.nodes());
        nodes.add(new AnnotationNode("A0001", "package", List.of(new AnnotationNode.PackageEdit("ADD @x",
                java.util.Map.of(), java.util.Optional.empty(), java.util.Optional.empty())), "S0",
                java.util.Optional.empty(), "COMPLETED", "d", List.of(), java.util.Optional.empty(),
                java.util.OptionalInt.of(1)));
        WarningGraph withEdge = new WarningGraph(g.runId(), g.checkerId(), nodes,
                List.of(new GraphEdge("W0001", "A0001", GraphEdge.RESOLVED_BY, java.util.Map.of("evaluation", "e")),
                        new GraphEdge("A0001", "W0003", GraphEdge.INTRODUCES)));
        String dot = DotExporter.toDot(withEdge);
        assertEquals(2, Pattern.compile("(?m)^  \"[^\"]+\" -> ").matcher(dot).results().count());
        assertTrue(dot.contains("  \"W0001\" -> \"A0001\" [label=\"resolved-by\""), dot);
        assertTrue(dot.contains("  \"A0001\" -> \"W0003\" [label=\"introduces\""), dot);
        assertTrue(dot.contains("evaluation: e"), "provenance is in the edge tooltip: " + dot);
        assertFalse(dot.contains("style=dashed") || dot.contains("style=dotted") || dot.contains("dashed"), dot);
        assertFalse(dot.contains("layout=osage"), "graphs with edges use the hierarchical dot layout");
    }

    @Test
    void tooltipHasLocationSeverityCheckerAndFullMessage() {
        var node = VisualizationFixtures.threeIsolated().warningNodes().getFirst();
        assertEquals("W0001\nsrc/p/A.java:46:30\nerror [NullAway]\n@NonNull static field 'x' not initialized",
                DotExporter.tooltipText(node));
        String dot = DotExporter.toDot(VisualizationFixtures.threeIsolated());
        assertTrue(dot.contains("tooltip=\"W0001\\nsrc/p/A.java:46:30\\nerror [NullAway]\\n"
                + "@NonNull static field 'x' not initialized\""), dot);
        assertTrue(dot.contains("tooltip=\"W0002\\nsrc/p/B File.java:7\\nwarning [NullAway]"),
                "missing column is omitted");
    }

    @Test
    void escapingOfQuotesBackslashesEntitiesAndNewlines() {
        String raw = "a\"b\\N & <c>\r\nd\te\u0001";
        assertEquals("\"a\\\"b\\\\N &amp; <c>\\nd e\"", DotExporter.label(raw));
        assertEquals("\"a\\\"b\\\\\\\\N &amp; <c>\\nd e\"", DotExporter.tooltip(raw));
        assertEquals("\"W\\\"1\"", DotExporter.id("W\"1"));

        String dot = DotExporter.toDot(VisualizationFixtures.threeIsolated());
        assertTrue(dot.contains("field \\\"a\\\\\\\\b\\\" uses \\\\\\\\N and \\\\\\\\G &amp; &amp;lt; <T> 'x'"
                + "\\n(see http://t.uber.com/nullaway )"), dot);
        // Every line of the DOT file is complete: no raw newline leaked into a quoted string.
        for (String line : dot.split("\n")) {
            long quotes = line.replace("\\\\", "").replace("\\\"", "").chars().filter(c -> c == '"').count();
            assertEquals(0, quotes % 2, "unbalanced quotes in: " + line);
        }
    }

    @Test
    void emptyGraphShowsNoWarningsMessage() {
        String dot = DotExporter.toDot(VisualizationFixtures.empty());
        assertTrue(dot.contains("label=\"No warnings\\nrun 20261118T000203Z-nullaway (nullaway): "
                + "the completed analysis reported no findings\""), dot);
        assertEquals(0, NODE_LINE.matcher(dot).results().count(), "no nodes");
        assertFalse(dot.contains("->"));
    }

    @Test
    void exportIsDeterministic() {
        assertEquals(DotExporter.toDot(VisualizationFixtures.threeIsolated()),
                DotExporter.toDot(VisualizationFixtures.threeIsolated()));
    }
}
