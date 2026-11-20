package generalannotator.edits;

import java.util.List;

/** A distinct edit of a proposal and every answer entry that proposed it (identical edits merged). */
public record ProposedEdit(AnnotationEdit edit, List<EditOrigin> origins) {

    public ProposedEdit {
        origins = List.copyOf(origins);
        if (origins.isEmpty()) {
            throw new IllegalArgumentException("a proposed edit needs at least one origin");
        }
    }
}
