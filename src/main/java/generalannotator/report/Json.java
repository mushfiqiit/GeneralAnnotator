package generalannotator.report;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * Minimal JSON writer for report data built from maps, collections, strings, numbers, booleans and
 * {@code null}. Map iteration order is preserved; output is indented with two spaces.
 */
public final class Json {

    private Json() {}

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        return out.append('\n').toString();
    }

    private static void write(Object value, StringBuilder out, int depth) {
        switch (value) {
            case null -> out.append("null");
            case String s -> string(s, out);
            case Boolean b -> out.append(b);
            case Integer i -> out.append(i);
            case Long l -> out.append(l);
            case Double d when d.isNaN() || d.isInfinite() ->
                    throw new IllegalArgumentException("JSON cannot represent " + d);
            case Number n -> out.append(n);
            case Map<?, ?> map -> object(map, out, depth);
            case Collection<?> items -> array(items, out, depth);
            default -> throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass());
        }
    }

    private static void object(Map<?, ?> map, StringBuilder out, int depth) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{\n");
        Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<?, ?> e = it.next();
            if (!(e.getKey() instanceof String key)) {
                throw new IllegalArgumentException("JSON object keys must be strings: " + e.getKey());
            }
            indent(out, depth + 1);
            string(key, out);
            out.append(": ");
            write(e.getValue(), out, depth + 1);
            out.append(it.hasNext() ? ",\n" : "\n");
        }
        indent(out, depth);
        out.append('}');
    }

    private static void array(Collection<?> items, StringBuilder out, int depth) {
        if (items.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        Iterator<?> it = items.iterator();
        while (it.hasNext()) {
            indent(out, depth + 1);
            write(it.next(), out, depth + 1);
            out.append(it.hasNext() ? ",\n" : "\n");
        }
        indent(out, depth);
        out.append(']');
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }

    private static void string(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
