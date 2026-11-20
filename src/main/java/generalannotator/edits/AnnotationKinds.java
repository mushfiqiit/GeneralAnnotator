package generalannotator.edits;

import java.util.Set;

/**
 * <b>Provisional (project-spec §6):</b> what the applier knows about annotation types without
 * resolving them. Where an annotation can be written depends on its {@code @Target}:
 * {@code java.util.@A Map} and {@code String @A []} are legal only for type-use annotations, and a
 * type-use annotation in declaration position on an array annotates the element type. Since the
 * applier does not load annotation classes, this table names the common ones. Unknown annotations
 * are only placed where both readings give the same source (a simple, non-array, unqualified
 * declared type). It also groups nullness annotations, so contradictory ones are not combined.
 */
public final class AnnotationKinds {

    /** How an annotation may be placed. */
    public enum Kind {
        /** {@code @Target(TYPE_USE)} only: placed at the exact type position. */
        TYPE_USE,
        /** Declaration annotation (field, method, parameter): placed in declaration position. */
        DECLARATION,
        /** Not in the table. */
        UNKNOWN
    }

    static final Set<String> TYPE_USE_ANNOTATIONS = Set.of(
            "org.jspecify.annotations.Nullable",
            "org.jspecify.annotations.NonNull",
            "org.checkerframework.checker.nullness.qual.Nullable",
            "org.checkerframework.checker.nullness.qual.NonNull",
            "org.checkerframework.checker.nullness.qual.PolyNull");

    static final Set<String> DECLARATION_ANNOTATIONS = Set.of(
            "javax.annotation.Nullable",
            "javax.annotation.CheckForNull",
            "javax.annotation.Nonnull",
            "androidx.annotation.Nullable",
            "androidx.annotation.NonNull",
            "android.support.annotation.Nullable",
            "android.support.annotation.NonNull",
            "android.annotation.Nullable",
            "android.annotation.NonNull",
            "edu.umd.cs.findbugs.annotations.Nullable",
            "edu.umd.cs.findbugs.annotations.CheckForNull",
            "edu.umd.cs.findbugs.annotations.NonNull");

    /** Simple names of annotations that mean "may be null". */
    static final Set<String> NULLABLE_NAMES = Set.of("Nullable", "CheckForNull", "NullableDecl", "NullableType",
            "RecentlyNullable", "PolyNull");
    /** Simple names of annotations that mean "not null". */
    static final Set<String> NON_NULL_NAMES = Set.of("NonNull", "Nonnull", "NotNull", "NonNullDecl", "NonNullType",
            "RecentlyNonNull");

    private AnnotationKinds() {}

    public static Kind kind(String qualifiedName) {
        if (TYPE_USE_ANNOTATIONS.contains(qualifiedName)) {
            return Kind.TYPE_USE;
        }
        if (DECLARATION_ANNOTATIONS.contains(qualifiedName)) {
            return Kind.DECLARATION;
        }
        return Kind.UNKNOWN;
    }

    /** The nullness family of a simple name: {@code "nullable"}, {@code "non-null"} or {@code null}. */
    public static String nullness(String simpleName) {
        if (NULLABLE_NAMES.contains(simpleName)) {
            return "nullable";
        }
        if (NON_NULL_NAMES.contains(simpleName)) {
            return "non-null";
        }
        return null;
    }

    public static String simpleName(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
