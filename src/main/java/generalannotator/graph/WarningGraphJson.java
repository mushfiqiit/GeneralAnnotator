package generalannotator.graph;

import generalannotator.diagnostics.Diagnostic;
import generalannotator.diagnostics.Severity;
import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Saves and loads {@link WarningGraph} as JSON. Loading validates the structure and fails on anything
 * unexpected.
 *
 * <p>Schema version {@value #SCHEMA_VERSION} ({@code warning-graph.json} of a run) holds warning
 * nodes only. It is still written for every graph that has nothing else, so initial graphs are
 * unchanged. Schema version {@value #EXPANDED_SCHEMA_VERSION} (an evaluation's
 * {@code evaluation-graph.json}) adds a {@code state} to warning nodes, annotation nodes and the
 * edge kinds of {@link GraphEdge}:
 * <pre>
 *   { "id": "W0001", "kind": "warning", "state": "S0", ... as in version 1 }
 *   { "id": "A0001", "kind": "annotation", "baselineState": "S0", "resultState": "S0+A0001",
 *     "outcome": "COMPLETED", "detail": "...", "canonical": "...",
 *     "edit": { "sourceRoot": ..., "file": ..., "declaringClass": ..., "member": ..., "position": ...,
 *               "action": ..., "annotation": ... },
 *     "proposals": [ { "warningId": "W0001", "group": "W0001@1", "answerEditIndex": 0,
 *                      "baselineWarningId": "W0001" } ],
 *     "counts": { "baselineFindings": 42, "resultFindings": 41, "resolved": 1, "persisting": 41,
 *                 "introduced": 0, "ambiguousBaseline": 0, "ambiguousResult": 0 } | null }
 * </pre>
 * Version 1 (the initial graph) looks like this:
 *
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "graphType": "warning-graph",
 *   "runId": "...",
 *   "checker": "nullaway",
 *   "nodes": [
 *     { "id": "W0001", "kind": "warning", "root": true, "distance": 0, "targetPath": "...",
 *       "diagnostic": { "sourcePath": "...", "line": 20, "column": 25, "severity": "error",
 *                       "checker": "NullAway", "message": "...", "sourceSnippet": "..." } }
 *   ],
 *   "edges": [ { "source": "W0001", "target": "...", "kind": "..." } ]
 * }
 * </pre>
 */
public final class WarningGraphJson {

    public static final String FILE_NAME = "warning-graph.json";
    public static final int SCHEMA_VERSION = 1;
    public static final int EXPANDED_SCHEMA_VERSION = 2;
    public static final String GRAPH_TYPE = "warning-graph";

    private WarningGraphJson() {}

    public static String write(WarningGraph graph) {
        boolean expanded = graph.nodes().stream().anyMatch(n -> n instanceof AnnotationNode
                || (n instanceof WarningNode w && w.state().isPresent()));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", expanded ? EXPANDED_SCHEMA_VERSION : SCHEMA_VERSION);
        root.put("graphType", GRAPH_TYPE);
        root.put("runId", graph.runId());
        root.put("checker", graph.checkerId());
        List<Object> nodes = new ArrayList<>();
        for (GraphNode node : graph.nodes()) {
            nodes.add(switch (node) {
                case WarningNode w -> warningNode(w, expanded);
                case AnnotationNode a -> annotationNode(a);
            });
        }
        root.put("nodes", nodes);
        List<Object> edges = new ArrayList<>();
        for (GraphEdge e : graph.edges()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", e.source());
            m.put("target", e.target());
            m.put("kind", e.kind());
            edges.add(m);
        }
        root.put("edges", edges);
        return Json.write(root);
    }

    public static void save(WarningGraph graph, Path file) throws IOException {
        Files.writeString(file, write(graph), StandardCharsets.UTF_8);
    }

    public static WarningGraph load(Path file) throws IOException {
        return read(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static Map<String, Object> annotationNode(AnnotationNode a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("kind", AnnotationNode.KIND);
        m.put("baselineState", a.baselineState());
        m.put("resultState", a.resultState().orElse(null));
        m.put("outcome", a.outcome());
        m.put("detail", a.detail());
        m.put("canonical", a.canonical());
        m.put("edit", a.edit());
        List<Object> proposals = new ArrayList<>();
        for (AnnotationNode.Proposal p : a.proposals()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("warningId", p.warningId());
            pm.put("group", p.group());
            pm.put("answerEditIndex", p.answerEditIndex());
            pm.put("baselineWarningId", p.baselineWarningId().orElse(null));
            proposals.add(pm);
        }
        m.put("proposals", proposals);
        if (a.counts().isPresent()) {
            AnnotationNode.Counts c = a.counts().get();
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("baselineFindings", c.baselineFindings());
            cm.put("resultFindings", c.resultFindings());
            cm.put("resolved", c.resolved());
            cm.put("persisting", c.persisting());
            cm.put("introduced", c.introduced());
            cm.put("ambiguousBaseline", c.ambiguousBaseline());
            cm.put("ambiguousResult", c.ambiguousResult());
            m.put("counts", cm);
        } else {
            m.put("counts", null);
        }
        return m;
    }

    private static Map<String, Object> warningNode(WarningNode w, boolean expanded) {
        Diagnostic d = w.diagnostic();
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("sourcePath", d.sourcePath());
        diagnostic.put("line", d.line());
        diagnostic.put("column", d.column().isPresent() ? d.column().getAsInt() : null);
        diagnostic.put("severity", d.severity().label());
        diagnostic.put("checker", d.checkerName().orElse(null));
        diagnostic.put("message", d.message());
        diagnostic.put("sourceSnippet", d.sourceSnippet().orElse(null));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.id());
        m.put("kind", WarningNode.KIND);
        if (expanded) {
            m.put("state", w.state().orElse(null));
        }
        m.put("root", w.root());
        m.put("distance", w.distance());
        m.put("targetPath", w.targetPath().orElse(null));
        m.put("diagnostic", diagnostic);
        return m;
    }

    /**
     * Parses and validates a saved graph.
     *
     * @throws IllegalArgumentException if the JSON is malformed or does not match the schema
     */
    public static WarningGraph read(String json) {
        Map<String, Object> root = object(JsonParser.parse(json), "graph");
        long version = integer(root, "schemaVersion", "graph");
        if (version != SCHEMA_VERSION && version != EXPANDED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported warning-graph schemaVersion " + version);
        }
        if (!GRAPH_TYPE.equals(string(root, "graphType", "graph"))) {
            throw new IllegalArgumentException("graphType must be \"" + GRAPH_TYPE + "\"");
        }
        String runId = string(root, "runId", "graph");
        String checker = string(root, "checker", "graph");

        List<GraphNode> nodes = new ArrayList<>();
        for (Object item : list(root, "nodes", "graph")) {
            Map<String, Object> n = object(item, "node");
            String kind = string(n, "kind", "node");
            boolean annotation = version == EXPANDED_SCHEMA_VERSION && kind.equals(AnnotationNode.KIND);
            if (!kind.equals(WarningNode.KIND) && !annotation) {
                throw new IllegalArgumentException("unknown node kind \"" + kind + "\"");
            }
            String id = string(n, "id", "node");
            String where = "node " + id;
            if (annotation) {
                nodes.add(annotationNode(n, id, where));
                continue;
            }
            Map<String, Object> d = object(n.get("diagnostic"), where + " diagnostic");
            Long column = optionalInteger(d, "column", where);
            Diagnostic diagnostic = new Diagnostic(
                    string(d, "sourcePath", where),
                    Math.toIntExact(integer(d, "line", where)),
                    column == null ? OptionalInt.empty() : OptionalInt.of(Math.toIntExact(column)),
                    severity(string(d, "severity", where)),
                    Optional.ofNullable(optionalString(d, "checker", where)),
                    string(d, "message", where),
                    Optional.ofNullable(optionalString(d, "sourceSnippet", where)));
            Optional<String> state = version == EXPANDED_SCHEMA_VERSION
                    ? Optional.ofNullable(optionalString(n, "state", where)) : Optional.empty();
            nodes.add(new WarningNode(id, diagnostic, Optional.ofNullable(optionalString(n, "targetPath", where)),
                    bool(n, "root", where), Math.toIntExact(integer(n, "distance", where)), state));
        }
        List<GraphEdge> edges = new ArrayList<>();
        for (Object item : list(root, "edges", "graph")) {
            Map<String, Object> e = object(item, "edge");
            edges.add(new GraphEdge(string(e, "source", "edge"), string(e, "target", "edge"),
                    string(e, "kind", "edge")));
        }
        return new WarningGraph(runId, checker, nodes, edges);
    }

    private static AnnotationNode annotationNode(Map<String, Object> n, String id, String where) {
        Map<String, Object> e = object(n.get("edit"), where + " edit");
        Map<String, String> edit = new LinkedHashMap<>();
        for (String key : e.keySet()) {
            edit.put(key, string(e, key, where + " edit"));
        }
        List<AnnotationNode.Proposal> proposals = new ArrayList<>();
        for (Object item : list(n, "proposals", where)) {
            Map<String, Object> p = object(item, where + " proposal");
            proposals.add(new AnnotationNode.Proposal(string(p, "warningId", where), string(p, "group", where),
                    Math.toIntExact(integer(p, "answerEditIndex", where)),
                    Optional.ofNullable(optionalString(p, "baselineWarningId", where))));
        }
        if (!n.containsKey("counts")) {
            throw new IllegalArgumentException(where + ": \"counts\" is missing");
        }
        Optional<AnnotationNode.Counts> counts = Optional.empty();
        if (n.get("counts") != null) {
            Map<String, Object> c = object(n.get("counts"), where + " counts");
            counts = Optional.of(new AnnotationNode.Counts(count(c, "baselineFindings", where),
                    count(c, "resultFindings", where), count(c, "resolved", where), count(c, "persisting", where),
                    count(c, "introduced", where), count(c, "ambiguousBaseline", where),
                    count(c, "ambiguousResult", where)));
        }
        return new AnnotationNode(id, string(n, "canonical", where), edit, string(n, "baselineState", where),
                Optional.ofNullable(optionalString(n, "resultState", where)), string(n, "outcome", where),
                string(n, "detail", where), proposals, counts);
    }

    private static int count(Map<String, Object> m, String key, String where) {
        return Math.toIntExact(integer(m, key, where));
    }

    private static Severity severity(String label) {
        try {
            return Severity.valueOf(label.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown severity \"" + label + "\"");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String where) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException(where + " must be a JSON object");
    }

    private static List<?> list(Map<String, Object> m, String key, String where) {
        if (m.get(key) instanceof List<?> l) {
            return l;
        }
        throw new IllegalArgumentException(where + ": \"" + key + "\" must be an array");
    }

    private static String string(Map<String, Object> m, String key, String where) {
        if (m.get(key) instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(where + ": \"" + key + "\" must be a string");
    }

    private static String optionalString(Map<String, Object> m, String key, String where) {
        if (!m.containsKey(key)) {
            throw new IllegalArgumentException(where + ": \"" + key + "\" is missing");
        }
        Object v = m.get(key);
        if (v == null || v instanceof String) {
            return (String) v;
        }
        throw new IllegalArgumentException(where + ": \"" + key + "\" must be a string or null");
    }

    private static long integer(Map<String, Object> m, String key, String where) {
        if (m.get(key) instanceof Long l) {
            return l;
        }
        throw new IllegalArgumentException(where + ": \"" + key + "\" must be an integer");
    }

    private static Long optionalInteger(Map<String, Object> m, String key, String where) {
        if (!m.containsKey(key)) {
            throw new IllegalArgumentException(where + ": \"" + key + "\" is missing");
        }
        Object v = m.get(key);
        if (v == null || v instanceof Long) {
            return (Long) v;
        }
        throw new IllegalArgumentException(where + ": \"" + key + "\" must be an integer or null");
    }

    private static boolean bool(Map<String, Object> m, String key, String where) {
        if (m.get(key) instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException(where + ": \"" + key + "\" must be true or false");
    }
}
