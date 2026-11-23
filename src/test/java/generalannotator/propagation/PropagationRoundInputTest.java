package generalannotator.propagation;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import generalannotator.evaluation.RoundApplication;
import generalannotator.evaluation.matching.AnchoredWarningMatcher;
import generalannotator.graph.AnnotationNode;
import generalannotator.graph.InitialWarningGraph;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.graph.WarningNode;
import generalannotator.report.Json;
import generalannotator.report.JsonParser;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Propagation reads the combined round-1 result (its annotated code and warnings) named in pipeline.json, keeps its
 * annotations and warning IDs, extends the earlier graph, refuses unusable results and stays single-pass.
 */
class PropagationRoundInputTest {

    static final String ASSIGN = NullabilityPropagation.ASSIGN;
    static final String RETURN = NullabilityPropagation.RETURN;

    /** The original program: nothing is @Nullable yet, so the original warnings offer nothing to propagate. */
    static final String SOURCE = """
            package p;

            /** Fixture. */
            public class A {
                Object a;
                Object b;
                Object c;
                @NonNull Object d;

                void init() {
                    b = a;
                    c = b;
                    d = a;
                    a.toString();
                }

                Object get() { return a; }

                String text() { return "x"; }
            }
            """;

    @TempDir Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Map<String, String> env = new HashMap<>();
    private Path run;
    private FakeChecker checker;
    private Path roundOne;
    private Path earlierGraph;

    /** Like NullAway: annotating a (round 1) makes b = a, d = a, a.toString() and return a warn. */
    static CheckerOutcome rule(FakeChecker.Source s) {
        List<Diagnostic> f = new ArrayList<>();
        if (!s.annotated("Object a;")) {
            f.add(s.at("Object a;", "@NonNull field 'a' not initialized"));
        } else {
            if (!s.annotated("Object b;")) {
                f.add(s.at("b = a;", ASSIGN));
            } else if (!s.annotated("Object c;")) {
                f.add(s.at("c = b;", ASSIGN)); // only once B is @Nullable: a later pass's business
            }
            f.add(s.at("d = a;", ASSIGN));
            f.add(s.at("a.toString()", "dereferenced expression 'a' is @Nullable"));
            if (!s.annotated("Object get()")) {
                f.add(s.at("return a;", RETURN));
            }
        }
        f.add(s.at("return \"x\"", "unrelated finding"));
        return new CompletedAnalysis(f, List.of());
    }

    /**
     * The original run, round 1 (an answer annotating a, evaluated and combined with the real components) and a
     * pipeline.json that records both, as {@code pipeline finish} leaves them.
     */
    private void roundOne() throws Exception {
        run = temp.resolve("runs/run1");
        Path snapshot = run.resolve("workspace/target");
        Path file = snapshot.resolve("src/p/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        Files.writeString(snapshot.resolve("src/p/Nullable.java"), "package p;\n\npublic @interface Nullable {}\n");
        Files.writeString(snapshot.resolve("src/p/NonNull.java"), "package p;\n\npublic @interface NonNull {}\n");
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
        checker = new FakeChecker(PropagationRoundInputTest::rule);

        String aInit = WarningGraphJson.load(run.resolve(WarningGraphJson.FILE_NAME)).warningNodes().stream()
                .filter(n -> n.diagnostic().message().startsWith("@NonNull field 'a'")).findFirst().orElseThrow().id();
        AnnotationEdit nullableA = new AnnotationEdit("src", "p/A.java", "p.A", MemberRef.parse("a"),
                AnnotationPosition.field(), EditAction.ADD, "p.Nullable");
        Proposal answer = new Proposal(new Proposal.ProposalSource("run1", "sub", aInit, 1, "prompt", run.resolve(
                "answer.json"), "answer"), Proposal.deduplicate(List.of(nullableA), List.of(new EditOrigin(aInit, 0,
                Optional.of("u")))), "because", "some");
        Candidates llm = Candidates.fromProposals("sub", "run1", run, snapshot, List.of("src"), List.of(answer),
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
        pipeline("COMPLETED");
    }

    private void pipeline(String combinedStatus) throws IOException {
        Map<String, Object> stages = new LinkedHashMap<>();
        stages.put("evaluation", Map.of("status", "COMPLETED", "artifacts", Map.of("graph",
                run.relativize(earlierGraph).toString())));
        stages.put("combined", Map.of("status", combinedStatus, "artifacts", Map.of("directory",
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

    private String output() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    private static Map<?, ?> json(Path file) throws IOException {
        return (Map<?, ?>) JsonParser.parse(Files.readString(file));
    }

    private List<Path> list(String dir) throws IOException {
        Path d = run.resolve(dir);
        if (!Files.isDirectory(d)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(d)) {
            return s.filter(p -> !p.getFileName().toString().startsWith(".")).sorted().toList();
        }
    }

    /** The round-1 combined result's ID of the warning whose message starts with {@code message} on {@code marker}. */
    private String roundId(String marker) throws IOException {
        int line = SOURCE.lines().toList().indexOf(SOURCE.lines().filter(l -> l.contains(marker)).findFirst()
                .orElseThrow()) + 1;
        return WarningGraphJson.load(roundOne.resolve(WarningGraphJson.FILE_NAME)).warningNodes().stream()
                .filter(n -> n.diagnostic().line() == line).findFirst().orElseThrow().id();
    }

    @Test
    void propagationUsesTheCombinedRoundsAnnotationsAndWarningsNotTheOriginalBaseline() throws Exception {
        roundOne();
        assertEquals(List.of(), NullabilityPropagation.analyze(run).matches(), "the original warnings offer nothing");
        int warnings = ((Number) json(roundOne.resolve("round.json")).get("findingCount")).intValue();

        assertEquals(ExitCode.COMPLETED, propagate(), output());

        assertTrue(output().contains("source round 1, state " + roundOne.getFileName() + ", " + warnings + " warning(s)"),
                output());
        assertTrue(output().contains("snapshot:   " + roundOne.resolve("workspace/target")), output());
        Path dir = list("propagation").get(0);
        Map<?, ?> r = json(dir.resolve(PropagationStage.RESULT));
        Map<?, ?> input = (Map<?, ?>) r.get("input");
        assertEquals(1L, input.get("sourceRound"));
        assertEquals(roundOne.getFileName().toString(), input.get("state"));
        assertEquals((long) warnings, input.get("warnings"));
        assertEquals(2L, r.get("round"), "introduced warnings belong to the next round");

        Map<?, ?> analysis = json(dir.resolve(PropagationStage.ANALYSIS));
        Map<String, String> matched = new HashMap<>();
        for (Object o : (List<?>) analysis.get("matches")) {
            Map<?, ?> m = (Map<?, ?>) o;
            matched.put((String) m.get("warningId"), (String) ((Map<?, ?>) m.get("edit")).get("member"));
        }
        assertEquals(Map.of(roundId("b = a;"), "b", roundId("return a;"), "get()"), matched,
                "candidates come from the warnings round 1 introduced, with their round IDs");
        Map<String, String> skipped = new HashMap<>();
        for (Object o : (List<?>) analysis.get("skipped")) {
            Map<?, ?> s = (Map<?, ?>) o;
            skipped.put((String) s.get("warningId"), (String) s.get("category"));
        }
        assertEquals("OTHER_CATEGORY", skipped.get(roundId("a.toString()")), "nullable dereferences stay ignored");
        assertEquals("EXPLICIT_CONTRACT", skipped.get(roundId("d = a;")));

        // Each package was evaluated on a copy of the annotated round-1 code, keeping round 1's annotation.
        Path evaluation = run.resolve((String) ((Map<?, ?>) r.get("evaluation")).get("directory"));
        String bPackage = packageFor(r, "#b");
        String attemptSource = Files.readString(evaluation.resolve("attempts/" + bPackage + "/source/src/p/A.java"));
        assertTrue(attemptSource.contains("@p.Nullable Object a;") && attemptSource.contains("@p.Nullable Object b;"),
                attemptSource);
        Map<?, ?> attempt = json(evaluation.resolve("attempts/" + bPackage + "/attempt.json"));
        assertEquals(List.of(roundId("b = a;")), attempt.get("resolved"), "baseline IDs are the round's IDs");
        assertEquals(roundOne.getFileName() + "+" + bPackage, attempt.get("resultState"));
    }

    private static String packageFor(Map<?, ?> record, String member) {
        return ((List<?>) record.get("packages")).stream().map(p -> (Map<?, ?>) p)
                .filter(p -> p.get("edits").toString().contains(member + " ")).findFirst().orElseThrow().get("id")
                .toString();
    }

    @Test
    void theGraphIsExtendedKeepingEarlierNodesEdgesAndIdsWithoutCollisions() throws Exception {
        roundOne();
        String earlierBefore = Files.readString(earlierGraph);
        String roundBefore = EvaluationFiles.treeHash(roundOne);
        WarningGraph earlier = WarningGraphJson.load(earlierGraph);

        assertEquals(ExitCode.COMPLETED, propagate(), output());

        Path dir = list("propagation").get(0);
        Map<?, ?> r = json(dir.resolve(PropagationStage.RESULT));
        WarningGraph g = WarningGraphJson.load(dir.resolve(PropagationStage.GRAPH));
        Set<String> ids = new HashSet<>();
        g.nodes().forEach(n -> assertTrue(ids.add(n.id()), "duplicate node " + n.id()));
        // earlier history: the answer package A0001, its resolution edge and the original baseline warnings
        AnnotationNode a0001 = (AnnotationNode) g.node("A0001").orElseThrow();
        assertEquals(earlier.node("A0001").orElseThrow(), a0001);
        earlier.edges().stream().filter(e -> e.kind().equals("resolved-by"))
                .forEach(e -> assertTrue(g.edges().contains(e), "kept: " + e));
        earlier.warningNodes().stream().filter(WarningNode::root)
                .forEach(w -> assertTrue(g.node(w.id()).isPresent(), "kept: " + w.id()));
        // attempt-introduced warnings and their edges are kept too, unchanged
        earlier.nodes().forEach(n -> assertEquals(n, g.node(n.id()).orElseThrow(), "kept: " + n.id()));
        earlier.edges().forEach(e -> assertTrue(g.edges().contains(e), "kept: " + e));
        // the round's b = a keeps its report ID as its own node; its correspondence to A0001's warning is metadata
        WarningNode bAssign = (WarningNode) g.node(roundId("b = a;")).orElseThrow();
        assertEquals(java.util.OptionalInt.of(1), bAssign.round());
        assertEquals(Optional.of(roundOne.getFileName().toString()), bAssign.state(), "the combined occurrence");
        Map<?, ?> history = (Map<?, ?>) r.get("history");
        assertTrue(history.get("inputWarnings").toString().contains("corresponds-to-package-warning"),
                history.toString());
        String bPackage = packageFor(r, "#b");
        assertTrue(bPackage.startsWith("P"));
        AnnotationNode p = (AnnotationNode) g.node(bPackage).orElseThrow();
        assertEquals(roundOne.getFileName().toString(), p.baselineState());
        assertEquals(java.util.OptionalInt.of(2), p.round());
        assertTrue(p.proposals().get(0).group().endsWith("@propagation-r1"));
        List<String> edges = g.edges().stream().map(e -> e.source() + " " + e.kind() + " " + e.target()).toList();
        assertTrue(edges.contains(roundId("b = a;") + " resolved-by " + bPackage), edges.toString());
        assertTrue(edges.contains(bPackage + " introduces W2001"), edges.toString());
        WarningNode introduced = (WarningNode) g.node("W2001").orElseThrow();
        assertEquals(java.util.OptionalInt.of(2), introduced.round());
        assertEquals(2, introduced.distance());
        assertEquals(earlierBefore, Files.readString(earlierGraph), "the earlier graph is not modified");
        assertEquals(roundBefore, EvaluationFiles.treeHash(roundOne), "the round-1 result is not modified");
    }

    @Test
    void theCombinedPropagationStateKeepsEarlierAnnotationsAndStaysSinglePass() throws Exception {
        roundOne();

        assertEquals(ExitCode.COMPLETED, propagate(), output());

        Path dir = list("propagation").get(0);
        Map<?, ?> r = json(dir.resolve(PropagationStage.RESULT));
        Map<?, ?> combined = (Map<?, ?>) r.get("combined");
        assertEquals("COMPLETED", combined.get("status"));
        Path round = run.resolve((String) combined.get("directory"));
        assertTrue(round.getFileName().toString().startsWith("round2-"), round.toString());
        String source = Files.readString(round.resolve("workspace/target/src/p/A.java"));
        for (String annotated : List.of("@p.Nullable Object a;", "@p.Nullable Object b;", "@p.Nullable Object get()")) {
            assertTrue(source.contains(annotated), annotated + " in\n" + source);
        }
        assertTrue(source.contains("    Object c;"), "single pass: c = b warns now, but C is not annotated");
        Map<?, ?> findings = json(round.resolve("findings.json"));
        WarningGraph layer = WarningGraphJson.load(run.resolve((String) ((Map<?, ?>) r.get("evaluation"))
                .get("directory")).resolve(Evaluator.GRAPH));
        Set<String> layerIds = new HashSet<>();
        layer.nodes().forEach(n -> layerIds.add(n.id()));
        boolean anyIntroduced = false;
        for (Object o : (List<?>) findings.get("findings")) {
            Map<?, ?> f = (Map<?, ?>) o;
            if (f.get("status").equals("INTRODUCED")) {
                anyIntroduced = true;
                String id = (String) f.get("warningId");
                assertTrue(id.startsWith("W2") && !layerIds.contains(id), id + " collides with " + layerIds);
            } else if (f.get("status").equals("PERSISTING")) {
                assertTrue(json(roundOne.resolve("findings.json")).toString().contains((String) f.get("warningId")),
                        "persisting warnings keep the round-1 IDs");
            }
        }
        assertTrue(anyIntroduced);
        assertTrue(Files.readString(round.resolve("changes.diff")).contains("+    @p.Nullable Object b;"));
        assertEquals(2, list("rounds").size(), "round 1 is kept beside the propagation result");
    }

    @Test
    void anUnavailableOrUnverifiableCombinedResultIsRefusedWithoutFallingBack() throws Exception {
        roundOne();

        pipeline("PENDING");
        assertEquals(ExitCode.EXECUTION_FAILURE, propagate(), output());
        assertTrue(output().contains("no completed combined round result") && output().contains("pipeline finish"),
                output());

        pipeline("COMPLETED");
        Path file = roundOne.resolve("workspace/target/src/p/A.java");
        String text = Files.readString(file);
        Files.writeString(file, text + "// changed\n");
        assertEquals(ExitCode.EXECUTION_FAILURE, propagate(), output());
        assertTrue(output().contains("no longer matches round.json"), output());
        Files.writeString(file, text);

        Path report = roundOne.resolve("report.json");
        String reportText = Files.readString(report);
        Files.writeString(report, reportText.replaceFirst("\"runId\": \"[^\"]*\"", "\"runId\": \"other\""));
        assertEquals(ExitCode.EXECUTION_FAILURE, propagate(), output());
        assertTrue(output().contains("do not belong to its round.json"), output());
        Files.writeString(report, reportText);

        Path findings = roundOne.resolve("findings.json");
        String findingsText = Files.readString(findings);
        Files.writeString(findings, findingsText.replace("\"complete\": true", "\"complete\": false"));
        assertEquals(ExitCode.EXECUTION_FAILURE, propagate(), output());
        assertTrue(output().contains("incomplete"), output());
        Files.writeString(findings, findingsText);

        assertEquals(List.of(), list("propagation"), "nothing was analyzed instead");
        assertEquals(ExitCode.COMPLETED, propagate(), output());
    }

    @Test
    void anEarlierResultForTheOriginalBaselineIsKeptButNotReused() throws Exception {
        roundOne();
        Path pipelineJson = run.resolve("pipeline.json");
        String pipeline = Files.readString(pipelineJson);
        Files.delete(pipelineJson); // a plain run: its own baseline, as the earlier propagation read it
        assertEquals(ExitCode.COMPLETED, propagate(), output());
        assertTrue(output().contains("the run's own baseline (round 0)"), output());
        Path old = list("propagation").get(0);
        assertEquals("NO_CANDIDATES", json(old.resolve(PropagationStage.RESULT)).get("outcome"));
        Files.writeString(pipelineJson, pipeline);

        assertEquals(ExitCode.COMPLETED, propagate(), output());

        List<Path> results = list("propagation");
        assertEquals(2, results.size(), "a new result keyed by the round-1 input");
        Path current = results.stream().filter(p -> !p.equals(old)).findFirst().orElseThrow();
        assertEquals("COMPLETED", json(current.resolve(PropagationStage.RESULT)).get("outcome"));
        assertEquals("NO_CANDIDATES", json(old.resolve(PropagationStage.RESULT)).get("outcome"), "kept as it was");
    }
}
