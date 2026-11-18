package generalannotator.visualization;

import generalannotator.diagnostics.Diagnostic;
import generalannotator.diagnostics.Severity;
import generalannotator.graph.GraphNode;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningNode;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Deterministic warning graphs for visualization tests. */
public final class VisualizationFixtures {

    /** A message exercising every escaping rule: quotes, backslash escapes, entities, markup, newline. */
    public static final String TRICKY_MESSAGE =
            "field \"a\\b\" uses \\N and \\G & &lt; <T> 'x'\n(see http://t.uber.com/nullaway )";

    private VisualizationFixtures() {}

    public static WarningNode node(String id, String path, int line, Integer column, Severity severity, String message) {
        Diagnostic d = new Diagnostic("/runs/r 1/workspace/target/" + path, line,
                column == null ? OptionalInt.empty() : OptionalInt.of(column), severity, Optional.of("NullAway"),
                message, Optional.of("    source"));
        return new WarningNode(id, d, Optional.of(path), true, 0);
    }

    /** Three isolated warning nodes, one with a tricky message. */
    public static WarningGraph threeIsolated() {
        List<GraphNode> nodes = List.of(
                node("W0001", "src/p/A.java", 46, 30, Severity.ERROR, "@NonNull static field 'x' not initialized"),
                node("W0002", "src/p/B File.java", 7, null, Severity.WARNING, TRICKY_MESSAGE),
                node("W0003", "src/p/C.java", 1, 1, Severity.NOTE, "note message"));
        return new WarningGraph("20261118T000203Z-nullaway", "nullaway", nodes, List.of());
    }

    public static WarningGraph empty() {
        return new WarningGraph("20261118T000203Z-nullaway", "nullaway", List.of(), List.of());
    }
}
