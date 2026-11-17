package generalannotator.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void writesNestedValuesInOrderWithEscaping() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "a \"q\" \\ \n\t\u0001");
        m.put("n", 3);
        m.put("l", 4L);
        m.put("b", true);
        m.put("z", null);
        m.put("list", Arrays.asList(1, null, "x"));
        m.put("empty", List.of());
        m.put("obj", Map.of());
        assertEquals("""
                {
                  "s": "a \\"q\\" \\\\ \\n\\t\\u0001",
                  "n": 3,
                  "l": 4,
                  "b": true,
                  "z": null,
                  "list": [
                    1,
                    null,
                    "x"
                  ],
                  "empty": [],
                  "obj": {}
                }
                """, Json.write(m));
    }

    @Test
    void rejectsUnsupportedValues() {
        assertThrows(IllegalArgumentException.class, () -> Json.write(Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> Json.write(Double.NaN));
    }

    @Test
    void mapsIsolatedPathsBackToTargetRelativePaths() {
        Path root = Path.of("/runs/r 1/workspace/target");
        assertEquals(Optional.of("EventBus/src/A.java"),
                RunReport.targetRelativePath("/runs/r 1/workspace/target/EventBus/src/A.java", root));
        assertEquals(Optional.empty(), RunReport.targetRelativePath("/runs/r 1/generated/probe/P.java", root));
        assertEquals(Optional.empty(), RunReport.targetRelativePath("/x/A.java", null));
    }
}
