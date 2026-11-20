package generalannotator.graph;

import java.util.Objects;

/**
 * A directed edge between two nodes of a {@link WarningGraph}.
 *
 * <p>The initial graph has no edges. An evaluation graph has two kinds, both derived only from a
 * trustworthy comparison (a completed analysis after the edit, compared by a {@code WarningMatcher}):
 * <ul>
 *   <li>{@value #RESOLVED_BY}: baseline warning → annotation; the warning has no counterpart after
 *       the edit.
 *   <li>{@value #INTRODUCES}: annotation → warning observed only in the state after the edit.
 * </ul>
 *
 * @param source ID of the source node
 * @param target ID of the target node
 * @param kind edge kind
 */
public record GraphEdge(String source, String target, String kind) {

    /** Baseline warning → annotation that resolved it. */
    public static final String RESOLVED_BY = "resolved-by";
    /** Annotation → warning it introduced. */
    public static final String INTRODUCES = "introduces";

    public GraphEdge {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(kind, "kind");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("edge kind must not be blank");
        }
    }
}
