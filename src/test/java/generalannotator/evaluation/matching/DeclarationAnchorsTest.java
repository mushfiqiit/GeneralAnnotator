package generalannotator.evaluation.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Structural anchors of findings, and their stability under annotation insertion. */
class DeclarationAnchorsTest {

    private static final String SOURCE = """
            package p;

            public class A {
                Object x, y;
                static { init(); }

                A(String s) { x = s; }

                Object get(java.util.List<String> names, int... more) {
                    Runnable r = new Runnable() {
                        public void run() { names.size(); }
                    };
                    return x;
                }

                static class Inner {
                    void m() {}
                }

                enum E { ONE { void f() {} }; void f() {} }
            }
            """;

    @TempDir Path temp;

    private DeclarationAnchors anchors(String text) throws IOException {
        Path dir = Files.createDirectories(temp.resolve("state" + Math.abs(text.hashCode())));
        Files.createDirectories(dir.resolve("src/p"));
        Files.writeString(dir.resolve("src/p/A.java"), text);
        return new DeclarationAnchors(dir);
    }

    private static String at(DeclarationAnchors a, int line, int column) {
        return a.anchor("src/p/A.java", line, OptionalInt.of(column)).declaration();
    }

    @Test
    void identifiesMembersStructurally() throws IOException {
        DeclarationAnchors a = anchors(SOURCE);

        assertEquals("p.A#field:x,y", at(a, 4, 12));
        assertEquals("p.A#<clinit>#0", at(a, 5, 14));
        assertEquals("p.A#<init>(String)", at(a, 7, 20));
        assertEquals("p.A#get(java.util.List<String>,int[])", at(a, 13, 9));
        assertEquals("p.A.get(java.util.List<String>,int[]).new Runnable{}#run()", at(a, 11, 33));
        assertEquals("p.A.Inner#m()", at(a, 17, 9));
        assertEquals("p.A.E.ONE{}#f()", at(a, 20, 22));
        assertEquals("p.A.E#f()", at(a, 20, 39));
        assertEquals(9, a.anchor("src/p/A.java", 13, OptionalInt.of(9)).line());
        assertEquals("p.A#get(java.util.List<String>,int[])", a.anchor("src/p/A.java", 13, OptionalInt.empty())
                .declaration());
    }

    @Test
    void insertedAnnotationsDoNotChangeTheAnchor() throws IOException {
        DeclarationAnchors before = anchors(SOURCE);
        DeclarationAnchors after = anchors(SOURCE
                .replace("Object get(java.util.List<String> names", "@org.jspecify.annotations.Nullable Object get("
                        + "java.util.List<@org.jspecify.annotations.Nullable String> names")
                .replace("A(String s)", "A(@org.jspecify.annotations.Nullable String s)"));

        assertEquals(at(before, 13, 9), at(after, 13, 9));
        assertEquals(before.anchor("src/p/A.java", 13, OptionalInt.of(9)).line(),
                after.anchor("src/p/A.java", 13, OptionalInt.of(9)).line());
        assertEquals(at(before, 7, 20), at(after, 7, 60));
    }

    @Test
    void unparsableOrMissingFilesGiveUnresolvedAnchors() throws IOException {
        DeclarationAnchors broken = anchors("package p; class A { void m( }");
        ObservedFinding.Anchor a = broken.anchor("src/p/A.java", 1, OptionalInt.of(22));
        assertFalse(a.resolved());
        assertTrue(a.problem().contains("does not parse"), a.problem());

        ObservedFinding.Anchor missing = broken.anchor("src/p/Missing.java", 1, OptionalInt.empty());
        assertFalse(missing.resolved());
        ObservedFinding.Anchor outside = broken.anchor("../escape/A.java", 1, OptionalInt.empty());
        assertFalse(outside.resolved());
        assertEquals("?", outside.declaration());
    }
}
