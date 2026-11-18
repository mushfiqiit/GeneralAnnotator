package generalannotator.targets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.diagnostics.Diagnostic;
import generalannotator.diagnostics.Severity;
import generalannotator.graph.GraphNode;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deterministic fixture: warning positions are marked in the source with {@code /*@name*}{@code /}
 * comments; a warning "at" a marker points at the character right after it.
 */
class TargetExtractorTest {

    static final String SAMPLE = """
            package p.q;

            import java.util.List;
            import java.util.function.Supplier;

            public /*@header*/class Sample {
                static Object FIELD = /*@fieldInit*/null;
                int a, /*@b*/b = 1;
                /*@shared*/int c, d;
                Supplier<String> lambdaField = () -> /*@lambdaInField*/null;
                int e; int f;

                Sample() { /*@ctor0*/ }
                Sample(int x, String... rest) { /*@ctorVarargs*/ }

                void over(int x) { /*@overInt*/ }
                void over(String s) { /*@overString*/ }
                void varargsMethod(int x, String... rest) { /*@varargsMethod*/ }
                <T> void generic(List<? extends T> xs, java.util.Map<String, List<Integer>> m, int arr[],
                        @Deprecated Object o, java.util.@Deprecated List<@Deprecated String> l) { /*@generic*/ }

                void withLambda() {
                    Runnable r = () -> { /*@lambda*/ };
                }
                void withAnonymous() {
                    Runnable r = new Runnable() { public void run() { /*@anon*/ } };
                }
                void withLocal() {
                    class Local { void m() { /*@local*/ } }
                }
                static { /*@staticInit*/ }

                static class Nested {
                    Nested(Object o) { /*@nestedCtor*/ }
                    class Inner { void deep(Nested n) { /*@deep*/ } }
                }
                enum Color { RED(/*@enumConst*/null), GREEN; Color(Object o) { /*@enumCtor*/ } Color() {} }
                record Point(int /*@component*/x, int y) {
                    Point { /*@compact*/ }
                    static int /*@origin*/ORIGIN = 0;
                }
                @interface Tag { String /*@annoElement*/value(); }
            }
            /*@outside*/
            class Secondary { void s() { /*@secondary*/ } }
            """;

    @TempDir Path temp;

    private Path copy;
    private final List<GraphNode> nodes = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        copy = Files.createDirectories(temp.resolve("work space/target"));
        write("mod/src/main/java/p/q/Sample.java", SAMPLE);
    }

    private void write(String relative, String text) throws IOException {
        Path file = copy.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /** A warning node at the character after {@code marker} in {@link #SAMPLE}. */
    private WarningNode at(String marker, String message) {
        return at("mod/src/main/java/p/q/Sample.java", SAMPLE, marker, message, true);
    }

    private WarningNode at(String file, String text, String marker, String message, boolean withColumn) {
        String token = "/*@" + marker + "*/";
        int index = text.indexOf(token);
        if (index < 0) {
            throw new IllegalArgumentException(marker);
        }
        int position = index + token.length();
        int line = 1 + (int) text.substring(0, position).chars().filter(c -> c == '\n').count();
        int column = position - text.lastIndexOf('\n', position - 1);
        return node(file, line, withColumn ? OptionalInt.of(column) : OptionalInt.empty(), message);
    }

    private WarningNode node(String file, int line, OptionalInt column, String message) {
        String id = String.format("W%04d", nodes.size() + 1);
        Diagnostic d = new Diagnostic(copy.resolve(file).toString(), line, column, Severity.ERROR,
                Optional.of("NullAway"), message, Optional.empty());
        WarningNode node = new WarningNode(id, d, Optional.of(file), true, 0);
        nodes.add(node);
        return node;
    }

    private WarningTarget extract(WarningNode node) {
        return new TargetExtractor(copy, List.of(Path.of("mod/src/main/java")), OptionalInt.empty()).extract(node);
    }

    private WarningTarget.Resolved resolved(String marker) {
        return resolved(marker, "some warning");
    }

    private WarningTarget.Resolved resolved(String marker, String message) {
        WarningTarget t = extract(at(marker, message));
        return assertInstanceOf(WarningTarget.Resolved.class, t, () -> marker + ": " + t);
    }

    private WarningTarget.Unresolved unresolved(String marker, String message) {
        WarningTarget t = extract(at(marker, message));
        return assertInstanceOf(WarningTarget.Unresolved.class, t, () -> marker + ": " + t);
    }

    @Test
    void methodTargetHasRootFileAndSignature() {
        WarningTarget.Resolved r = resolved("overInt");
        assertEquals(MemberKind.METHOD, r.memberKind());
        assertEquals("p.q.Sample#over(int)", r.target());
        assertEquals(Optional.of("p.q.Sample#over(int)"), r.targetMethod());
        assertEquals(Optional.empty(), r.targetField());
        assertEquals("mod/src/main/java", r.sourceRoot());
        assertEquals(copy.resolve("mod/src/main/java").toString(), r.isolatedSourceRoot());
        assertEquals("p/q/Sample.java", r.targetFile());
        assertEquals("p.q.Sample", r.enclosingClass());
        assertEquals(Optional.of("mod/src/main/java"), r.configuredSourceRoot());
        assertFalse(r.requiresDisableRootValidation());
        assertTrue(r.notes().isEmpty());
    }

    @Test
    void overloadsAreDistinguishedByParameterTypes() {
        assertEquals("p.q.Sample#over(int)", resolved("overInt").target());
        assertEquals("p.q.Sample#over(String)", resolved("overString").target());
    }

    @Test
    void parameterTypesAreWrittenAsInSourceWithoutAnnotations() {
        assertEquals("p.q.Sample#generic(List<? extends T>, java.util.Map<String, List<Integer>>, int[], Object, "
                + "java.util.List<String>)", resolved("generic").target());
    }

    @Test
    void constructorsUseTheSimpleClassNameAndVarargs() {
        WarningTarget.Resolved none = resolved("ctor0");
        assertEquals(MemberKind.CONSTRUCTOR, none.memberKind());
        assertEquals("p.q.Sample#Sample()", none.target());
        WarningTarget.Resolved varargs = resolved("ctorVarargs");
        assertEquals("p.q.Sample#Sample(int, String)", varargs.target(), "JavaParser/Specimin drop ... for constructors");
        assertTrue(varargs.notes().getFirst().startsWith("varargs constructor parameter written without '...'"));
        assertEquals("p.q.Sample#varargsMethod(int, String...)", resolved("varargsMethod").target());
        assertEquals("p.q.Sample.Color#Color(Object)", resolved("enumCtor").target());
    }

    @Test
    void fieldInitializersTargetTheField() {
        WarningTarget.Resolved r = resolved("fieldInit");
        assertEquals(MemberKind.FIELD, r.memberKind());
        assertEquals(Optional.of("p.q.Sample#FIELD"), r.targetField());
        assertEquals(Optional.empty(), r.targetMethod());
        assertEquals("p.q.Sample.Point#ORIGIN", resolved("origin").target());
    }

    @Test
    void fieldsDeclaredTogetherAreSeparatedByNameOrMessage() {
        assertEquals("p.q.Sample#b", resolved("b").target());

        WarningTarget.Resolved byMessage = resolved("shared", "@NonNull field 'Sample.d' not initialized");
        assertEquals("p.q.Sample#d", byMessage.target());
        assertTrue(byMessage.notes().getFirst().contains("chosen by the name in the warning message"));

        WarningTarget.Unresolved ambiguous = unresolved("shared", "assigning @Nullable expression to @NonNull field");
        assertTrue(ambiguous.reason().contains("shared by fields declared together (c, d)"), ambiguous.reason());
    }

    @Test
    void messageNamingAnotherFieldIsNotSilentlyIgnored() {
        WarningTarget.Unresolved u = unresolved("fieldInit", "@NonNull field 'other' not initialized");
        assertTrue(u.reason().contains("in field FIELD but the warning message names field other"), u.reason());
    }

    @Test
    void nestedClassesUseDotsAndConstructorSimpleNames() {
        assertEquals("p.q.Sample.Nested#Nested(Object)", resolved("nestedCtor").target());
        WarningTarget.Resolved deep = resolved("deep");
        assertEquals("p.q.Sample.Nested.Inner#deep(Nested)", deep.target());
        assertEquals("p.q.Sample.Nested.Inner", deep.enclosingClass());
        assertEquals("p/q/Sample.java", deep.targetFile());
    }

    @Test
    void lambdasAnonymousAndLocalClassesSelectTheEnclosingMember() {
        WarningTarget.Resolved lambda = resolved("lambda");
        assertEquals("p.q.Sample#withLambda()", lambda.target());
        assertEquals(List.of("inside a lambda expression; the enclosing member is targeted"), lambda.notes());

        WarningTarget.Resolved inField = resolved("lambdaInField");
        assertEquals("p.q.Sample#lambdaField", inField.target());
        assertEquals(MemberKind.FIELD, inField.memberKind());

        WarningTarget.Resolved anon = resolved("anon");
        assertEquals("p.q.Sample#withAnonymous()", anon.target());
        assertEquals(List.of("inside an anonymous class; the enclosing member is targeted"), anon.notes());

        WarningTarget.Resolved local = resolved("local");
        assertEquals("p.q.Sample#withLocal()", local.target());
        assertEquals(List.of("inside a local class; the enclosing member is targeted"), local.notes());
    }

    @Test
    void unsupportedLocationsAreExplicitlyUnresolved() {
        assertTrue(unresolved("staticInit", "m").reason().contains("initializer block"));
        assertTrue(unresolved("enumConst", "m").reason().contains("enum constant p.q.Sample.Color.RED"));
        assertTrue(unresolved("component", "m").reason().contains("record component p.q.Sample.Point.x"));
        assertTrue(unresolved("compact", "m").reason().contains("compact canonical constructor"));
        assertTrue(unresolved("annoElement", "m").reason().contains("annotation type element"));
        assertTrue(unresolved("header", "m").reason().contains("not inside a method, constructor, or field"));
        assertTrue(unresolved("outside", "m").reason().contains("outside any class declaration"));
    }

    @Test
    void secondaryTopLevelClassNeedsRootValidationDisabled() {
        WarningTarget.Resolved r = resolved("secondary");
        assertEquals("p.q.Secondary#s()", r.target());
        assertTrue(r.requiresDisableRootValidation());
    }

    @Test
    void missingColumnResolvesOnlyWhenTheLineHasOneMember() {
        String file = "mod/src/main/java/p/q/Sample.java";
        WarningTarget one = extract(at(file, SAMPLE, "overString", "m", false));
        assertEquals("p.q.Sample#over(String)", assertInstanceOf(WarningTarget.Resolved.class, one).target());

        int twoMembersLine = SAMPLE.substring(0, SAMPLE.indexOf("int e; int f;")).split("\n", -1).length;
        WarningTarget two = extract(node(file, twoMembersLine, OptionalInt.empty(), "m"));
        assertTrue(assertInstanceOf(WarningTarget.Unresolved.class, two).reason()
                .contains("contains more than one member"), two.toString());
    }

    @Test
    void fileProblemsAreUnresolvedWithReasons() throws IOException {
        WarningTarget missing = extract(node("mod/src/main/java/p/q/Gone.java", 1, OptionalInt.of(1), "m"));
        assertTrue(assertInstanceOf(WarningTarget.Unresolved.class, missing).reason().contains("does not exist"));

        write("mod/src/main/java/p/q/Broken.java", "package p.q;\nclass Broken { void m( }\n");
        WarningTarget broken = extract(node("mod/src/main/java/p/q/Broken.java", 2, OptionalInt.of(20), "m"));
        assertTrue(assertInstanceOf(WarningTarget.Unresolved.class, broken).reason().contains("does not parse"));

        write("mod/src/main/java/wrong/Misplaced.java", "package p.q;\nclass Misplaced { void m() {} }\n");
        WarningTarget misplaced = extract(node("mod/src/main/java/wrong/Misplaced.java", 2, OptionalInt.of(28), "m"));
        assertTrue(assertInstanceOf(WarningTarget.Unresolved.class, misplaced).reason()
                .contains("does not end with its package path 'p/q'"));

        WarningNode outsideCopy = new WarningNode("W0099", new Diagnostic("/elsewhere/X.java", 1, OptionalInt.of(1),
                Severity.ERROR, Optional.of("NullAway"), "m", Optional.empty()), Optional.empty(), true, 0);
        assertTrue(assertInstanceOf(WarningTarget.Unresolved.class, extract(outsideCopy)).reason()
                .contains("outside the isolated working copy"));
    }

    @Test
    void defaultPackageFileAtTheTargetRoot() throws IOException {
        write("Top.java", "class Top { void m() { } }\n");
        WarningTarget.Resolved r = assertInstanceOf(WarningTarget.Resolved.class,
                extract(node("Top.java", 1, OptionalInt.of(23), "m")));
        assertEquals("Top#m()", r.target());
        assertEquals(".", r.sourceRoot());
        assertEquals("Top.java", r.targetFile());
        assertTrue(r.notes().contains("the file is not under any configured source root"));
    }

    @Test
    void wholeGraphKeepsWarningIdsAndOrder() {
        at("overInt", "m");
        at("staticInit", "m");
        at("fieldInit", "m");
        WarningGraph graph = new WarningGraph("run", "nullaway", nodes, List.of());
        List<WarningTarget> targets =
                new TargetExtractor(copy, List.of(Path.of("mod/src/main/java")), OptionalInt.empty()).extract(graph);
        assertEquals(List.of("W0001", "W0002", "W0003"), targets.stream().map(WarningTarget::warningId).toList());
        assertInstanceOf(WarningTarget.Unresolved.class, targets.get(1));
    }
}
