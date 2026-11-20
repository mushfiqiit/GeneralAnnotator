package generalannotator.edits;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A structured path to a type inside a declaration's type, as in the prompt template grammar:
 * {@code field}, {@code return} or {@code parameter[i]}, followed by steps {@code /typeArgument[i]},
 * {@code /arrayComponent} and {@code /wildcardBound}. For example {@code return/typeArgument[0]} is
 * {@code String} in {@code List<String> f()}.
 *
 * @param root the declaration type the path starts from
 * @param parameterIndex the parameter index when {@code root} is {@link Root#PARAMETER}
 * @param steps the steps, in order from the outside in
 */
public record TypeUsePath(Root root, OptionalInt parameterIndex, List<Step> steps) {

    /** Where a path starts. */
    public enum Root { FIELD, RETURN, PARAMETER }

    /** One step into a type. */
    public sealed interface Step permits TypeArgument, ArrayComponent, WildcardBound {}

    /** The {@code index}-th type argument (from 0) of a parameterized type's last name component. */
    public record TypeArgument(int index) implements Step {
        public TypeArgument {
            if (index < 0) {
                throw new IllegalArgumentException("type argument index must be >= 0");
            }
        }
    }

    /** The component type of an array type. */
    public record ArrayComponent() implements Step {}

    /** The bound of a wildcard ({@code ? extends B} or {@code ? super B}). */
    public record WildcardBound() implements Step {}

    private static final Pattern ROOT = Pattern.compile("(field|return|parameter\\[([0-9]+)])");
    private static final Pattern STEP = Pattern.compile("typeArgument\\[([0-9]+)]|arrayComponent|wildcardBound");

    public TypeUsePath {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(parameterIndex, "parameterIndex");
        steps = List.copyOf(steps);
        if ((root == Root.PARAMETER) != parameterIndex.isPresent()) {
            throw new IllegalArgumentException("a parameter index is required exactly for parameter roots");
        }
    }

    /** Parses the template grammar; throws {@link IllegalArgumentException} with the reason. */
    public static TypeUsePath parse(String text) {
        String[] parts = text.split("/", -1);
        Matcher root = ROOT.matcher(parts[0]);
        if (!root.matches()) {
            throw new IllegalArgumentException("type-use path '" + text + "' must start with field, return or "
                    + "parameter[i]");
        }
        List<Step> steps = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            Matcher m = STEP.matcher(parts[i]);
            if (!m.matches()) {
                throw new IllegalArgumentException("type-use path '" + text + "' has an unknown step '" + parts[i]
                        + "' (expected typeArgument[i], arrayComponent or wildcardBound)");
            }
            if (m.group(1) != null) {
                steps.add(new TypeArgument(Integer.parseInt(m.group(1))));
            } else if (parts[i].equals("arrayComponent")) {
                steps.add(new ArrayComponent());
            } else {
                steps.add(new WildcardBound());
            }
        }
        return switch (root.group(1).charAt(0)) {
            case 'f' -> new TypeUsePath(Root.FIELD, OptionalInt.empty(), steps);
            case 'r' -> new TypeUsePath(Root.RETURN, OptionalInt.empty(), steps);
            default -> new TypeUsePath(Root.PARAMETER, OptionalInt.of(Integer.parseInt(root.group(2))), steps);
        };
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder(switch (root) {
            case FIELD -> "field";
            case RETURN -> "return";
            case PARAMETER -> "parameter[" + parameterIndex.getAsInt() + "]";
        });
        for (Step s : steps) {
            b.append('/').append(switch (s) {
                case TypeArgument t -> "typeArgument[" + t.index() + "]";
                case ArrayComponent a -> "arrayComponent";
                case WildcardBound w -> "wildcardBound";
            });
        }
        return b.toString();
    }
}
