package generalannotator.evaluation.matching;

import generalannotator.diagnostics.Diagnostic;
import java.util.Objects;

/**
 * One checker finding of one repository state, prepared for matching.
 *
 * @param id the finding's ID within its state (a baseline warning node ID, or {@code R001, ...} for a
 *     state after an edit)
 * @param diagnostic the complete diagnostic
 * @param path the normalized project-relative path (or the reported path if it lies outside the state)
 * @param anchor the enclosing declaration in that state's source
 */
public record ObservedFinding(String id, Diagnostic diagnostic, String path, Anchor anchor) {

    public ObservedFinding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(diagnostic, "diagnostic");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(anchor, "anchor");
    }

    /**
     * The innermost declaration (member, initializer, enum constant or type) whose source range contains
     * the finding, identified structurally rather than by line.
     *
     * @param declaration for example {@code p.A#get(String)}, {@code p.A#field:x}, {@code p.A.Inner#<init>()};
     *     {@code ?} if unresolved
     * @param line the declaration's first line, or 0 if unresolved
     * @param problem why the anchor is unresolved, or empty
     */
    public record Anchor(String declaration, int line, String problem) {

        public static Anchor unresolved(String problem) {
            return new Anchor("?", 0, problem);
        }

        public boolean resolved() {
            return line > 0;
        }
    }
}
