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
 * {@code evaluation-graph.json}) adds a {@code state} and the inference {@code round} to warning
 * nodes, annotation nodes holding a complete annotation package ({@code edits}), and the edge kinds of
 * {@link GraphEdge}. Introduced warnings have IDs by round ({@link WarningIds}). Earlier expanded
 * versions held one edit per annotation node ({@code "canonical"} and {@code "edit"}, proposals with an
 * {@code answerEditIndex}): version {@value #LEGACY_EXPANDED_SCHEMA_VERSION} without rounds
 * (introduced warnings named {@code A0001-N1}) and version {@value #EDIT_LEVEL_SCHEMA_VERSION} with
 * rounds. Both are still read (into single-edit nodes) but never written.
 * <pre>
 *   { "id": "W0001", "kind": "warning", "state": "S0", "round": 0, ... as in version 1 }
 *   { "id": "W1001", "kind": "warning", "state": "S0+A0001", "round": 1, "root": false, "distance": 1, ... }
 *   { "id": "A0001", "kind": "annotation", "round": 1, "baselineState": "S0", "resultState": "S0+A0001",
 *     "outcome": "COMPLETED", "detail": "...", "canonical": "package of 3 edit(s) proposed for W0037",
 *     "edits": [ { "canonical": "...",
 *                  "edit": { "sourceRoot": ..., "file": ..., "declaringClass": ..., "member": ...,
 *                            "position": ..., "action": ..., "annotation": ... },
 *                  "answerEditIndices": [0], "applierOutcome": "APPLIED" | null, "applierDetail": ... } ],
 *     "proposals": [ { "warningId": "W0037", "group": "W0037@1", "answerEditIndex": null,
 *                      "baselineWarningId": "W0037" } ],
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
    public static final int LEGACY_EXPANDED_SCHEMA_VERSION = 2;
    public static final int EDIT_LEVEL_SCHEMA_VERSION = 3;
    public static final int EXPANDED_SCHEMA_VERSION = 4;
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
        m.put("round", a.round().isPresent() ? a.round().getAsInt() : null);
        m.put("baselineState", a.baselineState());
        m.put("resultState", a.resultState().orElse(null));
        m.put("outcome", a.outcome());
        m.put("detail", a.detail());
        m.put("canonical", a.canonical());
        List<Object> edits = new ArrayList<>();
        for (AnnotationNode.PackageEdit e : a.edits()) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("canonical", e.canonical());
            em.put("edit", e.fields());
            em.put("answerEditIndices", e.answerEditIndices());
            em.put("applierOutcome", e.applierOutcome().orElse(null));
            em.put("applierDetail", e.applierDetail().orElse(null));
            edits.add(em);
        }
        m.put("edits", edits);
        List<Object> proposals = new ArrayList<>();
        for (AnnotationNode.Proposal p : a.proposals()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("warningId", p.warningId());
            pm.put("group", p.group());
            pm.put("answerEditIndex", p.answerEditIndex().isPresent() ? p.answerEditIndex().getAsInt() : null);
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
            m.put("round", w.round().isPresent() ? w.round().getAsInt() : null);
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
        if (version != SCHEMA_VERSION && version != LEGACY_EXPANDED_SCHEMA_VERSION
                && version != EDIT_LEVEL_SCHEMA_VERSION && version != EXPANDED_SCHEMA_VERSION) {
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
            boolean expanded = version != SCHEMA_VERSION;
            boolean annotation = expanded && kind.equals(AnnotationNode.KIND);
            if (!kind.equals(WarningNode.KIND) && !annotation) {
                throw new IllegalArgumentException("unknown node kind \"" + kind + "\"");
            }
            String id = string(n, "id", "node");
            String where = "node " + id;
            if (annotation) {
                nodes.add(annotationNode(n, id, where, version));
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
            Optional<String> state = expanded
                    ? Optional.ofNullable(optionalString(n, "state", where)) : Optional.empty();
            OptionalInt round = version >= EDIT_LEVEL_SCHEMA_VERSION ? round(n, where) : OptionalInt.empty();
            nodes.add(new WarningNode(id, diagnostic, Optional.ofNullable(optionalString(n, "targetPath", where)),
                    bool(n, "root", where), Math.toIntExact(integer(n, "distance", where)), state, round));
        }
        List<GraphEdge> edges = new ArrayList<>();
        for (Object item : list(root, "edges", "graph")) {
            Map<String, Object> e = object(item, "edge");
            edges.add(new GraphEdge(string(e, "source", "edge"), string(e, "target", "edge"),
                    string(e, "kind", "edge")));
        }
        return new WarningGraph(runId, checker, nodes, edges);
    }

    private static OptionalInt round(Map<String, Object> n, String where) {
        Long round = optionalInteger(n, "round", where);
        return round == null ? OptionalInt.empty() : OptionalInt.of(Math.toIntExact(round));
    }

    private static Map<String, String> editFields(Map<String, Object> n, String where) {
        Map<String, Object> e = object(n.get("edit"), where + " edit");
        Map<String, String> edit = new LinkedHashMap<>();
        for (String key : e.keySet()) {
            edit.put(key, string(e, key, where + " edit"));
        }
        return edit;
    }

    private static AnnotationNode annotationNode(Map<String, Object> n, String id, String where, long version) {
        boolean packages = version == EXPANDED_SCHEMA_VERSION;
        List<AnnotationNode.PackageEdit> edits = new ArrayList<>();
        if (packages) {
            for (Object item : list(n, "edits", where)) {
                Map<String, Object> e = object(item, where + " edit");
                List<Integer> indices = new ArrayList<>();
                for (Object i : list(e, "answerEditIndices", where)) {
                    if (!(i instanceof Long l)) {
                        throw new IllegalArgumentException(where + ": answerEditIndices must hold integers");
                    }
                    indices.add(Math.toIntExact(l));
                }
                edits.add(new AnnotationNode.PackageEdit(string(e, "canonical", where), editFields(e, where), indices,
                        Optional.ofNullable(optionalString(e, "applierOutcome", where)),
                        Optional.ofNullable(optionalString(e, "applierDetail", where))));
            }
        } else {
            edits.add(new AnnotationNode.PackageEdit(string(n, "canonical", where), editFields(n, where), List.of(),
                    Optional.empty(), Optional.empty()));
        }
        List<AnnotationNode.Proposal> proposals = new ArrayList<>();
        for (Object item : list(n, "proposals", where)) {
            Map<String, Object> p = object(item, where + " proposal");
            Long index = packages ? optionalInteger(p, "answerEditIndex", where) : (Long) integer(p, "answerEditIndex",
                    where);
            proposals.add(new AnnotationNode.Proposal(string(p, "warningId", where), string(p, "group", where),
                    index == null ? OptionalInt.empty() : OptionalInt.of(Math.toIntExact(index)),
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
        return new AnnotationNode(id, string(n, "canonical", where), edits, string(n, "baselineState", where),
                Optional.ofNullable(optionalString(n, "resultState", where)), string(n, "outcome", where),
                string(n, "detail", where), proposals, counts,
                version >= EDIT_LEVEL_SCHEMA_VERSION ? round(n, where) : OptionalInt.empty());
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
