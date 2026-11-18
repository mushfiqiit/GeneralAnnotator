package generalannotator.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.checker.CheckerOutcome.ExecutionFailure;
import generalannotator.checker.FailureKind;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.diagnostics.Severity;
import generalannotator.graph.InitialWarningGraph;
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
    void completedAnalysisListsGraphIdsInGraphOrder() {
        var outcome = new CompletedAnalysis(List.of(
                finding(ROOT + "/EventBus/src/B.java", 7, OptionalInt.empty(), "returning @Nullable"),
                finding(ROOT + "/EventBus/src/A.java", 20, OptionalInt.of(25),
                        "@NonNull field 'head' not initialized\n(see http://t.uber.com/nullaway )")), List.of());
        var graph = InitialWarningGraph.build("r", "nullaway", outcome, ROOT);

        String text = WarningsText.format("nullaway", "/home/me/EventBus", outcome, ROOT, Optional.of(graph));

        assertEquals("""
                # GeneralAnnotator warnings
                # checker: nullaway
                # target:  /home/me/EventBus
                # status:  COMPLETED
                # count:   2
                # format:  ID  path:line:column: severity: [checker] message (IDs as in warning-graph.json; full text in report.json)
                W0001  EventBus/src/A.java:20:25: error: [NullAway] @NonNull field 'head' not initialized
                W0002  EventBus/src/B.java:7: error: [NullAway] returning @Nullable
                """, text);
    }

    @Test
    void failureIsMarkedIncompleteWithoutIds() {
        var outcome = new ExecutionFailure(FailureKind.COMPILATION_FAILED, List.of("cannot find symbol"),
                List.of(finding(ROOT + "/A.java", 1, OptionalInt.of(1), "m")), List.of());

        String text = WarningsText.format("nullaway", "/t", outcome, ROOT, Optional.empty());

        assertTrue(text.contains("# status:  EXECUTION_FAILURE (COMPILATION_FAILED)"));
        assertTrue(text.contains("this list is INCOMPLETE and no warning"));
        assertTrue(text.endsWith("\nA.java:1:1: error: [NullAway] m\n"));
    }

    @Test
    void graphMustBePresentExactlyForCompletedAnalyses() {
        var completed = new CompletedAnalysis(List.of(), List.of());
        assertThrows(IllegalArgumentException.class,
                () -> WarningsText.format("nullaway", "/t", completed, ROOT, Optional.empty()));
        var failure = ExecutionFailure.before(FailureKind.TIMED_OUT, "t");
        var graph = InitialWarningGraph.build("r", "nullaway", completed, ROOT);
        assertThrows(IllegalArgumentException.class,
                () -> WarningsText.format("nullaway", "/t", failure, ROOT, Optional.of(graph)));
    }
}
