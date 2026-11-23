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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Nullable returns from methods that override or implement other methods: one atomic package annotates the
 * returning method and every return contract above it, never its overrides, siblings or overloads. A fake
 * NullAway also reports an override whose nullable return contradicts a non-null ancestor.
 */
class PropagationReturnHierarchyTest {

    static final String RETURNING = "returning @Nullable expression from method with @NonNull return type";

    static final String SOURCE = """
            package p;

            import q.NonNull;

            /** Return-hierarchy fixture. */
            public class A {
                void use(HasScope h) { h.getScope().hashCode(); } // caller

                interface HasScope { Object getScope(); } // has scope
                static class Event implements HasScope {
                    @Nullable Object context;
                    @Override public Object getScope() { return context; } // event scope
                }
                static class OtherEvent implements HasScope {
                    @Override public Object getScope() { return "fixed"; } // sibling scope
                }

                abstract static class Base {
                    abstract Object value(); // base value
                    Object value(int i) { return ""; } // base overload
                }
                static class Mid extends Base { @Override Object value() { return ""; } } // mid value
                static class Leaf extends Mid {
                    @Nullable Object v;
                    @Override Object value() { return v; } // leaf value
                }
                static class Below extends Leaf { @Override Object value() { return ""; } } // below value

                interface Named { Object id(); } // named
                interface Keyed { Object id(); } // keyed
                static class Both implements Named, Keyed {
                    @Nullable Object id;
                    @Override public Object id() { return id; } // both id
                }

                interface Source<T> {
                    T get(); // source get
                    T get(int i); // source overload
                }
                static class Text implements Source<String> {
                    @Nullable String s;
                    @Override public String get() { return s; } // text get
                    @Override public String get(int i) { return ""; } // text overload
                }

                interface Kept { @Nullable Object k(); } // kept iface
                static class KeptImpl implements Kept {
                    @Nullable Object k;
                    @Override public Object k() { return k; } // kept impl
                }

                interface Strict { @NonNull Object st(); } // strict iface
                static class StrictImpl implements Strict {
                    @Nullable Object st;
                    @Override public @NonNull Object st() { return st; } // strict impl
                }

                static class Ext implements java.util.function.Supplier<Object> {
                    @Nullable Object e;
                    @Override public Object get() { return e; } // external
                }
                static class Str {
                    @Nullable String t;
                    @Override public String toString() { return t; } // to string
                }

                interface Handler { Object handle(); } // handler
                static class Helper { @Nullable Object h; public Object handle() { return h; } } // helper
                static class Impl extends Helper implements Handler {}

                static class Plain { @Nullable Object p; Object plain() { return p; } } // plain
            }
            """;

    /** A returning method (by marker), its returned value, and the markers of the contracts above it. */
    record Contract(String method, String value, List<String> ancestors) {}

    static final List<Contract> CONTRACTS = List.of(
            new Contract("// event scope", "context", List.of("// has scope")),
            new Contract("// leaf value", "v", List.of("// mid value", "// base value")),
            new Contract("// both id", "id", List.of("// named", "// keyed")),
            new Contract("// text get", "s", List.of("// source get")),
            new Contract("// kept impl", "k", List.of("// kept iface")),
            new Contract("// strict impl", "st", List.of("// strict iface")),
            new Contract("// external", "e", List.of()),
            new Contract("// to string", "t", List.of()),
            new Contract("// helper", "h", List.of("// handler")),
            new Contract("// plain", "p", List.of()));

    static boolean nullable(FakeChecker.Source s, String marker) {
        return s.text().lines().filter(l -> l.contains(marker)).findFirst().orElseThrow()
                .matches(".*@(p\\.)?Nullable (Object|String|T) [a-zA-Z]+\\(.*");
    }

    /**
     * Like NullAway: a return warning disappears once the method's return is {@code @Nullable}; a nullable return
     * under a non-null ancestor contract is reported; a dereference of {@code getScope()} is reported once
     * {@code HasScope#getScope} is nullable (a new caller warning).
     */
    static CheckerOutcome rule(FakeChecker.Source s) {
        List<Diagnostic> f = new ArrayList<>();
        for (Contract c : CONTRACTS) {
            if (!nullable(s, c.method())) {
                f.add(s.at("return " + c.value() + ";", RETURNING));
            } else {
                for (String a : c.ancestors()) {
                    if (!nullable(s, a)) {
                        f.add(s.at(c.method(), "method returns @Nullable, but superclass method " + a
                                + " returns @NonNull"));
                    }
                }
            }
        }
        if (nullable(s, "// has scope")) {
            f.add(s.at("h.getScope().hashCode()", "dereferenced expression h.getScope() is @Nullable"));
        }
        return new CompletedAnalysis(f, List.of());
    }

    @TempDir Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
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
        checker = new FakeChecker(PropagationReturnHierarchyTest::rule);
    }

    private ExitCode propagate() {
        out.reset();
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        return new PropagateCommand(run.toString(), env, temp, Path.of(System.getProperty("java.home")),
                (c, e) -> checker, new JavaParserAnnotationApplier(), new AnchoredWarningMatcher(),
                PrepareInferenceCommandTest.CLOCK, ps, ps).run();
    }

    private String warning(String marker) throws IOException {
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
                .filter(w -> w.diagnostic().line() == target).findFirst().orElseThrow().id();
    }

    private static List<String> canonical(NullabilityPropagation.Match m) {
        return m.edits().stream().map(AnnotationEdit::canonical).toList();
    }

    private static String ret(String member) {
        return "ADD @p.Nullable at RETURN of " + member + " in src/p/A.java";
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
    void nullableReturnsAnnotateEveryContractAboveThemAsOnePackage() throws Exception {
        NullabilityPropagation.Analysis a = NullabilityPropagation.analyze(run);
        Map<String, NullabilityPropagation.Match> matches = new HashMap<>();
        a.matches().forEach(m -> matches.put(m.warningId(), m));
        Map<String, NullabilityPropagation.Skip> skips = new HashMap<>();
        a.skipped().forEach(s -> skips.put(s.warningId(), s));
        assertEquals(10, a.warnings());
        assertEquals(8, matches.size(), skips.toString());
        assertTrue(a.matches().stream().allMatch(m -> m.rule() == NullabilityPropagation.Rule.RETURN));

        // an interface getter (the EventBus HasExecutionScope shape); the sibling OtherEvent keeps its return
        NullabilityPropagation.Match event = matches.get(warning("// event scope"));
        assertEquals(List.of(ret("p.A.Event#getScope()"), ret("p.A.HasScope#getScope()")), canonical(event));
        assertTrue(event.evidence().contains("overrides and sibling implementations keep their non-null returns"),
                event.evidence());
        // multi-level superclass overrides up to the abstract method; the subclass Below and value(int) untouched
        assertEquals(List.of(ret("p.A.Leaf#value()"), ret("p.A.Base#value()"), ret("p.A.Mid#value()")),
                canonical(matches.get(warning("// leaf value"))));
        // several interface contracts
        assertEquals(List.of(ret("p.A.Both#id()"), ret("p.A.Keyed#id()"), ret("p.A.Named#id()")),
                canonical(matches.get(warning("// both id"))));
        // generic substitution: String get() implements Source<String>.get(); get(int) is an unrelated overload
        assertEquals(List.of(ret("p.A.Text#get()"), ret("p.A.Source#get()")),
                canonical(matches.get(warning("// text get"))));
        // an already-nullable contract is kept
        NullabilityPropagation.Match kept = matches.get(warning("// kept impl"));
        assertEquals(List.of(ret("p.A.KeptImpl#k()")), canonical(kept));
        assertTrue(kept.evidence().contains("already @Nullable, kept: p.A.Kept#k()"), kept.evidence());
        // explicit non-null returns: replaced on the method and on its contract, each recorded
        NullabilityPropagation.Match strict = matches.get(warning("// strict impl"));
        assertEquals(List.of("REMOVE @q.NonNull at RETURN of p.A.StrictImpl#st() in src/p/A.java",
                ret("p.A.StrictImpl#st()"), "REMOVE @q.NonNull at RETURN of p.A.Strict#st() in src/p/A.java",
                ret("p.A.Strict#st()")), canonical(strict));
        assertEquals(2, strict.removals().size());
        assertTrue(strict.evidence().contains("contract change: the explicit non-null return annotation of "
                + "p.A.StrictImpl#st() (@NonNull), p.A.Strict#st() (@NonNull) is replaced by @p.Nullable"),
                strict.evidence());
        // an implementation inherited by a subclass: the interface method it implements there
        assertEquals(List.of(ret("p.A.Helper#handle()"), ret("p.A.Handler#handle()")),
                canonical(matches.get(warning("// helper"))));
        // no ancestors: the method alone
        NullabilityPropagation.Match plain = matches.get(warning("// plain"));
        assertEquals(List.of(ret("p.A.Plain#plain()")), canonical(plain));
        assertFalse(plain.evidence().contains("contracts it overrides"));

        // required contracts outside the editable sources: the whole package is skipped
        NullabilityPropagation.Skip external = skips.get(warning("// external"));
        assertEquals(NullabilityPropagation.SkipCategory.INCOMPLETE_HIERARCHY, external.category());
        assertTrue(external.reason().contains("java.util.function.Supplier#get(), which p.A.Ext#get() overrides or "
                + "implements, is declared outside the editable project sources"), external.reason());
        NullabilityPropagation.Skip str = skips.get(warning("// to string"));
        assertEquals(NullabilityPropagation.SkipCategory.INCOMPLETE_HIERARCHY, str.category());
        assertTrue(str.reason().contains("java.lang.Object#toString()"), str.reason());
    }

    @Test
    void returnHierarchyPackagesAreAppliedAtomicallyEvaluatedAloneAndPropagatedExactlyOnce() throws Exception {
        String snapshotBefore = EvaluationFiles.treeHash(run.resolve("workspace/target"));

        assertEquals(ExitCode.COMPLETED, propagate(), out.toString(StandardCharsets.UTF_8));

        Path dir = only(run.resolve(PropagationStage.DIR));
        Map<?, ?> r = json(dir.resolve(PropagationStage.RESULT));
        assertEquals("COMPLETED", r.get("outcome"), out.toString(StandardCharsets.UTF_8));
        assertEquals("nullable-flow-4", json(dir.resolve(PropagationStage.ANALYSIS)).get("rules"));
        assertEquals(8L, ((Map<?, ?>) r.get("counts")).get("uniquePackages"));
        assertEquals(10, checker.calls(), "evaluation baseline, eight packages, one combined check");

        Path evaluation = only(run.resolve("evaluations"));
        WarningGraph g = WarningGraphJson.load(evaluation.resolve("evaluation-graph.json"));
        List<AnnotationNode> nodes = g.nodes().stream().filter(AnnotationNode.class::isInstance)
                .map(AnnotationNode.class::cast).toList();
        assertEquals(8, nodes.size());
        // every package is consistent alone: no nullable return under a non-null contract
        assertTrue(nodes.stream().allMatch(n -> n.outcome().equals("COMPLETED")), nodes.toString());
        AnnotationNode leaf = nodes.stream().filter(n -> n.edits().stream()
                .anyMatch(e -> e.canonical().contains("p.A.Leaf#value()"))).findFirst().orElseThrow();
        assertEquals(3, leaf.edits().size(), "the method and both contracts above it are one annotation node");
        AnnotationNode event = nodes.stream().filter(n -> n.edits().stream()
                .anyMatch(e -> e.canonical().contains("p.A.Event#getScope()"))).findFirst().orElseThrow();
        List<String> edges = g.edges().stream().map(e -> e.source() + " " + e.kind() + " " + e.target()).toList();
        assertTrue(edges.contains(warning("// event scope") + " resolved-by " + event.id()), edges.toString());
        assertTrue(g.edges().stream().allMatch(e -> e.kind().equals(GraphEdge.RESOLVED_BY)
                || e.kind().equals(GraphEdge.INTRODUCES)));
        assertEquals(List.of(event.id()), g.edges().stream().filter(e -> e.kind().equals(GraphEdge.INTRODUCES))
                .map(GraphEdge::source).toList(), "only the getScope package introduces a (caller) warning");

        Path round = run.resolve((String) ((Map<?, ?>) r.get("combined")).get("directory"));
        String source = Files.readString(round.resolve("workspace/target/src/p/A.java"));
        for (String annotated : List.of("interface HasScope { @p.Nullable Object getScope(); }",
                "@Override public @p.Nullable Object getScope() { return context; }",
                "abstract @p.Nullable Object value(); // base value",
                "@Override @p.Nullable Object value() { return \"\"; } } // mid value",
                "@Override @p.Nullable Object value() { return v; }", "interface Named { @p.Nullable Object id(); }",
                "interface Keyed { @p.Nullable Object id(); }", "@p.Nullable T get(); // source get",
                "public @p.Nullable String get() { return s; }", "interface Strict { @p.Nullable Object st(); }",
                "public @p.Nullable Object st() { return st; }", "interface Handler { @p.Nullable Object handle(); }",
                "public @p.Nullable Object handle() { return h; }", "@p.Nullable Object plain() { return p; }")) {
            assertTrue(source.contains(annotated), annotated + " in\n" + source);
        }
        for (String unchanged : List.of("@Override public Object getScope() { return \"fixed\"; }",
                "@Override Object value() { return \"\"; } } // below value", "Object value(int i) { return \"\"; }",
                "T get(int i); // source overload", "public String get(int i) { return \"\"; }",
                "@Override public Object get() { return e; }", "public String toString() { return t; }")) {
            assertTrue(source.contains(unchanged), unchanged + " in\n" + source);
        }
        assertFalse(source.contains("@NonNull"), "explicit non-null returns replaced, no contradiction:\n" + source);
        assertFalse(Files.readString(round.resolve("warnings.txt")).contains("superclass method"));
        // one pass: the caller's dereference is a new warning, recorded, not propagated
        assertEquals(1L, ((Map<?, ?>) ((Map<?, ?>) r.get("combined")).get("comparison")).get("introduced"));
        assertTrue(Files.readString(round.resolve("warnings.txt")).contains("[INTRODUCED]"));
        assertEquals(snapshotBefore, EvaluationFiles.treeHash(run.resolve("workspace/target")));

        int calls = checker.calls();
        assertEquals(ExitCode.COMPLETED, propagate(), out.toString(StandardCharsets.UTF_8));
        assertEquals(calls, checker.calls(), "rerunning reuses everything");
    }
}
