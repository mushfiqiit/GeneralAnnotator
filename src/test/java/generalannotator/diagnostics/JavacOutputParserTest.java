package generalannotator.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.TestResources;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class JavacOutputParserTest {

    private static CompilerOutput parse(String fixture) {
        return JavacOutputParser.parse(TestResources.text("javac-output/" + fixture));
    }

    @Test
    void emptyOutputHasNothingAndIsClean() {
        CompilerOutput out = parse("empty.txt");
        assertTrue(out.diagnostics().isEmpty());
        assertTrue(out.isClean());
        assertEquals(OptionalInt.empty(), out.reportedErrorCount());
    }

    @Test
    void singleNullAwayDiagnosticKeepsAllFields() {
        CompilerOutput out = parse("single-nullaway.txt");
        assertTrue(out.isClean(), out.problems().toString());
        Diagnostic d = out.diagnostics().getFirst();
        assertEquals(1, out.diagnostics().size());
        assertEquals("/work/my project/src/com/example/Foo.java", d.sourcePath());
        assertEquals(12, d.line());
        assertEquals(OptionalInt.of(17), d.column());
        assertEquals(Severity.ERROR, d.severity());
        assertEquals(Optional.of("NullAway"), d.checkerName());
        assertEquals(
                "dereferenced expression x is @Nullable\n(see http://t.uber.com/nullaway )",
                d.message());
        assertEquals(Optional.of("        return x.length();"), d.sourceSnippet());
        assertEquals(OptionalInt.of(1), out.reportedErrorCount());
    }

    @Test
    void multipleAndMixedDiagnosticsAreSeparatedByTagAndSeverity() {
        CompilerOutput out = parse("multiple-mixed.txt");
        assertTrue(out.isClean(), out.problems() + " " + out.unrecognizedLines());
        List<Diagnostic> ds = out.diagnostics();
        assertEquals(3, ds.size());
        assertTrue(ds.get(0).isFromChecker("NullAway"));
        assertTrue(ds.get(0).message().startsWith("initializer method does not guarantee"));
        assertEquals(Optional.of("deprecation"), ds.get(1).checkerName());
        assertEquals(Severity.WARNING, ds.get(1).severity());
        assertEquals(9, ds.get(2).line());
        assertEquals(2, out.generalMessages().size());
        assertEquals(Severity.NOTE, out.generalMessages().getFirst().severity());
        assertEquals(OptionalInt.of(2), out.reportedErrorCount());
        assertEquals(OptionalInt.of(1), out.reportedWarningCount());
    }

    @Test
    void multilineJavacMessageKeepsContinuationLines() {
        CompilerOutput out = parse("unrelated-compile-error.txt");
        assertTrue(out.isClean(), out.problems().toString());
        Diagnostic broken = out.diagnostics().get(1);
        assertEquals(Optional.empty(), broken.checkerName());
        assertEquals(
                "cannot find symbol\nsymbol:   class Missing\nlocation: class Broken", broken.message());
        assertEquals(OptionalInt.of(9), broken.column());
    }

    @Test
    void truncatedOutputIsReportedAsProblem() {
        CompilerOutput out = parse("truncated.txt");
        assertEquals(2, out.diagnostics().size());
        assertFalse(out.isClean());
        assertTrue(out.problems().stream().anyMatch(p -> p.contains("no source/caret lines")));
        assertTrue(out.problems().stream().anyMatch(p -> p.contains("count line is missing")));
    }

    @Test
    void crashOutputIsUnrecognizedNotDropped() {
        CompilerOutput out = parse("crash.txt");
        assertTrue(out.diagnostics().isEmpty());
        assertEquals(3, out.unrecognizedLines().size());
        assertTrue(out.unrecognizedLines().getFirst().startsWith("Exception in thread"));
    }

    @Test
    void generalErrorIsRecognizedAndCounted() {
        CompilerOutput out = parse("general-error.txt");
        assertTrue(out.isClean(), out.problems().toString());
        assertEquals(Severity.ERROR, out.generalMessages().getFirst().severity());
        assertEquals("plug-in not found: ErrorProne", out.generalMessages().getFirst().text());
    }

    @Test
    void countMismatchIsAProblem() {
        CompilerOutput out = JavacOutputParser.parse(
                TestResources.text("javac-output/single-nullaway.txt").replace("1 error", "3 errors"));
        assertTrue(out.problems().stream().anyMatch(p -> p.contains("reported 3 error(s) but 1")));
    }

    @Test
    void unindentedLineAfterCaretEndsDiagnosticAndIsUnrecognized() {
        String text = TestResources.text("javac-output/single-nullaway.txt")
                .replace("1 error", "garbage line\n1 error");
        CompilerOutput out = JavacOutputParser.parse(text);
        assertEquals(1, out.diagnostics().size());
        assertEquals(List.of("garbage line"), out.unrecognizedLines());
    }

    @Test
    void streamsAreCombined() {
        CompilerOutput out = JavacOutputParser.parse(
                TestResources.text("javac-output/single-nullaway.txt").replace("1 error\n", ""),
                "1 error\n");
        assertTrue(out.isClean(), out.problems().toString());
        assertEquals(1, out.diagnostics().size());
    }

    @Test
    void windowsLineEndingsAreAccepted() {
        CompilerOutput out = JavacOutputParser.parse(
                TestResources.text("javac-output/single-nullaway.txt").replace("\n", "\r\n"));
        assertTrue(out.isClean(), out.problems().toString());
        assertEquals(1, out.diagnostics().size());
    }
}
