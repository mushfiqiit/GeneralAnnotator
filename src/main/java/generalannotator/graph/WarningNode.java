package generalannotator.graph;

import generalannotator.diagnostics.Diagnostic;
import java.util.Objects;
import java.util.Optional;

/**
 * One checker finding observed in one analysis run.
 *
 * <p>The ID identifies this occurrence within its graph only. Nothing here says whether a warning
 * in another run or repository state is "the same" warning (project-spec §6.1); an evaluation
 * graph records such links only as edges derived from a {@code WarningMatcher} comparison.
 *
 * @param id unique ID within the graph, for example {@code W0001}
 * @param diagnostic the complete diagnostic as parsed from the checker output
 * @param targetPath the diagnostic's path relative to the original target project, if it lies in
 *     the working copy
 * @param root whether this node is a root of the graph (warnings of the initial or baseline state)
 * @param distance number of annotation edits applied to the baseline in the state where the warning
 *     was observed: 0 for roots, 1 for a warning introduced by one evaluated edit
 * @param state the repository state the warning was observed in (for example {@code S0} for an
 *     evaluation baseline, {@code S0+A0003} for the state after edit {@code A0003}); empty in an
 *     initial warning graph, where every node belongs to the run's single analyzed state
 */
public record WarningNode(
        String id, Diagnostic diagnostic, Optional<String> targetPath, boolean root, int distance,
        Optional<String> state)
        implements GraphNode {

    public static final String KIND = "warning";

    public WarningNode {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("node id must not be blank");
        }
        Objects.requireNonNull(diagnostic, "diagnostic");
        Objects.requireNonNull(targetPath, "targetPath");
        Objects.requireNonNull(state, "state");
        if (distance < 0) {
            throw new IllegalArgumentException("distance must be >= 0: " + distance);
        }
        if (root && distance != 0) {
            throw new IllegalArgumentException("a root node must have distance 0, got " + distance);
        }
    }

    /** A node of an initial warning graph (no state label). */
    public WarningNode(String id, Diagnostic diagnostic, Optional<String> targetPath, boolean root, int distance) {
        this(id, diagnostic, targetPath, root, distance, Optional.empty());
    }

    @Override
    public String kind() {
        return KIND;
    }
}
