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
import generalannotator.diagnostics.Severity;
import generalannotator.edits.AnnotationEdit;
import generalannotator.edits.EditAction;
import generalannotator.edits.javaparser.JavaParserAnnotationApplier;
import generalannotator.evaluation.EvaluationFiles;
import generalannotator.evaluation.FakeChecker;
import generalannotator.evaluation.matching.AnchoredWarningMatcher;
import generalannotator.graph.AnnotationNode;
import generalannotator.graph.GraphEdge;
import generalannotator.graph.InitialWarningGraph;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@link NullabilityPropagation.Rule#ARGUMENT} flow: "passing @Nullable parameter" warnings annotate the
 * receiving parameter javac resolved, with a fake NullAway, the real applier and javac.
 */
class PropagationArgumentTest {

    static final String PASS = "passing @Nullable parameter '%s' where @NonNull is required";

    static final String SOURCE = """
            package p;

            import q.NonNull;

            /** Fixture. */
            public class A {
                @Nullable Object a;
                @Nullable String text;
                Object plain;

                @Nullable Object find() { return null; }

                void calls(I i, Base base, Sub2 sub2) {
                    use(a); // direct
                    Object local = a;
                    Object copy = local;
                    useLocal(copy); // local
                    useResult(find()); // result
                    strict(a); // nonnull
                    over(a); // overload object
                    over(text); // overload string
                    pair(a, text); // pair
                    use(a); // again
                    a.toString(); // deref
                    vargs(a); // varargs
                    System.identityHashCode(a); // jdk
                    i.take(a); // abstract
                    base.m(a); // overridden
                    sub2.inherited(a); // inherited
                    use(null); // literal
                    Object mixed = plain != null ? plain : a;
                    fallback(mixed); // fallback
                }

                void use(Object x) { inner(x); }
                void inner(Object y) {}
                void useLocal(Object x) {}
                void useResult(Object x) {}
                void strict(@NonNull Object x) {}
                void over(Object o) {}
                void over(String s) {}
                void pair(Object first, Object second) {}
                void vargs(Object... xs) {}
                void fallback(Object x) {}

                interface I { void take(Object o); }

                static class Base { void m(Object o) {} }

                static class Sub extends Base { @Override void m(Object o) {} }

                static class Base2 { void inherited(Object o) {} }

                static class Sub2 extends Base2 {}
            }
            """;

    /** A NullAway-like argument finding: the line containing {@code line}, column right after {@code before}. */
    static Diagnostic arg(FakeChecker.Source s, String line, String before, String quoted) {
        List<String> lines = s.text().lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.contains(line)) {
                int col = l.indexOf(before) + before.length();
                return new Diagnostic(s.file().toString(), i + 1, OptionalInt.of(col + 1), Severity.ERROR,
                        Optional.of("NullAway"), PASS.formatted(quoted) + "\n(see http://t.uber.com/nullaway )",
                        Optional.of(l));
            }
        }
        throw new IllegalStateException("no line contains " + line);
    }

    /** Like NullAway: each argument warning disappears once its receiving parameter is @Nullable. */
    static CheckerOutcome rule(FakeChecker.Source s) {
        String t = s.text();
        List<Diagnostic> f = new ArrayList<>();
        if (!t.contains("use(@p.Nullable Object x)")) {
            f.add(arg(s, "// direct", "use(", "a"));
            f.add(arg(s, "// again", "use(", "a"));
        } else {
            f.add(arg(s, "void use(", "inner(", "x")); // introduced: a later pass's business
        }
        if (!t.contains("useLocal(@p.Nullable")) {
            f.add(arg(s, "// local", "useLocal(", "copy"));
        }
        if (!t.contains("useResult(@p.Nullable")) {
            f.add(arg(s, "// result", "useResult(", "find()"));
        }
        if (!t.contains("strict(@p.Nullable")) {
            f.add(arg(s, "// nonnull", "strict(", "a"));
        }
        if (!t.contains("over(@p.Nullable Object")) {
            f.add(arg(s, "// overload object", "over(", "a"));
        }
        if (!t.contains("over(@p.Nullable String")) {
            f.add(arg(s, "// overload string", "over(", "text"));
        }
        if (!t.contains("@p.Nullable Object first")) {
            f.add(arg(s, "// pair", "pair(", "a"));
        }
        if (!t.contains("@p.Nullable Object second")) {
            f.add(arg(s, "// pair", "pair(a, ", "text"));
        }
        f.add(s.at("a.toString()", "dereferenced expression 'a' is @Nullable"));
        f.add(arg(s, "// varargs", "vargs(", "a"));
        f.add(arg(s, "// jdk", "identityHashCode(", "a"));
        if (!t.contains("void take(@p.Nullable Object o)")) {
            f.add(arg(s, "// abstract", "take(", "a"));
        }
        if (!t.contains("static class Base { void m(@p.Nullable Object o) {} }")) {
            f.add(arg(s, "// overridden", "m(", "a"));
        }
        if (!t.contains("inherited(@p.Nullable")) {
            f.add(arg(s, "// inherited", "inherited(", "a"));
        }
        f.add(arg(s, "// literal", "use(", "null"));
        if (!t.contains("fallback(@p.Nullable")) {
            f.add(arg(s, "// fallback", "fallback(", "mixed"));
        }
        return new CompletedAnalysis(f, List.of());
    }

    @TempDir Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Map<String, String> env = new HashMap<>();
    private Path run;
    private FakeChecker checker;

    @BeforeEach
    void setUp() throws IOException {
        run = temp.resolve("runs/run1");
        Path snapshot = run.resolve("workspace/target");
        Path file = snapshot.resolve("src/p/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        Files.writeString(snapshot.resolve("src/p/Nullable.java"), "package p;\n\npublic @interface Nullable {}\n");
        Files.createDirectories(snapshot.resolve("src/q"));
        Files.writeString(snapshot.resolve("src/q/NonNull.java"), "package q;\n\npublic @interface NonNull {}\n");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sourceRoots", List.of("src"));
        config.put("classpath", List.of());
        config.put("javacRelease", null);
        Files.writeString(run.resolve("report.json"), Json.write(Map.of("runId", "run1", "configuration", config)));
        CompletedAnalysis completed = (CompletedAnalysis) rule(new FakeChecker.Source(snapshot, file, SOURCE));
        WarningGraphJson.save(InitialWarningGraph.build("run1", "nullaway", completed, snapshot),
                run.resolve(WarningGraphJson.FILE_NAME));
        Files.createDirectories(temp.resolve("checker"));
        env.put("GA_CHECKER", "nullaway");
        env.put("GA_CHECKER_HOME", temp.resolve("checker").toString());
        checker = new FakeChecker(PropagationArgumentTest::rule);
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

    /** The baseline warning on the line containing {@code marker} (the {@code n}-th of that line, from 0). */
    private String warning(String marker, int n) throws IOException {
        List<String> lines = SOURCE.lines().toList();
        int line = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(marker)) {
                line = i + 1;
                break;
            }
        }
        int target = line;
        return WarningGraphJson.load(run.resolve(WarningGraphJson.FILE_NAME)).warningNodes().stream()
                .filter(w -> w.diagnostic().line() == target).toList().get(n).id();
    }

    private String warning(String marker) throws IOException {
        return warning(marker, 0);
    }

    private static Map<?, ?> json(Path file) throws IOException {
        return (Map<?, ?>) JsonParser.parse(Files.readString(file));
    }

    private Path only(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> l = s.filter(p -> !p.getFileName().toString().startsWith(".")).toList();
            assertEquals(1, l.size(), l.toString());
            return l.get(0);
        }
    }

    @Test
    void argumentsAreMappedToTheResolvedParameterAndUnsupportedTargetsAreSkippedWithReasons() throws Exception {
        NullabilityPropagation.Analysis a = NullabilityPropagation.analyze(run);

        Map<String, NullabilityPropagation.Match> matches = new HashMap<>();
        a.matches().forEach(m -> matches.put(m.warningId(), m));
        Map<String, NullabilityPropagation.Skip> skips = new HashMap<>();
        a.skipped().forEach(s -> skips.put(s.warningId(), s));
        assertEquals(17, a.warnings());
        assertEquals(13, matches.size(), skips.toString());
        assertTrue(a.matches().stream().allMatch(m -> m.rule() == NullabilityPropagation.Rule.ARGUMENT));

        // direct field argument
        assertEquals("ADD @p.Nullable at PARAMETER[0] of p.A#use(Object) in src/p/A.java",
                matches.get(warning("// direct")).edit().canonical());
        assertTrue(matches.get(warning("// direct")).evidence().contains("the @Nullable field 'a'"));
        // repeated proposals: the same edit, kept per warning (merged into one package later)
        assertEquals(matches.get(warning("// direct")).edit(), matches.get(warning("// again")).edit());
        // local-variable flow, traced through two locals; the argument itself carries no annotation
        NullabilityPropagation.Match local = matches.get(warning("// local"));
        assertEquals("ADD @p.Nullable at PARAMETER[0] of p.A#useLocal(Object) in src/p/A.java", local.edit().canonical());
        assertTrue(local.evidence().contains("the local variable 'copy' (initialized from the local variable 'local' "
                + "(initialized from the @Nullable field 'a'))"), local.evidence());
        // nullable method result
        assertTrue(matches.get(warning("// result")).evidence().contains("the @Nullable method result 'find()'"));
        // explicit @NonNull: replaced, recorded as a contract change
        NullabilityPropagation.Match strict = matches.get(warning("// nonnull"));
        assertEquals(List.of("REMOVE @q.NonNull at PARAMETER[0] of p.A#strict(Object) in src/p/A.java",
                "ADD @p.Nullable at PARAMETER[0] of p.A#strict(Object) in src/p/A.java"),
                strict.edits().stream().map(AnnotationEdit::canonical).toList());
        assertEquals(EditAction.REMOVE, strict.removal().orElseThrow().action());
        assertTrue(strict.evidence().contains("contract change: the explicit non-null annotation of "
                + "p.A#strict(java.lang.Object) (@NonNull) is replaced by @p.Nullable"), strict.evidence());
        // overloads: told apart by attribution
        assertEquals("p.A#over(Object)", "p.A#" + matches.get(warning("// overload object")).edit().member()
                .canonical());
        assertEquals("p.A#over(String)", "p.A#" + matches.get(warning("// overload string")).edit().member()
                .canonical());
        // several arguments of one call: each warning maps to its own index
        assertEquals("PARAMETER[0]", matches.get(warning("// pair", 0)).edit().position().toString());
        assertEquals("PARAMETER[1]", matches.get(warning("// pair", 1)).edit().position().toString());
        // inherited: annotated where it is declared, not in the receiver's class
        assertEquals("p.A.Base2", matches.get(warning("// inherited")).edit().declaringClass());
        // no annotated origin: the project's single nullable annotation type
        NullabilityPropagation.Match fallback = matches.get(warning("// fallback"));
        assertEquals("p.Nullable", fallback.edit().annotation());
        assertTrue(fallback.evidence().contains("the project's nullable annotation type"), fallback.evidence());

        assertEquals(NullabilityPropagation.SkipCategory.OTHER_CATEGORY, skips.get(warning("// deref")).category());
        assertTrue(skips.get(warning("// deref")).reason().contains("never dereferences"));
        assertEquals(NullabilityPropagation.SkipCategory.UNSUPPORTED_TARGET, skips.get(warning("// varargs")).category());
        assertTrue(skips.get(warning("// varargs")).reason().contains("varargs"));
        assertEquals(NullabilityPropagation.SkipCategory.NOT_EDITABLE, skips.get(warning("// jdk")).category());
        // an interface method without implementations in the sources: the interface alone, coverage stated
        NullabilityPropagation.Match take = matches.get(warning("// abstract"));
        assertEquals(List.of("ADD @p.Nullable at PARAMETER[0] of p.A.I#take(Object) in src/p/A.java"),
                take.edits().stream().map(AnnotationEdit::canonical).toList());
        assertTrue(take.evidence().contains("no implementation or override found"), take.evidence());
        assertTrue(take.evidence().contains("are not discovered"), take.evidence());
        // an overridden method: the override is annotated in the same package
        assertEquals(List.of("ADD @p.Nullable at PARAMETER[0] of p.A.Base#m(Object) in src/p/A.java",
                "ADD @p.Nullable at PARAMETER[0] of p.A.Sub#m(Object) in src/p/A.java"),
                matches.get(warning("// overridden")).edits().stream().map(AnnotationEdit::canonical).toList());
        assertEquals(NullabilityPropagation.SkipCategory.NOT_A_NULLABLE_FIELD_OR_METHOD,
                skips.get(warning("// literal")).category());
        // an overridable method without overrides: the package is the method alone
        assertEquals(1, matches.get(warning("// direct")).edits().size());
        assertTrue(matches.get(warning("// direct")).evidence().contains("no implementation or override found"));
    }

    @Test
    void aDiagnosticThatNamesAnotherExpressionIsNotMatched() throws Exception {
        Path snapshot = run.resolve("workspace/target");
        Path file = snapshot.resolve("src/p/A.java");
        CompletedAnalysis wrong = new CompletedAnalysis(List.of(
                arg(new FakeChecker.Source(snapshot, file, SOURCE), "// direct", "use(", "b"),
                arg(new FakeChecker.Source(snapshot, file, SOURCE), "// direct", "", "a")), List.of());
        WarningGraphJson.save(InitialWarningGraph.build("run1", "nullaway", wrong, snapshot),
                run.resolve(WarningGraphJson.FILE_NAME));

        NullabilityPropagation.Analysis a = NullabilityPropagation.analyze(run);

        assertEquals(List.of(), a.matches());
        assertTrue(a.skipped().stream().allMatch(s -> s.category() == NullabilityPropagation.SkipCategory.UNRESOLVED),
                a.skipped().toString());
    }

    @Test
    void parameterPackagesAreEvaluatedAloneGraphedCombinedAndPropagatedExactlyOnce() throws Exception {
        String snapshotBefore = EvaluationFiles.treeHash(run.resolve("workspace/target"));

        assertEquals(ExitCode.COMPLETED, propagate(), output());

        Path dir = only(run.resolve(PropagationStage.DIR));
        Map<?, ?> r = json(dir.resolve(PropagationStage.RESULT));
        assertEquals("COMPLETED", r.get("outcome"), output());
        assertEquals(NullabilityPropagation.RULES, r.get("rules"));
        assertEquals("nullable-flow-3", json(dir.resolve(PropagationStage.ANALYSIS)).get("rules"),
                "the rule version is part of the analysis, hence of every cache key");
        assertEquals(12L, ((Map<?, ?>) r.get("counts")).get("uniquePackages"), "use's two warnings share one package");
        assertEquals(14, checker.calls(), "evaluation baseline, twelve packages, one combined check\n" + output());
        assertTrue(Files.readString(dir.resolve(PropagationStage.ANALYSIS)).contains("\"contractChange\""));

        Path evaluation = only(run.resolve("evaluations"));
        WarningGraph g = WarningGraphJson.load(evaluation.resolve("evaluation-graph.json"));
        List<AnnotationNode> nodes = g.nodes().stream().filter(AnnotationNode.class::isInstance)
                .map(AnnotationNode.class::cast).toList();
        AnnotationNode use = nodes.stream().filter(n -> n.edits().get(0).canonical().contains("#use(Object)"))
                .findFirst().orElseThrow();
        assertEquals(List.of(warning("// direct"), warning("// again")).stream().sorted().toList(),
                use.proposals().stream().map(AnnotationNode.Proposal::warningId).sorted().toList());
        String nonnull = warning("// nonnull");
        AnnotationNode strict = nodes.stream().filter(n -> n.canonical().contains(nonnull))
                .findFirst().orElseThrow();
        assertEquals(2, strict.edits().size(), "the removal and the addition are one atomic package");
        assertEquals("COMPLETED", strict.outcome(), strict.detail());
        assertTrue(nodes.stream().allMatch(n -> n.outcome().equals("COMPLETED")), nodes.toString());
        List<String> edges = g.edges().stream().map(e -> e.source() + " " + e.kind() + " " + e.target()).toList();
        assertTrue(edges.contains(warning("// direct") + " resolved-by " + use.id()), edges.toString());
        assertTrue(edges.contains(warning("// again") + " resolved-by " + use.id()), edges.toString());
        assertTrue(edges.contains(use.id() + " introduces W1001"), edges.toString());
        assertTrue(g.edges().stream().allMatch(e -> e.kind().equals(GraphEdge.RESOLVED_BY)
                || e.kind().equals(GraphEdge.INTRODUCES)));
        String deref = warning("// deref");
        assertFalse(edges.stream().anyMatch(e -> e.startsWith(deref + " ")), "skipped warnings stay unresolved");

        // the strict package's own workspace: @NonNull replaced, no contradictory annotations
        Path round = run.resolve((String) ((Map<?, ?>) r.get("combined")).get("directory"));
        String source = Files.readString(round.resolve("workspace/target/src/p/A.java"));
        for (String annotated : List.of("void use(@p.Nullable Object x)", "void useLocal(@p.Nullable Object x)",
                "void useResult(@p.Nullable Object x)", "void strict(@p.Nullable Object x)",
                "void over(@p.Nullable Object o)", "void over(@p.Nullable String s)",
                "void pair(@p.Nullable Object first, @p.Nullable Object second)",
                "static class Base2 { void inherited(@p.Nullable Object o) {} }",
                "void fallback(@p.Nullable Object x)", "interface I { void take(@p.Nullable Object o); }",
                "static class Base { void m(@p.Nullable Object o) {} }", "@Override void m(@p.Nullable Object o) {}")) {
            assertTrue(source.contains(annotated), annotated + " in\n" + source);
        }
        assertFalse(source.contains("@NonNull"), source);
        for (String unchanged : List.of("void inner(Object y)", "void vargs(Object... xs)")) {
            assertTrue(source.contains(unchanged), unchanged + " in\n" + source);
        }
        // one pass: inner(x) is a new warning, recorded, not propagated
        assertTrue(Files.readString(round.resolve("warnings.txt")).contains("[INTRODUCED]"));
        assertEquals(1L, ((Map<?, ?>) ((Map<?, ?>) r.get("combined")).get("comparison")).get("introduced"));
        assertEquals(snapshotBefore, EvaluationFiles.treeHash(run.resolve("workspace/target")));

        int calls = checker.calls();
        assertEquals(ExitCode.COMPLETED, propagate(), output());
        assertEquals(calls, checker.calls(), "rerunning reuses everything");
    }
}
