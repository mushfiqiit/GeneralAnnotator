package generalannotator.propagation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.checker.CheckerOutcome;
import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.checker.CheckerOutcome.ExecutionFailure;
import generalannotator.checker.FailureKind;
import generalannotator.cli.ExitCode;
import generalannotator.cli.PrepareInferenceCommandTest;
import generalannotator.cli.PropagateCommand;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.edits.javaparser.JavaParserAnnotationApplier;
import generalannotator.evaluation.EvaluationFiles;
import generalannotator.evaluation.FakeChecker;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The single-pass nullability propagation stage, with a fake NullAway and the real applier and javac. */
class PropagationTest {

    static final String ASSIGN = NullabilityPropagation.ASSIGN;
    static final String RETURN = NullabilityPropagation.RETURN;

    /**
     * b = a (B from nullable A), c = b (C would become eligible only after B is annotated), d = a (explicit
     * @NonNull), e = a (field initializer), f from a nullable method and from a (one package, two warnings),
     * get() returns a, a dereference, a returned local, and an unrelated warning.
     */
    static final String SOURCE = """
            package p;

            /** Fixture. */
            public class A {
                @Nullable Object a;
                Object b;
                Object c;
                @NonNull Object d;
                Object e = a;
                Object f;

                @Nullable Object find() { return null; }

                void init() {
                    b = a;
                    c = b;
                    d = a;
                    f = find();
                    this.f = a;
                    a.toString();
                }

                Object get() { return a; }

                Object other() { Object o = null; return o; }

                String text() { return "x"; }
            }
            """;

    @TempDir Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Map<String, String> env = new HashMap<>();
    private Path run;
    private FakeChecker checker;

    /** Like NullAway on the fixture: warnings disappear when their destination is annotated. */
    static CheckerOutcome rule(FakeChecker.Source s) {
        List<Diagnostic> f = new ArrayList<>();
        if (!s.annotated("Object b;")) {
            f.add(s.at("b = a;", ASSIGN));
        } else if (!s.annotated("Object c;")) {
            f.add(s.at("c = b;", ASSIGN)); // only once B is @Nullable: a later pass's business
        }
        f.add(s.at("d = a;", ASSIGN));
        if (!s.annotated("Object e = a;")) {
            f.add(s.at("e = a;", ASSIGN));
        }
        if (!s.annotated("Object f;")) {
            f.add(s.at("f = find();", ASSIGN));
            f.add(s.at("this.f = a;", ASSIGN));
        }
        f.add(s.at("a.toString()", "dereferenced expression 'a' is @Nullable"));
        if (!s.annotated("Object get()")) {
            f.add(s.at("return a;", RETURN));
        }
        f.add(s.at("return o;", RETURN));
        f.add(s.at("return \"x\"", "unrelated finding"));
        return new CompletedAnalysis(f, List.of());
    }

    /** A completed run: snapshot, report.json and the baseline warning graph of {@code baseline}. */
    private void setUp(String source, FakeChecker.Rule baseline, FakeChecker.Rule evaluation) throws IOException {
        run = temp.resolve("runs/run1");
        Path snapshot = run.resolve("workspace/target");
        Path file = snapshot.resolve("src/p/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        Files.writeString(snapshot.resolve("src/p/Nullable.java"), "package p;\n\npublic @interface Nullable {}\n");
        Files.writeString(snapshot.resolve("src/p/NonNull.java"), "package p;\n\npublic @interface NonNull {}\n");
        Files.createDirectories(snapshot.resolve("src/q"));
        Files.writeString(snapshot.resolve("src/q/Nullable.java"), "package q;\n\npublic @interface Nullable {}\n");
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("sourceRoots", List.of("src"));
        config.put("classpath", List.of());
        config.put("javacRelease", null);
        Files.writeString(run.resolve("report.json"), Json.write(Map.of("runId", "run1", "configuration", config)));
        CompletedAnalysis completed = (CompletedAnalysis) baseline.analyze(new FakeChecker.Source(snapshot, file,
                source));
        WarningGraphJson.save(InitialWarningGraph.build("run1", "nullaway", completed, snapshot),
                run.resolve(WarningGraphJson.FILE_NAME));
        Files.createDirectories(temp.resolve("checker"));
        env.put("GA_CHECKER", "nullaway");
        env.put("GA_CHECKER_HOME", temp.resolve("checker").toString());
        checker = new FakeChecker(evaluation);
    }

    private void setUp() throws IOException {
        setUp(SOURCE, PropagationTest::rule, PropagationTest::rule);
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

    /** The baseline warning ID on the line containing {@code marker}. */
    private String warning(String marker) throws IOException {
        int line = 0;
        List<String> lines = SOURCE.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(marker)) {
                line = i + 1;
                break;
            }
        }
        int target = line;
        return WarningGraphJson.load(run.resolve(WarningGraphJson.FILE_NAME)).warningNodes().stream()
                .filter(n -> n.diagnostic().line() == target).findFirst().orElseThrow().id();
    }

    private Path only(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> l = s.filter(p -> !p.getFileName().toString().startsWith(".")).toList();
            assertEquals(1, l.size(), l.toString());
            return l.get(0);
        }
    }

    private static Map<?, ?> json(Path file) throws IOException {
        return (Map<?, ?>) JsonParser.parse(Files.readString(file));
    }

    @Test
    void everyBaselineWarningIsMatchedOrSkippedWithAReasonFromTheUnchangedBaseline() throws Exception {
        setUp();

        NullabilityPropagation.Analysis a = NullabilityPropagation.analyze(run);

        Map<String, NullabilityPropagation.Match> matches = new HashMap<>();
        a.matches().forEach(m -> matches.put(m.warningId(), m));
        Map<String, NullabilityPropagation.Skip> skips = new HashMap<>();
        a.skipped().forEach(s -> skips.put(s.warningId(), s));
        assertEquals(9, a.warnings());
        assertEquals(5, matches.size());
        assertEquals(4, skips.size());

        NullabilityPropagation.Match b = matches.get(warning("b = a;"));
        assertEquals(NullabilityPropagation.Rule.FIELD_ASSIGNMENT, b.rule());
        assertEquals("ADD @p.Nullable at FIELD of p.A#b in src/p/A.java", b.edit().canonical());
        assertEquals(NullabilityPropagation.Rule.FIELD_INITIALIZER, matches.get(warning("e = a;")).rule());
        assertEquals("p.A#e", "p.A#" + matches.get(warning("e = a;")).edit().member().canonical());
        assertTrue(matches.get(warning("f = find();")).evidence().contains("method result"));
        assertEquals(matches.get(warning("f = find();")).edit(), matches.get(warning("this.f = a;")).edit());
        NullabilityPropagation.Match get = matches.get(warning("return a;"));
        assertEquals(NullabilityPropagation.Rule.RETURN, get.rule());
        assertEquals("ADD @p.Nullable at RETURN of p.A#get() in src/p/A.java", get.edit().canonical());

        assertEquals(NullabilityPropagation.SkipCategory.EXPLICIT_CONTRACT, skips.get(warning("d = a;")).category());
        assertTrue(skips.get(warning("d = a;")).reason().contains("explicit non-null contract (@p.NonNull)"));
        assertEquals(NullabilityPropagation.SkipCategory.OTHER_CATEGORY, skips.get(warning("a.toString()")).category());
        assertTrue(skips.get(warning("a.toString()")).reason().contains("dereference"));
        assertEquals(NullabilityPropagation.SkipCategory.NOT_A_NULLABLE_FIELD_OR_METHOD,
                skips.get(warning("return o;")).category());
        assertEquals(NullabilityPropagation.SkipCategory.OTHER_CATEGORY, skips.get(warning("return \"x\"")).category());
        assertTrue(a.matches().stream().noneMatch(m -> m.edit().member().canonical().equals("c")),
                "c = b is no baseline warning: C is not a candidate in this pass");
    }

    @Test
    void theTextAloneNeverMatches() throws Exception {
        // "assigning @Nullable ..." messages placed on a field without initializer and on a return statement are
        // skipped: the tree at the position must agree with the message.
        setUp(SOURCE, s -> new CompletedAnalysis(List.of(s.at("Object c;", ASSIGN), s.at("return o;", ASSIGN)),
                List.of()), PropagationTest::rule);

        NullabilityPropagation.Analysis a = NullabilityPropagation.analyze(run);

        assertEquals(List.of(), a.matches());
        assertEquals(List.of(NullabilityPropagation.SkipCategory.UNRESOLVED,
                NullabilityPropagation.SkipCategory.UNRESOLVED), a.skipped().stream()
                        .map(NullabilityPropagation.Skip::category).toList());
    }

    @Test
    void uniquePackagesAreEvaluatedAloneGraphedAndCombinedInExactlyOnePass() throws Exception {
        setUp();
        String snapshotBefore = EvaluationFiles.treeHash(run.resolve("workspace/target"));

        assertEquals(ExitCode.COMPLETED, propagate(), output());

        assertEquals(6, checker.calls(), "evaluation baseline, four packages, one combined check");
        Path dir = only(run.resolve(PropagationStage.DIR));
        Map<?, ?> r = json(dir.resolve(PropagationStage.RESULT));
        assertEquals("COMPLETED", r.get("outcome"));
        assertEquals(4L, ((Map<?, ?>) r.get("counts")).get("uniquePackages"), "f's two warnings share one package");
        assertTrue(((List<?>) r.get("packages")).stream().allMatch(p -> ((Map<?, ?>) p).get("combined")
                .equals("INCLUDED")));
        assertTrue(Files.readString(dir.resolve(PropagationStage.ANALYSIS)).contains("explicit non-null contract"));

        Path evaluation = only(run.resolve("evaluations"));
        assertEquals(PropagationStage.GENERATOR, json(evaluation.resolve("evaluation.json")).get("generator"));
        assertEquals(PropagationStage.GENERATOR, json(evaluation.resolve("candidates.json")).get("generator"));
        WarningGraph g = WarningGraphJson.load(evaluation.resolve("evaluation-graph.json"));
        List<AnnotationNode> nodes = g.nodes().stream().filter(AnnotationNode.class::isInstance)
                .map(AnnotationNode.class::cast).toList();
        assertEquals(4, nodes.size());
        assertTrue(nodes.stream().allMatch(n -> n.proposals().stream().allMatch(p -> p.group().endsWith(
                "@" + PropagationStage.group(0)))), "every node records that this pass proposed it");
        AnnotationNode bNode = node(nodes, "b");
        AnnotationNode fNode = node(nodes, "f");
        assertEquals(List.of(warning("f = find();"), warning("this.f = a;")).stream().sorted().toList(),
                fNode.proposals().stream().map(AnnotationNode.Proposal::warningId).sorted().toList());
        List<String> edges = g.edges().stream().map(e -> e.source() + " " + e.kind() + " " + e.target()).toList();
        assertTrue(edges.contains(warning("b = a;") + " resolved-by " + bNode.id()), edges.toString());
        assertTrue(edges.contains(bNode.id() + " introduces W1001"), edges.toString());
        assertTrue(edges.contains(warning("f = find();") + " resolved-by " + fNode.id()), edges.toString());
        assertTrue(edges.contains(warning("this.f = a;") + " resolved-by " + fNode.id()), edges.toString());
        assertTrue(edges.contains(warning("return a;") + " resolved-by " + node(nodes, "get()").id()), edges.toString());
        String d = warning("d = a;");
        assertFalse(edges.stream().anyMatch(e -> e.startsWith(d + " ")), "skipped warnings stay unresolved");
        WarningNode introduced = (WarningNode) g.node("W1001").orElseThrow();
        assertTrue(introduced.diagnostic().message().startsWith(ASSIGN));
        assertEquals(1, g.warningNodes().stream().filter(w -> !w.root()).count());

        Map<?, ?> combined = (Map<?, ?>) r.get("combined");
        assertEquals("COMPLETED", combined.get("status"));
        Path round = run.resolve((String) combined.get("directory"));
        String source = Files.readString(round.resolve("workspace/target/src/p/A.java"));
        for (String annotated : List.of("@p.Nullable Object b;", "@p.Nullable Object e = a;", "@p.Nullable Object f;",
                "@p.Nullable Object get()")) {
            assertTrue(source.contains(annotated), annotated + " in\n" + source);
        }
        assertTrue(source.contains("    Object c;"), "one pass: C stays unannotated even though c = b now warns");
        assertTrue(Files.readString(round.resolve("warnings.txt")).contains("[INTRODUCED]"));
        assertEquals(1L, ((Map<?, ?>) combined.get("comparison")).get("introduced"));
        assertEquals(snapshotBefore, EvaluationFiles.treeHash(run.resolve("workspace/target")));

        // Rerunning reuses everything.
        int calls = checker.calls();
        assertEquals(ExitCode.COMPLETED, propagate(), output());
        assertEquals(calls, checker.calls());
    }

    private static AnnotationNode node(List<AnnotationNode> nodes, String member) {
        return nodes.stream().filter(n -> n.edits().get(0).fields().get("member").equals(member)).findFirst()
                .orElseThrow();
    }

    @Test
    void aFailedPackageEvaluationClaimsNothingAndIsLeftOutOfTheCombinedResult() throws Exception {
        setUp(SOURCE, PropagationTest::rule, s -> s.annotated("Object get()")
                ? new ExecutionFailure(FailureKind.COMPILATION_FAILED, List.of("cannot find symbol"), List.of(),
                        List.of())
                : rule(s));

        assertEquals(ExitCode.COMPLETED, propagate(), output());

        Map<?, ?> r = json(only(run.resolve(PropagationStage.DIR)).resolve(PropagationStage.RESULT));
        Map<?, ?> get = ((List<?>) r.get("packages")).stream().map(p -> (Map<?, ?>) p)
                .filter(p -> p.get("edits").toString().contains("#get()")).findFirst().orElseThrow();
        assertEquals("COMPILATION_FAILED", get.get("evaluation"));
        assertEquals("EXCLUDED", get.get("combined"));
        WarningGraph g = WarningGraphJson.load(only(run.resolve("evaluations")).resolve("evaluation-graph.json"));
        String getId = (String) get.get("id");
        assertFalse(g.edges().stream().anyMatch(e -> e.target().equals(getId) || e.source().equals(getId)));
        Path round = run.resolve((String) ((Map<?, ?>) r.get("combined")).get("directory"));
        assertTrue(Files.readString(round.resolve("workspace/target/src/p/A.java")).contains("    Object get()"));
    }

    @Test
    void aFailedEvaluationBaselineEvaluatesNothing() throws Exception {
        setUp(SOURCE, PropagationTest::rule, s -> ExecutionFailure.before(FailureKind.CRASHED, "javac crashed"));

        assertEquals(ExitCode.EXECUTION_FAILURE, propagate(), output());

        Map<?, ?> r = json(only(run.resolve(PropagationStage.DIR)).resolve(PropagationStage.RESULT));
        assertEquals("BASELINE_FAILED", r.get("outcome"));
        assertFalse(Files.exists(run.resolve("rounds")));
    }

    @Test
    void conflictingPackagesAreReportedAndKeptOutWhileCompatibleOnesAreCombined() throws Exception {
        String source = """
                package p;

                public class A {
                    @Nullable Object a;
                    @q.Nullable Object h;
                    Object b;
                    Object g;

                    void init() {
                        b = a;
                        g = a;
                        g = h;
                    }
                }
                """;
        FakeChecker.Rule rule = s -> {
            List<Diagnostic> f = new ArrayList<>();
            if (!s.annotated("Object b;")) {
                f.add(s.at("b = a;", ASSIGN));
            }
            if (!s.annotated("Object g;")) {
                f.add(s.at("g = a;", ASSIGN));
                f.add(s.at("g = h;", ASSIGN));
            }
            return new CompletedAnalysis(f, List.of());
        };
        setUp(source, rule, rule);

        assertEquals(ExitCode.COMPLETED, propagate(), output());

        Map<?, ?> r = json(only(run.resolve(PropagationStage.DIR)).resolve(PropagationStage.RESULT));
        assertEquals(3L, ((Map<?, ?>) r.get("counts")).get("uniquePackages"), "@p.Nullable g and @q.Nullable g differ");
        Map<?, ?> conflicts = (Map<?, ?>) r.get("conflicts");
        assertEquals(2, conflicts.size(), conflicts.toString());
        assertTrue(output().contains("conflicts:"), output());
        Path round = run.resolve((String) ((Map<?, ?>) r.get("combined")).get("directory"));
        String combined = Files.readString(round.resolve("workspace/target/src/p/A.java"));
        assertTrue(combined.contains("@p.Nullable Object b;"), combined);
        assertTrue(combined.contains("    Object g;"), "neither conflicting edit is chosen");
    }

    @Test
    void noMatchingWarningEndsExplicitlyWithoutEvaluating() throws Exception {
        setUp(SOURCE, s -> new CompletedAnalysis(List.of(s.at("a.toString()", "dereferenced expression 'a' is "
                + "@Nullable")), List.of()), PropagationTest::rule);

        assertEquals(ExitCode.COMPLETED, propagate(), output());

        assertEquals(0, checker.calls());
        Map<?, ?> r = json(only(run.resolve(PropagationStage.DIR)).resolve(PropagationStage.RESULT));
        assertEquals("NO_CANDIDATES", r.get("outcome"));
        assertFalse(Files.exists(run.resolve("evaluations")));
    }
}
