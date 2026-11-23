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
import java.io.UncheckedIOException;
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
 * Arguments passed to overridable methods (interface, abstract and overridden methods): one atomic package
 * annotates the receiving parameter and the same parameter of every implementation and override found in the
 * editable sources, with a fake NullAway that also reports inconsistent overrides, the real applier and javac.
 */
class PropagationHierarchyTest {

    static final String SOURCE = """
            package p;

            import q.NonNull;

            /** Hierarchy fixture. */
            public class A {
                @Nullable Throwable cause;
                @Nullable Object a;
                @Nullable String text;

                void calls(Log log, Shape shape, Handler handler, L1 l1, L2 l2, Sink<String> sink, Keep keep,
                        Strict strict, Out out, Cb cb) {
                    log.log("WARN", "failed", cause); // interface
                    Throwable t = cause;
                    log.log("WARN", "again", t); // interface local
                    shape.draw(a); // abstract
                    handler.handle(a); // inherited implementation
                    l1.go(a); // multi-level
                    l2.go(text); // from the middle
                    sink.put(text); // generic
                    keep.k(a); // kept
                    strict.s(a); // strict
                    out.println(a); // external
                    cb.on(a); // anonymous
                }

                interface Log {
                    void log(String level, String msg); // log 2
                    void log(String level, String msg, Throwable th); // log 3
                }

                static class JavaLog implements Log {
                    @Override public void log(String level, String msg) {} // java log 2
                    @Override public void log(String level, String msg, Throwable th) {} // java log 3
                }

                static class OutLog implements Log {
                    @Override public void log(String level, String msg) {} // out log 2
                    @Override public void log(String level, String msg, Throwable th) { // out log 3
                        th.printStackTrace(); // out deref
                    }
                }

                abstract static class Shape {
                    abstract void draw(Object o); // shape draw
                    void draw(Object o, int times) {} // shape draw overload
                }

                static class Circle extends Shape { @Override void draw(Object o) {} } // circle draw

                interface Handler { void handle(Object o); } // handler
                static class Helper { public void handle(Object o) {} } // helper
                static class Impl extends Helper implements Handler {}
                static class Other extends Helper { @Override public void handle(Object o) {} } // other

                static class L1 { void go(Object o) {} } // l1
                static class L2 extends L1 { @Override void go(Object o) {} } // l2
                static class L3 extends L2 { @Override void go(Object o) {} } // l3

                interface Sink<T> { void put(T value); } // sink
                static class StringSink implements Sink<String> {
                    @Override public void put(String value) {} // string sink
                    public void put(Integer value) {} // integer overload
                }

                interface Keep { void k(Object o); } // keep iface
                static class KeepA implements Keep { @Override public void k(@Nullable Object o) {} } // keep a
                static class KeepB implements Keep { @Override public void k(Object o) {} } // keep b

                interface Strict { void s(Object o); } // strict iface
                static class StrictImpl implements Strict { @Override public void s(@NonNull Object o) {} } // strict impl

                interface Out { void println(Object x); } // out iface
                static class Printer extends java.io.PrintStream implements Out { Printer() { super(System.out); } }

                interface Cb { void on(Object o); } // cb
                final Cb anon = new Cb() { @Override public void on(Object o) {} }; // anon class
            }
            """;

    static final String FILE_LOG = """
            package p;

            /** A second-level implementation in another file. */
            class FileLog extends A.JavaLog {
                @Override public void log(String level, String msg, Throwable th) {} // file log 3
            }
            """;

    /** One receiving declaration, the markers of its overrides, and the call whose warning it resolves. */
    record Contract(String declaration, List<String> overrides, String call, String before, String quoted) {}

    static final List<Contract> CONTRACTS = List.of(
            new Contract("// log 3", List.of("// java log 3", "// out log 3", "file:// file log 3"), "// interface",
                    "\"failed\", ", "cause"),
            new Contract("// log 3", List.of(), "// interface local", "\"again\", ", "t"),
            new Contract("// shape draw", List.of("// circle draw"), "// abstract", "draw(", "a"),
            new Contract("// handler", List.of("// helper", "// other"), "// inherited implementation", "handle(", "a"),
            new Contract("// l1", List.of("// l2", "// l3"), "// multi-level", "go(", "a"),
            new Contract("// l2", List.of("// l3"), "// from the middle", "go(", "text"),
            new Contract("// sink", List.of("// string sink"), "// generic", "put(", "text"),
            new Contract("// keep iface", List.of("// keep a", "// keep b"), "// kept", "k(", "a"),
            new Contract("// strict iface", List.of("// strict impl"), "// strict", "s(", "a"),
            new Contract("// out iface", List.of(), "// external", "println(", "a"),
            new Contract("// cb", List.of("// anon class"), "// anonymous", "on(", "a"));

    /** Whether the line with {@code marker} (in A.java, or FileLog.java for {@code file:}) is {@code @p.Nullable}. */
    static boolean nullable(FakeChecker.Source s, String marker) {
        FakeChecker.Source in = source(s, marker);
        String m = marker.replace("file:", "");
        return in.text().lines().filter(l -> l.contains(m)).findFirst().orElseThrow().matches(".*@(p\\.)?Nullable .*");
    }

    static FakeChecker.Source source(FakeChecker.Source s, String marker) {
        if (!marker.startsWith("file:")) {
            return s;
        }
        Path f = s.root().resolve("src/p/FileLog.java");
        try {
            return new FakeChecker.Source(s.root(), f, Files.readString(f));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Like NullAway: an argument warning disappears once its receiving parameter is {@code @Nullable}; an override
     * whose parameter stays non-null under a {@code @Nullable} overridden parameter is reported; a dereference of a
     * parameter made nullable is reported.
     */
    static CheckerOutcome rule(FakeChecker.Source s) {
        List<Diagnostic> f = new ArrayList<>();
        for (Contract c : CONTRACTS) {
            if (!nullable(s, c.declaration())) {
                f.add(PropagationArgumentTest.arg(s, c.call(), c.before(), c.quoted()));
            } else {
                for (String o : c.overrides()) {
                    if (!nullable(s, o) && f.stream().noneMatch(d -> d.message().contains(o))) {
                        f.add(source(s, o).at(o.replace("file:", ""), "parameter is @NonNull, but parameter in "
                                + "superclass method is @Nullable " + o));
                    }
                }
            }
        }
        if (nullable(s, "// out log 3")) {
            f.add(s.at("th.printStackTrace()", "dereferenced expression th is @Nullable"));
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
        Files.writeString(snapshot.resolve("src/p/FileLog.java"), FILE_LOG);
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
        checker = new FakeChecker(PropagationHierarchyTest::rule);
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

    private static String add(String member) {
        int hash = member.indexOf('#');
        String file = member.startsWith("p.FileLog") ? "src/p/FileLog.java" : "src/p/A.java";
        return "ADD @p.Nullable at PARAMETER[" + member.substring(member.indexOf('[') + 1, member.indexOf(']'))
                + "] of " + member.substring(0, hash) + "#" + member.substring(hash + 1, member.indexOf('['))
                + " in " + file;
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
    void overridableTargetsAnnotateTheirWholeEditableHierarchyAsOnePackage() throws Exception {
        NullabilityPropagation.Analysis a = NullabilityPropagation.analyze(run);
        Map<String, NullabilityPropagation.Match> matches = new HashMap<>();
        a.matches().forEach(m -> matches.put(m.warningId(), m));
        Map<String, NullabilityPropagation.Skip> skips = new HashMap<>();
        a.skipped().forEach(s -> skips.put(s.warningId(), s));
        assertEquals(11, a.warnings());
        assertEquals(9, matches.size(), skips.toString());

        // an interface with several implementations, one of them two levels down in another file; the
        // two-parameter overloads are not touched
        String log3 = "p.A.Log#log(String, String, Throwable)[2]";
        List<String> logPackage = List.of(add(log3), add("p.A.JavaLog#log(String, String, Throwable)[2]"),
                add("p.A.OutLog#log(String, String, Throwable)[2]"), add("p.FileLog#log(String, String, Throwable)[2]"));
        assertEquals(logPackage, canonical(matches.get(warning("// interface"))));
        // through a local: the same complete package
        assertEquals(logPackage, canonical(matches.get(warning("// interface local"))));
        // an abstract method; its two-parameter overload is not touched
        assertEquals(List.of(add("p.A.Shape#draw(Object)[0]"), add("p.A.Circle#draw(Object)[0]")),
                canonical(matches.get(warning("// abstract"))));
        // an implementation inherited from a class outside the interface's hierarchy, and that class's override
        assertEquals(List.of(add("p.A.Handler#handle(Object)[0]"), add("p.A.Helper#handle(Object)[0]"),
                add("p.A.Other#handle(Object)[0]")), canonical(matches.get(warning("// inherited implementation"))));
        // multi-level overrides; from the middle level, the ancestor's contract is not widened
        assertEquals(List.of(add("p.A.L1#go(Object)[0]"), add("p.A.L2#go(Object)[0]"), add("p.A.L3#go(Object)[0]")),
                canonical(matches.get(warning("// multi-level"))));
        assertEquals(List.of(add("p.A.L2#go(Object)[0]"), add("p.A.L3#go(Object)[0]")),
                canonical(matches.get(warning("// from the middle"))));
        // generic substitution: put(String) implements Sink<String>.put(T); put(Integer) is an unrelated overload
        assertEquals(List.of(add("p.A.Sink#put(T)[0]"), add("p.A.StringSink#put(String)[0]")),
                canonical(matches.get(warning("// generic"))));
        // an implementation that is already @Nullable is kept as it is
        NullabilityPropagation.Match kept = matches.get(warning("// kept"));
        assertEquals(List.of(add("p.A.Keep#k(Object)[0]"), add("p.A.KeepB#k(Object)[0]")), canonical(kept));
        assertTrue(kept.evidence().contains("already @Nullable, kept: p.A.KeepA#k(java.lang.Object)"),
                kept.evidence());
        // an explicit non-null implementation: replaced in the same package, a recorded contract change
        NullabilityPropagation.Match strict = matches.get(warning("// strict"));
        assertEquals(List.of(add("p.A.Strict#s(Object)[0]"),
                "REMOVE @q.NonNull at PARAMETER[0] of p.A.StrictImpl#s(Object) in src/p/A.java",
                add("p.A.StrictImpl#s(Object)[0]")), canonical(strict));
        assertEquals(1, strict.removals().size());
        assertTrue(strict.evidence().contains("contract change: the explicit non-null annotation of "
                + "p.A.StrictImpl#s(java.lang.Object) (@NonNull) is replaced by @p.Nullable"), strict.evidence());
        // the coverage is stated, not overclaimed
        assertTrue(matches.get(warning("// interface")).evidence().contains("the configured source roots only; "
                + "implementations elsewhere, such as other modules, generated or library code, are not discovered"));

        // a required implementation outside the editable sources: the whole package is skipped
        NullabilityPropagation.Skip external = skips.get(warning("// external"));
        assertEquals(NullabilityPropagation.SkipCategory.INCOMPLETE_HIERARCHY, external.category());
        assertTrue(external.reason().contains("java.io.PrintStream#println(java.lang.Object), which implements "
                + "p.A.Out#println(java.lang.Object) in p.A.Printer, is declared outside the editable project "
                + "sources"), external.reason());
        assertTrue(external.reason().contains("the whole package is skipped"));
        // an override in an anonymous class cannot be addressed by an edit: skipped as a whole too
        NullabilityPropagation.Skip anonymous = skips.get(warning("// anonymous"));
        assertEquals(NullabilityPropagation.SkipCategory.INCOMPLETE_HIERARCHY, anonymous.category());
        assertTrue(anonymous.reason().contains("anonymous or local class"), anonymous.reason());
    }

    @Test
    void hierarchyPackagesAreAppliedAtomicallyEvaluatedAloneAndPropagatedExactlyOnce() throws Exception {
        String snapshotBefore = EvaluationFiles.treeHash(run.resolve("workspace/target"));

        assertEquals(ExitCode.COMPLETED, propagate(), out.toString(StandardCharsets.UTF_8));

        Path dir = only(run.resolve(PropagationStage.DIR));
        Map<?, ?> r = json(dir.resolve(PropagationStage.RESULT));
        assertEquals("COMPLETED", r.get("outcome"), out.toString(StandardCharsets.UTF_8));
        assertEquals("nullable-flow-3", json(dir.resolve(PropagationStage.ANALYSIS)).get("rules"));
        assertTrue(Files.readString(dir.resolve(PropagationStage.ANALYSIS)).contains("\"hierarchy\""));
        assertEquals(8L, ((Map<?, ?>) r.get("counts")).get("uniquePackages"), "both log warnings share one package");
        assertEquals(10, checker.calls(), "evaluation baseline, eight packages, one combined check");

        Path evaluation = only(run.resolve("evaluations"));
        WarningGraph g = WarningGraphJson.load(evaluation.resolve("evaluation-graph.json"));
        List<AnnotationNode> nodes = g.nodes().stream().filter(AnnotationNode.class::isInstance)
                .map(AnnotationNode.class::cast).toList();
        assertEquals(8, nodes.size());
        AnnotationNode log = nodes.stream().filter(n -> n.edits().stream().anyMatch(e -> e.canonical().contains("p.A.Log#log")))
                .findFirst().orElseThrow();
        assertEquals(4, log.edits().size(), "every declaration of the hierarchy is one annotation node");
        assertEquals(List.of(warning("// interface"), warning("// interface local")).stream().sorted().toList(),
                log.proposals().stream().map(AnnotationNode.Proposal::warningId).distinct().sorted().toList());
        // every package is consistent on its own: no override is left non-null under a @Nullable parameter
        assertTrue(nodes.stream().allMatch(n -> n.outcome().equals("COMPLETED")), nodes.toString());
        List<String> edges = g.edges().stream().map(e -> e.source() + " " + e.kind() + " " + e.target()).toList();
        assertTrue(edges.contains(warning("// interface") + " resolved-by " + log.id()), edges.toString());
        assertTrue(edges.contains(warning("// interface local") + " resolved-by " + log.id()), edges.toString());
        assertTrue(g.edges().stream().allMatch(e -> e.kind().equals(GraphEdge.RESOLVED_BY)
                || e.kind().equals(GraphEdge.INTRODUCES)));
        List<String> introducedBy = g.edges().stream().filter(e -> e.kind().equals(GraphEdge.INTRODUCES))
                .map(GraphEdge::source).toList();
        assertEquals(List.of(log.id()), introducedBy, "only the log package introduces a warning (the dereference)");
        String external = warning("// external");
        assertFalse(edges.stream().anyMatch(e -> e.startsWith(external + " ")));

        // combined: every hierarchy edit applied once, the skipped hierarchies untouched
        Path round = run.resolve((String) ((Map<?, ?>) r.get("combined")).get("directory"));
        String source = Files.readString(round.resolve("workspace/target/src/p/A.java"));
        for (String annotated : List.of("void log(String level, String msg, @p.Nullable Throwable th); // log 3",
                "public void log(String level, String msg, @p.Nullable Throwable th) {} // java log 3",
                "public void log(String level, String msg, @p.Nullable Throwable th) { // out log 3",
                "abstract void draw(@p.Nullable Object o);", "@Override void draw(@p.Nullable Object o) {}",
                "void handle(@p.Nullable Object o); }", "static class Helper { public void handle(@p.Nullable Object o)",
                "@Override public void handle(@p.Nullable Object o) {} } // other",
                "static class L1 { void go(@p.Nullable Object o) {} }", "@Override void go(@p.Nullable Object o) {} } // l2",
                "@Override void go(@p.Nullable Object o) {} } // l3", "void put(@p.Nullable T value);",
                "public void put(@p.Nullable String value) {}", "public void k(@p.Nullable Object o) {} } // keep b",
                "public void s(@p.Nullable Object o) {} } // strict impl")) {
            assertTrue(source.contains(annotated), annotated + " in\n" + source);
        }
        for (String unchanged : List.of("void log(String level, String msg); // log 2", "void draw(Object o, int times)",
                "public void put(Integer value) {}", "interface Out { void println(Object x); }",
                "interface Cb { void on(Object o); }", "public void on(Object o) {} }; // anon class")) {
            assertTrue(source.contains(unchanged), unchanged + " in\n" + source);
        }
        assertFalse(source.contains("@NonNull"), source);
        assertTrue(Files.readString(round.resolve("workspace/target/src/p/FileLog.java"))
                .contains("public void log(String level, String msg, @p.Nullable Throwable th) {}"));
        assertFalse(Files.readString(round.resolve("warnings.txt")).contains("superclass method"));
        // one pass: the dereference is a new warning, recorded, not propagated
        assertEquals(1L, ((Map<?, ?>) ((Map<?, ?>) r.get("combined")).get("comparison")).get("introduced"));
        assertTrue(Files.readString(round.resolve("warnings.txt")).contains("[INTRODUCED]"));
        assertEquals(snapshotBefore, EvaluationFiles.treeHash(run.resolve("workspace/target")));

        int calls = checker.calls();
        assertEquals(ExitCode.COMPLETED, propagate(), out.toString(StandardCharsets.UTF_8));
        assertEquals(calls, checker.calls(), "rerunning reuses everything");
    }
}
