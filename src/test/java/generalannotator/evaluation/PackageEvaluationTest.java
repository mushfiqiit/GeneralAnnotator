package generalannotator.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.checker.CheckerOutcome;
import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.cli.EvaluateAnnotationsCommand;
import generalannotator.cli.ExitCode;
import generalannotator.cli.PrepareInferenceCommandTest;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.edits.javaparser.JavaParserAnnotationApplier;
import generalannotator.evaluation.matching.AnchoredWarningMatcher;
import generalannotator.graph.AnnotationNode;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.proposals.ProposalFixture;
import generalannotator.report.JsonParser;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One answer = one annotation package: applied together, all or nothing, checked once, one node. */
class PackageEvaluationTest {

    private static final Map<String, Object> FIELD = Map.of("kind", "FIELD");

    /** Like EventBus' AsyncExecutor.Builder: three fields one constructor leaves uninitialized. */
    private static final String BUILDER = """
            package p;

            /** Fixture class. */
            public class A {
                Object x; // x
                Object y;
                Object z;

                A() {}

                Object get() { return x; }
            }
            """;

    @TempDir Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Map<String, String> env = new HashMap<>();

    private ProposalFixture fixture(String source) throws Exception {
        ProposalFixture fx = new ProposalFixture(temp, 1);
        Files.writeString(fx.runDir.resolve("workspace/target/src/p/A.java"), source);
        Files.createDirectories(temp.resolve("checker"));
        env.put("GA_CHECKER", "nullaway");
        env.put("GA_CHECKER_HOME", temp.resolve("checker").toString());
        return fx;
    }

    private void answer(ProposalFixture fx, List<Map<String, Object>> edits) throws IOException {
        fx.completed("W0001", ProposalFixture.answer("W0001", "PROPOSED", edits));
        fx.collection();
    }

    private static Map<String, Object> edit(String member, String action) {
        return ProposalFixture.editJson(member, FIELD, action);
    }

    private static Map<String, Object> inB(String member) {
        Map<String, Object> e = edit(member, "ADD");
        e.put("file", "p/B.java");
        e.put("declaringClass", "p.B");
        return e;
    }

    /** One message naming every field not yet annotated, as NullAway reports W0037; annotating all three
     * removes it and makes {@code return x} a new finding. */
    static CheckerOutcome initializer(FakeChecker.Source s) {
        List<String> missing = new ArrayList<>();
        for (String f : List.of("x", "y", "z")) {
            if (!s.annotated("Object " + f + ";")) {
                missing.add("'" + f + "'");
            }
        }
        List<Diagnostic> findings = new ArrayList<>();
        if (!missing.isEmpty()) {
            findings.add(s.at("A() {}", "initializer method does not guarantee @NonNull fields "
                    + String.join(", ", missing) + " are initialized"));
        } else {
            findings.add(s.at("return x", "returning @Nullable expression from method with @NonNull return type"));
        }
        return new CompletedAnalysis(findings, List.of());
    }

    private ExitCode run(ProposalFixture fx, FakeChecker checker) {
        out.reset();
        err.reset();
        return new EvaluateAnnotationsCommand(fx.submission.toString(), Optional.empty(), env, temp,
                Path.of(System.getProperty("java.home")), (c, e) -> checker, new JavaParserAnnotationApplier(),
                new AnchoredWarningMatcher(), 1, PrepareInferenceCommandTest.CLOCK,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
                .run();
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    private static Path evaluation(ProposalFixture fx) throws IOException {
        try (Stream<Path> s = Files.list(fx.runDir.resolve("evaluations"))) {
            return s.findFirst().orElseThrow();
        }
    }

    private static Map<?, ?> attempt(ProposalFixture fx) throws IOException {
        return (Map<?, ?>) JsonParser.parse(Files.readString(evaluation(fx).resolve("attempts/A0001/attempt.json")));
    }

    private static WarningGraph graph(ProposalFixture fx) throws IOException {
        return WarningGraphJson.load(evaluation(fx).resolve(Evaluator.GRAPH));
    }

    private static List<String> edgeList(WarningGraph g) {
        return g.edges().stream().map(e -> e.source() + " -" + e.kind() + "-> " + e.target()).toList();
    }

    private static List<String> applierOutcomes(Map<?, ?> attempt) {
        return ((List<?>) attempt.get("edits")).stream()
                .map(e -> (String) ((Map<?, ?>) ((Map<?, ?>) e).get("applier")).get("outcome")).toList();
    }

    @Test
    void threeEditsOfOneAnswerAreOnePackageOneWorkspaceOneCheckAndOneNode() throws Exception {
        ProposalFixture fx = fixture(BUILDER);
        answer(fx, List.of(edit("x", "ADD"), edit("y", "ADD"), edit("z", "ADD")));
        FakeChecker checker = new FakeChecker(PackageEvaluationTest::initializer);

        assertEquals(ExitCode.COMPLETED, run(fx, checker), output());

        assertEquals(2, checker.calls(), "the baseline and one run for the whole package");
        Path dir = evaluation(fx);
        try (Stream<Path> attempts = Files.list(dir.resolve("attempts"))) {
            assertEquals(List.of("A0001"), attempts.map(p -> p.getFileName().toString()).toList());
        }
        String source = Files.readString(dir.resolve("attempts/A0001/source/src/p/A.java"));
        for (String f : List.of("x", "y", "z")) {
            assertTrue(source.contains("@org.jspecify.annotations.Nullable Object " + f + ";"), source);
        }
        String diff = Files.readString(dir.resolve("attempts/A0001/changes.diff"));
        assertEquals(3, diff.lines().filter(l -> l.startsWith("+    @org.jspecify")).count(), diff);
        Map<?, ?> r = attempt(fx);
        assertEquals("COMPLETED", r.get("outcome"));
        assertEquals(List.of("W0001"), r.get("resolved"), "the initializer warning is resolved by the package");
        assertEquals(List.of("APPLIED", "APPLIED", "APPLIED"), applierOutcomes(r));

        WarningGraph g = graph(fx);
        List<AnnotationNode> nodes = g.nodes().stream().filter(AnnotationNode.class::isInstance)
                .map(AnnotationNode.class::cast).toList();
        assertEquals(1, nodes.size());
        AnnotationNode a = nodes.get(0);
        assertEquals(3, a.edits().size());
        assertEquals(List.of("APPLIED", "APPLIED", "APPLIED"),
                a.edits().stream().map(e -> e.applierOutcome().orElseThrow()).toList());
        assertEquals("package of 3 edit(s) proposed for W0001 (W0001@1)", a.canonical());
        assertEquals(List.of("W0001 -resolved-by-> A0001", "A0001 -introduces-> W1001"), edgeList(g));
        assertTrue(output().contains("A0001  package of 3 edit(s) proposed for W0001 (W0001@1):"), output());
        assertTrue(output().contains("ADD @org.jspecify.annotations.Nullable at FIELD of p.A#z in src/p/A.java"),
                output());

        // Resume reuses the package result without checking again.
        FakeChecker again = new FakeChecker(PackageEvaluationTest::initializer);
        assertEquals(ExitCode.COMPLETED, run(fx, again), output());
        assertEquals(0, again.calls());
    }

    @Test
    void aPartialPackageThatOnlyNarrowsTheMessageIsRecordedAsResolvedPlusIntroduced() throws Exception {
        // Two of the three fields: the message changes, which the provisional matcher treats as one resolved
        // and one introduced warning (project-spec §6.1), never as a count-based resolution.
        ProposalFixture fx = fixture(BUILDER);
        answer(fx, List.of(edit("x", "ADD"), edit("y", "ADD")));

        assertEquals(ExitCode.COMPLETED, run(fx, new FakeChecker(PackageEvaluationTest::initializer)), output());

        Map<?, ?> r = attempt(fx);
        assertEquals(List.of("W0001"), r.get("resolved"));
        assertEquals(1, ((List<?>) r.get("introduced")).size(), "the narrowed message is a different finding");
        assertEquals(Map.of("baselineFindings", 1L, "resultFindings", 1L, "resolved", 1L, "persisting", 0L,
                "introduced", 1L, "ambiguousBaseline", 0L, "ambiguousResult", 0L), r.get("counts"));
    }

    @Test
    void aMultiFilePackageIsAppliedTogether() throws Exception {
        ProposalFixture fx = fixture(ProposalFixture.SOURCE);
        Files.writeString(fx.runDir.resolve("workspace/target/src/p/B.java"), "package p;\n\npublic class B {\n"
                + "    Object z;\n}\n");
        answer(fx, List.of(edit("x", "ADD"), inB("z")));
        FakeChecker checker = new FakeChecker(FakeChecker::nullness);

        assertEquals(ExitCode.COMPLETED, run(fx, checker), output());

        assertEquals(2, checker.calls());
        Path dir = evaluation(fx);
        String diff = Files.readString(dir.resolve("attempts/A0001/changes.diff"));
        assertTrue(diff.contains("--- a/src/p/A.java") && diff.contains("--- a/src/p/B.java"), diff);
        assertTrue(Files.readString(dir.resolve("attempts/A0001/source/src/p/B.java"))
                .contains("@org.jspecify.annotations.Nullable Object z;"));
        assertEquals(List.of("APPLIED", "APPLIED"), applierOutcomes(attempt(fx)));
    }

    @Test
    void oneInvalidEditRejectsTheWholePackageWithoutAWorkspace() throws Exception {
        ProposalFixture fx = fixture(ProposalFixture.SOURCE);
        Files.writeString(fx.runDir.resolve("workspace/target/src/p/B.java"), "package p;\n\npublic class B {\n"
                + "    Object z;\n}\n");
        answer(fx, List.of(edit("x", "ADD"), inB("missing")));
        FakeChecker checker = new FakeChecker(FakeChecker::nullness);

        assertEquals(ExitCode.COMPLETED, run(fx, checker), output());

        assertEquals(1, checker.calls(), "only the baseline");
        Map<?, ?> r = attempt(fx);
        assertEquals("REJECTED", r.get("outcome"));
        assertTrue(((String) r.get("detail")).contains("package rejected, nothing applied: edit 1"), (String) r.get(
                "detail"));
        assertEquals(List.of("APPLIED", "REJECTED"), applierOutcomes(r), "the valid edit was checked, not kept");
        Path dir = evaluation(fx);
        assertFalse(Files.exists(dir.resolve("attempts/A0001/source")));
        assertFalse(Files.exists(dir.resolve("attempts/A0001/changes.diff")));
        assertEquals(List.of(), graph(fx).edges());
    }

    @Test
    void conflictingEditsRejectThePackage() throws Exception {
        ProposalFixture fx = fixture(ProposalFixture.SOURCE);
        answer(fx, List.of(edit("x", "ADD"), edit("x", "REMOVE")));
        FakeChecker checker = new FakeChecker(FakeChecker::nullness);

        assertEquals(ExitCode.COMPLETED, run(fx, checker), output());

        assertEquals(1, checker.calls());
        assertEquals("REJECTED", attempt(fx).get("outcome"));
        assertFalse(Files.exists(evaluation(fx).resolve("attempts/A0001/source")));
    }

    @Test
    void duplicateEditsAreMergedKeepingTheirAnswerIndices() throws Exception {
        ProposalFixture fx = fixture(ProposalFixture.SOURCE);
        answer(fx, List.of(edit("x", "ADD"), edit("y", "ADD"), edit("x", "ADD")));

        assertEquals(ExitCode.COMPLETED, run(fx, new FakeChecker(FakeChecker::nullness)), output());

        Map<?, ?> r = attempt(fx);
        List<?> edits = (List<?>) r.get("edits");
        assertEquals(2, edits.size());
        List<?> associations = (List<?>) ((Map<?, ?>) ((List<?>) r.get("origins")).get(0)).get("edits");
        assertEquals(List.of(0L, 2L), ((Map<?, ?>) associations.get(0)).get("answerEditIndices"));
        assertEquals(List.of("u-x", "u-x"), ((Map<?, ?>) associations.get(0)).get("uncertainties"));
        assertEquals(List.of("APPLIED", "APPLIED"), applierOutcomes(r));
        assertEquals(List.of(0, 2), ((AnnotationNode) graph(fx).node("A0001").orElseThrow()).proposals().get(0)
                .answerEditIndices().get(0));
    }

    @Test
    void aPackageMixingNoOpAndAppliedEditsIsCheckedAndAnAllNoOpPackageIsNot() throws Exception {
        String preannotated = ProposalFixture.SOURCE.replace("    Object y;",
                "    @org.jspecify.annotations.Nullable Object y;");
        ProposalFixture mixed = fixture(preannotated);
        answer(mixed, List.of(edit("x", "ADD"), edit("y", "ADD")));
        FakeChecker checker = new FakeChecker(FakeChecker::nullness);

        assertEquals(ExitCode.COMPLETED, run(mixed, checker), output());

        assertEquals(2, checker.calls());
        Map<?, ?> r = attempt(mixed);
        assertEquals("COMPLETED", r.get("outcome"));
        assertEquals(List.of("APPLIED", "NO_OP"), applierOutcomes(r));
        assertEquals(1L, r.get("appliedEdits"));
        assertEquals(1L, r.get("noOpEdits"));
    }

    @Test
    void anAllNoOpPackageIsReportedWithoutACheck() throws Exception {
        ProposalFixture fx = fixture(ProposalFixture.SOURCE.replace("    Object y;",
                "    @org.jspecify.annotations.Nullable Object y;"));
        answer(fx, List.of(edit("y", "ADD")));
        FakeChecker checker = new FakeChecker(FakeChecker::nullness);

        assertEquals(ExitCode.COMPLETED, run(fx, checker), output());

        assertEquals(1, checker.calls());
        Map<?, ?> r = attempt(fx);
        assertEquals("NO_OP", r.get("outcome"));
        assertTrue(((String) r.get("detail")).contains("all 1 edit(s) of the package are already satisfied"));
        assertEquals("NO_OP", ((AnnotationNode) graph(fx).node("A0001").orElseThrow()).outcome());
    }
}
