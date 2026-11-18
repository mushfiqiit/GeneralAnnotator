package generalannotator.graph;

import static generalannotator.graph.GraphFixtures.ROOT;
import static generalannotator.graph.GraphFixtures.completed;
import static generalannotator.graph.GraphFixtures.findings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WarningGraphJsonTest {

    @TempDir Path temp;

    private static WarningGraph graph() {
        return InitialWarningGraph.build("20261117T120000Z-nullaway", "nullaway", completed(findings()), ROOT);
    }

    @Test
    void saveAndLoadPreservesEverything() throws IOException {
        WarningGraph original = graph();
        Path file = temp.resolve("dir with spaces/warning-graph.json");
        java.nio.file.Files.createDirectories(file.getParent());

        WarningGraphJson.save(original, file);
        WarningGraph loaded = WarningGraphJson.load(file);

        assertEquals(original, loaded);
        assertEquals(List.of("W0001", "W0002", "W0003", "W0004"),
                loaded.nodes().stream().map(GraphNode::id).toList());
        assertTrue(loaded.warningNodes().stream().allMatch(n -> n.root() && n.distance() == 0));
        assertTrue(loaded.edges().isEmpty());
        // Saving the loaded graph reproduces the file byte for byte.
        assertEquals(WarningGraphJson.write(original), WarningGraphJson.write(loaded));
    }

    @Test
    void emptyGraphRoundTripsWithEmptyCollections() {
        WarningGraph empty = InitialWarningGraph.build("r", "nullaway", completed(List.of()), ROOT);
        String json = WarningGraphJson.write(empty);
        assertTrue(json.contains("\"nodes\": []"));
        assertTrue(json.contains("\"edges\": []"));
        assertEquals(empty, WarningGraphJson.read(json));
    }

    @Test
    void writesDocumentedShape() {
        String json = WarningGraphJson.write(graph());
        assertTrue(json.startsWith("{\n  \"schemaVersion\": 1,\n  \"graphType\": \"warning-graph\",\n"
                + "  \"runId\": \"20261117T120000Z-nullaway\",\n  \"checker\": \"nullaway\",\n"));
        assertTrue(json.contains("\"id\": \"W0001\",\n      \"kind\": \"warning\",\n      \"root\": true,\n"
                + "      \"distance\": 0,\n      \"targetPath\": \"src/p/EventBus.java\""), json);
        assertTrue(json.contains("\"column\": null"), "a missing column is written as null");
    }

    @Test
    void edgesRoundTrip() {
        WarningGraph g = graph();
        WarningGraph withEdge = new WarningGraph(g.runId(), g.checkerId(), g.nodes(),
                List.of(new GraphEdge("W0001", "W0002", "test-kind")));
        assertEquals(withEdge, WarningGraphJson.read(WarningGraphJson.write(withEdge)));
    }

    @Test
    void invalidGraphsAreRejected() {
        String good = WarningGraphJson.write(graph());
        List<String> bad = List.of(
                good.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
                good.replace("\"graphType\": \"warning-graph\"", "\"graphType\": \"other\""),
                good.replace("\"id\": \"W0002\"", "\"id\": \"W0001\""),
                good.replaceFirst("\"distance\": 0", "\"distance\": 3"),
                good.replaceFirst("\"kind\": \"warning\"", "\"kind\": \"action\""),
                good.replaceFirst("\"severity\": \"error\"", "\"severity\": \"fatal\""),
                good.replaceFirst("\"line\": \\d+", "\"line\": \"x\""),
                good.replace("\"edges\": []", "\"edges\": [{\"source\": \"W0001\", \"target\": \"W0042\", \"kind\": \"k\"}]"),
                good.replace("\"edges\": []", "\"edges\": {}"),
                good.substring(0, good.length() / 2));
        for (String json : bad) {
            assertThrows(IllegalArgumentException.class, () -> WarningGraphJson.read(json), json);
        }
    }

    @Test
    void nodesWithoutTargetPathKeepReportedPath() {
        WarningGraph g = InitialWarningGraph.build("r", "nullaway", completed(findings()), null);
        WarningGraph loaded = WarningGraphJson.read(WarningGraphJson.write(g));
        assertEquals(Optional.empty(), loaded.warningNodes().getFirst().targetPath());
        assertTrue(loaded.warningNodes().getFirst().diagnostic().sourcePath().startsWith(ROOT.toString()));
    }
}
