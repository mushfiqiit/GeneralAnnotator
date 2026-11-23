package generalannotator.propagation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import generalannotator.evaluation.WarningRegistry;
import generalannotator.evaluation.WarningIntegrity;
import generalannotator.evaluation.RegistryMigration;
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
 * shared, {@code W1002} b = a, {@code W1003} x.hashCode, {@code W1004} shared); the combined result draws from the
 * same round-1 sequence through the run's warning registry, so its own warnings continue after them ({@code W1005}
 * x.hashCode, ...) instead of reusing W1001 to W1004 for different warnings (the EventBus W1004 collision).
 * {@code m = n} is an original warning that persists. Propagation then annotates {@code b} (introducing
 * {@code c = b}) and {@code m} (introducing nothing).
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
    void independentAndCombinedOccurrencesNeverShareAnId() throws Exception {
        assertEquals("W1001", earlierId("shared();", "S0+A0001"));
        assertEquals("W1002", earlierId("b = a;", "S0+A0001"));
        assertEquals("W1003", earlierId("x.hashCode()", "S0+A0002"));
        assertEquals("W1004", earlierId("shared();", "S0+A0002"));
        // the combined state's warnings are distinct occurrences: their own IDs, after the evaluation's
        assertEquals("W1005", combinedId("x.hashCode()"), "never the evaluation's W1001 (a different warning)");
        Set<String> evaluationIds = new HashSet<>();
        earlier.warningNodes().forEach(w -> evaluationIds.add(w.id()));
        WarningGraphJson.load(roundOne.resolve(WarningGraphJson.FILE_NAME)).warningNodes().stream()
                .filter(w -> w.id().startsWith("W1"))
                .forEach(w -> assertFalse(evaluationIds.contains(w.id()), w.id() + " is used by both"));
        WarningRegistry registry = WarningRegistry.load(run);
        assertEquals(java.util.OptionalInt.of(8), registry.counter(1), "one shared round-1 sequence");
        assertEquals("evaluation", registry.warning("W1004").orElseThrow().kind());
        assertEquals("combined", registry.warning("W1005").orElseThrow().kind());
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

        // the combined result's b = a keeps its report ID in the graph; its correspondence to A0001's W1002 (a
        // distinct occurrence in another state) is metadata, never a substituted ID or an invented edge
        String bPackage = packageFor(r, "b");
        String bCombined = combinedId("b = a;");
        assertFalse(bCombined.equals(bEarlier));
        assertTrue(edges.contains(bCombined + " resolved-by " + bPackage), edges.toString());
        assertTrue(edges.contains(bPackage + " introduces W2001"), edges.toString());
        assertFalse(edges.contains(bEarlier + " resolved-by " + bPackage), "the report ID, never the earlier node's");
        assertFalse(edges.contains("A0001 introduces " + bCombined), "no fabricated causal edge");
        Map<?, ?> b = inputWarning((Map<?, ?>) r.get("history"), bCombined);
        assertEquals("corresponds-to-package-warning", b.get("relation"));
        assertEquals(bEarlier, ((Map<?, ?>) b.get("provenance")).get("correspondsTo"));
        assertEquals(bCombined, b.get("graphNode"));
        assertTrue(WarningRegistry.load(run).correspondences().contains(new WarningRegistry.Correspondence(bCombined,
                bEarlier, String.valueOf(((Map<?, ?>) b.get("provenance")).get("evidence")))));
        WarningNode w2001 = (WarningNode) g.node("W2001").orElseThrow();
        assertTrue(w2001.diagnostic().sourceSnippet().orElseThrow().contains("c = b;"));
        assertEquals(java.util.OptionalInt.of(2), w2001.round());
        GraphEdge resolved = g.edges().stream().filter(e -> e.source().equals(bCombined) && e.target().equals(bPackage))
                .findFirst().orElseThrow();
        assertEquals(bCombined, resolved.provenance().get("reportWarning"));
        assertTrue(resolved.provenance().get("claim").contains("own independent evaluation"));
        assertEquals(roundOne.getFileName().toString(), resolved.provenance().get("sourceState"));
        assertEquals(roundOne.getFileName() + "+" + bPackage, resolved.provenance().get("resultState"));
        assertTrue(resolved.provenance().get("evaluation").startsWith("evaluations/"), resolved.provenance().toString());
        AnnotationNode p = (AnnotationNode) g.node(bPackage).orElseThrow();
        assertEquals(java.util.OptionalInt.of(2), p.round());
        assertEquals(Optional.of(bCombined), p.proposals().get(0).baselineWarningId(), "the report ID");

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

        // every report ID is its own graph ID
        reportToGraph.forEach((k, v) -> assertEquals(k, v));
        // x.hashCode (the combined W1005) corresponds to A0002's W1003; it keeps W1005
        String hash = earlierId("x.hashCode()", "S0+A0002");
        String hashCombined = combinedId("x.hashCode()");
        assertTrue(g.node(hashCombined).isPresent());
        assertTrue(edges(g).contains("A0002 introduces " + hash), "the earlier causal edge is kept");
        Map<?, ?> link = inputWarning(history, hashCombined);
        assertEquals("corresponds-to-package-warning", link.get("relation"));
        assertEquals("A0002", ((Map<?, ?>) link.get("provenance")).get("package"));
        assertEquals(hash, ((Map<?, ?>) link.get("provenance")).get("correspondsTo"));

        // shared() was introduced alone by both packages: ambiguous, so no correspondence; its own report ID
        String shared = id(g, "shared();", Optional.of(state));
        assertEquals(combinedId("shared();"), shared);
        assertTrue(earlier.node(shared).isEmpty(), "a new, unique ID: " + shared);
        Map<?, ?> amb = inputWarning(history, shared);
        assertEquals("ambiguous-correspondence", amb.get("relation"));
        assertEquals(shared, amb.get("graphNode"));
        String candidates = String.valueOf(((Map<?, ?>) amb.get("provenance")).get("candidates"));
        assertTrue(candidates.contains("A0001") && candidates.contains("A0002"), candidates);
        // e = a appears only in combination: no individual evaluation establishes its origin
        String combo = id(g, "e = a;", Optional.of(state));
        assertTrue(earlier.node(combo).isEmpty());
        assertEquals(combinedId("e = a;"), combo);
        Map<?, ?> unattributed = inputWarning(history, combo);
        assertEquals("introduced-in-combined-state", unattributed.get("relation"));
        assertTrue(String.valueOf(((Map<?, ?>) unattributed.get("provenance")).get("note"))
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

    /** Replaces every string equal to a key of {@code map} in a JSON file (to fabricate a legacy artifact). */
    private static void renameIn(Path file, Map<String, String> map) throws IOException {
        Object json = rename(JsonParser.parse(Files.readString(file)), map);
        Files.writeString(file, Json.write(json));
    }

    private static Object rename(Object json, Map<String, String> map) {
        if (json instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), rename(v, map)));
            return copy;
        }
        if (json instanceof List<?> l) {
            return l.stream().map(v -> rename(v, map)).toList();
        }
        return json instanceof String str && map.containsKey(str) ? map.get(str) : json;
    }

    @Test
    void aLegacyRunWithCollidingIdsIsDetectedMigratedAndValidated() throws Exception {
        assertEquals(ExitCode.COMPLETED, propagate(), output());
        Path stage = stage();
        Path evaluation = earlierGraph.getParent();
        // Legacy: the evaluation numbered its warnings from W1005 (as the earlier, unshared allocator could), so
        // its W1005 (shared(), A0001) is not the combined result's W1005 (x.hashCode()), as in EventBus's W1004.
        Map<String, String> legacy = Map.of("W1001", "W1005", "W1002", "W1006", "W1003", "W1007", "W1004", "W1008");
        List<Path> files = new ArrayList<>(List.of(evaluation.resolve(Evaluator.GRAPH),
                evaluation.resolve("summary.json")));
        try (Stream<Path> s = Files.list(evaluation.resolve("attempts"))) {
            s.filter(d -> Files.isRegularFile(d.resolve("attempt.json"))).forEach(d -> {
                files.add(d.resolve("attempt.json"));
                files.add(d.resolve("comparison.json"));
            });
        }
        for (Path f : files) {
            renameIn(f, legacy);
        }
        Path record = stage.resolve(PropagationStage.RESULT);
        Files.writeString(record, Files.readString(record).replace(HistoryGraph.MODEL, "warning-annotation-history-2"));
        Files.delete(run.resolve(WarningRegistry.FILE));
        String legacyGraph = Files.readString(evaluation.resolve(Evaluator.GRAPH));
        String roundsBefore = EvaluationFiles.treeHash(run.resolve("rounds"));
        String baselineBefore = EvaluationFiles.treeHash(evaluation.resolve("baseline"));
        String snapshotBefore = EvaluationFiles.treeHash(run.resolve("workspace/target"));
        int edgesBefore = WarningGraphJson.load(stage.resolve(PropagationStage.GRAPH)).edges().size();
        int checks = checker.calls();
        assertEquals("W1005", combinedId("x.hashCode()"));
        earlier = WarningGraphJson.load(earlierGraph);
        assertEquals("W1005", earlierId("shared();", "S0+A0001"), "the fabricated collision");

        // detected, and every writer refuses the inconsistent run
        WarningIntegrity.Report before = WarningIntegrity.check(run);
        assertFalse(before.ok());
        assertTrue(before.problems().stream().anyMatch(p -> p.contains("give one public ID to different warnings")
                && p.contains("warning-ids migrate")), before.problems().toString());
        assertThrows(WarningRegistry.Conflict.class, () -> WarningRegistry.rebuild(run));

        // a failed migration restores every file
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        RegistryMigration.Result failed = RegistryMigration.migrate(run, new AnchoredWarningMatcher(),
                PrepareInferenceCommandTest.CLOCK, sink, s -> {
                    throw new IOException("simulated interruption");
                });
        assertFalse(failed.ok());
        assertEquals(legacyGraph, Files.readString(evaluation.resolve(Evaluator.GRAPH)), "restored");
        assertFalse(Files.exists(run.resolve(WarningRegistry.FILE)), "no half-written registry");

        // migrated: combined-report IDs kept, the colliding evaluation occurrences renamed to fresh round-1 IDs
        RegistryMigration.Result m = RegistryMigration.migrate(run, new AnchoredWarningMatcher(),
                PrepareInferenceCommandTest.CLOCK, sink, s -> {
                    try {
                        PropagationStage.rebuildGraph(s, new AnchoredWarningMatcher(), PrepareInferenceCommandTest.CLOCK,
                                sink);
                    } catch (NullabilityPropagation.InvalidException e) {
                        throw new RegistryMigration.Superseded(e.getMessage());
                    }
                });
        assertTrue(m.ok(), m.problems().toString());
        assertEquals(Map.of(run.relativize(evaluation).toString(), Map.of("W1005", "W1009", "W1006", "W1010", "W1007",
                "W1011", "W1008", "W1012")), m.renames());
        WarningIntegrity.Report after = WarningIntegrity.check(run);
        assertTrue(after.ok(), after.problems().toString());

        earlier = WarningGraphJson.load(earlierGraph);
        String hash = earlierId("x.hashCode()", "S0+A0002");
        assertEquals("W1011", hash);
        assertEquals("W1005", combinedId("x.hashCode()"), "the report keeps its ID");
        WarningGraph g = WarningGraphJson.load(stage.resolve(PropagationStage.GRAPH));
        assertEquals("x.hashCode();", ((WarningNode) g.node("W1005").orElseThrow()).diagnostic().sourceSnippet()
                .orElseThrow().strip(), "graph and report agree about W1005");
        List<String> edges = edges(g);
        assertTrue(edges.contains("A0002 introduces W1011"), edges.toString());
        assertFalse(edges.contains("A0002 introduces W1005"), "no fabricated edge");
        assertEquals(edgesBefore, g.edges().size(), "no edge added or lost");
        String bPackage = packageFor(json(stage.resolve(PropagationStage.RESULT)), "b");
        assertEquals(List.of(combinedId("b = a;")), g.edges().stream().filter(e -> e.target().equals(bPackage))
                .map(GraphEdge::source).toList(), "the package resolves only the combined occurrence it was given");
        WarningRegistry reg = WarningRegistry.load(run);
        assertTrue(reg.correspondences().stream().anyMatch(c -> c.warningId().equals("W1005")
                && c.correspondsTo().equals("W1011")), reg.correspondences().toString());

        // raw results and snapshots are untouched; derived artifacts are backed up
        assertEquals(roundsBefore, EvaluationFiles.treeHash(run.resolve("rounds")));
        assertEquals(baselineBefore, EvaluationFiles.treeHash(evaluation.resolve("baseline")));
        assertEquals(snapshotBefore, EvaluationFiles.treeHash(run.resolve("workspace/target")));
        Path backup = m.report().getParent().resolve("backup");
        assertEquals(legacyGraph, Files.readString(backup.resolve(run.relativize(evaluation.resolve(Evaluator.GRAPH))
                .toString())));
        assertEquals(checks, checker.calls(), "nothing was rechecked");

        // idempotent, and the registry rebuilds deterministically from the migrated artifacts
        assertFalse(RegistryMigration.migrate(run, new AnchoredWarningMatcher(), PrepareInferenceCommandTest.CLOCK,
                sink, s -> {
                    throw new IOException("must not rebuild again");
                }).migrated());
        String saved = Files.readString(run.resolve(WarningRegistry.FILE));
        WarningRegistry.rebuild(run).save();
        assertEquals(saved, Files.readString(run.resolve(WarningRegistry.FILE)), "rebuild == saved registry");
        // and the migrated run keeps working: rerunning propagation reuses everything and changes nothing
        String graphText = Files.readString(stage.resolve(PropagationStage.GRAPH));
        assertEquals(ExitCode.COMPLETED, propagate(), output());
        assertEquals(checks, checker.calls());
        assertEquals(graphText, Files.readString(stage.resolve(PropagationStage.GRAPH)));
        assertTrue(WarningIntegrity.check(run).ok());
    }

    private ExitCode attribute(boolean savedOnly) {
        out.reset();
        err.reset();
        return generalannotator.cli.AttributeCommand.run(run.toString(), savedOnly, env, temp,
                Path.of(System.getProperty("java.home")), (c, e) -> checker, new JavaParserAnnotationApplier(),
                new AnchoredWarningMatcher(), PrepareInferenceCommandTest.CLOCK,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void disconnectedCombinedWarningsGetEdgesOnlyForVerifiedContributions() throws Exception {
        assertEquals(ExitCode.COMPLETED, propagate(), output());
        Path stage = stage();
        Path graphFile = stage.resolve(PropagationStage.GRAPH);
        String hash = combinedId("x.hashCode()");
        String bCombined = combinedId("b = a;");
        String shared = combinedId("shared();");
        String combo = combinedId("e = a;");
        WarningGraph before = WarningGraphJson.load(graphFile);
        for (String w : List.of(hash, bCombined, shared, combo)) {
            assertTrue(before.edges().stream().noneMatch(e -> e.target().equals(w)), w + " starts disconnected");
        }
        // a graph-only rebuild claims nothing without saved attribution evidence
        assertEquals(ExitCode.COMPLETED, rebuild(stage), output());
        assertEquals(before.edges().size(), WarningGraphJson.load(graphFile).edges().size());
        String registryBefore = Files.readString(run.resolve(WarningRegistry.FILE));
        int checks = checker.calls();

        // two packages: every counterfactual is a saved state (the other package's own evaluation)
        assertEquals(ExitCode.COMPLETED, attribute(true), output());

        assertEquals(checks, checker.calls(), "reused evaluations only, no checker run\n" + output());
        WarningGraph g = WarningGraphJson.load(graphFile);
        List<String> edges = edges(g);
        // A0002 alone introduced x.hashCode, and without it the combined warning is gone: A0002 -> the combined warning
        assertTrue(edges.contains("A0002 introduces " + hash), edges.toString());
        assertTrue(edges.contains("A0001 introduces " + bCombined), edges.toString());
        GraphEdge e = g.edges().stream().filter(x -> x.source().equals("A0002") && x.target().equals(hash))
                .findFirst().orElseThrow();
        assertEquals("combined-context contribution (leave one out)", e.provenance().get("basis"));
        assertTrue(e.provenance().get("claim").contains("not proof that A0002 alone causes it"));
        assertEquals(roundOne.getFileName().toString(), e.provenance().get("combinedState"));
        assertTrue(e.provenance().get("counterfactualState").contains("A0001's own evaluation"),
                e.provenance().toString());
        assertTrue(e.provenance().get("packageKey").length() == 64);
        assertEquals("anchored-1", e.provenance().get("matcher"));
        // both packages alone introduce shared(): each is still reported without the other, so no edge
        assertTrue(g.edges().stream().noneMatch(x -> x.target().equals(shared)), edges.toString());
        // e = a only arises in combination: no saved evaluation proposes a candidate, so no edge
        assertTrue(g.edges().stream().noneMatch(x -> x.target().equals(combo)), edges.toString());
        Map<?, ?> history = (Map<?, ?>) json(stage.resolve(PropagationStage.RESULT)).get("history");
        Map<?, ?> sharedAttribution = (Map<?, ?>) inputWarning(history, shared).get("attribution");
        assertEquals("UNATTRIBUTED", sharedAttribution.get("status"));
        assertEquals("NO_CANDIDATE", ((Map<?, ?>) inputWarning(history, combo).get("attribution")).get("status"));
        assertEquals(2L, history.get("attributedEdges"));
        // history, IDs and the occurrences are unchanged; only edges were added
        before.nodes().forEach(n -> assertEquals(n, g.node(n.id()).orElseThrow()));
        assertEquals(before.edges().size() + 2, g.edges().size());
        assertEquals(registryBefore, Files.readString(run.resolve(WarningRegistry.FILE)), "no ID allocated or changed");
        assertTrue(WarningIntegrity.check(run).ok(), WarningIntegrity.check(run).problems().toString());
        Map<?, ?> pipeline = json(run.resolve("pipeline.json"));
        assertTrue(String.valueOf(((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) pipeline.get("stages")).get("combined"))
                .get("artifacts")).get("attribution")).startsWith("attributions/"));
        assertOnlyWarningAndAnnotationNodesAndCausalEdges(g, Files.readString(graphFile));

        // repeated: identical graph and record
        String graphText = Files.readString(graphFile);
        assertEquals(ExitCode.COMPLETED, attribute(true), output());
        assertEquals(graphText, Files.readString(graphFile));
        assertEquals(ExitCode.COMPLETED, rebuild(stage), output());
        assertEquals(graphText, Files.readString(graphFile), "a graph-only rebuild keeps the evidence-backed edges");

        // a forged edge without verified evidence is caught by the integrity check
        Path record = run.resolve(e.provenance().get("attribution"));
        Files.writeString(record, Files.readString(record).replace("\"status\": \"VERIFIED\"",
                "\"status\": \"NOT_A_CONTRIBUTOR\""));
        assertTrue(WarningIntegrity.check(run).problems().stream().anyMatch(p -> p.contains("attribution edge")));
    }
}

