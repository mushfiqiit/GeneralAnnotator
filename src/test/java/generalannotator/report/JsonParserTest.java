package generalannotator.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonParserTest {

    @Test
    void parsesAllValueTypesPreservingOrder() {
        Object v = JsonParser.parse(
                " {\"b\": 1, \"a\": [true, false, null, -2.5e1, \"x\\n\\u00e9\\\"\"], \"o\": {}, \"big\": 12345678901234567890} ");
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("b", 1L);
        expected.put("a", Arrays.asList(true, false, null, new BigDecimal("-2.5e1"), "x\né\""));
        expected.put("o", Map.of());
        expected.put("big", new BigDecimal("12345678901234567890"));
        assertEquals(expected, v);
        assertEquals(List.of("b", "a", "o", "big"), List.copyOf(((Map<?, ?>) v).keySet()));
    }

    @Test
    void roundTripsWriterOutput() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "tab\tquote\" back\\ ctrl\u0001 path with spaces/é");
        m.put("n", 42L);
        m.put("list", Arrays.asList(1L, null, "x", List.of()));
        assertEquals(m, JsonParser.parse(Json.write(m)));
    }

    @Test
    void rejectsMalformedInput() {
        for (String bad : List.of("", "{", "[1,]", "{\"a\":1,}", "{'a':1}", "01", "1.", "tru", "\"x",
                "{\"a\":1} x", "{\"a\":1,\"a\":2}", "\"\\x\"", "\"\u0001\"", "// c\n1", "NaN")) {
            assertThrows(IllegalArgumentException.class, () -> JsonParser.parse(bad), bad);
        }
    }
}
