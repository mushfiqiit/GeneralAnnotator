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

    private static java.util.Map<String, String> fields(String member) {
        java.util.Map<String, String> edit = new java.util.LinkedHashMap<>();
        edit.put("sourceRoot", "src");
        edit.put("file", "p/Queue.java");
        edit.put("member", member);
        edit.put("annotation", "org.jspecify.annotations.Nullable");
        return edit;
    }

    /**
     * A package of three edits shared by two answers (one entry merged from two indices of the first answer),
     * and an unevaluated package.
     */
    private static WarningGraph expanded() {
        WarningGraph g = graph();
        List<GraphNode> nodes = new java.util.ArrayList<>();
        for (WarningNode w : g.warningNodes()) {
            nodes.add(new WarningNode(w.id(), w.diagnostic(), w.targetPath(), true, 0, Optional.of("S0"),
                    java.util.OptionalInt.of(0)));
        }
        List<AnnotationNode.PackageEdit> edits = List.of(
                new AnnotationNode.PackageEdit("ADD @x at FIELD of p.Queue#head", fields("head"),
                        Optional.of("APPLIED"), Optional.of("added")),
                new AnnotationNode.PackageEdit("ADD @x at FIELD of p.Queue#tail", fields("tail"),
                        Optional.of("NO_OP"), Optional.of("already present")),
                new AnnotationNode.PackageEdit("ADD @x at FIELD of p.Queue#size", fields("size"),
                        Optional.of("APPLIED"), Optional.of("added")));
        nodes.add(new AnnotationNode("A0001", "package of 3 edit(s) proposed by 2 answers (W0001, W0004)", edits,
                "S0", Optional.of("S0+A0001"), "COMPLETED", "findings 4 -> 4", List.of(
                        new AnnotationNode.Proposal("W0001", "W0001@1", List.of(List.of(0, 2), List.of(1), List.of(3)),
                                Optional.of("W0001")),
                        new AnnotationNode.Proposal("W0004", "W0004@1", List.of(List.of(2), List.of(0), List.of(1)),
                                Optional.empty())),
                Optional.of(new AnnotationNode.Counts(4, 4, 1, 3, 1, 0, 0)), java.util.OptionalInt.of(1)));
        WarningNode n = g.warningNodes().get(0);
        nodes.add(new WarningNode("W1001", n.diagnostic(), n.targetPath(), false, 1, Optional.of("S0+A0001"),
                java.util.OptionalInt.of(1)));
        nodes.add(new AnnotationNode("A0002", "package of 1 edit(s) proposed for W0002 (W0002@1)",
                List.of(new AnnotationNode.PackageEdit("REMOVE ...", fields("head"), Optional.empty(),
                        Optional.empty())), "S0", Optional.empty(), "UNEVALUATED", "not evaluated yet",
                List.of(new AnnotationNode.Proposal("W0002", "W0002@1", List.of(List.of(0)), Optional.empty())),
                Optional.empty(), java.util.OptionalInt.of(1)));
        return new WarningGraph(g.runId(), g.checkerId(), nodes, List.of(
                new GraphEdge("W0002", "A0001", GraphEdge.RESOLVED_BY),
                new GraphEdge("W0003", "A0001", GraphEdge.RESOLVED_BY),
                new GraphEdge("A0001", "W1001", GraphEdge.INTRODUCES)));
    }

    @Test
    void expandedGraphsUseSchemaVersionFiveWithSharedPackagesAndRoundsAndRoundTrip() {
        WarningGraph original = expanded();
        String json = WarningGraphJson.write(original);

        assertTrue(json.contains("\"schemaVersion\": 5"), json);
        assertTrue(json.contains("\"id\": \"W1001\",\n      \"kind\": \"warning\",\n      \"state\": \"S0+A0001\",\n"
                + "      \"round\": 1"), json);
        assertTrue(json.contains("\"answerEditIndices\": [\n            [\n              0,\n              2\n            ],"), json);
        assertTrue(json.contains("\"applierOutcome\": \"NO_OP\""), json);
        WarningGraph loaded = WarningGraphJson.read(json);
        assertEquals(original, loaded);
        assertEquals(3, ((AnnotationNode) loaded.node("A0001").orElseThrow()).edits().size());
        assertEquals(List.of("W0001", "W0004"), ((AnnotationNode) loaded.node("A0001").orElseThrow()).proposals()
                .stream().map(AnnotationNode.Proposal::warningId).toList());
        assertEquals(json, WarningGraphJson.write(loaded));
        // Initial graphs keep version 1 without a state field.
        String initial = WarningGraphJson.write(graph());
        assertTrue(initial.contains("\"schemaVersion\": 1") && !initial.contains("\"state\""), initial);
    }

    @Test
    void annotationNodesAreRejectedInVersionOneAndValidatedInLaterVersions() {
        String json = WarningGraphJson.write(expanded());
        assertThrows(IllegalArgumentException.class,
                () -> WarningGraphJson.read(json.replace("\"schemaVersion\": 5", "\"schemaVersion\": 1")));
        assertThrows(IllegalArgumentException.class,
                () -> WarningGraphJson.read(json.replace("\"outcome\": \"COMPLETED\",", "")));
        assertThrows(IllegalArgumentException.class,
                () -> WarningGraphJson.read(json.replace("\"schemaVersion\": 5", "\"schemaVersion\": 6")));
        // a proposal gives indices for every edit of its node, or none
        assertThrows(IllegalArgumentException.class, () -> new AnnotationNode("A0009", "p", List.of(
                new AnnotationNode.PackageEdit("e", fields("head"), Optional.empty(), Optional.empty())), "S0",
                Optional.empty(), "UNEVALUATED", "d", List.of(new AnnotationNode.Proposal("W0001", "W0001@1",
                        List.of(List.of(0), List.of(1)), Optional.empty())), Optional.empty(),
                java.util.OptionalInt.of(1)));
        // a package needs at least one edit; a round that contradicts the node kind is rejected
        assertThrows(IllegalArgumentException.class,
                () -> WarningGraphJson.read(json.replaceFirst("(?s)\"edits\": \\[.*?\"proposals\"",
                        "\"edits\": [],\n      \"proposals\"")));
        assertThrows(IllegalArgumentException.class,
                () -> WarningGraphJson.read(json.replaceFirst("\"round\": 0", "\"round\": 2")));
    }

    /** An annotation node as earlier single-edit versions (2 and 3) wrote it. */
    private static String singleEditNode(boolean rounds) {
        return """
                { "id": "A0001", "kind": "annotation", %s"baselineState": "S0", "resultState": "S0+A0001",
                  "outcome": "COMPLETED", "detail": "d", "canonical": "ADD @x at FIELD of p.Queue#head",
                  "edit": { "sourceRoot": "src", "file": "p/Queue.java" },
                  "proposals": [ { "warningId": "W0001", "group": "W0001@1", "answerEditIndex": 2,
                                   "baselineWarningId": "W0001" } ],
                  "counts": null }""".formatted(rounds ? "\"round\": 1, " : "");
    }

    @Test
    void earlierSingleEditGraphsAreReadAsOneEditNodes() {
        for (int version : List.of(2, 3)) {
            String json = "{ \"schemaVersion\": " + version + ", \"graphType\": \"warning-graph\", \"runId\": \"r\", "
                    + "\"checker\": \"nullaway\", \"nodes\": [" + singleEditNode(version == 3) + "], \"edges\": [] }";
            WarningGraph g = WarningGraphJson.read(json);

            AnnotationNode a = (AnnotationNode) g.node("A0001").orElseThrow();
            assertEquals(1, a.edits().size());
            assertEquals("ADD @x at FIELD of p.Queue#head", a.edits().get(0).canonical());
            assertEquals(List.of(List.of(2)), a.proposals().get(0).answerEditIndices());
            assertEquals(version == 3 ? java.util.OptionalInt.of(1) : java.util.OptionalInt.empty(), a.round());
            // re-saving writes the current version, never an earlier one
            assertTrue(WarningGraphJson.write(g).contains("\"schemaVersion\": 5"));
        }
    }

    @Test
    void oneAnswerPackageGraphsOfVersionFourAreReadWithTheirIndicesMovedToTheProposal() {
        String json = """
                { "schemaVersion": 4, "graphType": "warning-graph", "runId": "r", "checker": "nullaway", "nodes": [
                  { "id": "A0001", "kind": "annotation", "round": 1, "baselineState": "S0", "resultState": null,
                    "outcome": "UNEVALUATED", "detail": "d", "canonical": "package of 2 edit(s) proposed for W0001",
                    "edits": [ { "canonical": "a", "edit": { "member": "head" }, "answerEditIndices": [0, 2],
                                 "applierOutcome": null, "applierDetail": null },
                               { "canonical": "b", "edit": { "member": "tail" }, "answerEditIndices": [1],
                                 "applierOutcome": null, "applierDetail": null } ],
                    "proposals": [ { "warningId": "W0001", "group": "W0001@1", "answerEditIndex": null,
                                     "baselineWarningId": null } ],
                    "counts": null } ], "edges": [] }""";

        AnnotationNode a = (AnnotationNode) WarningGraphJson.read(json).node("A0001").orElseThrow();

        assertEquals(List.of(List.of(0, 2), List.of(1)), a.proposals().get(0).answerEditIndices());
        assertEquals(2, a.edits().size());
    }
}
