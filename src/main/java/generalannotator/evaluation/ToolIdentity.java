package generalannotator.evaluation;

import generalannotator.checker.CheckerRun;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Identifies the checker that actually ran and the scope it used, from a run's details: the checker
 * ID, its version, the SHA-256 of every processor-path entry (the built checker and its dependencies)
 * and the annotated packages. A baseline and an attempt are comparable only if these are equal. Jar
 * hashes are cached by path, size and modification time.
 */
final class ToolIdentity {

    private final Map<String, String> cache = new HashMap<>();

    Map<String, Object> of(CheckerRun run) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checker", run.checkerId());
        m.put("version", run.details().get("nullawayVersion"));
        List<Object> path = new ArrayList<>();
        if (run.details().get("processorPath") instanceof List<?> entries) {
            for (Object o : entries) {
                Path p = Path.of(String.valueOf(o));
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("file", p.getFileName() == null ? p.toString() : p.getFileName().toString());
                e.put("sha256", hash(p));
                path.add(e);
            }
        }
        m.put("processorPath", path);
        m.put("annotatedPackages", run.details().get("annotatedPackages"));
        return m;
    }

    private String hash(Path p) throws IOException {
        if (!Files.exists(p)) {
            return "missing";
        }
        String key = p + "|" + Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis();
        String cached = cache.get(key);
        if (cached == null) {
            cached = EvaluationFiles.hashPath(p);
            cache.put(key, cached);
        }
        return cached;
    }

    /** The keys whose values differ, or an empty list if the identities are equal. */
    static List<String> differences(Map<?, ?> a, Map<?, ?> b) {
        List<String> diff = new ArrayList<>();
        for (Object k : a.keySet()) {
            if (!java.util.Objects.equals(normalize(a.get(k)), normalize(b.get(k)))) {
                diff.add(String.valueOf(k));
            }
        }
        for (Object k : b.keySet()) {
            if (!a.containsKey(k)) {
                diff.add(String.valueOf(k));
            }
        }
        return diff;
    }

    /** JSON round trips turn integers into longs; compare through the JSON text. */
    private static Object normalize(Object value) {
        return value == null ? null : generalannotator.report.Json.write(value);
    }
}
