package generalannotator.edits;

import java.util.ArrayList;
import java.util.List;

/**
 * Normalization of Java type text as written in member signatures, so harmless syntactic
 * differences do not matter while meaningful ones remain: whitespace, annotations and {@code final}
 * are dropped; {@code ...} (varargs) becomes {@code []}, which is the same parameter type. Names are
 * not resolved, so {@code Map} and {@code java.util.Map} stay different, and so do different type
 * arguments.
 */
public final class TypeText {

    private TypeText() {}

    /** The normalized form, for example {@code Map<String,List<? extends Number>>[]}. */
    public static String normalize(String type) {
        List<String> tokens = tokens(type);
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("@")) {
                // skip @Name(.Name)* and an optional balanced (...) argument list
                i++;
                while (i + 2 < tokens.size() && tokens.get(i + 1).equals(".") && isWord(tokens.get(i + 2))) {
                    i += 2;
                }
                if (i + 1 < tokens.size() && tokens.get(i + 1).equals("(")) {
                    int depth = 0;
                    for (i = i + 1; i < tokens.size(); i++) {
                        if (tokens.get(i).equals("(")) {
                            depth++;
                        } else if (tokens.get(i).equals(")") && --depth == 0) {
                            break;
                        }
                    }
                }
                continue;
            }
            if (t.equals("final")) {
                continue;
            }
            if (t.equals("...")) {
                kept.add("[]");
            } else if (t.equals("[") && i + 1 < tokens.size() && tokens.get(i + 1).equals("]")) {
                kept.add("[]");
                i++;
            } else {
                kept.add(t);
            }
        }
        StringBuilder b = new StringBuilder();
        String previous = null;
        for (String t : kept) {
            if (previous != null && wordLike(previous) && wordLike(t)) {
                b.append(' ');
            }
            b.append(t);
            previous = t;
        }
        return b.toString();
    }

    /** {@code normalize(type)} with every {@code <...>} removed: {@code Class<T>} gives {@code Class}. */
    public static String erase(String normalized) {
        StringBuilder b = new StringBuilder();
        int depth = 0;
        for (char c : normalized.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (depth == 0) {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** Whether the normalized type has type arguments anywhere. */
    public static boolean hasTypeArguments(String normalized) {
        return normalized.indexOf('<') >= 0;
    }

    /**
     * Splits a parameter list at top-level commas; an empty list gives no types. A part that looks
     * like a type followed by a parameter name is rejected.
     */
    public static List<String> splitTopLevel(String list) {
        List<String> parts = new ArrayList<>();
        if (list.isBlank()) {
            return parts;
        }
        int depth = 0;
        int start = 0;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '<' || c == '(') {
                depth++;
            } else if (c == '>' || c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(list.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(list.substring(start));
        List<String> types = new ArrayList<>();
        for (String p : parts) {
            String n = normalize(p);
            if (n.isEmpty()) {
                throw new IllegalArgumentException("empty parameter type in (" + list + ")");
            }
            if (topLevelWords(n) > 1) {
                throw new IllegalArgumentException("parameter '" + p.strip() + "' looks like a type and a name; "
                        + "give parameter types only");
            }
            types.add(n);
        }
        return types;
    }

    private static int topLevelWords(String normalized) {
        int depth = 0;
        int words = 0;
        for (String t : tokens(normalized)) {
            if (t.equals("<")) {
                depth++;
            } else if (t.equals(">")) {
                depth--;
            } else if (depth == 0 && isWord(t) && !t.equals("extends") && !t.equals("super")) {
                words++;
            } else if (depth == 0 && t.equals(".")) {
                words--; // qualified names: a.b.C counts as one word
            }
        }
        return words;
    }

    private static boolean isWord(String t) {
        return !t.isEmpty() && Character.isJavaIdentifierPart(t.charAt(0)) && !t.equals("[]");
    }

    private static boolean wordLike(String t) {
        return isWord(t) || t.equals("?");
    }

    static List<String> tokens(String s) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isJavaIdentifierPart(c)) {
                int j = i;
                while (j < s.length() && Character.isJavaIdentifierPart(s.charAt(j))) {
                    j++;
                }
                tokens.add(s.substring(i, j));
                i = j;
            } else if (s.startsWith("...", i)) {
                tokens.add("...");
                i += 3;
            } else {
                tokens.add(String.valueOf(c));
                i++;
            }
        }
        return tokens;
    }
}
