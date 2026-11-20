package generalannotator.edits;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Where an annotation goes. FIELD, RETURN and PARAMETER mean the declared type itself; TYPE_USE
 * names a type nested inside it with a non-empty {@link TypeUsePath}. A TYPE_USE path without steps
 * is the same position as its root and is canonicalized to FIELD, RETURN or PARAMETER by {@link
 * #of}, so equal positions are equal values.
 */
public record AnnotationPosition(PositionKind kind, OptionalInt parameterIndex, Optional<TypeUsePath> typeUsePath) {

    public AnnotationPosition {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(parameterIndex, "parameterIndex");
        Objects.requireNonNull(typeUsePath, "typeUsePath");
        if ((kind == PositionKind.PARAMETER) != parameterIndex.isPresent()) {
            throw new IllegalArgumentException("a parameter index is required exactly for PARAMETER");
        }
        if (parameterIndex.isPresent() && parameterIndex.getAsInt() < 0) {
            throw new IllegalArgumentException("parameter index must be >= 0");
        }
        if ((kind == PositionKind.TYPE_USE) != typeUsePath.isPresent()) {
            throw new IllegalArgumentException("a type-use path is required exactly for TYPE_USE");
        }
        if (typeUsePath.isPresent() && typeUsePath.get().steps().isEmpty()) {
            throw new IllegalArgumentException("a TYPE_USE path needs at least one step; use FIELD, RETURN or "
                    + "PARAMETER for the declared type itself");
        }
    }

    public static AnnotationPosition field() {
        return new AnnotationPosition(PositionKind.FIELD, OptionalInt.empty(), Optional.empty());
    }

    public static AnnotationPosition returnType() {
        return new AnnotationPosition(PositionKind.RETURN, OptionalInt.empty(), Optional.empty());
    }

    public static AnnotationPosition parameter(int index) {
        return new AnnotationPosition(PositionKind.PARAMETER, OptionalInt.of(index), Optional.empty());
    }

    /** A position from a type-use path; a path without steps becomes its root position. */
    public static AnnotationPosition of(TypeUsePath path) {
        if (path.steps().isEmpty()) {
            return switch (path.root()) {
                case FIELD -> field();
                case RETURN -> returnType();
                case PARAMETER -> parameter(path.parameterIndex().getAsInt());
            };
        }
        return new AnnotationPosition(PositionKind.TYPE_USE, OptionalInt.empty(), Optional.of(path));
    }

    /** The path from the declaration to the annotated type (empty steps for the declared type). */
    public TypeUsePath path() {
        return switch (kind) {
            case FIELD -> new TypeUsePath(TypeUsePath.Root.FIELD, OptionalInt.empty(), List.of());
            case RETURN -> new TypeUsePath(TypeUsePath.Root.RETURN, OptionalInt.empty(), List.of());
            case PARAMETER -> new TypeUsePath(TypeUsePath.Root.PARAMETER, parameterIndex, List.of());
            case TYPE_USE -> typeUsePath.orElseThrow();
        };
    }

    @Override
    public String toString() {
        return switch (kind) {
            case FIELD, RETURN -> kind.name();
            case PARAMETER -> "PARAMETER[" + parameterIndex.getAsInt() + "]";
            case TYPE_USE -> "TYPE_USE " + typeUsePath.orElseThrow();
        };
    }
}
