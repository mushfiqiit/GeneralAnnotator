package generalannotator.graph;

import java.util.Objects;

/**
 * A directed edge between two nodes of a {@link WarningGraph}.
 *
 * <p>No edge kinds are produced yet: the initial graph has no edges. The kinds and meaning of
 * edges (for example warning → annotation action → resulting warning) belong to later tasks and
 * depend on unresolved decisions (project-spec §6).
 *
 * @param source ID of the source node
 * @param target ID of the target node
 * @param kind edge kind
 */
public record GraphEdge(String source, String target, String kind) {

    public GraphEdge {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(kind, "kind");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("edge kind must not be blank");
        }
    }
}
