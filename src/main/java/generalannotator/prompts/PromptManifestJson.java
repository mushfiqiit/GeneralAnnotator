package generalannotator.prompts;

import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code prompts/manifest.json} (schema version {@value #SCHEMA_VERSION}): one entry per warning ID
 * with the template version, input paths, generation status (GENERATED, SKIPPED, OVERSIZED),
 * reasons, notes and output sizes. Entries of warnings not selected in a later invocation are kept;
 * selected ones are replaced. The file has no timestamps, so equal inputs give equal bytes.
 */
public final class PromptManifestJson {

    public static final String FILE_NAME = "manifest.json";
    public static final int SCHEMA_VERSION = 1;
    public static final String NOTE = "Prompt units for an LLM proposer; no LLM has been called. A GENERATED unit "
            + "contains exactly one warning, its Specimin slice and its compact usage context. Slice reproduction is "
            + "copied from slice-results.json (NOT_VALIDATED means the checker has not been run on the slice).";

    private PromptManifestJson() {}

    /** Existing entries keyed by warning ID, or empty if the file does not exist. */
    public static Map<String, Map<?, ?>> loadEntries(Path file, String runId) throws IOException {
        Map<String, Map<?, ?>> entries = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return entries;
        }
        Object root = JsonParser.parse(Files.readString(file, StandardCharsets.UTF_8));
        if (!(root instanceof Map<?, ?> r) || !(r.get("prompts") instanceof List<?> prompts)) {
            throw new IllegalArgumentException(file + " is not a prompt manifest (no \"prompts\" array)");
        }
        if (!runId.equals(r.get("runId"))) {
            throw new IllegalArgumentException(file + " belongs to run " + r.get("runId") + ", not " + runId);
        }
        for (Object item : prompts) {
            if (!(item instanceof Map<?, ?> m) || !(m.get("warningId") instanceof String id)) {
                throw new IllegalArgumentException(file + " has an entry without a warningId");
            }
            entries.put(id, m);
        }
        return entries;
    }

    public static String write(String runId, String checkerId, PromptLimits limits,
            Map<String, ? extends Map<?, ?>> entries) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("runId", runId);
        root.put("checker", checkerId);
        root.put("templateVersion", PromptTemplate.VERSION);
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("maxPromptChars", limits.maxPromptChars());
        l.put("maxFileChars", limits.maxFileChars());
        root.put("limits", l);
        root.put("note", NOTE);
        List<Map<?, ?>> sorted = new ArrayList<>(entries.values());
        sorted.sort(Comparator.comparing(e -> String.valueOf(e.get("warningId"))));
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String status : List.of("GENERATED", "SKIPPED", "OVERSIZED")) {
            summary.put(status.toLowerCase(java.util.Locale.ROOT),
                    sorted.stream().filter(e -> status.equals(e.get("status"))).count());
        }
        root.put("summary", summary);
        root.put("prompts", sorted);
        return Json.write(root);
    }

    /** Writes atomically (temporary file, then move). */
    public static void save(Path file, String content) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
