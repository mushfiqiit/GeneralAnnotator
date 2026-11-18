package generalannotator.targets;

import generalannotator.report.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes {@code warning-targets.json} (schema version {@value #SCHEMA_VERSION}): the warning-ID to
 * Specimin-target mapping, saved beside {@code warning-graph.json}.
 */
public final class WarningTargetsJson {

    public static final String FILE_NAME = "warning-targets.json";
    public static final int SCHEMA_VERSION = 1;
    public static final String NOTE = "A resolved target names the member enclosing the warning. It does not "
            + "establish that a Specimin slice of that member reproduces the warning; Specimin has not been run.";

    private WarningTargetsJson() {}

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
