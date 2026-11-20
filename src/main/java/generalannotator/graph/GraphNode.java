package generalannotator.graph;

/**
 * A node of a {@link WarningGraph}: a checker finding ({@link WarningNode}) or a concrete annotation
 * edit evaluated against a baseline state ({@link AnnotationNode}).
 */
public sealed interface GraphNode permits WarningNode, AnnotationNode {

    /** Unique, readable ID within one graph (for example {@code W0001} or {@code A0001}). */
    String id();

    /** Node kind as written to JSON ({@code warning} or {@code annotation}). */
    String kind();
}
