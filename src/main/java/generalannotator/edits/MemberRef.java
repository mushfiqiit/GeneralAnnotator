package generalannotator.edits;

import java.util.List;
import java.util.regex.Pattern;

/** The declaration an edit targets inside its declaring class: a field, or a method or constructor. */
public sealed interface MemberRef {

    Pattern IDENTIFIER = Pattern.compile("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*");

    /** A field by name. */
    record Field(String name) implements MemberRef {
        public Field {
            if (!IDENTIFIER.matcher(name).matches()) {
                throw new IllegalArgumentException("'" + name + "' is not a field name");
            }
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * A method or constructor (a constructor's name is its class's simple name) with its parameter
     * types as written, normalized by {@link TypeText#normalize}.
     */
    record Executable(String name, List<String> parameterTypes) implements MemberRef {
        public Executable {
            if (!IDENTIFIER.matcher(name).matches()) {
                throw new IllegalArgumentException("'" + name + "' is not a method or constructor name");
            }
            parameterTypes = parameterTypes.stream().map(TypeText::normalize).toList();
            for (String t : parameterTypes) {
                if (t.isEmpty()) {
                    throw new IllegalArgumentException("empty parameter type in " + name);
                }
            }
        }

        @Override
        public String toString() {
            return name + "(" + String.join(", ", parameterTypes) + ")";
        }
    }

    /**
     * Parses {@code name} (a field) or {@code name(T1, T2)} (a method or constructor). Parameter
     * names, if a proposer wrote them, are not accepted: the template asks for types only.
     */
    static MemberRef parse(String text) {
        String s = text.strip();
        int open = s.indexOf('(');
        if (open < 0) {
            return new Field(s);
        }
        if (!s.endsWith(")")) {
            throw new IllegalArgumentException("member '" + text + "' must end with ')'");
        }
        String name = s.substring(0, open).strip();
        String inside = s.substring(open + 1, s.length() - 1);
        return new Executable(name, TypeText.splitTopLevel(inside));
    }

    /** Canonical text used in edit identity. */
    default String canonical() {
        return toString();
    }
}
