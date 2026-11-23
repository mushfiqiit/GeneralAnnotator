package generalannotator.propagation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.checker.CheckerOutcome;
import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.cli.ExitCode;
import generalannotator.cli.PrepareInferenceCommandTest;
import generalannotator.cli.PropagateCommand;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.edits.AnnotationEdit;
import generalannotator.edits.AnnotationPosition;
import generalannotator.edits.EditAction;
import generalannotator.edits.EditOrigin;
import generalannotator.edits.MemberRef;
import generalannotator.edits.Proposal;
import generalannotator.edits.javaparser.JavaParserAnnotationApplier;
import generalannotator.evaluation.Candidates;
import generalannotator.evaluation.EvaluationConfig;
import generalannotator.evaluation.EvaluationFiles;
import generalannotator.evaluation.Evaluator;
import generalannotator.evaluation.FakeChecker;
import generalannotator.evaluation.HistoryGraph;
import generalannotator.evaluation.RoundApplication;
import generalannotator.evaluation.matching.AnchoredWarningMatcher;
import generalannotator.graph.AnnotationNode;
import generalannotator.graph.GraphEdge;
import generalannotator.graph.InitialWarningGraph;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.graph.WarningNode;
import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import generalannotator.visualization.DotExporter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The extended graph keeps the earlier history whole, holds only warning and annotation nodes and causal edges from
 * independent evaluations, and links the combined round's warnings to earlier nodes only through verified provenance
 * and the matcher, never by ID.
 *
 * <p>Round 1 has two packages: A0001 annotates {@code a} (proposed by two equivalent answers, merged), A0002
 * annotates {@code x}. Alone, A0001 introduces
 * {@code shared()} and {@code b = a}; A0002 introduces {@code x.hashCode()} and {@code shared()}. Together they also
 * introduce {@code e = a} (only in combination). The evaluation numbers A0001's warnings first ({@code W1001}
 * shared, {@code W1002} b = a, {@code W1003} x.hashCode, {@code W1004} shared), but the combined result numbers its
 * own by position ({@code W1001} x.hashCode, {@code W1002} shared, {@code W1003} b = a, {@code W1004} e = a): the same
 * IDs name different warnings. {@code m = n} is an original warning that persists. Propagation then annotates
 * {@code b} (introducing {@code c = b}) and {@code m} (introducing nothing).
 */
class PropagationHistoryGraphTest {

    static final String ASSIGN = NullabilityPropagation.ASSIGN;

    static final String SOURCE = """
            package p;

            /** Fixture. */
            public class A {
                @Nullable Object n;
                Object m;
                Object x;
                Object a;
                Object b;
                Object c;
                Object e;

                void early() {
                    x.hashCode();
                    shared();
                }

                void init() {
                    m = n;
                    b = a;
                    c = b;
                    e = a;
                }

                void shared() {}
            }
            """;

    static CheckerOutcome rule(FakeChecker.Source s) {
        List<Diagnostic> f = new ArrayList<>();
        boolean a = s.annotated("Object a;");
        boolean x = s.annotated("Object x;");
        if (!s.annotated("Object m;")) {
            f.add(s.at("m = n;", ASSIGN));
        }
        if (!x) {
            f.add(s.at("Object x;", "@NonNull field 'x' not initialized"));
        } else {
            f.add(s.at("x.hashCode()", "dereferenced expression 'x' is @Nullable"));
        }
        if (a || x) {
            f.add(s.at("shared();", "a shared effect"));
        }
        if (!a) {
            f.add(s.at("Object a;", "@NonNull field 'a' not initialized"));
        } else {
            if (!s.annotated("Object b;")) {
                f.add(s.at("b = a;", ASSIGN));
            } else if (!s.annotated("Object c;")) {
                f.add(s.at("c = b;", ASSIGN));
            }
            if (x) {
                f.add(s.at("e = a;", "an effect of the combination"));
            }
        }
        return new CompletedAnalysis(f, List.of());
    }

    @TempDir Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Map<String, String> env = new HashMap<>();
    private Path run;
    private FakeChecker checker;
    private Path roundOne;
    private Path earlierGraph;
    private WarningGraph earlier;

    @BeforeEach
    void roundOne() throws Exception {
        run = temp.resolve("runs/run1");
        Path snapshot = run.resolve("workspace/target");
        Path file = snapshot.resolve("src/p/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        Files.writeString(snapshot.resolve("src/p/Nullable.java"), "package p;\n\npublic @interface Nullable {}\n");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sourceRoots", List.of("src"));
        config.put("classpath", List.of());
        config.put("javacRelease", null);
        Files.writeString(run.resolve("report.json"), Json.write(Map.of("runId", "run1", "configuration", config)));
        WarningGraphJson.save(InitialWarningGraph.build("run1", "nullaway", (CompletedAnalysis) rule(
                new FakeChecker.Source(snapshot, file, SOURCE)), snapshot), run.resolve(WarningGraphJson.FILE_NAME));
        Files.createDirectories(temp.resolve("checker"));
        env.put("GA_CHECKER", "nullaway");
        env.put("GA_CHECKER_HOME", temp.resolve("checker").toString());
        checker = new FakeChecker(PropagationHistoryGraphTest::rule);

        WarningGraph initial = WarningGraphJson.load(run.resolve(WarningGraphJson.FILE_NAME));
        List<Proposal> answers = new ArrayList<>();
        for (String member : List.of("a", "x", "a")) {
            String warning = initial.warningNodes().stream()
                    .filter(w -> w.diagnostic().message().startsWith("@NonNull field '" + member + "'"))
                    .findFirst().orElseThrow().id();
            AnnotationEdit edit = new AnnotationEdit("src", "p/A.java", "p.A", MemberRef.parse(member),
                    AnnotationPosition.field(), EditAction.ADD, "p.Nullable");
            int attempt = (int) answers.stream().filter(p -> p.source().warningId().equals(warning)).count() + 1;
            answers.add(new Proposal(new Proposal.ProposalSource("run1", "sub", warning, attempt, "prompt",
                    run.resolve("answer-" + member + attempt + ".json"), "answer-" + member + attempt),
                    Proposal.deduplicate(List.of(edit),
                    List.of(new EditOrigin(warning, 0, Optional.of("u")))), "because", "some"));
        }
        Candidates llm = Candidates.fromProposals("sub", "run1", run, snapshot, List.of("src"), answers,
                Candidates.LLM_GENERATOR, "1");
        EvaluationConfig evaluationConfig = EvaluationConfig.load(env, temp, Path.of(System.getProperty("java.home")),
                snapshot, List.of("src"), run.resolve(Evaluator.DIR));
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        Evaluator.Result evaluated = new Evaluator(llm, evaluationConfig, checker, new JavaParserAnnotationApplier(),
                new AnchoredWarningMatcher(), 1, PrepareInferenceCommandTest.CLOCK, sink).run(Optional.empty());
        RoundApplication.Result combined = new RoundApplication(llm, evaluationConfig, checker,
                new JavaParserAnnotationApplier(), new AnchoredWarningMatcher(), 1, PrepareInferenceCommandTest.CLOCK,
                sink).run();
        assertEquals(RoundApplication.Status.COMPLETED, combined.status());
        roundOne = combined.directory();
        earlierGraph = evaluated.directory().resolve(Evaluator.GRAPH);
        earlier = WarningGraphJson.load(earlierGraph);
        Map<String, Object> stages = new LinkedHashMap<>();
        stages.put("evaluation", Map.of("status", "COMPLETED", "artifacts", Map.of("graph",
                run.relativize(earlierGraph).toString())));
        stages.put("combined", Map.of("status", "COMPLETED", "artifacts", Map.of("directory",
                run.relativize(roundOne).toString())));
        Files.writeString(run.resolve("pipeline.json"), Json.write(Map.of("schemaVersion", 1, "pipelineId", "run1",
                "stages", stages)));
    }

    private ExitCode propagate() {
        out.reset();
        err.reset();
        return new PropagateCommand(run.toString(), env, temp, Path.of(System.getProperty("java.home")),
                (c, e) -> checker, new JavaParserAnnotationApplier(), new AnchoredWarningMatcher(),
                PrepareInferenceCommandTest.CLOCK, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run();
    }

    private ExitCode rebuild(Path stage) {
        out.reset();
        err.reset();
        return PropagateCommand.rebuildGraph(stage.toString(), temp, new AnchoredWarningMatcher(),
                PrepareInferenceCommandTest.CLOCK, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    private Path stage() throws IOException {
        try (Stream<Path> s = Files.list(run.resolve(PropagationStage.DIR))) {
            return s.sorted().toList().get(0);
        }
    }

    private static Map<?, ?> json(Path file) throws IOException {
        return (Map<?, ?>) JsonParser.parse(Files.readString(file));
    }

    /** The ID a graph's warning node has whose source line contains {@code marker}. */
    private static String id(WarningGraph g, String marker, Optional<String> state) {
        List<String> ids = g.warningNodes().stream()
                .filter(w -> w.diagnostic().sourceSnippet().orElse("").contains(marker))
                .filter(w -> state.isEmpty() || w.state().equals(state)).map(WarningNode::id).toList();
        assertEquals(1, ids.size(), marker + " in state " + state + ": " + ids);
        return ids.get(0);
    }

    private String combinedId(String marker) throws IOException {
        return id(WarningGraphJson.load(roundOne.resolve(WarningGraphJson.FILE_NAME)), marker, Optional.empty());
    }

    private String earlierId(String marker, String state) {
        return id(earlier, marker, Optional.of(state));
    }

    private static List<String> edges(WarningGraph g) {
        return g.edges().stream().map(e -> e.source() + " " + e.kind() + " " + e.target()).toList();
    }

    private static String packageFor(Map<?, ?> record, String member) {
        return ((List<?>) record.get("packages")).stream().map(p -> (Map<?, ?>) p)
                .filter(p -> p.get("edits").toString().contains("#" + member + " ")).findFirst().orElseThrow()
                .get("id").toString();
    }

    @Test
    void theFixtureReallyHasCollidingIdsBetweenTheIndependentEvaluationAndTheCombinedResult() throws Exception {
        assertEquals("W1001", earlierId("shared();", "S0+A0001"));
        assertEquals("W1001", combinedId("x.hashCode()"), "the same ID names a different warning in the combined result");
        assertEquals("W1002", earlierId("b = a;", "S0+A0001"));
        assertEquals("W1003", combinedId("b = a;"));
    }

    @Test
    void earlierHistoryIsKeptAndTheCombinedWarningExtendsItsOwnEarlierNode() throws Exception {
        String earlierBefore = Files.readString(earlierGraph);
        String roundBefore = EvaluationFiles.treeHash(roundOne);

        assertEquals(ExitCode.COMPLETED, propagate(), output());

        Path stage = stage();
        Map<?, ?> r = json(stage.resolve(PropagationStage.RESULT));
        assertEquals(HistoryGraph.MODEL, r.get("graphModel"));
        WarningGraph g = WarningGraphJson.load(stage.resolve(PropagationStage.GRAPH));
        // every earlier node and edge is kept unchanged, including the attempt-introduced W1... and their edges
        earlier.nodes().forEach(n -> assertEquals(n, g.node(n.id()).orElseThrow(), "kept: " + n.id()));
        Set<String> kept = new HashSet<>();
        g.edges().forEach(e -> kept.add(e.key()));
        earlier.edges().forEach(e -> assertTrue(kept.contains(e.key()), "kept: " + e));
        List<String> edges = edges(g);
        String bEarlier = earlierId("b = a;", "S0+A0001");
        assertTrue(edges.contains("A0001 introduces " + bEarlier), "the earlier annotation -> W1 edge: " + edges);

        // the combined result's b = a (its own W1003) is the earlier W1002, extended with W1002 -> P -> W2001
        String bPackage = packageFor(r, "b");
        assertTrue(edges.contains(bEarlier + " resolved-by " + bPackage), edges.toString());
        assertTrue(edges.contains(bPackage + " introduces W2001"), edges.toString());
        assertFalse(edges.contains(combinedId("b = a;") + " resolved-by " + bPackage), "never matched by ID");
        WarningNode w2001 = (WarningNode) g.node("W2001").orElseThrow();
        assertTrue(w2001.diagnostic().sourceSnippet().orElseThrow().contains("c = b;"));
        assertEquals(java.util.OptionalInt.of(2), w2001.round());
        GraphEdge resolved = g.edges().stream().filter(e -> e.source().equals(bEarlier) && e.target().equals(bPackage))
                .findFirst().orElseThrow();
        assertEquals(combinedId("b = a;"), resolved.provenance().get("reportWarning"));
        assertEquals(roundOne.getFileName().toString(), resolved.provenance().get("sourceState"));
        assertEquals(roundOne.getFileName() + "+" + bPackage, resolved.provenance().get("resultState"));
        assertTrue(resolved.provenance().get("evaluation").startsWith("evaluations/"), resolved.provenance().toString());
        AnnotationNode p = (AnnotationNode) g.node(bPackage).orElseThrow();
        assertEquals(java.util.OptionalInt.of(2), p.round());
        assertEquals(Optional.of(bEarlier), p.proposals().get(0).baselineWarningId(), "translated to the graph's node");

        assertEquals(earlierBefore, Files.readString(earlierGraph), "the earlier graph is not modified");
        assertEquals(roundBefore, EvaluationFiles.treeHash(roundOne), "the round-1 result is not modified");
    }

    @Test
    void aResolvedPersistingOriginalWarningKeepsItsIdAndEndsAtTheAnnotation() throws Exception {
        assertEquals(ExitCode.COMPLETED, propagate(), output());

        Path stage = stage();
        Map<?, ?> r = json(stage.resolve(PropagationStage.RESULT));
        WarningGraph g = WarningGraphJson.load(stage.resolve(PropagationStage.GRAPH));
        String original = earlierId("m = n;", "S0");
        assertTrue(original.startsWith("W0"), original);
        assertEquals(original, combinedId("m = n;"), "persisting warnings keep their IDs in the combined result");
        assertEquals(1, g.warningNodes().stream().filter(w -> w.diagnostic().sourceSnippet().orElse("")
                .contains("m = n;")).count(), "no second node for a persisting warning");
        String mPackage = packageFor(r, "m");
        List<String> edges = edges(g);
        assertTrue(edges.contains(original + " resolved-by " + mPackage), edges.toString());
        assertTrue(edges.stream().noneMatch(e -> e.startsWith(mPackage + " ")), "no introduced warnings: the branch "
                + "ends at the annotation: " + edges);
        assertEquals("COMPLETED", ((AnnotationNode) g.node(mPackage).orElseThrow()).outcome());
        long w2 = g.warningNodes().stream().filter(w -> w.id().startsWith("W2")).count();
        assertEquals(1, w2, "only the genuinely new c = b gets a round-2 node");
        Map<?, ?> history = (Map<?, ?>) r.get("history");
        Map<?, ?> m = inputWarning(history, original);
        assertEquals("persisting-from-base", m.get("relation"));
        assertEquals(original, m.get("graphNode"));
    }

    @Test
    void combinedWarningsAreLinkedOnlyWithEvidenceAndUnattributableOnesStaySeparateWithoutEdges() throws Exception {
        assertEquals(ExitCode.COMPLETED, propagate(), output());

        Path stage = stage();
        WarningGraph g = WarningGraphJson.load(stage.resolve(PropagationStage.GRAPH));
        String state = roundOne.getFileName().toString();
        assertTrue(g.node(state).isEmpty(), "no combined-state node");
        assertOnlyWarningAndAnnotationNodesAndCausalEdges(g, Files.readString(stage.resolve(PropagationStage.GRAPH)));
        Map<?, ?> history = (Map<?, ?>) json(stage.resolve(PropagationStage.RESULT)).get("history");
        Map<?, ?> reportToGraph = (Map<?, ?>) history.get("reportToGraph");

        // x.hashCode (the combined W1001) is the earlier W1003 of A0002, not the earlier W1001 (shared, A0001)
        String hash = earlierId("x.hashCode()", "S0+A0002");
        assertEquals(hash, reportToGraph.get("W1001"));
        assertTrue(edges(g).contains("A0002 introduces " + hash), "the earlier causal edge is the only origin");
        Map<?, ?> link = inputWarning(history, "W1001");
        assertEquals("linked-to-package-warning", link.get("relation"));
        assertEquals("A0002", ((Map<?, ?>) link.get("provenance")).get("package"));

        // shared() was introduced alone by both packages: an ambiguous link, so a separate node with a fresh ID
        String shared = id(g, "shared();", Optional.of(state));
        assertTrue(earlier.node(shared).isEmpty(), "a new, unique ID: " + shared);
        assertTrue(shared.startsWith("W1"), "a round-1 ID: " + shared);
        Map<?, ?> amb = inputWarning(history, combinedId("shared();"));
        assertEquals("ambiguous", amb.get("relation"));
        assertEquals(shared, amb.get("graphNode"));
        String candidates = String.valueOf(((Map<?, ?>) amb.get("provenance")).get("candidates"));
        assertTrue(candidates.contains("A0001") && candidates.contains("A0002"), candidates);
        // e = a appears only in combination: no individual evaluation establishes its origin
        String combo = id(g, "e = a;", Optional.of(state));
        assertTrue(earlier.node(combo).isEmpty());
        Map<?, ?> unattributed = inputWarning(history, combinedId("e = a;"));
        assertEquals("unattributed", unattributed.get("relation"));
        assertTrue(String.valueOf(((Map<?, ?>) unattributed.get("provenance")).get("limitation"))
                .contains("not established"), unattributed.toString());
        // neither gets an invented incoming edge
        assertTrue(g.edges().stream().noneMatch(e -> e.target().equals(shared) || e.target().equals(combo)),
                edges(g).toString());
        Set<String> ids = new HashSet<>();
        g.nodes().forEach(n -> assertTrue(ids.add(n.id()), "unique IDs"));
        assertEquals(reportToGraph.size(), new HashSet<>(reportToGraph.values()).size(), "one node per report ID");
    }

    @Test
    void equivalentAnswersStayOnePackageAndAnnotationIdsAreUniqueAcrossRounds() throws Exception {
        assertEquals(ExitCode.COMPLETED, propagate(), output());

        WarningGraph g = WarningGraphJson.load(stage().resolve(PropagationStage.GRAPH));
        List<AnnotationNode> annotations = g.nodes().stream().filter(AnnotationNode.class::isInstance)
                .map(AnnotationNode.class::cast).toList();
        AnnotationNode a = (AnnotationNode) g.node("A0001").orElseThrow();
        assertEquals(2, a.proposals().size(), "two answers with the same complete edit set: one package node");
        assertEquals(List.of("A0001", "A0002"), annotations.stream().filter(n -> n.round().equals(
                java.util.OptionalInt.of(1))).map(AnnotationNode::id).toList());
        assertTrue(annotations.stream().filter(n -> n.round().equals(java.util.OptionalInt.of(2)))
                .allMatch(n -> n.id().startsWith("P")), "propagation packages have their own IDs");
        assertEquals(annotations.size(), annotations.stream().map(AnnotationNode::id).distinct().count());
    }

    private static Map<?, ?> inputWarning(Map<?, ?> history, String reportId) {
        return ((List<?>) history.get("inputWarnings")).stream().map(o -> (Map<?, ?>) o)
                .filter(o -> o.get("reportWarning").equals(reportId)).findFirst().orElseThrow();
    }

    /** The graph and its DOT rendering hold only warning and annotation nodes and solid causal edges. */
    static void assertOnlyWarningAndAnnotationNodesAndCausalEdges(WarningGraph g, String json) {
        for (GraphEdge e : g.edges()) {
            boolean resolved = e.kind().equals(GraphEdge.RESOLVED_BY) && g.node(e.source()).orElseThrow()
                    instanceof WarningNode && g.node(e.target()).orElseThrow() instanceof AnnotationNode;
            boolean introduced = e.kind().equals(GraphEdge.INTRODUCES) && g.node(e.source()).orElseThrow()
                    instanceof AnnotationNode && g.node(e.target()).orElseThrow() instanceof WarningNode;
            assertTrue(resolved || introduced, "not a causal edge: " + e);
        }
        Map<?, ?> root = (Map<?, ?>) JsonParser.parse(json);
        for (Object n : (List<?>) root.get("nodes")) {
            assertTrue(List.of("warning", "annotation").contains(((Map<?, ?>) n).get("kind")), n.toString());
        }
        String dot = DotExporter.toDot(g);
        for (String forbidden : List.of("dashed", "dotted", "box3d", "combined state", "applied-in",
                "combined-introduces")) {
            assertFalse(dot.contains(forbidden), forbidden + " in " + dot);
        }
        assertTrue(dot.lines().filter(l -> l.matches("  \"[^\"]+\" -> \"[^\"]+\" \\[.*")).allMatch(l -> l.contains("[label=\"resolved-by\"")
                || l.contains("[label=\"introduces\"")), dot);
    }

    @Test
    void rebuildingFromSavedArtifactsIsIdenticalWithoutDuplicatesAndReplacesAStaleGraph() throws Exception {
        assertEquals(ExitCode.COMPLETED, propagate(), output());
        Path stage = stage();
        Path graph = stage.resolve(PropagationStage.GRAPH);
        String first = Files.readString(graph);
        int checks = checker.calls();

        assertEquals(ExitCode.COMPLETED, propagate(), output());
        assertEquals(first, Files.readString(graph), "rerunning propagation gives the same graph");

        // a stale graph (as written by the earlier model) and its renderings are replaced by a rebuild
        Files.writeString(graph, WarningGraphJson.write(earlier));
        Files.writeString(stage.resolve("evaluation-graph.svg"), "<svg/>");
        assertEquals(ExitCode.COMPLETED, rebuild(stage), output());
        assertEquals(first, Files.readString(graph), output());
        assertFalse(Files.exists(stage.resolve("evaluation-graph.svg")), "a stale rendering is removed");
        assertEquals(ExitCode.COMPLETED, rebuild(stage), output());
        assertEquals(first, Files.readString(graph), "repeated rebuilds are identical");
        assertEquals(checks, checker.calls(), "nothing was checked again");
        WarningGraph g = WarningGraphJson.load(graph);
        Set<String> keys = new HashSet<>();
        g.edges().forEach(e -> assertTrue(keys.add(e.key()), "duplicate edge " + e));
        assertOnlyWarningAndAnnotationNodesAndCausalEdges(g, first);
        assertEquals(HistoryGraph.MODEL, json(stage.resolve(PropagationStage.RESULT)).get("graphModel"));

        // a changed input is refused: the saved results describe another input
        Path findings = roundOne.resolve("findings.json");
        Files.writeString(findings, Files.readString(findings) + " ");
        Path report = roundOne.resolve("report.json");
        Files.writeString(report, Files.readString(report) + "\n");
        assertEquals(ExitCode.EXECUTION_FAILURE, rebuild(stage), output());
        assertTrue(output().contains("rerun propagate"), output());
    }
}
