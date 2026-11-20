package generalannotator.edits;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One annotation change, independent of any checker or parser. Two edits are the same change exactly
 * when they are equal; rationale, uncertainty and provenance are kept apart (see {@link
 * ProposedEdit}).
 *
 * @param sourceRoot the source root, relative to the project (for example {@code EventBus/src})
 * @param file the Java file relative to the source root (for example {@code org/example/Foo.java})
 * @param declaringClass fully qualified name of the declaring class, nested classes joined by {@code .}
 * @param member the field, method or constructor
 * @param position where the annotation goes
 * @param action add or remove
 * @param annotation fully qualified annotation name
 */
public record AnnotationEdit(String sourceRoot, String file, String declaringClass, MemberRef member,
        AnnotationPosition position, EditAction action, String annotation) {

    private static final Pattern QUALIFIED = Pattern.compile(
            "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(\\.[\\p{javaJavaIdentifierStart}]"
                    + "[\\p{javaJavaIdentifierPart}]*)+");
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_$.-]+");

    public AnnotationEdit {
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(action, "action");
        requireRelative(sourceRoot, "source root", false);
        requireRelative(file, "file", true);
        if (!QUALIFIED.matcher(declaringClass).matches()) {
            throw new IllegalArgumentException("declaring class '" + declaringClass + "' is not a fully qualified "
                    + "name");
        }
        if (!QUALIFIED.matcher(annotation).matches()) {
            throw new IllegalArgumentException("annotation '" + annotation + "' is not a fully qualified name");
        }
        boolean field = member instanceof MemberRef.Field;
        boolean fieldPosition = position.kind() == PositionKind.FIELD
                || (position.kind() == PositionKind.TYPE_USE && position.path().root() == TypeUsePath.Root.FIELD);
        if (field != fieldPosition) {
            throw new IllegalArgumentException("position " + position + " does not fit member '" + member + "' ("
                    + (field ? "a field" : "a method or constructor") + ")");
        }
    }

    /** A relative path of safe segments: no absolute path, no {@code .} or {@code ..}, no backslash. */
    private static void requireRelative(String path, String what, boolean javaFile) {
        Objects.requireNonNull(path, what);
        if (path.equals(".") && !javaFile) {
            return;
        }
        if (path.isEmpty() || path.startsWith("/") || path.endsWith("/") || path.contains("\\")) {
            throw new IllegalArgumentException(what + " '" + path + "' must be a relative path");
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..") || !SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException(what + " '" + path + "' has an unsafe segment '" + segment + "'");
            }
        }
        if (javaFile && !path.endsWith(".java")) {
            throw new IllegalArgumentException(what + " '" + path + "' is not a .java file");
        }
    }

    /** The project-relative path of the file. */
    public String projectPath() {
        return sourceRoot.equals(".") ? file : sourceRoot + "/" + file;
    }

    /** The identity of the annotated position, regardless of action and annotation. */
    public String positionKey() {
        return projectPath() + "|" + declaringClass + "|" + member.canonical() + "|" + position;
    }

    /** A canonical one-line description, used for identity in reports. */
    public String canonical() {
        return action + " @" + annotation + " at " + position + " of " + declaringClass + "#" + member.canonical()
                + " in " + projectPath();
    }
}
