package generalannotator.targets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import generalannotator.report.JsonParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WarningTargetsJsonTest {

    @Test
    void writesMappingWithStatusAndReasons() {
        List<WarningTarget> targets = List.of(
                new WarningTarget.Resolved("W0001", "src/p/A.java:5:3", "src", "/copy/src", "p/A.java",
                        MemberKind.METHOD, "p.A", "p.A#m(int)", Optional.of("src"), false,
                        List.of("inside a lambda expression; the enclosing member is targeted")),
                new WarningTarget.Resolved("W0002", "src/p/A.java:2:9", "src", "/copy/src", "p/A.java",
                        MemberKind.FIELD, "p.A", "p.A#f", Optional.of("src"), true, List.of()),
                new WarningTarget.Unresolved("W0003", "src/p/A.java:9:5", "inside an initializer block", List.of()));

        Map<?, ?> root = (Map<?, ?>) JsonParser.parse(WarningTargetsJson.write("run-1", Path.of("/copy"), targets));

        assertEquals(1L, root.get("schemaVersion"));
        assertEquals("run-1", root.get("runId"));
        assertEquals("warning-graph.json", root.get("graphFile"));
        assertEquals(Map.of("total", 3L, "resolved", 2L, "unresolved", 1L), root.get("summary"));
        List<?> entries = (List<?>) root.get("targets");
        Map<?, ?> method = (Map<?, ?>) entries.get(0);
        assertEquals("W0001", method.get("warningId"));
        assertEquals("RESOLVED", method.get("status"));
        assertEquals("METHOD", method.get("memberKind"));
        assertEquals("src", method.get("sourceRoot"));
        assertEquals("p/A.java", method.get("targetFile"));
        assertEquals("p.A#m(int)", method.get("targetMethod"));
        assertNull(method.get("targetField"));
        assertEquals(List.of("inside a lambda expression; the enclosing member is targeted"), method.get("notes"));
        Map<?, ?> field = (Map<?, ?>) entries.get(1);
        assertEquals("p.A#f", field.get("targetField"));
        assertNull(field.get("targetMethod"));
        assertEquals(true, field.get("requiresDisableRootValidation"));
        Map<?, ?> unresolved = (Map<?, ?>) entries.get(2);
        assertEquals("UNRESOLVED", unresolved.get("status"));
        assertEquals("inside an initializer block", unresolved.get("reason"));
        assertEquals(List.of("warningId", "location", "status", "reason", "notes"),
                List.copyOf(unresolved.keySet()));
    }

    @Test
    void readRoundTripsWhatWasWritten() {
        List<WarningTarget> targets = List.of(
                new WarningTarget.Resolved("W0001", "src/p/A.java:5:3", "src", "/copy dir/src", "p/A.java",
                        MemberKind.CONSTRUCTOR, "p.A", "p.A#A(Map<String, List<?>>)", Optional.of("src"), true,
                        List.of("a note")),
                new WarningTarget.Resolved("W0002", "src/p/A.java:2:9", "src", "/copy dir/src", "p/A.java",
                        MemberKind.FIELD, "p.A", "p.A#f", Optional.empty(), false, List.of()),
                new WarningTarget.Unresolved("W0003", "src/p/A.java:9:5", "inside an initializer block", List.of()));

        WarningTargetsJson.TargetsFile loaded =
                WarningTargetsJson.read(WarningTargetsJson.write("run 1", Path.of("/copy dir"), targets));

        assertEquals("run 1", loaded.runId());
        assertEquals(Path.of("/copy dir"), loaded.workingCopy());
        assertEquals(targets, loaded.targets());
    }

    @Test
    void readRejectsInvalidFiles() {
        String good = WarningTargetsJson.write("r", Path.of("/c"), List.of(
                new WarningTarget.Unresolved("W0001", "x:1", "reason", List.of())));
        for (String bad : List.of(good.replace("\"schemaVersion\": 1", "\"schemaVersion\": 9"),
                good.replace("\"UNRESOLVED\"", "\"MAYBE\""), "{}", "[]")) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> WarningTargetsJson.read(bad), bad);
        }
    }
}
