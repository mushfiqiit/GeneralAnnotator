package generalannotator.targets;

import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writes and reads {@code warning-targets.json} (schema version {@value #SCHEMA_VERSION}): the
 * warning-ID to Specimin-target mapping, saved beside {@code warning-graph.json}.
 */
public final class WarningTargetsJson {

    public static final String FILE_NAME = "warning-targets.json";
    public static final int SCHEMA_VERSION = 1;
    public static final String NOTE = "A resolved target names the member enclosing the warning. It does not "
            + "establish that a Specimin slice of that member reproduces the warning; Specimin has not been run.";

    private WarningTargetsJson() {}

    /** A loaded {@code warning-targets.json}. */
    public record TargetsFile(String runId, Path workingCopy, List<WarningTarget> targets) {
        public TargetsFile {
            targets = List.copyOf(targets);
        }
    }

    public static TargetsFile load(Path file) throws IOException {
        return read(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Parses and validates a saved file.
     *
     * @throws IllegalArgumentException if the JSON is malformed or does not match the schema
     */
    public static TargetsFile read(String json) {
        Map<?, ?> root = object(JsonParser.parse(json), "warning-targets");
        if (!Long.valueOf(SCHEMA_VERSION).equals(root.get("schemaVersion"))) {
            throw new IllegalArgumentException("unsupported warning-targets schemaVersion " + root.get("schemaVersion"));
        }
        List<WarningTarget> targets = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (!(root.get("targets") instanceof List<?> entries)) {
            throw new IllegalArgumentException("warning-targets: \"targets\" must be an array");
        }
        for (Object item : entries) {
            Map<?, ?> e = object(item, "target");
            String id = string(e, "warningId");
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate warningId " + id);
            }
            String location = string(e, "location");
            List<String> notes = new ArrayList<>();
            if (!(e.get("notes") instanceof List<?> rawNotes)) {
                throw new IllegalArgumentException(id + ": \"notes\" must be an array");
            }
            for (Object n : rawNotes) {
                notes.add(String.valueOf(n));
            }
            String status = string(e, "status");
            switch (status) {
                case "RESOLVED" -> {
                    MemberKind kind;
                    try {
                        kind = MemberKind.valueOf(string(e, "memberKind"));
                    } catch (IllegalArgumentException ex) {
                        throw new IllegalArgumentException(id + ": unknown memberKind " + e.get("memberKind"));
                    }
                    String target = kind == MemberKind.FIELD ? string(e, "targetField") : string(e, "targetMethod");
                    if (!(e.get("requiresDisableRootValidation") instanceof Boolean disable)) {
                        throw new IllegalArgumentException(id + ": \"requiresDisableRootValidation\" must be a boolean");
                    }
                    Object configured = e.get("configuredSourceRoot");
                    targets.add(new WarningTarget.Resolved(id, location, string(e, "sourceRoot"),
                            string(e, "isolatedSourceRoot"), string(e, "targetFile"), kind, string(e, "enclosingClass"),
                            target, configured instanceof String c ? Optional.of(c) : Optional.empty(), disable, notes));
                }
                case "UNRESOLVED" -> targets.add(new WarningTarget.Unresolved(id, location, string(e, "reason"), notes));
                default -> throw new IllegalArgumentException(id + ": unknown status " + status);
            }
        }
        return new TargetsFile(string(root, "runId"), Path.of(string(root, "workingCopy")), targets);
    }

    private static Map<?, ?> object(Object value, String where) {
        if (value instanceof Map<?, ?> m) {
            return m;
        }
        throw new IllegalArgumentException(where + " must be a JSON object");
    }

    private static String string(Map<?, ?> m, String key) {
        if (m.get(key) instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("\"" + key + "\" must be a string");
    }

    public static String write(String runId, Path workingCopyRoot, List<WarningTarget> targets) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("runId", runId);
        root.put("graphFile", "warning-graph.json");
        root.put("workingCopy", workingCopyRoot.toString());
        root.put("note", NOTE);
        long resolved = targets.stream().filter(WarningTarget.Resolved.class::isInstance).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", targets.size());
        summary.put("resolved", resolved);
        summary.put("unresolved", targets.size() - resolved);
        root.put("summary", summary);
        List<Object> entries = new ArrayList<>();
        for (WarningTarget target : targets) {
            entries.add(entry(target));
        }
        root.put("targets", entries);
        return Json.write(root);
    }

    public static void save(Path file, String runId, Path workingCopyRoot, List<WarningTarget> targets)
            throws IOException {
        Files.writeString(file, write(runId, workingCopyRoot, targets), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> entry(WarningTarget target) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("warningId", target.warningId());
        m.put("location", target.location());
        switch (target) {
            case WarningTarget.Resolved r -> {
                m.put("status", "RESOLVED");
                m.put("memberKind", r.memberKind().name());
                m.put("sourceRoot", r.sourceRoot());
                m.put("isolatedSourceRoot", r.isolatedSourceRoot());
                m.put("targetFile", r.targetFile());
                m.put("enclosingClass", r.enclosingClass());
                m.put("targetMethod", r.targetMethod().orElse(null));
                m.put("targetField", r.targetField().orElse(null));
                m.put("configuredSourceRoot", r.configuredSourceRoot().orElse(null));
                m.put("requiresDisableRootValidation", r.requiresDisableRootValidation());
                m.put("reason", null);
            }
            case WarningTarget.Unresolved u -> {
                m.put("status", "UNRESOLVED");
                m.put("reason", u.reason());
            }
        }
        m.put("notes", target.notes());
        return m;
    }
}
