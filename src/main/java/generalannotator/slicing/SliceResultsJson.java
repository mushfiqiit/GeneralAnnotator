package generalannotator.slicing;

import generalannotator.exec.CommandResult;
import generalannotator.exec.RecordedCommand;
import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import generalannotator.targets.WarningTarget;
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
 * {@code slices/slice-results.json} (schema version {@value #SCHEMA_VERSION}): one entry per sliced,
 * failed or skipped warning ID. Entries from earlier {@code slice} invocations are kept verbatim;
 * an ID is never written twice.
 */
public final class SliceResultsJson {

    public static final String FILE_NAME = "slice-results.json";
    public static final int SCHEMA_VERSION = 1;
    public static final String NOTE = "GENERATED means Specimin exited successfully and wrote Java files. Whether a "
            + "slice reproduces its warning is NOT_VALIDATED (the checker has not been run on the slices).";

    private SliceResultsJson() {}

    /** Existing entries keyed by warning ID (raw JSON maps), or empty if the file does not exist. */
    public static Map<String, Map<?, ?>> loadEntries(Path file) throws IOException {
        Map<String, Map<?, ?>> entries = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return entries;
        }
        Object root = JsonParser.parse(Files.readString(file, StandardCharsets.UTF_8));
        if (!(root instanceof Map<?, ?> r) || !(r.get("results") instanceof List<?> results)) {
            throw new IllegalArgumentException(file + " is not a slice-results file (no \"results\" array)");
        }
        for (Object item : results) {
            if (!(item instanceof Map<?, ?> m) || !(m.get("warningId") instanceof String id)) {
                throw new IllegalArgumentException(file + " has a result without a warningId");
            }
            entries.put(id, m);
        }
        return entries;
    }

    /** One result as a JSON map; paths are relative to {@code runDir} where possible. */
    public static Map<String, Object> entry(SliceResult result, Path runDir) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("warningId", result.warningId());
        m.put("status", result.status().name());
        m.put("reproduction", SliceResult.REPRODUCTION);
        m.put("target", target(result.target()));
        m.put("command", result.command().map(c -> command(c, runDir)).orElse(null));
        m.put("outputDirectory", result.outputDirectory().map(p -> relative(runDir, p)).orElse(null));
        m.put("outputFiles", result.outputFiles());
        m.put("failureReason", result.failureReason().orElse(null));
        return m;
    }

    private static Map<String, Object> target(WarningTarget target) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("location", target.location());
        switch (target) {
            case WarningTarget.Resolved r -> {
                m.put("status", "RESOLVED");
                m.put("memberKind", r.memberKind().name());
                m.put("sourceRoot", r.sourceRoot());
                m.put("isolatedSourceRoot", r.isolatedSourceRoot());
                m.put("targetFile", r.targetFile());
                m.put("targetMethod", r.targetMethod().orElse(null));
                m.put("targetField", r.targetField().orElse(null));
                m.put("requiresDisableRootValidation", r.requiresDisableRootValidation());
            }
            case WarningTarget.Unresolved u -> {
                m.put("status", "UNRESOLVED");
                m.put("reason", u.reason());
            }
        }
        return m;
    }

    static Map<String, Object> command(RecordedCommand rc, Path runDir) {
        CommandResult r = rc.result();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("command", r.spec().command());
        m.put("workingDirectory", r.spec().workingDirectory().toString());
        m.put("exitCode", r.exitCode().isPresent() ? r.exitCode().getAsInt() : null);
        m.put("durationMillis", r.duration().toMillis());
        m.put("timedOut", r.timedOut());
        m.put("timeoutSeconds", r.spec().timeout().toSeconds());
        m.put("startFailure", r.startFailure().orElse(null));
        m.put("stdoutFile", relative(runDir, rc.stdoutFile()));
        m.put("stderrFile", relative(runDir, rc.stderrFile()));
        return m;
    }

    private static String relative(Path runDir, Path p) {
        return p.startsWith(runDir) ? runDir.relativize(p).toString() : p.toString();
    }

    /**
     * Writes the file atomically (temporary file, then move).
     *
     * @param header run and Specimin information
     * @param entries all entries (earlier and new), written sorted by warning ID
     */
    public static void save(Path file, Map<String, Object> header, Map<String, ? extends Map<?, ?>> entries)
            throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.putAll(header);
        root.put("note", NOTE);
        List<Map<?, ?>> sorted = new ArrayList<>(entries.values());
        sorted.sort(Comparator.comparing(e -> String.valueOf(e.get("warningId"))));
        Map<String, Object> summary = new LinkedHashMap<>();
        for (SliceStatus s : SliceStatus.values()) {
            summary.put(s.name().toLowerCase(java.util.Locale.ROOT),
                    sorted.stream().filter(e -> s.name().equals(e.get("status"))).count());
        }
        root.put("summary", summary);
        root.put("results", sorted);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, Json.write(root), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
