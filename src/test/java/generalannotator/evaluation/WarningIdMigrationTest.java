package generalannotator.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.cli.EvaluateAnnotationsCommand;
import generalannotator.cli.ExitCode;
import generalannotator.cli.MigrateWarningIdsCommand;
import generalannotator.cli.PrepareInferenceCommandTest;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.diagnostics.Severity;
import generalannotator.edits.javaparser.JavaParserAnnotationApplier;
import generalannotator.evaluation.matching.AnchoredWarningMatcher;
import generalannotator.graph.AnnotationNode;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.graph.WarningNode;
import generalannotator.proposals.ProposalFixture;
import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code migrate-warning-ids} on a single-edit evaluation written before rounds were recorded (records
 * schema 1, graph schema 2, introduced warnings named {@code A0001-N1}), built by hand exactly as that
 * version wrote it. Also checks that package evaluations never reuse such results.
 */
class WarningIdMigrationTest {

    private static final String LEGACY = "20261119T120000Z-inference-0123456789ab";

    @TempDir Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private ProposalFixture fx;
    private Path dir;

    private static Map<String, Object> diagnostic(int line, String message) {
        Diagnostic d = new Diagnostic("/w/src/p/A.java", line, OptionalInt.of(5), Severity.ERROR,
                Optional.of("NullAway"), message, Optional.of("snippet"));
        return FindingJson.diagnostic(d);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> warningNode(String id, String state, boolean root, int line) {
        return map("id", id, "kind", "warning", "state", state, "root", root, "distance", root ? 0 : 1,
                "targetPath", "src/p/A.java", "diagnostic", diagnostic(line, "m" + line));
    }

    private static Map<String, Object> annotationNode(String id, String warning) {
        return map("id", id, "kind", "annotation", "baselineState", "S0", "resultState", "S0+" + id,
                "outcome", "COMPLETED", "detail", "d", "canonical", "ADD @x at FIELD of p.A#" + id,
                "edit", map("sourceRoot", "src", "file", "p/A.java"),
                "proposals", List.of(map("warningId", warning, "group", warning + "@1", "answerEditIndex", 0,
                        "baselineWarningId", warning)), "counts", null);
    }

    private static Map<String, Object> introduced(String nodeId, String resultId, int line) {
        return map("nodeId", nodeId, "resultId", resultId, "targetPath", "src/p/A.java", "diagnostic",
                diagnostic(line, "new " + line));
    }

    /** A0001 (proposed for W0001) introduced one warning, A0002 (for W0002) two. */
    private void legacyEvaluation(String w2) throws Exception {
        fx = new ProposalFixture(temp, 2);
        dir = fx.runDir.resolve("evaluations").resolve(LEGACY);
        write("evaluation.json", map("schemaVersion", 1, "evaluationId", LEGACY, "createdAt", "2026-11-20T00:00:00Z",
                "key", map("schemaVersion", 1, "runId", "run 1", "matcher", "anchored-1"), "baselineState", "S0"));
        write("candidates.json", map("schemaVersion", 1, "candidates", List.of(
                map("id", "A0001", "associations", List.of(map("warningId", "W0001", "group", "W0001@1"))),
                map("id", "A0002", "associations", List.of(map("warningId", w2, "group", w2 + "@1"))))));
        write("baseline/result.json", map("schemaVersion", 1, "findings", List.of(map("id", "W0001"),
                map("id", "W0002"))));
        write("attempts/A0001/attempt.json", map("schemaVersion", 1, "evaluationId", LEGACY, "candidateId", "A0001",
                "outcome", "COMPLETED", "resolved", List.of("W0001"),
                "introduced", List.of(introduced("A0001-N1", "R002", 8))));
        write("attempts/A0001/comparison.json", map("matcher", "anchored-1", "introduced", List.of("R002")));
        write("attempts/A0002/attempt.json", map("schemaVersion", 1, "evaluationId", LEGACY, "candidateId", "A0002",
                "outcome", "COMPLETED", "resolved", List.of("W0002"),
                "introduced", List.of(introduced("A0002-N1", "R001", 3), introduced("A0002-N2", "R003", 9))));
        write("attempts/A0002/comparison.json", map("matcher", "anchored-1", "introduced", List.of("R001", "R003"),
                "persisting", List.of()));
        write("evaluation-graph.json", map("schemaVersion", 2, "graphType", "warning-graph", "runId", "run 1",
                "checker", "nullaway", "nodes", List.of(warningNode("W0001", "S0", true, 5),
                        warningNode("W0002", "S0", true, 6), annotationNode("A0001", "W0001"),
                        warningNode("A0001-N1", "S0+A0001", false, 8), annotationNode("A0002", w2),
                        warningNode("A0002-N1", "S0+A0002", false, 3), warningNode("A0002-N2", "S0+A0002", false, 9)),
                "edges", List.of(map("source", "W0001", "target", "A0001", "kind", "resolved-by"),
                        map("source", "A0001", "target", "A0001-N1", "kind", "introduces"),
                        map("source", "W0002", "target", "A0002", "kind", "resolved-by"),
                        map("source", "A0002", "target", "A0002-N1", "kind", "introduces"),
                        map("source", "A0002", "target", "A0002-N2", "kind", "introduces"))));
        write("summary.json", map("schemaVersion", 1, "evaluationId", LEGACY, "candidates", List.of(
                map("candidateId", "A0001", "introduced", List.of("A0001-N1")),
                map("candidateId", "A0002", "introduced", List.of("A0002-N1", "A0002-N2")))));
        Files.writeString(dir.resolve("evaluation-graph.svg"), "<svg>old ids</svg>");
    }

    private void write(String file, Object json) throws IOException {
        Path f = dir.resolve(file);
        Files.createDirectories(f.getParent());
        Files.writeString(f, Json.write(json));
    }

    private ExitCode migrate() {
        out.reset();
        err.reset();
        return new MigrateWarningIdsCommand(dir.toString(), temp, PrepareInferenceCommandTest.CLOCK,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
                .run();
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    private Map<?, ?> json(String file) throws IOException {
        return (Map<?, ?>) JsonParser.parse(Files.readString(dir.resolve(file)));
    }

    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().equals("lock"))
                    .sorted().toList()) {
                files.put(root.relativize(p).toString(), Files.readString(p));
            }
        }
        return files;
    }

    private List<String> edges() throws IOException {
        WarningGraph g = WarningGraphJson.load(dir.resolve(Evaluator.GRAPH));
        return g.edges().stream().map(e -> e.source() + " -" + e.kind() + "-> " + e.target()).toList();
    }

    @Test
    void migrationRenamesIntroducedWarningsUpdatesReferencesAndKeepsABackup() throws Exception {
        legacyEvaluation("W0002");
        Map<String, String> legacy = snapshot(dir);

        assertEquals(ExitCode.COMPLETED, migrate(), output());

        assertTrue(output().contains("A0001-N1 -> W1001") && output().contains("A0002-N1 -> W1002")
                && output().contains("A0002-N2 -> W1003"), output());
        assertEquals(List.of("W0001 -resolved-by-> A0001", "A0001 -introduces-> W1001", "W0002 -resolved-by-> A0002",
                "A0002 -introduces-> W1002", "A0002 -introduces-> W1003"), edges());
        WarningGraph g = WarningGraphJson.load(dir.resolve(Evaluator.GRAPH));
        assertEquals(OptionalInt.of(1), ((WarningNode) g.node("W1003").orElseThrow()).round());
        assertEquals(OptionalInt.of(0), ((WarningNode) g.node("W0001").orElseThrow()).round());
        AnnotationNode a = (AnnotationNode) g.node("A0002").orElseThrow();
        assertEquals(1, a.edits().size(), "the historical single-edit meaning is kept");
        assertEquals(List.of(List.of(0)), a.proposals().get(0).answerEditIndices());

        List<?> introduced = (List<?>) json("attempts/A0002/attempt.json").get("introduced");
        assertEquals(List.of("W1002", "W1003"), introduced.stream().map(m -> ((Map<?, ?>) m).get("warningId")).toList());
        assertEquals((long) Evaluator.EDIT_LEVEL_RECORD_SCHEMA_VERSION,
                json("attempts/A0002/attempt.json").get("schemaVersion"));
        assertEquals(List.of(Map.of("resultId", "R001", "warningId", "W1002", "round", 1L),
                Map.of("resultId", "R003", "warningId", "W1003", "round", 1L)),
                json("attempts/A0002/comparison.json").get("introducedWarnings"));
        assertEquals(List.of("W1002", "W1003"), ((Map<?, ?>) ((List<?>) json(Evaluator.SUMMARY).get("candidates"))
                .get(1)).get("introduced"));
        Map<?, ?> record = json(Evaluator.RECORD);
        assertEquals(1L, record.get("round"));
        assertTrue(((String) record.get("candidateModel")).startsWith("single-edit"));
        assertTrue(((String) record.get("roundSource")).contains("initial warning of the run"));
        assertTrue(!Files.exists(dir.resolve("evaluation-graph.svg")) && !Files.exists(dir.resolve(
                WarningIdMigration.MARKER)));

        Map<?, ?> migration = (Map<?, ?>) ((List<?>) record.get("migrations")).get(0);
        Path backup = dir.resolve((String) migration.get("backup"));
        for (String f : List.of(Evaluator.RECORD, Evaluator.SUMMARY, Evaluator.GRAPH, "evaluation-graph.svg",
                "attempts/A0001/attempt.json", "attempts/A0002/comparison.json")) {
            assertEquals(legacy.get(f), Files.readString(backup.resolve(f)), f);
        }

        Map<String, String> migrated = snapshot(dir);
        assertEquals(ExitCode.COMPLETED, migrate(), output());
        assertTrue(output().contains("already uses round-sequenced warning IDs"), output());
        assertEquals(migrated, snapshot(dir));
    }

    @Test
    void packageEvaluationsNeverReuseSingleEditResults() throws Exception {
        legacyEvaluation("W0002");
        assertEquals(ExitCode.COMPLETED, migrate(), output());
        Map<String, String> before = snapshot(dir);
        fx.completed("W0001", ProposalFixture.answer("W0001", "PROPOSED", List.of(
                ProposalFixture.editJson("x", Map.of("kind", "FIELD"), "ADD"),
                ProposalFixture.editJson("y", Map.of("kind", "FIELD"), "ADD"))));
        fx.collection();
        Files.createDirectories(temp.resolve("checker"));
        Map<String, String> env = new HashMap<>(Map.of("GA_CHECKER", "nullaway",
                "GA_CHECKER_HOME", temp.resolve("checker").toString()));
        FakeChecker checker = new FakeChecker(FakeChecker::nullness);
        out.reset();

        ExitCode exit = new EvaluateAnnotationsCommand(fx.submission.toString(), Optional.empty(), env, temp,
                Path.of(System.getProperty("java.home")), (c, e) -> checker, new JavaParserAnnotationApplier(),
                new AnchoredWarningMatcher(), 1, PrepareInferenceCommandTest.CLOCK,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
                .run();

        assertEquals(ExitCode.COMPLETED, exit, output());
        assertEquals(2, checker.calls(), "a fresh baseline and a fresh run of the package");
        assertTrue(output().contains("kept, not reused: " + dir), output());
        assertEquals(before, snapshot(dir), "the historical evaluation is untouched");
        List<Path> dirs;
        try (Stream<Path> s = Files.list(fx.runDir.resolve("evaluations"))) {
            dirs = new ArrayList<>(s.filter(p -> !p.equals(dir)).toList());
        }
        assertEquals(1, dirs.size());
        WarningGraph g = WarningGraphJson.load(dirs.get(0).resolve(Evaluator.GRAPH));
        AnnotationNode a = (AnnotationNode) g.node("A0001").orElseThrow();
        assertEquals(2, a.edits().size());
        assertEquals(List.of("W0001 -resolved-by-> A0001", "W0002 -resolved-by-> A0001", "A0001 -introduces-> W1001"),
                g.edges().stream().map(e -> e.source() + " -" + e.kind() + "-> " + e.target()).toList(),
                "only edges from the package's own fresh comparison");
    }

    @Test
    void migrationRefusesWhenTheRoundCannotBeEstablished() throws Exception {
        legacyEvaluation("W0099");
        Map<String, String> before = snapshot(dir);

        assertEquals(ExitCode.EXECUTION_FAILURE, migrate(), output());

        assertTrue(output().contains("cannot be established") && output().contains("W0099"), output());
        assertEquals(before, snapshot(dir), "nothing changed, no backup");
    }

    @Test
    void migrationFollowsARecordedRound() throws Exception {
        legacyEvaluation("W0002");
        Map<String, Object> e = new LinkedHashMap<>();
        json(Evaluator.RECORD).forEach((k, v) -> e.put((String) k, v));
        e.put("round", 3);
        write(Evaluator.RECORD, e);

        assertEquals(ExitCode.COMPLETED, migrate(), output());

        assertTrue(edges().contains("A0002 -introduces-> W3003"), edges().toString());
    }

    @Test
    void migrationRefusesAGraphThatDisagreesWithTheRecords() throws Exception {
        legacyEvaluation("W0002");
        Path g = dir.resolve(Evaluator.GRAPH);
        Files.writeString(g, Files.readString(g).replace("\"A0002-N2\"", "\"A0002-N7\""));
        Map<String, String> before = snapshot(dir);

        assertEquals(ExitCode.EXECUTION_FAILURE, migrate(), output());

        assertTrue(output().contains("refusing to guess"), output());
        assertEquals(before, snapshot(dir));
    }

    @Test
    void anInterruptedMigrationCompletesWhenRunAgain() throws Exception {
        legacyEvaluation("W0002");
        assertEquals(ExitCode.COMPLETED, migrate(), output());
        Map<String, String> migrated = snapshot(dir);
        Map<?, ?> migration = (Map<?, ?>) ((List<?>) json(Evaluator.RECORD).get("migrations")).get(0);
        Path backup = dir.resolve((String) migration.get("backup"));
        // Simulate a crash after some files were rewritten: restore the rest from the backup, keep the marker.
        for (String f : List.of(Evaluator.RECORD, Evaluator.GRAPH, "attempts/A0002/attempt.json",
                "attempts/A0002/comparison.json")) {
            Files.copy(backup.resolve(f), dir.resolve(f), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        write(WarningIdMigration.MARKER, map("round", 1, "roundSource", "test", "backup", migration.get("backup"),
                "renames", migration.get("renames"), "startedAt", migration.get("startedAt")));

        assertEquals(ExitCode.COMPLETED, migrate(), output());

        assertTrue(output().contains("completed an interrupted migration"), output());
        assertEquals(migrated.get(Evaluator.GRAPH), Files.readString(dir.resolve(Evaluator.GRAPH)));
        assertEquals(migrated.get("attempts/A0002/attempt.json"),
                Files.readString(dir.resolve("attempts/A0002/attempt.json")));
        assertEquals(1L, json(Evaluator.RECORD).get("round"));
    }
}
