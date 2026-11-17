package generalannotator.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.checker.CheckerOutcome.ExecutionFailure;
import generalannotator.checker.FailureKind;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.diagnostics.Severity;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class WarningsTextTest {

    private static final Path ROOT = Path.of("/runs/r 1/workspace/target");

    private static Diagnostic finding(String path, int line, OptionalInt column, String message) {
        return new Diagnostic(path, line, column, Severity.ERROR, Optional.of("NullAway"), message, Optional.empty());
    }

    @Test
    void completedAnalysisListsOneLinePerFindingWithTargetRelativePaths() {
        var outcome = new CompletedAnalysis(List.of(
                finding(ROOT + "/EventBus/src/A.java", 20, OptionalInt.of(25),
                        "@NonNull field 'head' not initialized\n(see http://t.uber.com/nullaway )"),
                finding(ROOT + "/EventBus/src/B.java", 7, OptionalInt.empty(), "returning @Nullable")), List.of());

        String text = WarningsText.format("nullaway", "/home/me/EventBus", outcome, ROOT);

        assertEquals("""
                # GeneralAnnotator warnings
                # checker: nullaway
                # target:  /home/me/EventBus
                # status:  COMPLETED
                # count:   2
                # format:  path:line:column: severity: [checker] message (first line; full text in report.json)
                EventBus/src/A.java:20:25: error: [NullAway] @NonNull field 'head' not initialized
                EventBus/src/B.java:7: error: [NullAway] returning @Nullable
                """, text);
    }

    @Test
    void failureIsMarkedIncomplete() {
        var outcome = new ExecutionFailure(FailureKind.COMPILATION_FAILED, List.of("cannot find symbol"),
                List.of(finding(ROOT + "/A.java", 1, OptionalInt.of(1), "m")), List.of());

        String text = WarningsText.format("nullaway", "/t", outcome, ROOT);

        assertTrue(text.contains("# status:  EXECUTION_FAILURE (COMPILATION_FAILED)"));
        assertTrue(text.contains("this list is INCOMPLETE"));
        assertTrue(text.endsWith("A.java:1:1: error: [NullAway] m\n"));
    }

    @Test
    void pathOutsideWorkingCopyIsKeptAsReported() {
        var outcome = new CompletedAnalysis(List.of(finding("/elsewhere/X.java", 3, OptionalInt.empty(), "m")),
                List.of());
        assertTrue(WarningsText.format("nullaway", "/t", outcome, null).contains("/elsewhere/X.java:3: error:"));
    }
}
