package generalannotator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import generalannotator.cli.ExitCode;
import generalannotator.cli.VisualizeCommand;
import generalannotator.diagnostics.Severity;
import generalannotator.exec.CommandResult;
import generalannotator.exec.CommandSpec;
import generalannotator.exec.ProcessCommandRunner;
import generalannotator.graph.GraphNode;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.graph.WarningNode;
import generalannotator.visualization.DotExporter;
import generalannotator.visualization.GraphvizRenderer;
import generalannotator.visualization.VisualizationFixtures;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Renders warning graphs with the real Graphviz {@code dot} (GA_GRAPHVIZ_DOT or dot on PATH). */
class GraphvizIntegrationTest {

    private static final String XLINK = "http://www.w3.org/1999/xlink";
    private static final String SVG = "http://www.w3.org/2000/svg";

    @TempDir Path temp;

    private String dot;

    @BeforeEach
    void requireGraphviz() {
        dot = System.getenv().getOrDefault(GraphvizRenderer.DOT_ENV, GraphvizRenderer.DEFAULT_DOT);
        CommandResult version = new ProcessCommandRunner().run(
                new CommandSpec(List.of(dot, "-V"), temp, Map.of(), Duration.ofSeconds(30)));
        if (!version.succeeded()) {
            fail("Graphviz integration tests need `" + dot + "` (set GA_GRAPHVIZ_DOT or put dot on PATH): "
                    + version.describeStatus());
        }
    }

    private Path render(WarningGraph graph, String name) throws Exception {
        Path dir = Files.createDirectories(temp.resolve(name + " dir"));
        Path dotFile = dir.resolve("warning-graph.dot");
        Files.writeString(dotFile, DotExporter.toDot(graph));
        Path svgFile = dir.resolve("warning-graph.svg");
        GraphvizRenderer.Result result =
                new GraphvizRenderer(new ProcessCommandRunner(), dot, Duration.ofMinutes(1)).render(dotFile, svgFile);
        assertTrue(result.rendered(), () -> result.problem().orElse(""));
        return svgFile;
    }

    private static Document parse(Path svg) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory.newDocumentBuilder().parse(svg.toFile());
    }

    /** Node group id -> tooltip (xlink:title of the node's link). */
    private static Map<String, String> tooltips(Document svg) {
        Map<String, String> result = new HashMap<>();
        NodeList groups = svg.getElementsByTagNameNS(SVG, "g");
        for (int i = 0; i < groups.getLength(); i++) {
            Element g = (Element) groups.item(i);
            if (g.getAttribute("class").equals("node")) {
                Element link = (Element) g.getElementsByTagNameNS(SVG, "a").item(0);
                result.put(g.getAttribute("id"), link == null ? null : link.getAttributeNS(XLINK, "title"));
            }
        }
        return result;
    }

    private static List<String> texts(Document svg) {
        List<String> result = new ArrayList<>();
        NodeList nodes = svg.getElementsByTagNameNS(SVG, "text");
        for (int i = 0; i < nodes.getLength(); i++) {
            result.add(nodes.item(i).getTextContent());
        }
        return result;
    }

    @Test
    void labelsAreIdsAndTooltipsDecodeToTheExactLocationAndMessage() throws Exception {
        WarningGraph graph = VisualizationFixtures.threeIsolated();
        Document svg = parse(render(graph, "three"));

        Map<String, String> tooltips = tooltips(svg);
        assertEquals(3, tooltips.size(), "every isolated node is drawn");
        for (WarningNode node : graph.warningNodes()) {
            assertEquals(DotExporter.tooltipText(node), tooltips.get(node.id()), "tooltip of " + node.id());
        }
        assertTrue(tooltips.get("W0002").contains("\\N and \\G & &lt; <T>"), "backslashes, entities, markup intact");
        List<String> texts = texts(svg);
        assertTrue(texts.containsAll(List.of("W0001", "W0002", "W0003")), texts::toString);
        assertEquals(0, countEdgePaths(svg), "no edges are drawn");
    }

    private static int countEdgePaths(Document svg) {
        int count = 0;
        NodeList groups = svg.getElementsByTagNameNS(SVG, "g");
        for (int i = 0; i < groups.getLength(); i++) {
            Element g = (Element) groups.item(i);
            if (g.getAttribute("class").equals("edge")) {
                count++;
            }
        }
        return count;
    }

    @Test
    void emptyGraphShowsNoWarnings() throws Exception {
        Document svg = parse(render(VisualizationFixtures.empty(), "empty"));
        assertTrue(texts(svg).contains("No warnings"), texts(svg)::toString);
        assertTrue(tooltips(svg).isEmpty());
    }

    @Test
    void manyIsolatedNodesArePackedCompactly() throws Exception {
        List<GraphNode> nodes = new ArrayList<>();
        for (int i = 1; i <= 42; i++) {
            nodes.add(VisualizationFixtures.node(String.format("W%04d", i), "src/F" + i + ".java", i, 1,
                    Severity.ERROR, "message " + i));
        }
        Document svg = parse(render(new WarningGraph("r", "nullaway", nodes, List.of()), "many"));
        Element root = svg.getDocumentElement();
        double width = Double.parseDouble(root.getAttribute("width").replace("pt", ""));
        double height = Double.parseDouble(root.getAttribute("height").replace("pt", ""));
        assertEquals(42, tooltips(svg).size());
        // A single row of 42 boxes would be about 3000pt wide; packing yields a near-square grid.
        assertTrue(width < 1000 && width < 3 * height, "width=" + width + " height=" + height);
        List<String> expected = nodes.stream().map(GraphNode::id).toList();
        assertEquals(expected, readingOrder(svg), "grid is in ID order (left to right, top to bottom)");
    }

    /** Node label texts sorted top-to-bottom, then left-to-right. */
    private static List<String> readingOrder(Document svg) {
        record Placed(double y, double x, String text) {}
        List<Placed> placed = new ArrayList<>();
        NodeList groups = svg.getElementsByTagNameNS(SVG, "g");
        for (int i = 0; i < groups.getLength(); i++) {
            Element g = (Element) groups.item(i);
            if (g.getAttribute("class").equals("node")) {
                Element text = (Element) g.getElementsByTagNameNS(SVG, "text").item(0);
                placed.add(new Placed(Math.round(Double.parseDouble(text.getAttribute("y"))),
                        Double.parseDouble(text.getAttribute("x")), text.getTextContent()));
            }
        }
        placed.sort(java.util.Comparator.comparingDouble(Placed::y).thenComparingDouble(Placed::x));
        return placed.stream().map(Placed::text).toList();
    }

    @Test
    void visualizeCommandRendersASavedGraphFile() throws Exception {
        Path runDir = Files.createDirectories(temp.resolve("runs/run with spaces"));
        WarningGraphJson.save(VisualizationFixtures.threeIsolated(), runDir.resolve("warning-graph.json"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        ExitCode exit = new VisualizeCommand(runDir.toString(), Map.of(GraphvizRenderer.DOT_ENV, dot), temp,
                new ProcessCommandRunner(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run();

        System.out.println(out.toString(StandardCharsets.UTF_8));
        assertEquals(ExitCode.COMPLETED, exit, err.toString(StandardCharsets.UTF_8));
        assertEquals(3, tooltips(parse(runDir.resolve("warning-graph.svg"))).size());
        assertTrue(Files.exists(runDir.resolve("warning-graph.dot")));
    }
}
