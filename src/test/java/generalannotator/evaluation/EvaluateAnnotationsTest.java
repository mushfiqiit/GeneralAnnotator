package generalannotator.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.checker.CheckerOutcome;
import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.checker.CheckerOutcome.ExecutionFailure;
import generalannotator.checker.FailureKind;
import generalannotator.cli.EvaluateAnnotationsCommand;
import generalannotator.cli.ExitCode;
import generalannotator.cli.PrepareInferenceCommandTest;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.edits.javaparser.JavaParserAnnotationApplier;
import generalannotator.evaluation.matching.AnchoredWarningMatcher;
import generalannotator.graph.AnnotationNode;
import generalannotator.graph.GraphEdge;
import generalannotator.graph.GraphNode;
import generalannotator.graph.InitialWarningGraph;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.graph.WarningNode;
import generalannotator.proposals.ProposalFixture;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code evaluate-annotations} end to end with a fake checker and the real AST applier: no NullAway,
 * no cluster, no model.
 */
class EvaluateAnnotationsTest {

    private static final Map<String, Object> FIELD = Map.of("kind", "FIELD");

    @TempDir Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Map<String, String> env = new HashMap<>();
    private int round = 1;

    private ProposalFixture fixture(int warnings) throws Exception {
        ProposalFixture fx = new ProposalFixture(temp, warnings);
        Files.createDirectories(temp.resolve("checker"));
        env.put("GA_CHECKER", "nullaway");
        env.put("GA_CHECKER_HOME", temp.resolve("checker").toString());
        return fx;
    }

    private ExitCode run(ProposalFixture fx, FakeChecker checker, String... warning) {
        out.reset();
        err.reset();
        return new EvaluateAnnotationsCommand(fx.submission.toString(),
                warning.length == 0 ? Optional.empty() : Optional.of(warning[0]), env, temp,
                Path.of(System.getProperty("java.home")), (c, e) -> checker, new JavaParserAnnotationApplier(),
                new AnchoredWarningMatcher(), round, PrepareInferenceCommandTest.CLOCK,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
                .run();
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String output() {
        return stdout() + err.toString(StandardCharsets.UTF_8);
    }

    private static Path evaluation(ProposalFixture fx) throws IOException {
        try (Stream<Path> s = Files.list(fx.runDir.resolve("evaluations"))) {
            List<Path> dirs = s.toList();
            assertEquals(1, dirs.size(), dirs.toString());
            return dirs.get(0);
        }
    }

    private static Map<?, ?> json(Path file) throws IOException {
        return (Map<?, ?>) JsonParser.parse(Files.readString(file));
    }

    private static WarningGraph graph(ProposalFixture fx) throws IOException {
        return WarningGraphJson.load(evaluation(fx).resolve(Evaluator.GRAPH));
    }

    private static AnnotationNode annotation(WarningGraph g, String id) {
        return (AnnotationNode) g.node(id).orElseThrow();
    }

    private static List<String> edges(WarningGraph g) {
        return g.edges().stream().map(e -> e.source() + " -" + e.kind() + "-> " + e.target()).toList();
    }

    private static Map<String, Object> edit(String member, String action) {
        return ProposalFixture.editJson(member, FIELD, action);
    }

    /** W0001 proposes x; W0002 proposes x and y together; W0003 failed. */
    private ProposalFixture standard() throws Exception {
        ProposalFixture fx = fixture(3);
        fx.completed("W0001", ProposalFixture.answer("W0001", "PROPOSED", List.of(edit("x", "ADD"))));
        fx.completed("W0002", ProposalFixture.answer("W0002", "PROPOSED", List.of(edit("x", "ADD"),
                edit("y", "ADD"))));
        fx.failed("W0003", "STARTUP_TIMEOUT");
        fx.collection();
        return fx;
    }

    private static String treeHash(Path dir) throws IOException {
        return EvaluationFiles.treeHash(dir);
    }

    @Test
    void eachAnswerIsOnePackageEvenWhenPackagesShareEdits() throws Exception {
        ProposalFixture fx = standard();
        FakeChecker checker = new FakeChecker(FakeChecker::nullness);

        assertEquals(ExitCode.COMPLETED, run(fx, checker), output());

        Path dir = evaluation(fx);
        Map<?, ?> candidates = json(dir.resolve(Candidates.FILE));
        assertEquals(Candidates.MODEL, candidates.get("candidateModel"));
        List<?> cs = (List<?>) candidates.get("candidates");
        assertEquals(2, cs.size(), "one package per answer; the shared x edit is not merged across answers");
        Map<?, ?> a1 = (Map<?, ?>) cs.get(0);
        assertEquals("A0001", a1.get("id"));
        assertEquals("W0001", a1.get("warningId"));
        assertEquals(1, ((List<?>) a1.get("edits")).size());
        Map<?, ?> a2 = (Map<?, ?>) cs.get(1);
        assertEquals("W0002@1", a2.get("group"));
        assertEquals(2, ((List<?>) a2.get("edits")).size());
        assertEquals(List.of(1L), ((Map<?, ?>) ((List<?>) a2.get("edits")).get(1)).get("answerEditIndices"));
        assertEquals("because", ((Map<?, ?>) a2.get("proposal")).get("rationale"));
        assertEquals("some", ((Map<?, ?>) a2.get("proposal")).get("uncertainty"));
        Map<?, ?> excluded = (Map<?, ?>) ((List<?>) candidates.get("excluded")).get(0);
        assertEquals("W0003", excluded.get("warningId"));
        assertEquals("FAILED", excluded.get("status"));

        // A0001 (x): resolves W0001 and introduces the return finding; the total stays 2.
        Map<?, ?> r1 = json(dir.resolve("attempts/A0001/attempt.json"));
        assertEquals("COMPLETED", r1.get("outcome"));
        assertEquals(List.of("W0001"), r1.get("resolved"));
        assertEquals(Map.of("baselineFindings", 2L, "resultFindings", 2L, "resolved", 1L, "persisting", 1L,
                "introduced", 1L, "ambiguousBaseline", 0L, "ambiguousResult", 0L), r1.get("counts"));
        // A0002 (x and y together): resolves both and introduces the return finding.
        Map<?, ?> r2 = json(dir.resolve("attempts/A0002/attempt.json"));
        assertEquals(List.of("W0001", "W0002"), r2.get("resolved"));
        assertEquals(2L, r2.get("appliedEdits"));
        assertEquals("APPLIED", ((Map<?, ?>) ((Map<?, ?>) ((List<?>) r2.get("edits")).get(1)).get("applier"))
                .get("outcome"));

        WarningGraph g = graph(fx);
        assertEquals(List.of("W0001 -resolved-by-> A0001", "A0001 -introduces-> W1001",
                "W0001 -resolved-by-> A0002", "W0002 -resolved-by-> A0002", "A0002 -introduces-> W1002"), edges(g));
        WarningNode n1 = (WarningNode) g.node("W1001").orElseThrow();
        assertFalse(n1.root());
        assertEquals(1, n1.distance());
        assertEquals(Optional.of("S0+A0001"), n1.state());
        assertTrue(n1.diagnostic().message().startsWith("returning @Nullable"));
        assertEquals(Optional.of("S0"), ((WarningNode) g.node("W0001").orElseThrow()).state());
        AnnotationNode p2 = annotation(g, "A0002");
        assertEquals(2, p2.edits().size());
        assertEquals(List.of("W0002"), p2.proposals().stream().map(AnnotationNode.Proposal::warningId).toList());

        assertTrue(stdout().contains("A0001  COMPLETED  findings 2 -> 2 (0); resolved 1, introduced 1, persisting 1"),
                stdout());
        assertTrue(stdout().contains("A0002  package of 2 edit(s) proposed for W0002 (W0002@1):"), stdout());
        assertTrue(stdout().contains("A0002  COMPLETED  findings 2 -> 1 (-1); resolved 2, introduced 1"), stdout());
        assertTrue(stdout().contains("excluded W0003 (FAILED)"), stdout());
        assertTrue(stdout().contains("1 package(s) reduced the finding count"), stdout());

        // One baseline plus one checker run per package, always over every source root.
        assertEquals(3, checker.calls());
        checker.requests.forEach(r -> assertEquals(List.of(Path.of("src")), r.sourceRoots()));
        // Packages do not accumulate: A0001's workspace has x only, A0002's both edits.
        String a1Source = Files.readString(dir.resolve("attempts/A0001/source/src/p/A.java"));
        assertTrue(a1Source.contains("@org.jspecify.annotations.Nullable Object x;"), a1Source);
        assertTrue(a1Source.contains("    Object y;"), a1Source);
        String a2Source = Files.readString(dir.resolve("attempts/A0002/source/src/p/A.java"));
        assertTrue(a2Source.contains("@org.jspecify.annotations.Nullable Object y;"), a2Source);
        assertTrue(a2Source.contains("@org.jspecify.annotations.Nullable Object x;"), a2Source);
        assertEquals(ProposalFixture.SOURCE, Files.readString(dir.resolve("baseline/source/src/p/A.java")));
        assertTrue(Files.readString(dir.resolve("attempts/A0002/changes.diff")).contains("+    @org.jspecify"));
    }

    @Test
    void linksEveryActuallyResolvedWarningAndKeepsProposalsSeparate() throws Exception {
        ProposalFixture fx = fixture(1);
        // The answer for W0001 (the x warning) proposes an edit to y, which resolves W0002 instead.
        fx.completed("W0001", ProposalFixture.answer("W0001", "PROPOSED", List.of(edit("y", "ADD"))));
        fx.collection();

        assertEquals(ExitCode.COMPLETED, run(fx, new FakeChecker(FakeChecker::nullness)), output());

        WarningGraph g = graph(fx);
        assertEquals(List.of("W0002 -resolved-by-> A0001"), edges(g));
        assertEquals("W0001", annotation(g, "A0001").proposals().get(0).warningId());
    }

    @Test
    void oneEditCanResolveManyAndIntroduceMany() throws Exception {
        ProposalFixture fx = fixture(1);
        fx.completed("W0001", ProposalFixture.answer("W0001", "PROPOSED", List.of(
                ProposalFixture.editJson("get()", Map.of("kind", "RETURN"), "ADD"))));
        fx.collection();
        FakeChecker checker = new FakeChecker(s -> {
            if (!s.annotated("Object get()")) {
                return FakeChecker.nullness(s);
            }
            return new CompletedAnalysis(List.of(s.at("Object x;", "dereferenced expression is @Nullable"),
                    s.at("return x", "returning @Nullable from a @NonNull caller")), List.of());
        });

        assertEquals(ExitCode.COMPLETED, run(fx, checker), output());

        WarningGraph g = graph(fx);
        assertEquals(List.of("W0001 -resolved-by-> A0001", "W0002 -resolved-by-> A0001",
                "A0001 -introduces-> W1001", "A0001 -introduces-> W1002"), edges(g));
        assertEquals(2, annotation(g, "A0001").counts().orElseThrow().introduced());
    }

    @Test
    void anEditThatChangesNothingKeepsAnEdgeFreeNodeWithAnExplicitOutcome() throws Exception {
        ProposalFixture fx = fixture(1);
        fx.completed("W0001", ProposalFixture.answer("W0001", "PROPOSED", List.of(edit("x", "ADD"))));
        fx.collection();
        FakeChecker checker = new FakeChecker(s -> new CompletedAnalysis(List.of(s.at("x;", "@NonNull field 'x'"),
                s.at("y;", "@NonNull field 'y'")), List.of()));

        assertEquals(ExitCode.COMPLETED, run(fx, checker), output());

        WarningGraph g = graph(fx);
        assertEquals(List.of(), g.edges());
        AnnotationNode a = annotation(g, "A0001");
        assertEquals("COMPLETED", a.outcome());
        assertTrue(a.detail().contains("no finding changed"), a.detail());
        assertEquals(2, a.counts().orElseThrow().persisting());
        assertTrue(stdout().contains("0 package(s) reduced the finding count"), stdout());
    }

    /** Overwrites the snapshot file (test-owned) with a method that repeats one statement. */
    private static void repeatedSource(ProposalFixture fx) throws IOException {
        Files.writeString(fx.runDir.resolve("workspace/target/src/p/A.java"), """
                package p;

                /** Fixture class. */
                public class A {
                    Object x; // x
                    Object y;

                    Object get() {
                        use(x);
                        use(x);
                        return x;
                    }

                    void use(Object o) {}
                }
                """);
    }

    @Test
    void repeatedSimilarDiagnosticsArePairedByTheirPositionInTheDeclaration() throws Exception {
        ProposalFixture fx = fixture(1);
        repeatedSource(fx);
        fx.completed("W0001", ProposalFixture.answer("W0001", "PROPOSED", List.of(edit("x", "ADD"))));
        fx.collection();
        FakeChecker checker = new FakeChecker(s -> {
            List<Diagnostic> uses = s.all("use(x);", "passing @Nullable parameter");
            return new CompletedAnalysis(s.annotated("Object x;") ? uses.subList(1, 2) : uses, List.of());
        });

        assertEquals(ExitCode.COMPLETED, run(fx, checker), output());

        Path dir = evaluation(fx);
        Map<?, ?> r = json(dir.resolve("attempts/A0001/attempt.json"));
        assertEquals("COMPLETED", r.get("outcome"));
        assertEquals(List.of("W0001"), r.get("resolved"), "the first use (line 9) was resolved");
        Map<?, ?> cmp = json(dir.resolve("attempts/A0001/comparison.json"));
        assertEquals(List.of(Map.of("baseline", "W0002", "result", "R001")), cmp.get("persisting"));
    }

    @Test
    void indistinguishableRepeatedDiagnosticsAreAmbiguousAndSupportNoClaims() throws Exception {
        ProposalFixture fx = fixture(1);
        fx.completed("W0001", ProposalFixture.answer("W0001", "PROPOSED", List.of(edit("x", "ADD"))));
        fx.collection();
        // Two identical findings at one position before the edit, one after.
        FakeChecker checker = new FakeChecker(s -> {
            Diagnostic d = s.at("return x", "unboxing a @Nullable value");
            return new CompletedAnalysis(s.annotated("Object x;") ? List.of(d) : List.of(d, d), List.of());
        });

        assertEquals(ExitCode.COMPLETED, run(fx, checker), output());

        WarningGraph g = graph(fx);
        AnnotationNode a = annotation(g, "A0001");
        assertEquals("AMBIGUOUS", a.outcome());
        assertEquals(List.of(), g.edges());
        assertEquals(2, a.counts().orElseThrow().ambiguousBaseline());
        assertEquals(1, a.counts().orElseThrow().ambiguousResult());
        assertEquals(0, a.counts().orElseThrow().resolved());
        assertTrue(stdout().contains("A0001  AMBIGUOUS  findings 2 -> 1 (-1); resolved 0, introduced 0, persisting 0, "
                + "ambiguous 2/1"), stdout());
    }

    @Test
    void aFindingOnTheEditedLineWhoseColumnShiftsStillPersists() throws Exception {
        ProposalFixture fx = fixture(1);
        fx.completed("W0001", ProposalFixture.answer("W0001", "PROPOSED", List.of(edit("x", "ADD"))));
        fx.collection();
        FakeChecker checker = new FakeChecker(s -> new CompletedAnalysis(List.of(s.at("// x", "comment finding")),
                List.of()));

        assertEquals(ExitCode.COMPLETED, run(fx, checker), output());

        Map<?, ?> cmp = json(evaluation(fx).resolve("attempts/A0001/comparison.json"));
        assertEquals(List.of(Map.of("baseline", "W0001", "result", "R001")), cmp.get("persisting"));
        Map<?, ?> later = (Map<?, ?>) ((Map<?, ?>) ((List<?>) cmp.get("resultFindings")).get(0)).get("diagnostic");
        assertEquals(5L, later.get("line"));
        assertTrue((Long) later.get("column") > 14L, "the column shifted by the inserted annotation");
    }

    @Test
    void noOpRejectedAndSuppressingEditsAreRecordedWithoutRunningTheChecker() throws Exception {
        ProposalFixture fx = fixture(3);
        fx.completed("W0001", ProposalFixture.answer("W0001", "PROPOSED", List.of(edit("x", "REMOVE"))));
        fx.completed("W0002", ProposalFixture.answer("W0002", "PROPOSED", List.of(edit("missing", "ADD"))));
        Map<String, Object> suppress = edit("y", "ADD");
        suppress.put("annotation", "java.lang.SuppressWarnings");
        fx.completed("W0003", ProposalFixture.answer("W0003", "PROPOSED", List.of(suppress)));
        fx.collection();
        FakeChecker checker = new FakeChecker(FakeChecker::nullness);

        assertEquals(ExitCode.COMPLETED, run(fx, checker), output());

        assertEquals(1, checker.calls(), "only the baseline was checked");
        WarningGraph g = graph(fx);
        assertEquals("NO_OP", annotation(g, "A0001").outcome());
        assertEquals("REJECTED", annotation(g, "A0002").outcome());
        assertTrue(annotation(g, "A0002").detail().contains("package rejected, nothing applied"));
        assertEquals("REJECTED", annotation(g, "A0003").outcome());
        assertTrue(annotation(g, "A0003").detail().contains("suppresses or disables checking"));
        assertEquals(List.of(), g.edges());
        Path dir = evaluation(fx);
        for (String id : List.of("A0001", "A0002", "A0003")) {
            assertFalse(Files.exists(dir.resolve("attempts/" + id + "/source")), id);
            assertTrue(Files.exists(dir.resolve("attempts/" + id + "/attempt.json")), id);
        }
    }

    @Test
    void executionFailuresAreOutcomesNeverZeroWarnings() throws Exception {
        ProposalFixture fx = standard();
        FakeChecker checker = new FakeChecker(s -> {
            if (s.annotated("Object y;")) {
                return new ExecutionFailure(FailureKind.COMPILATION_FAILED, List.of("cannot find symbol Nullable"),
                        List.of(), List.of());
            }
            if (s.annotated("Object x;")) {
                return ExecutionFailure.before(FailureKind.TIMED_OUT, "javac timed out");
            }
            return FakeChecker.nullness(s);
        });

        assertEquals(ExitCode.COMPLETED, run(fx, checker), output());

        WarningGraph g = graph(fx);
        assertEquals("TIMED_OUT", annotation(g, "A0001").outcome());
        assertEquals("COMPILATION_FAILED", annotation(g, "A0002").outcome());
        assertTrue(annotation(g, "A0002").counts().isEmpty());
        assertEquals(List.of(), g.edges());
        assertFalse(stdout().contains("findings 2 ->"), stdout());
        assertTrue(stdout().contains("0 package(s) reduced the finding count"), stdout());
        Map<?, ?> r = json(evaluation(fx).resolve("attempts/A0002/attempt.json"));
        assertEquals("COMPILATION_FAILED", ((Map<?, ?>) r.get("failure")).get("kind"));
        assertEquals(null, r.get("counts"));
    }

    @Test
    void aFailedBaselineBlocksEvaluationAndIsRetriedLater() throws Exception {
        ProposalFixture fx = standard();
        FakeChecker broken = new FakeChecker(s -> ExecutionFailure.before(FailureKind.CRASHED, "javac crashed"));

        assertEquals(ExitCode.EXECUTION_FAILURE, run(fx, broken), output());

        Path dir = evaluation(fx);
        assertEquals(1, broken.calls());
        assertFalse(Files.exists(dir.resolve("attempts")));
        assertFalse(Files.exists(dir.resolve(Evaluator.GRAPH)));
        assertFalse(Files.exists(dir.resolve("baseline")));
        try (Stream<Path> failed = Files.list(dir.resolve("baseline-failed"))) {
            Path f = failed.findFirst().orElseThrow();
            assertEquals("CRASHED", json(f.resolve("failure.json")).get("failureKind"));
        }
        assertTrue(stdout().contains("Evaluation blocked"), stdout());

        FakeChecker working = new FakeChecker(FakeChecker::nullness);
        assertEquals(ExitCode.COMPLETED, run(fx, working), output());
        assertEquals(3, working.calls());
    }

    @Test
    void interruptedAttemptsAreMovedAsideAndRerunWhileCompleteOnesAreReused() throws Exception {
        ProposalFixture fx = standard();
        AtomicBoolean crash = new AtomicBoolean(true);
        FakeChecker crashing = new FakeChecker(s -> {
            if (s.annotated("Object y;") && crash.getAndSet(false)) {
                throw new IllegalStateException("killed");
            }
            return FakeChecker.nullness(s);
        });
        assertThrows(IllegalStateException.class, () -> run(fx, crashing));
        Path dir = evaluation(fx);
        assertTrue(Files.exists(dir.resolve("attempts/A0001/attempt.json")));
        assertTrue(Files.isDirectory(dir.resolve("attempts/A0002")));
        assertFalse(Files.exists(dir.resolve("attempts/A0002/attempt.json")));

        // Evaluating only W0001's candidate leaves A0002 explicitly interrupted in the graph.
        FakeChecker second = new FakeChecker(FakeChecker::nullness);
        assertEquals(ExitCode.COMPLETED, run(fx, second, "W0001"), output());
        assertEquals(0, second.calls(), "baseline and A0001 are reused");
        assertEquals("INTERRUPTED", annotation(graph(fx), "A0002").outcome());

        FakeChecker third = new FakeChecker(FakeChecker::nullness);
        assertEquals(ExitCode.COMPLETED, run(fx, third), output());
        assertEquals(1, third.calls(), "only A0002 is checked again");
        assertTrue(stdout().contains("rerun after an interrupted attempt (kept in attempts/interrupted/A0002-1)"),
                stdout());
        assertTrue(stdout().contains("[reused]"), stdout());
        assertTrue(Files.isDirectory(dir.resolve("attempts/interrupted/A0002-1")));
        WarningGraph g = graph(fx);
        assertEquals("COMPLETED", annotation(g, "A0002").outcome());
        assertEquals(5, g.edges().size());
    }

    @Test
    void resumingReusesEverythingAndDuplicatesNothing() throws Exception {
        ProposalFixture fx = standard();
        assertEquals(ExitCode.COMPLETED, run(fx, new FakeChecker(FakeChecker::nullness)), output());
        String graphBefore = Files.readString(evaluation(fx).resolve(Evaluator.GRAPH));

        FakeChecker again = new FakeChecker(FakeChecker::nullness);
        assertEquals(ExitCode.COMPLETED, run(fx, again), output());

        assertEquals(0, again.calls());
        assertEquals(graphBefore, Files.readString(evaluation(fx).resolve(Evaluator.GRAPH)));
        assertTrue(stdout().contains("baseline:   reused"), stdout());
    }

    @Test
    @SuppressWarnings("try")
    void aSecondWriterIsRefusedWhileTheLockIsHeld() throws Exception {
        ProposalFixture fx = standard();
        assertEquals(ExitCode.COMPLETED, run(fx, new FakeChecker(FakeChecker::nullness)), output());
        Path dir = evaluation(fx);
        try (EvaluationFiles.Lock held = EvaluationFiles.lock(dir, "test holder")) {
            FakeChecker checker = new FakeChecker(FakeChecker::nullness);
            assertEquals(ExitCode.EXECUTION_FAILURE, run(fx, checker), output());
            assertEquals(0, checker.calls());
            assertTrue(output().contains("another GeneralAnnotator process is writing"), output());
        }
    }

    @Test
    void theWarningFilterSelectsCandidatesButNeverNarrowsTheCheck() throws Exception {
        ProposalFixture fx = standard();
        FakeChecker checker = new FakeChecker(FakeChecker::nullness);

        assertEquals(ExitCode.COMPLETED, run(fx, checker, "W0001"), output());

        assertEquals(2, checker.calls(), "baseline and A0001 only");
        checker.requests.forEach(r -> assertEquals(List.of(Path.of("src")), r.sourceRoots()));
        WarningGraph g = graph(fx);
        assertEquals("UNEVALUATED", annotation(g, "A0002").outcome());
        assertEquals(2, annotation(g, "A0001").counts().orElseThrow().baselineFindings());

        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(fx, checker, "W0042"), output());
        assertEquals(ExitCode.EXECUTION_FAILURE, run(fx, checker, "W0003"), output());
        assertTrue(stdout().contains("yielded no annotation package"), stdout());
    }

    @Test
    void sourcesUnderAnIgnoredRunsDirectoryOfAParentGitRepositoryAreCopiedAndOriginalsStayUnchanged()
            throws Exception {
        Files.createDirectories(temp.resolve(".git"));
        Files.writeString(temp.resolve(".gitignore"), "runs/\n");
        ProposalFixture fx = standard();
        Path snapshot = fx.runDir.resolve("workspace/target");
        String snapshotBefore = treeHash(snapshot);
        String inferenceBefore = treeHash(fx.submission);
        byte[] graphBefore = Files.readAllBytes(fx.runDir.resolve("warning-graph.json"));
        FakeChecker checker = new FakeChecker(FakeChecker::nullness);

        assertEquals(ExitCode.COMPLETED, run(fx, checker), output());

        Path dir = evaluation(fx);
        assertEquals(ProposalFixture.SOURCE, Files.readString(dir.resolve("baseline/source/src/p/A.java")));
        assertTrue(checker.requests.get(0).workingCopyRoot().startsWith(dir.resolve("baseline")));
        assertEquals(snapshotBefore, treeHash(snapshot));
        assertEquals(inferenceBefore, treeHash(fx.submission));
        assertTrue(java.util.Arrays.equals(graphBefore, Files.readAllBytes(fx.runDir.resolve("warning-graph.json"))));
    }

    @Test
    void anotherConfigurationGetsItsOwnEvaluationAndBaseline() throws Exception {
        ProposalFixture fx = standard();
        assertEquals(ExitCode.COMPLETED, run(fx, new FakeChecker(FakeChecker::nullness)), output());
        Path jar = Files.writeString(temp.resolve("jspecify.jar"), "not really a jar");
        env.put("GA_TARGET_CLASSPATH", jar.toString());
        FakeChecker checker = new FakeChecker(FakeChecker::nullness);

        assertEquals(ExitCode.COMPLETED, run(fx, checker), output());

        assertEquals(3, checker.calls(), "a new baseline and both candidates");
        try (Stream<Path> s = Files.list(fx.runDir.resolve("evaluations"))) {
            assertEquals(2, s.count());
        }
        assertEquals(List.of(jar), checker.requests.get(0).classpath());
    }

    @Test
    void aDifferentCheckerBuildMakesTheAttemptNotComparable() throws Exception {
        ProposalFixture fx = fixture(1);
        fx.completed("W0001", ProposalFixture.answer("W0001", "PROPOSED", List.of(edit("x", "ADD"))));
        fx.collection();
        FakeChecker checker = new FakeChecker(FakeChecker::nullness);
        checker.details = n -> Map.of("nullawayVersion", n == 1 ? "0.12.1" : "0.12.2");

        assertEquals(ExitCode.COMPLETED, run(fx, checker), output());

        AnnotationNode a = annotation(graph(fx), "A0001");
        assertEquals("NOT_COMPARABLE", a.outcome());
        assertTrue(a.detail().contains("version"), a.detail());
        assertTrue(a.counts().isEmpty());
    }

    @Test
    void baselineIdsMatchTheRunGraphWhenTheFindingsAreReproduced() throws Exception {
        ProposalFixture fx = standard();
        Path snapshot = fx.runDir.resolve("workspace/target");
        Path file = snapshot.resolve("src/p/A.java");
        CheckerOutcome same = FakeChecker.nullness(new FakeChecker.Source(snapshot, file, Files.readString(file)));
        WarningGraphJson.save(InitialWarningGraph.build("run 1", "nullaway", (CompletedAnalysis) same, snapshot),
                fx.runDir.resolve("warning-graph.json"));

        assertEquals(ExitCode.COMPLETED, run(fx, new FakeChecker(FakeChecker::nullness)), output());

        Map<?, ?> summary = json(evaluation(fx).resolve(Evaluator.SUMMARY));
        assertEquals(true, ((Map<?, ?>) summary.get("runWarningGraph")).get("identicalToRunWarningGraph"));
        assertEquals(Optional.of("W0001"), annotation(graph(fx), "A0001").proposals().get(0).baselineWarningId());
    }

    @Test
    void theGraphRoundTripsAndRendersBothNodeKinds() throws Exception {
        ProposalFixture fx = standard();
        assertEquals(ExitCode.COMPLETED, run(fx, new FakeChecker(FakeChecker::nullness)), output());
        String text = Files.readString(evaluation(fx).resolve(Evaluator.GRAPH));
        WarningGraph g = WarningGraphJson.read(text);

        assertEquals(text, WarningGraphJson.write(g));
        assertEquals(4L, json(evaluation(fx).resolve(Evaluator.GRAPH)).get("schemaVersion"));
        String dot = DotExporter.toDot(g);
        assertTrue(dot.contains("\"A0001\" [id=\"A0001\", shape=ellipse"), dot);
        assertTrue(dot.contains("\"W1001\" [id=\"W1001\", label=\"W1001\""), dot);
        assertTrue(dot.contains("state S0+A0001, introduced in round 1"), dot);
        assertTrue(dot.contains("style=\"rounded,filled,dashed\""), dot);
        assertTrue(dot.contains("\"W0001\" -> \"A0001\" [label=\"resolved-by\", color=\"#2e7d32\""), dot);
        assertTrue(dot.contains("\"A0001\" -> \"W1001\" [label=\"introduces\", color=\"#c62828\""), dot);
        assertTrue(dot.contains("Evaluation graph: run run 1"), dot);
        assertTrue(dot.contains("label=\"A0002\\nCOMPLETED\\n2 edits\""), dot);
        assertTrue(dot.contains("A0002: package of 2 edit(s) proposed for W0002 (W0002@1)\\n  ADD @org.jspecify"
                + ".annotations.Nullable at FIELD of p.A#x in src/p/A.java [APPLIED]\\n  ADD @org.jspecify.annotations"
                + ".Nullable at FIELD of p.A#y in src/p/A.java [APPLIED]"), dot);
        List<String> kinds = new ArrayList<>();
        for (GraphNode n : g.nodes()) {
            kinds.add(n.kind());
        }
        assertEquals(List.of("warning", "warning", "annotation", "warning", "annotation", "warning"), kinds);
        assertTrue(g.edges().stream().allMatch(e -> e.kind().equals(GraphEdge.RESOLVED_BY)
                || e.kind().equals(GraphEdge.INTRODUCES)));
    }
}
