package generalannotator.edits.javaparser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.edits.AnnotationApplier.ApplyResult;
import generalannotator.edits.AnnotationApplier.Outcome;
import generalannotator.edits.AnnotationEdit;
import generalannotator.edits.AnnotationPosition;
import generalannotator.edits.EditAction;
import generalannotator.edits.MemberRef;
import generalannotator.edits.TypeUsePath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Before/after source tests of {@link JavaParserAnnotationApplier} on small fixture files. */
class JavaParserAnnotationApplierTest {

    static final String N = "org.jspecify.annotations.Nullable";
    static final String NN = "org.jspecify.annotations.NonNull";

    @TempDir Path temp;
    private Path root;
    private final JavaParserAnnotationApplier applier = new JavaParserAnnotationApplier();

    static final String A = """
            package p;

            import java.util.List;
            import java.util.Map;

            /** A fixture. */
            public class A {
                // the field comment
                private Object field = new Object(); // trailing
                java.util.Map<String, Integer> qualified;
                String[] array;
                String[][] matrix;
                List<? extends Number> wildcard;
                Outer.Inner nested;
                final int primitive = 1;
                Object a, b;
                String cStyle[];

                public A(String s, int n) {
                    /* body */ field = s;
                }

                public <T> T get(Class<T> type) {
                    return null;
                }

                String find(String key) { return key; }
                String find(int index) { return null; }
                List<String> find(List<String> keys, Map<String, Object> options) { return keys; }
                void run(String... args) {}
                void none() {}

                static class Inner {
                    Object value;
                    Object compute(Object input) { return input; }
                }
            }
            """;

    static final String OUTER = """
            package p;

            public class Outer {
                public static class Inner {}
            }
            """;

    static final String NULLABLE = """
            package org.jspecify.annotations;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;

            /** Local stand-in for JSpecify (TYPE_USE only), so tests need no dependency. */
            @Target(ElementType.TYPE_USE)
            public @interface Nullable {}
            """;

    @BeforeEach
    void setUp() throws IOException {
        root = temp.resolve("workspace");
        write("src/p/A.java", A);
        write("src/p/Outer.java", OUTER);
        write("src/org/jspecify/annotations/Nullable.java", NULLABLE);
        write("src/org/jspecify/annotations/NonNull.java", NULLABLE.replace("Nullable", "NonNull"));
    }

    private void write(String path, String text) throws IOException {
        Path f = root.resolve(path);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    private String read(String path) throws IOException {
        return Files.readString(root.resolve(path));
    }

    static AnnotationEdit edit(String file, String cls, String member, AnnotationPosition position, EditAction action,
            String annotation) {
        return new AnnotationEdit("src", file, cls, MemberRef.parse(member), position, action, annotation);
    }

    static AnnotationEdit add(String member, AnnotationPosition position) {
        return edit("p/A.java", "p.A", member, position, EditAction.ADD, N);
    }

    static AnnotationPosition typeUse(String path) {
        return AnnotationPosition.of(TypeUsePath.parse(path));
    }

    private ApplyResult apply(AnnotationEdit... edits) throws IOException {
        return applier.apply(root, List.of(edits));
    }

    private void assertApplied(ApplyResult r) {
        assertTrue(r.committed(), r.results().toString());
        r.results().forEach(e -> assertEquals(Outcome.APPLIED, e.outcome(), e.toString()));
    }

    private String rejection(ApplyResult r, int index) {
        assertFalse(r.committed());
        assertEquals(Outcome.REJECTED, r.results().get(index).outcome(), r.results().toString());
        String detail = r.results().get(index).detail();
        System.out.println("REJECTED: " + detail);
        return detail;
    }

    @Test
    void addsAtFieldsReturnsParametersAndConstructorsAndPreservesEverythingElse() throws IOException {
        ApplyResult r = apply(
                add("field", AnnotationPosition.field()),
                add("get(Class<T>)", AnnotationPosition.returnType()),
                add("find(String)", AnnotationPosition.parameter(0)),
                add("A(String, int)", AnnotationPosition.parameter(0)));
        assertApplied(r);
        String expected = A
                .replace("private Object field", "private @" + N + " Object field")
                .replace("public <T> T get", "public <T> @" + N + " T get")
                .replace("String find(String key)", "String find(@" + N + " String key)")
                .replace("public A(String s", "public A(@" + N + " String s");
        assertEquals(expected, read("src/p/A.java"), "only the annotations are inserted; comments and layout stay");
        assertEquals(1, r.changes().size());
        assertEquals("src/p/A.java", r.changes().get(0).path());
    }

    @Test
    void choosesTheRightOverloadNestedClassAndRawEquivalentAndRejectsAmbiguity() throws IOException {
        assertApplied(apply(
                add("find(int)", AnnotationPosition.returnType()),
                add("find(List<String>, Map<String,Object>)", AnnotationPosition.parameter(1)),
                edit("p/A.java", "p.A.Inner", "compute(Object)", AnnotationPosition.returnType(), EditAction.ADD, N),
                edit("p/A.java", "p.A.Inner", "value", AnnotationPosition.field(), EditAction.ADD, N)));
        String text = read("src/p/A.java");
        assertTrue(text.contains("@" + N + " String find(int index)"), text);
        assertTrue(text.contains("String find(String key)"), "the other overload is untouched");
        assertTrue(text.contains("List<String> keys, @" + N + " Map<String, Object> options"), text);
        assertTrue(text.contains("@" + N + " Object compute(Object input)"), text);
        assertTrue(text.contains("        @" + N + " Object value;"), text);

        // A raw type in the proposal matches the declared generic type (Java forbids overloads that differ
        // only in type arguments), and the match is reported.
        ApplyResult raw = apply(add("get(Class)", AnnotationPosition.parameter(0)));
        assertApplied(raw);
        assertTrue(raw.results().get(0).detail().contains("ignoring type arguments"), raw.results().toString());

        // Different type arguments, qualified-vs-simple names and wrong arity are not matched.
        assertTrue(rejection(apply(add("find(List<Integer>, Map<String,Object>)", AnnotationPosition.parameter(0))), 0)
                .contains("no declarations match"));
        assertTrue(rejection(apply(add("find(java.lang.String)", AnnotationPosition.parameter(0))), 0)
                .contains("candidates"));
        write("src/p/B.java", "package p; class B { B(int x) {} void B(int y) {} }");
        assertTrue(rejection(applier.apply(root, List.of(edit("p/B.java", "p.B", "B(int)",
                AnnotationPosition.parameter(0), EditAction.ADD, N))), 0).contains("ambiguous"));
    }

    @Test
    void annotatesTypeUsePositionsExactly() throws IOException {
        assertApplied(apply(
                add("qualified", AnnotationPosition.field()),
                add("array", AnnotationPosition.field()),
                add("matrix", typeUse("field/arrayComponent")),
                add("wildcard", typeUse("field/typeArgument[0]/wildcardBound")),
                add("nested", AnnotationPosition.field()),
                add("primitive", AnnotationPosition.field()),
                add("find(List<String>, Map<String,Object>)", typeUse("return/typeArgument[0]")),
                add("find(List<String>, Map<String,Object>)", typeUse("parameter[1]/typeArgument[1]"))));
        String text = read("src/p/A.java");
        assertTrue(text.contains("java.util.@" + N + " Map<String, Integer> qualified;"), text);
        assertTrue(text.contains("String @" + N + " [] array;"), text);
        assertTrue(text.contains("String[] @" + N + " [] matrix;"), "the component of String[][]: " + text);
        assertTrue(text.contains("List<? extends @" + N + " Number> wildcard;"), text);
        assertTrue(text.contains("Outer.@" + N + " Inner nested;"), text);
        assertTrue(text.contains("final @" + N + " int primitive"), text);
        assertTrue(text.contains("List<@" + N + " String> find(List<String> keys, Map<String, @" + N
                + " Object> options)"), text);
        compile();
    }

    @Test
    void existingAnnotationsGiveNoOpsAndRemovalsAndRepeatedApplicationChangesNothing() throws IOException {
        AnnotationEdit field = add("field", AnnotationPosition.field());
        assertApplied(apply(field));
        String once = read("src/p/A.java");
        ApplyResult again = apply(field);
        assertFalse(again.committed());
        assertEquals(Outcome.NO_OP, again.results().get(0).outcome());
        assertEquals(once, read("src/p/A.java"));

        // Imported simple name: recognized as the same annotation.
        write("src/p/C.java", """
                package p;

                import org.jspecify.annotations.Nullable;

                class C {
                    @Nullable
                    Object x;
                    private @Nullable Object y;
                }
                """);
        AnnotationEdit cx = edit("p/C.java", "p.C", "x", AnnotationPosition.field(), EditAction.ADD, N);
        assertEquals(Outcome.NO_OP, applier.apply(root, List.of(cx)).results().get(0).outcome());
        ApplyResult removed = applier.apply(root, List.of(
                edit("p/C.java", "p.C", "x", AnnotationPosition.field(), EditAction.REMOVE, N),
                edit("p/C.java", "p.C", "y", AnnotationPosition.field(), EditAction.REMOVE, N)));
        assertApplied(removed);
        assertEquals("""
                package p;

                import org.jspecify.annotations.Nullable;

                class C {
                    Object x;
                    private Object y;
                }
                """, read("src/p/C.java"), "the import is left alone");
        ApplyResult absent = applier.apply(root, List.of(
                edit("p/C.java", "p.C", "x", AnnotationPosition.field(), EditAction.REMOVE, N)));
        assertEquals(Outcome.NO_OP, absent.results().get(0).outcome());
        assertFalse(absent.committed());
    }

    @Test
    void ambiguousOrConflictingExistingAnnotationsAreRejected() throws IOException {
        write("src/p/D.java", """
                package p;

                import org.jspecify.annotations.*;

                class D {
                    @Nullable Object onDemand;
                    @javax.annotation.Nullable Object other;
                    @org.jspecify.annotations.NonNull Object nonNull;
                }
                """);
        assertTrue(rejection(applier.apply(root, List.of(edit("p/D.java", "p.D", "onDemand", AnnotationPosition.field(),
                EditAction.ADD, N))), 0).contains("cannot be resolved"));
        assertTrue(rejection(applier.apply(root, List.of(edit("p/D.java", "p.D", "onDemand", AnnotationPosition.field(),
                EditAction.REMOVE, N))), 0).contains("not guessing"));
        assertTrue(rejection(applier.apply(root, List.of(edit("p/D.java", "p.D", "other", AnnotationPosition.field(),
                EditAction.ADD, N))), 0).contains("duplicate"));
        assertTrue(rejection(applier.apply(root, List.of(edit("p/D.java", "p.D", "nonNull", AnnotationPosition.field(),
                EditAction.ADD, N))), 0).contains("contradict"));
    }

    @Test
    void missingDeclarationsInvalidIndicesAndUnsupportedPositionsAreRejected() throws IOException {
        assertTrue(rejection(apply(add("nothing", AnnotationPosition.field())), 0).contains("declares no field"));
        assertTrue(rejection(apply(edit("p/A.java", "p.A.Missing", "x", AnnotationPosition.field(), EditAction.ADD,
                N)), 0).contains("not found"));
        assertTrue(rejection(apply(edit("p/A.java", "q.A", "field", AnnotationPosition.field(), EditAction.ADD, N)), 0)
                .contains("not in package"));
        assertTrue(rejection(apply(add("absent()", AnnotationPosition.returnType())), 0).contains("no method"));
        assertTrue(rejection(apply(add("find(String)", AnnotationPosition.parameter(1))), 0)
                .contains("no parameter index 1"));
        assertTrue(rejection(apply(add("A(String, int)", AnnotationPosition.returnType())), 0).contains("constructor"));
        assertTrue(rejection(apply(add("none()", AnnotationPosition.returnType())), 0).contains("void"));
        assertTrue(rejection(apply(add("run(String...)", AnnotationPosition.parameter(0))), 0).contains("varargs"));
        assertTrue(rejection(apply(add("cStyle", AnnotationPosition.field())), 0).contains("C-style"));
        assertTrue(rejection(apply(add("field", typeUse("field/typeArgument[0]"))), 0).contains("no type argument"));
        assertTrue(rejection(apply(add("array", typeUse("field/wildcardBound"))), 0).contains("not a bounded"));
        assertTrue(rejection(apply(edit("p/A.java", "p.A", "find(List<String>, Map<String,Object>)", typeUse("return/typeArgument[0]"),
                EditAction.ADD, "javax.annotation.Nullable")), 0).contains("declaration annotation"));
        assertTrue(rejection(apply(edit("p/A.java", "p.A", "qualified", AnnotationPosition.field(), EditAction.ADD,
                "com.example.Unknown")), 0).contains("@Target"));
        assertTrue(rejection(apply(edit("p/Gone.java", "p.Gone", "x", AnnotationPosition.field(), EditAction.ADD, N)),
                0).contains("does not exist"));
        assertEquals(A, read("src/p/A.java"));
    }

    @Test
    void declarationAnnotationsGoInDeclarationPositionAndUnknownOnesOnlyWhereUnambiguous() throws IOException {
        assertApplied(apply(
                edit("p/A.java", "p.A", "array", AnnotationPosition.field(), EditAction.ADD, "javax.annotation.Nullable"),
                edit("p/A.java", "p.A", "get(Class<T>)", AnnotationPosition.returnType(), EditAction.ADD,
                        "javax.annotation.Nullable"),
                edit("p/A.java", "p.A", "field", AnnotationPosition.field(), EditAction.ADD, "com.example.Marker")));
        String text = read("src/p/A.java");
        assertTrue(text.contains("@javax.annotation.Nullable String[] array;"), text);
        assertTrue(text.contains("@javax.annotation.Nullable public <T> T get"), "before the type parameters: " + text);
        assertTrue(text.contains("private @com.example.Marker Object field"), text);
    }

    @Test
    void multiVariableFieldsAreRejectedAndSiblingsAreNeverAnnotated() throws IOException {
        String why = rejection(apply(add("a", AnnotationPosition.field())), 0);
        assertTrue(why.contains("declared together with [b]"), why);
        assertEquals(A, read("src/p/A.java"));
    }

    @Test
    void duplicateAndConflictingEditsInOneProposal() throws IOException {
        AnnotationEdit f = add("field", AnnotationPosition.field());
        ApplyResult dup = apply(f, f);
        assertTrue(dup.committed());
        assertEquals(Outcome.APPLIED, dup.results().get(0).outcome());
        assertEquals(Outcome.NO_OP, dup.results().get(1).outcome());
        assertEquals(1, read("src/p/A.java").split("@" + N, -1).length - 1, "added once");

        AnnotationEdit addValue = edit("p/A.java", "p.A.Inner", "value", AnnotationPosition.field(), EditAction.ADD, N);
        AnnotationEdit removeValue = edit("p/A.java", "p.A.Inner", "value", AnnotationPosition.field(),
                EditAction.REMOVE, N);
        ApplyResult conflict = apply(addValue, removeValue);
        assertTrue(rejection(conflict, 0).contains("conflicting ADD and REMOVE"));
        assertEquals(Outcome.REJECTED, conflict.results().get(1).outcome());

        AnnotationEdit nonNull = edit("p/A.java", "p.A.Inner", "value", AnnotationPosition.field(), EditAction.ADD, NN);
        assertTrue(rejection(apply(addValue, nonNull), 1).contains("two nullness annotations"));
        assertFalse(read("src/p/A.java").contains("@" + N + " Object value"));
    }

    @Test
    void aMultiFileProposalWithOneBadEditWritesNothing() throws IOException {
        byte[] a = Files.readAllBytes(root.resolve("src/p/A.java"));
        byte[] outer = Files.readAllBytes(root.resolve("src/p/Outer.java"));
        ApplyResult r = apply(
                add("field", AnnotationPosition.field()),
                edit("p/Outer.java", "p.Outer", "missing", AnnotationPosition.field(), EditAction.ADD, N));
        assertEquals(Outcome.APPLIED, r.results().get(0).outcome(), "the good edit was valid ...");
        assertEquals(Outcome.REJECTED, r.results().get(1).outcome());
        assertFalse(r.committed(), "... but nothing is committed");
        assertEquals(List.of(), r.changes());
        assertTrue(java.util.Arrays.equals(a, Files.readAllBytes(root.resolve("src/p/A.java"))));
        assertTrue(java.util.Arrays.equals(outer, Files.readAllBytes(root.resolve("src/p/Outer.java"))));
        try (Stream<Path> files = Files.walk(root)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".ga-tmp")));
        }
    }

    @Test
    void pathTraversalAndSymbolicLinksAreRejected() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> new AnnotationEdit("src", "../outside/X.java", "p.X",
                MemberRef.parse("x"), AnnotationPosition.field(), EditAction.ADD, N));
        assertThrows(IllegalArgumentException.class, () -> new AnnotationEdit("/abs", "p/X.java", "p.X",
                MemberRef.parse("x"), AnnotationPosition.field(), EditAction.ADD, N));
        assertThrows(IllegalArgumentException.class, () -> new AnnotationEdit("src/..", "p/X.java", "p.X",
                MemberRef.parse("x"), AnnotationPosition.field(), EditAction.ADD, N));

        Path outside = temp.resolve("outside");
        Files.createDirectories(outside.resolve("q"));
        Files.writeString(outside.resolve("Victim.java"), "package p; class Victim { Object x; }");
        Files.writeString(outside.resolve("q/V.java"), "package q; class V { Object x; }");
        Files.createSymbolicLink(root.resolve("src/p/Victim.java"), outside.resolve("Victim.java"));
        Files.createSymbolicLink(root.resolve("src/q"), outside.resolve("q"));
        assertTrue(rejection(applier.apply(root, List.of(edit("p/Victim.java", "p.Victim", "x",
                AnnotationPosition.field(), EditAction.ADD, N))), 0).contains("symbolic link"));
        assertTrue(rejection(applier.apply(root, List.of(edit("q/V.java", "q.V", "x", AnnotationPosition.field(),
                EditAction.ADD, N))), 0).contains("symbolic link"));
        assertEquals("package p; class Victim { Object x; }", Files.readString(outside.resolve("Victim.java")));
        assertEquals("package q; class V { Object x; }", Files.readString(outside.resolve("q/V.java")));
    }

    @Test
    void positionsAndMembersAreCanonical() {
        assertEquals(AnnotationPosition.field(), typeUse("field"), "a path without steps is the root position");
        assertEquals(AnnotationPosition.parameter(2), typeUse("parameter[2]"));
        assertEquals(MemberRef.parse("find(Map<String, Object>, int)"), MemberRef.parse("find(Map<String,Object>,int)"));
        assertEquals(MemberRef.parse("run(String...)"), MemberRef.parse("run(String[])"));
        assertEquals(MemberRef.parse("f(@Nullable final String)"), MemberRef.parse("f(String)"));
        assertThrows(IllegalArgumentException.class, () -> MemberRef.parse("f(String name)"));
        assertThrows(IllegalArgumentException.class, () -> TypeUsePath.parse("field/elementType"));
        assertThrows(IllegalArgumentException.class, () -> new AnnotationEdit("src", "p/A.java", "p.A",
                MemberRef.parse("f()"), AnnotationPosition.field(), EditAction.ADD, N), "FIELD on a method");
    }

    /** The edited sources compile with the local annotation fixtures. */
    private void compile() throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<String> args = new ArrayList<>(List.of("-proc:none", "-d", temp.resolve("classes").toString()));
        try (Stream<Path> files = Files.walk(root.resolve("src"))) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
        }
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = javac.run(null, null, err, args.toArray(new String[0]));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
    }
}
