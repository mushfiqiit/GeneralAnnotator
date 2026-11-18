package generalannotator.graph;

import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.diagnostics.Severity;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Deterministic findings for graph tests. */
final class GraphFixtures {

    static final Path ROOT = Path.of("/runs/r 1/workspace/target");

    private GraphFixtures() {}

    static Diagnostic nullaway(String relativePath, int line, Integer column, String message) {
        return new Diagnostic(ROOT.resolve(relativePath).toString(), line,
                column == null ? OptionalInt.empty() : OptionalInt.of(column), Severity.ERROR,
                Optional.of("NullAway"), message + "\n(see http://t.uber.com/nullaway )",
                Optional.of("    source line"));
    }

    /** Four findings in deliberately unsorted (javac-like) order. */
    static List<Diagnostic> findings() {
        return List.of(
                nullaway("src/p/Queue.java", 43, 22, "assigning @Nullable expression to @NonNull field"),
                nullaway("src/p/EventBus.java", 119, 26, "assigning @Nullable expression to @NonNull field"),
                nullaway("src/p/Queue.java", 20, 25, "@NonNull field 'head' not initialized"),
                nullaway("src/p/EventBus.java", 46, null, "@NonNull static field 'defaultInstance' not initialized"));
    }

    static CompletedAnalysis completed(List<Diagnostic> findings) {
        return new CompletedAnalysis(findings, List.of());
    }
}
