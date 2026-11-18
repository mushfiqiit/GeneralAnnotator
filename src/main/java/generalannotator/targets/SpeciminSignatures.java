package generalannotator.targets;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.WildcardTree;
import java.util.List;
import java.util.Locale;

/**
 * Formats Specimin target signatures, following Specimin's {@code TargetMemberFinderVisitor} and
 * {@code JavaParserUtil.removeMethodReturnTypeAndAnnotations} (mushfiqiit/specimin, commit b1e5d43):
 *
 * <ul>
 *   <li>Class: the top-level class's fully qualified name, with nested classes appended by {@code .}
 *       (not {@code $}).
 *   <li>Method: {@code Class#name(T1, T2)}; constructor: {@code Class#SimpleClassName(T1, T2)}.
 *       Parameter types appear as written in the source (simple names stay simple, generics kept,
 *       {@code T[]} for arrays including C-style {@code T x[]}, {@code T...} for method varargs) with
 *       annotations removed. Constructor varargs are written without {@code ...} (just the element
 *       type), because that is how Specimin's JavaParser 3.28.2 prints constructor declarations. Receiver parameters are excluded. No return type, modifiers or type
 *       parameters.
 *   <li>Field: {@code Class#name}.
 * </ul>
 *
 * Specimin removes all whitespace before comparing, so the {@code ", "} separators used here are
 * only for readability.
 */
public final class SpeciminSignatures {

    private SpeciminSignatures() {}

    public static String method(String qualifiedClass, String name, List<String> parameterTypes) {
        return qualifiedClass + "#" + name + "(" + String.join(", ", parameterTypes) + ")";
    }

    public static String field(String qualifiedClass, String name) {
        return qualifiedClass + "#" + name;
    }

    /** Thrown for type syntax that cannot appear in a Specimin parameter list. */
    public static final class UnsupportedTypeException extends Exception {
        private static final long serialVersionUID = 1L;

        UnsupportedTypeException(String message) {
            super(message);
        }
    }

    /**
     * Renders a parameter type tree as written in the source, without annotations.
     *
     * @param varargs whether this is a varargs parameter (its tree is an array type)
     */
    public static String parameterType(Tree type, boolean varargs) throws UnsupportedTypeException {
        if (varargs) {
            if (!(type instanceof ArrayTypeTree array)) {
                throw new UnsupportedTypeException("varargs parameter without an array type: " + type.getKind());
            }
            return render(array.getType()) + "...";
        }
        return render(type);
    }

    /**
     * The element type of a varargs parameter, without {@code ...}: JavaParser 3.28.2, which Specimin
     * uses, writes constructor varargs parameters this way ({@code C(int, String)} for {@code C(int x,
     * String... r)}), while method varargs keep {@code ...}.
     */
    public static String varargsElementType(Tree type) throws UnsupportedTypeException {
        if (!(type instanceof ArrayTypeTree array)) {
            throw new UnsupportedTypeException("varargs parameter without an array type: " + type.getKind());
        }
        return render(array.getType());
    }

    static String render(Tree type) throws UnsupportedTypeException {
        return switch (type) {
            case PrimitiveTypeTree p -> p.getPrimitiveTypeKind().name().toLowerCase(Locale.ROOT);
            case IdentifierTree id -> id.getName().toString();
            case MemberSelectTree select -> render(select.getExpression()) + "." + select.getIdentifier();
            case ParameterizedTypeTree pt -> {
                StringBuilder args = new StringBuilder();
                for (Tree arg : pt.getTypeArguments()) {
                    if (!args.isEmpty()) {
                        args.append(", ");
                    }
                    args.append(render(arg));
                }
                yield render(pt.getType()) + "<" + args + ">";
            }
            case ArrayTypeTree array -> render(array.getType()) + "[]";
            case AnnotatedTypeTree annotated -> render(annotated.getUnderlyingType());
            case WildcardTree w -> switch (w.getKind()) {
                case UNBOUNDED_WILDCARD -> "?";
                case EXTENDS_WILDCARD -> "? extends " + render(w.getBound());
                case SUPER_WILDCARD -> "? super " + render(w.getBound());
                default -> throw new UnsupportedTypeException("unexpected wildcard " + w.getKind());
            };
            default -> throw new UnsupportedTypeException("unsupported parameter type syntax " + type.getKind());
        };
    }
}
