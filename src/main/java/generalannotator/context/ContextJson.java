package generalannotator.context;

import generalannotator.report.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes {@code context/<warningId>.json} (schema version {@value #SCHEMA_VERSION}). */
public final class ContextJson {

    public static final int SCHEMA_VERSION = 1;
    public static final String NOTE = "Usage evidence for a proposer. Observations describe the code; they are "
            + "not proofs of the correct annotation. No LLM was called, no annotation was edited, and no safe/unsafe "
            + "region policy was applied.";

    private ContextJson() {}

    /**
     * @param runId the run the warning belongs to
     * @param analysis run-wide attribution details (files, options, compiler errors)
     * @param limits the output limits used
     */
    public static String write(WarningContext c, String runId, Map<String, Object> analysis, ContextLimits limits) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("runId", runId);
        root.put("warningId", c.warningId());
        root.put("status", c.status().name());
        root.put("reason", c.reason().orElse(null));
        root.put("note", NOTE);
        root.put("warning", c.warning());
        root.put("target", c.target().isEmpty() ? null : c.target());
        root.put("references", c.references().isEmpty() ? null : c.references());
        Map<String, Object> categories = new LinkedHashMap<>();
        for (EvidenceCategory category : c.categories()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("description", category.description());
            m.put("total", category.total());
            m.put("omitted", category.omitted());
            List<Object> items = new ArrayList<>();
            category.items().forEach(e -> items.add(evidence(e)));
            m.put("items", items);
            categories.put(category.name(), m);
        }
        root.put("evidence", categories);
        root.put("limitations", c.limitations());
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("maxItemsPerCategory", limits.maxItemsPerCategory());
        l.put("maxSnippetLines", limits.maxSnippetLines());
        l.put("maxConditions", limits.maxConditions());
        l.put("maxLocalUses", limits.maxLocalUses());
        root.put("limits", l);
        root.put("analysis", analysis);
        return Json.write(root);
    }

    static Map<String, Object> evidence(Evidence e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", e.kind());
        m.put("location", e.location() == null ? null : e.location().toString());
        m.put("enclosingMember", e.enclosingMember());
        m.put("expression", e.expression());
        m.put("observations", e.observations().stream().map(Enum::name).toList());
        m.put("details", e.details());
        List<Object> conditions = new ArrayList<>();
        for (Evidence.Condition c : e.conditions()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("kind", c.kind());
            cm.put("condition", c.text());
            cm.put("line", c.line());
            conditions.add(cm);
        }
        m.put("conditions", conditions);
        m.put("omittedConditions", e.omittedConditions());
        List<Object> defs = new ArrayList<>();
        for (Evidence.LocalDefinition d : e.localDefinitions()) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("variable", d.variable());
            dm.put("kind", d.kind());
            dm.put("line", d.line());
            dm.put("text", d.text());
            defs.add(dm);
        }
        m.put("localDefinitions", defs);
        if (e.snippet() == null) {
            m.put("snippet", null);
        } else {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("startLine", e.snippet().startLine());
            s.put("endLine", e.snippet().endLine());
            s.put("omittedLines", e.snippet().omittedLines());
            s.put("lines", e.snippet().lines());
            m.put("snippet", s);
        }
        return m;
    }
}
