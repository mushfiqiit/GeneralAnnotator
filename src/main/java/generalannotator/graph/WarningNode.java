package generalannotator.graph;

import generalannotator.diagnostics.Diagnostic;
import java.util.Objects;
import java.util.Optional;

/**
 * One checker finding observed in one analysis run.
 *
 * <p>The ID identifies this occurrence within its graph only. Nothing here says whether a warning
 * in another run or repository state is "the same" warning (project-spec §6.1).
 *
 * @param id unique ID within the graph, for example {@code W0001}
 * @param diagnostic the complete diagnostic as parsed from the checker output
 * @param targetPath the diagnostic's path relative to the original target project, if it lies in
 *     the working copy
 * @param root whether this node is a root of the graph (all initial warnings are roots)
 * @param distance distance from the roots; 0 for roots
 */
public record WarningNode(
        String id, Diagnostic diagnostic, Optional<String> targetPath, boolean root, int distance)
        implements GraphNode {

    public static final String KIND = "warning";

    public WarningNode {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("node id must not be blank");
        }
        Objects.requireNonNull(diagnostic, "diagnostic");
        Objects.requireNonNull(targetPath, "targetPath");
        if (distance < 0) {
            throw new IllegalArgumentException("distance must be >= 0: " + distance);
        }
        if (root && distance != 0) {
            throw new IllegalArgumentException("a root node must have distance 0, got " + distance);
        }
    }

    @Override
    public String kind() {
        return KIND;
    }
}
