package generalannotator.edits;

/** Where on a declaration an annotation goes. */
public enum PositionKind {
    /** The declared type of a field. */
    FIELD,
    /** The return type of a method. */
    RETURN,
    /** The declared type of a method or constructor parameter (needs a parameter index). */
    PARAMETER,
    /** A type nested inside a field, return or parameter type (needs a non-empty type-use path). */
    TYPE_USE
}
