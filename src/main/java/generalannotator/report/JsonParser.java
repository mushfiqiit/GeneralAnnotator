package generalannotator.report;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict JSON parser (RFC 8259) for files GeneralAnnotator writes with {@link Json}.
 *
 * <p>Objects become {@link LinkedHashMap} (member order preserved; duplicate keys rejected), arrays
 * become {@link List}, integers that fit become {@link Long} (otherwise {@link BigDecimal}),
 * other numbers {@link BigDecimal}, plus {@link String}, {@link Boolean} and {@code null}. Trailing
 * commas, comments, single quotes and trailing content are rejected.
 */
public final class JsonParser {

    private static final int MAX_DEPTH = 512;

    private final String text;
    private int pos;

    private JsonParser(String text) {
        this.text = text;
    }

    /** @throws IllegalArgumentException with the character offset of the first error */
    public static Object parse(String text) {
        JsonParser p = new JsonParser(text);
        p.skipWhitespace();
        Object value = p.value(0);
        p.skipWhitespace();
        if (p.pos != text.length()) {
            throw p.error("unexpected trailing content");
        }
        return value;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("nesting deeper than " + MAX_DEPTH);
        }
        if (pos >= text.length()) {
            throw error("unexpected end of input");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield number();
                }
                throw error("unexpected character '" + c + "'");
            }
        };
    }

    private Map<String, Object> object(int depth) {
        pos++; // {
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek('}')) {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (!peek('"')) {
                throw error("expected a string key");
            }
            String key = string();
            if (map.containsKey(key)) {
                throw error("duplicate key \"" + key + "\"");
            }
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, value(depth + 1));
            skipWhitespace();
            if (peek(',')) {
                pos++;
                continue;
            }
            expect('}');
            return map;
        }
    }

    private List<Object> array(int depth) {
        pos++; // [
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek(']')) {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(value(depth + 1));
            skipWhitespace();
            if (peek(',')) {
                pos++;
                continue;
            }
            expect(']');
            return list;
        }
    }

    private String string() {
        pos++; // opening quote
        StringBuilder out = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw error("unescaped control character in string");
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (pos >= text.length()) {
                throw error("unterminated escape");
            }
            char e = text.charAt(pos++);
            switch (e) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw error("truncated \\u escape");
                    }
                    try {
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("invalid \\u escape");
                    }
                    pos += 4;
                }
                default -> throw error("invalid escape \\" + e);
            }
        }
    }

    private Object number() {
        int start = pos;
        if (peek('-')) {
            pos++;
        }
        if (peek('0')) {
            pos++;
        } else if (pos < text.length() && text.charAt(pos) >= '1' && text.charAt(pos) <= '9') {
            digits();
        } else {
            throw error("invalid number");
        }
        boolean integral = true;
        if (peek('.')) {
            integral = false;
            pos++;
            requireDigit();
            digits();
        }
        if (peek('e') || peek('E')) {
            integral = false;
            pos++;
            if (peek('+') || peek('-')) {
                pos++;
            }
            requireDigit();
            digits();
        }
        String literal = text.substring(start, pos);
        if (integral) {
            try {
                return Long.parseLong(literal);
            } catch (NumberFormatException e) {
                return new BigDecimal(literal);
            }
        }
        return new BigDecimal(literal);
    }

    private void requireDigit() {
        if (pos >= text.length() || text.charAt(pos) < '0' || text.charAt(pos) > '9') {
            throw error("expected a digit");
        }
    }

    private void digits() {
        while (pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') {
            pos++;
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw error("invalid literal");
        }
        pos += word.length();
        return value;
    }

    private boolean peek(char c) {
        return pos < text.length() && text.charAt(pos) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("Invalid JSON at offset " + pos + ": " + message);
    }
}
