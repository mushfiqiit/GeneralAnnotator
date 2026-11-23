package generalannotator.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A directed edge between two nodes of a {@link WarningGraph}.
 *
 * <p>The initial graph has no edges. Evaluation graphs, single-round or extended across rounds, have exactly two
 * kinds, both derived only from a trustworthy comparison of one independent package evaluation (a completed
 * analysis after the whole package, compared with its source state by a {@code WarningMatcher}):
 * <ul>
 *   <li>{@value #RESOLVED_BY}: warning → annotation; applying the package resolved the warning.
 *   <li>{@value #INTRODUCES}: annotation → warning; applying the package introduced the warning.
 * </ul>
 * {@link WarningGraph} accepts no other kind.
 *
 * @param source ID of the source node
 * @param target ID of the target node
 * @param kind edge kind
 * @param provenance what supports the edge (evaluation, states, matcher, attribution), in a fixed order; empty
 *     for edges of a single evaluation graph, whose provenance is that evaluation
 */
public record GraphEdge(String source, String target, String kind, Map<String, String> provenance) {

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
        provenance = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(provenance, "provenance")));
    }

    /** An edge without recorded provenance. */
    public GraphEdge(String source, String target, String kind) {
        this(source, target, kind, Map.of());
    }

    /** The edge's identity in a graph: source, target and kind (provenance excluded). */
    public String key() {
        return source + "\n" + target + "\n" + kind;
    }
}
