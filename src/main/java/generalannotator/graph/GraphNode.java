package generalannotator.graph;

/**
 * A node of a {@link WarningGraph}. Only {@link WarningNode} exists so far; annotation-action
 * nodes are expected to be added as further permitted types by later tasks.
 */
public sealed interface GraphNode permits WarningNode {

    /** Unique, readable ID within one graph (for example {@code W0001}). */
    String id();

    /** Node kind as written to JSON (for example {@code warning}). */
    String kind();
}
