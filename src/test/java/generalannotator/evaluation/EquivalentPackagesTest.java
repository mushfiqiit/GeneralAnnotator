package generalannotator.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.checker.CheckerOutcome;
import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.cli.EvaluateAnnotationsCommand;
import generalannotator.cli.ExitCode;
import generalannotator.cli.PrepareInferenceCommandTest;
import generalannotator.diagnostics.Diagnostic;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Answers with the same complete edit set share one package: one checker run, one annotation node, one
 * set of introduced warnings, every origin kept. Also the explicit rebuild from an earlier
 * one-package-per-answer evaluation ({@code --reuse-from}).
 */
class EquivalentPackagesTest {

    private static final Map<String, Object> FIELD = Map.of("kind", "FIELD");
    private static final String OLD = "old-answer-packages";

    @TempDir Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Map<String, String> env = new HashMap<>();
    private int round = 1;

    private ProposalFixture fixture(int warnings) throws Exception {
        ProposalFixture fx = new ProposalFixture(temp, warnings);
        Files.createDirectories(temp.resolve("checker"));
        env.put("GA_CHECKER", "nullaway");
        env.put("GA_CHECKER_HOME", temp.resolve("checker").toString());
        return fx;
    }

    private static Map<String, Object> edit(String member) {
        return ProposalFixture.editJson(member, FIELD, "ADD");
    }

    private static void answer(ProposalFixture fx, String warning, List<Map<String, Object>> edits)
            throws IOException {
        Map<String, Object> a = ProposalFixture.answer(warning, "PROPOSED", edits);
        a.put("rationale", "rationale of " + warning);
        a.put("uncertainty", "uncertainty of " + warning);
        fx.completed(warning, a);
    }

    /**
     * Like EventBus' FindState.clazz: W0001-W0004 each propose {@code @Nullable x} (W0003 lists it twice),
     * W0005 proposes y.
     */
    private ProposalFixture clazz() throws Exception {
        ProposalFixture fx = fixture(5);
        answer(fx, "W0001", List.of(edit("x")));
        answer(fx, "W0002", List.of(edit("x")));
        Map<String, Object> again = edit("x");
        again.put("uncertainty", "second mention");
        answer(fx, "W0003", List.of(edit("x"), again));
        answer(fx, "W0004", List.of(edit("x")));
        answer(fx, "W0005", List.of(edit("y")));
        fx.collection();
        return fx;
    }

    /** Four warnings about x until it is annotated, then five new findings; y is reported until annotated. */
    static CheckerOutcome rule(FakeChecker.Source s) {
        List<Diagnostic> findings = new ArrayList<>();
        if (!s.annotated("Object x;")) {
            for (int i = 1; i <= 4; i++) {
                findings.add(s.at("x;", "x warning " + i));
            }
        } else {
            findings.add(s.at("return x", "introduced 1"));
            findings.add(s.at("return x", "introduced 2"));
            findings.add(s.at("Object get()", "introduced 3"));
            findings.add(s.at("public class A", "introduced 4"));
            findings.add(s.at("Object y;", "introduced 5"));
        }
        if (!s.annotated("Object y;")) {
            findings.add(s.at("y;", "@NonNull field 'y' not initialized"));
        }
        return new CompletedAnalysis(findings, List.of());
    }

    private ExitCode run(ProposalFixture fx, FakeChecker checker, Optional<String> warning, Optional<Path> reuse) {
        out.reset();
        err.reset();
        return new EvaluateAnnotationsCommand(fx.submission.toString(), warning, reuse.map(Path::toString), env,
                temp, Path.of(System.getProperty("java.home")), (c, e) -> checker, new JavaParserAnnotationApplier(),
                new AnchoredWarningMatcher(), round, PrepareInferenceCommandTest.CLOCK,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
                .run();
    }

    private ExitCode run(ProposalFixture fx, FakeChecker checker) {
        return run(fx, checker, Optional.empty(), Optional.empty());
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    private static List<Path> evaluations(ProposalFixture fx) throws IOException {
        try (Stream<Path> s = Files.list(fx.runDir.resolve("evaluations"))) {
            return s.filter(p -> !p.getFileName().toString().equals(OLD)).sorted().toList();
        }
    }

    private static Path evaluation(ProposalFixture fx) throws IOException {
        List<Path> dirs = evaluations(fx);
        assertEquals(1, dirs.size(), dirs.toString());
        return dirs.get(0);
    }

    private static Map<?, ?> json(Path file) throws IOException {
        return (Map<?, ?>) JsonParser.parse(Files.readString(file));
    }

    private static WarningGraph graph(Path dir) throws IOException {
        return WarningGraphJson.load(dir.resolve(Evaluator.GRAPH));
    }

    private static List<AnnotationNode> annotations(WarningGraph g) {
        return g.nodes().stream().filter(AnnotationNode.class::isInstance).map(AnnotationNode.class::cast).toList();
    }

    private static List<String> edges(WarningGraph g) {
        return g.edges().stream().map(e -> e.source() + " -" + e.kind() + "-> " + e.target()).toList();
    }

    @Test
    void fourEquivalentProposalsShareOnePackageOneCheckOneNodeAndOneSetOfIntroducedWarnings() throws Exception {
        ProposalFixture fx = clazz();
        FakeChecker checker = new FakeChecker(EquivalentPackagesTest::rule);

        assertEquals(ExitCode.COMPLETED, run(fx, checker), output());

        assertEquals(3, checker.calls(), "the baseline, the shared x package once, and the y package");
        Path dir = evaluation(fx);
        try (Stream<Path> attempts = Files.list(dir.resolve("attempts"))) {
            assertEquals(List.of("A0001", "A0002"), attempts.map(p -> p.getFileName().toString()).sorted().toList());
        }
        Map<?, ?> candidates = json(dir.resolve(Candidates.FILE));
        assertEquals(Candidates.MODEL, candidates.get("candidateModel"));
        assertEquals(5L, candidates.get("importedProposals"));
        assertEquals(2L, candidates.get("uniquePackages"));
        assertTrue(output().contains("5 imported proposal(s) -> 2 unique annotation package(s)"), output());
        assertTrue(output().contains("A0001  package of 1 edit(s) proposed by 4 answers (W0001, W0002, W0003, W0004):"),
                output());

        WarningGraph g = graph(dir);
        List<AnnotationNode> nodes = annotations(g);
        assertEquals(2, nodes.size());
        AnnotationNode shared = nodes.get(0);
        assertEquals(List.of("W0001", "W0002", "W0003", "W0004"),
                shared.proposals().stream().map(AnnotationNode.Proposal::warningId).toList());
        assertEquals(List.of(List.of(0, 1)), shared.proposals().get(2).answerEditIndices());
        assertEquals(List.of("W0001 -resolved-by-> A0001", "W0002 -resolved-by-> A0001", "W0003 -resolved-by-> A0001",
                "W0004 -resolved-by-> A0001", "A0001 -introduces-> W1001", "A0001 -introduces-> W1002",
                "A0001 -introduces-> W1003", "A0001 -introduces-> W1004", "A0001 -introduces-> W1005",
                "W0005 -resolved-by-> A0002"), edges(g));
        assertEquals(g.edges().size(), new LinkedHashSet<>(g.edges()).size(), "no duplicate edges");
        List<WarningNode> introduced = g.warningNodes().stream().filter(w -> !w.root()).toList();
        assertEquals(5, introduced.size(), "one set of introduced warnings, not one per answer");
        assertTrue(introduced.stream().allMatch(w -> w.state().equals(Optional.of("S0+A0001"))));

        // Provenance of every answer is kept in the record.
        Map<?, ?> r = json(dir.resolve("attempts/A0001/attempt.json"));
        assertEquals(List.of("W0001", "W0002", "W0003", "W0004"), r.get("warningIds"));
        List<?> origins = (List<?>) r.get("origins");
        assertEquals(4, origins.size());
        Map<?, ?> w3 = (Map<?, ?>) origins.get(2);
        assertEquals("W0003@1", w3.get("group"));
        assertEquals("rationale of W0003", w3.get("rationale"));
        assertEquals("uncertainty of W0003", w3.get("uncertainty"));
        assertEquals(2L, w3.get("answerEdits"));
        assertEquals(List.of("u-x", "second mention"), ((Map<?, ?>) ((List<?>) w3.get("edits")).get(0))
                .get("uncertainties"));
        assertEquals(4, origins.stream().map(o -> ((Map<?, ?>) o).get("promptSha256")).distinct().count());
        assertEquals(4, origins.stream().map(o -> ((Map<?, ?>) o).get("answerSha256")).distinct().count());
        Map<?, ?> summary = json(dir.resolve(Evaluator.SUMMARY));
        assertEquals(5L, summary.get("importedProposals"));
        assertEquals(2L, summary.get("uniquePackages"));
    }

    @Test
    void theWarningFilterSelectsTheSharedPackageWithoutDiscardingItsOtherOrigins() throws Exception {
        ProposalFixture fx = clazz();
        FakeChecker checker = new FakeChecker(EquivalentPackagesTest::rule);

        assertEquals(ExitCode.COMPLETED, run(fx, checker, Optional.of("W0003"), Optional.empty()), output());

        assertEquals(2, checker.calls());
        assertTrue(output().contains("selected:   A0001, the package proposed for W0003 (shared with the answers for "
                + "W0001, W0002, W0004)"), output());
        WarningGraph g = graph(evaluation(fx));
        AnnotationNode shared = annotations(g).get(0);
        assertEquals("COMPLETED", shared.outcome());
        assertEquals(4, shared.proposals().size());
        assertEquals("UNEVALUATED", annotations(g).get(1).outcome());
    }

    @Test
    void reorderedAndRepeatedEditsMergeIntoOneAtomicPackage() throws Exception {
        ProposalFixture fx = fixture(2);
        answer(fx, "W0001", List.of(edit("x"), edit("y")));
        answer(fx, "W0002", List.of(edit("y"), edit("x"), edit("y")));
        fx.collection();
        FakeChecker checker = new FakeChecker(EquivalentPackagesTest::rule);

        assertEquals(ExitCode.COMPLETED, run(fx, checker), output());

        assertEquals(2, checker.calls());
        Path dir = evaluation(fx);
        Map<?, ?> r = json(dir.resolve("attempts/A0001/attempt.json"));
        assertEquals(2L, r.get("appliedEdits"));
        String source = Files.readString(dir.resolve("attempts/A0001/source/src/p/A.java"));
        assertTrue(source.contains("Nullable Object x;") && source.contains("Nullable Object y;"), source);
        AnnotationNode a = annotations(graph(dir)).get(0);
        assertEquals(List.of(List.of(1), List.of(0, 2)), a.proposals().get(1).answerEditIndices());
    }

    @Test
    void overlappingAndSubsetPackagesAreEvaluatedSeparately() throws Exception {
        ProposalFixture fx = fixture(3);
        answer(fx, "W0001", List.of(edit("x")));
        answer(fx, "W0002", List.of(edit("x"), edit("y")));
        answer(fx, "W0003", List.of(edit("y")));
        fx.collection();
        FakeChecker checker = new FakeChecker(EquivalentPackagesTest::rule);

        assertEquals(ExitCode.COMPLETED, run(fx, checker), output());

        assertEquals(4, checker.calls());
        assertEquals(List.of(1, 2, 1), annotations(graph(evaluation(fx))).stream().map(a -> a.edits().size()).toList());
        assertTrue(output().contains("3 imported proposal(s) -> 3 unique annotation package(s)"), output());
    }

    @Test
    void sharedPackagesAreNotReusedAcrossBaselinesConfigurationsOrRounds() throws Exception {
        ProposalFixture fx = clazz();
        assertEquals(ExitCode.COMPLETED, run(fx, new FakeChecker(EquivalentPackagesTest::rule)), output());

        Path jar = Files.writeString(temp.resolve("jspecify.jar"), "not really a jar");
        env.put("GA_TARGET_CLASSPATH", jar.toString());
        FakeChecker configuration = new FakeChecker(EquivalentPackagesTest::rule);
        assertEquals(ExitCode.COMPLETED, run(fx, configuration), output());
        assertEquals(3, configuration.calls(), "another configuration: a new baseline and both packages again");

        round = 2;
        FakeChecker secondRound = new FakeChecker(EquivalentPackagesTest::rule);
        assertEquals(ExitCode.COMPLETED, run(fx, secondRound), output());
        assertEquals(3, secondRound.calls(), "another round: evaluated again");

        Path file = fx.runDir.resolve("workspace/target/src/p/A.java");
        Files.writeString(file, Files.readString(file).replace("/** Fixture class. */", "/** Changed. */"));
        FakeChecker changedBaseline = new FakeChecker(EquivalentPackagesTest::rule);
        assertEquals(ExitCode.COMPLETED, run(fx, changedBaseline), output());
        assertEquals(3, changedBaseline.calls(), "another snapshot: evaluated again");

        assertEquals(4, evaluations(fx).size());
        Map<?, ?> roundTwo = evaluations(fx).stream().map(d -> {
            try {
                return json(d.resolve(Evaluator.RECORD));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).filter(m -> Long.valueOf(2).equals(m.get("round"))).findFirst().orElseThrow();
        assertEquals(Candidates.MODEL, roundTwo.get("candidateModel"));
    }

    @Test
    void resumeReusesTheSharedResultAndDuplicatesNoNodesOrEdges() throws Exception {
        ProposalFixture fx = clazz();
        assertEquals(ExitCode.COMPLETED, run(fx, new FakeChecker(EquivalentPackagesTest::rule)), output());
        Path dir = evaluation(fx);
        String graph = Files.readString(dir.resolve(Evaluator.GRAPH));
        String candidates = Files.readString(dir.resolve(Candidates.FILE));

        FakeChecker again = new FakeChecker(EquivalentPackagesTest::rule);
        assertEquals(ExitCode.COMPLETED, run(fx, again), output());

        assertEquals(0, again.calls());
        assertEquals(graph, Files.readString(dir.resolve(Evaluator.GRAPH)));
        assertEquals(candidates, Files.readString(dir.resolve(Candidates.FILE)));
        assertEquals(candidates, Candidates.load(fx.submission).toJson(), "a new import gives the same packages");
        assertEquals(graph, WarningGraphJson.write(WarningGraphJson.read(graph)));
    }

    @Test
    void aRecordWithAnotherEditSetIsNeverReusedEvenUnderTheSamePackageKey() throws Exception {
        ProposalFixture fx = clazz();
        assertEquals(ExitCode.COMPLETED, run(fx, new FakeChecker(EquivalentPackagesTest::rule)), output());
        Path record = evaluation(fx).resolve("attempts/A0001/attempt.json");
        Files.writeString(record, Files.readString(record).replace("\"member\": \"x\"", "\"member\": \"z\""));

        assertEquals(ExitCode.INTERNAL_ERROR, run(fx, new FakeChecker(EquivalentPackagesTest::rule)), output());
        assertTrue(output().contains("records another evaluation, round or package"), output());
    }

    // ---------------------------------------------------------------------------------------------
    // Rebuilding from an earlier one-package-per-answer evaluation

    /**
     * Writes, next to a merged evaluation, the evaluation the one-package-per-answer version (T8b) wrote
     * for the same answers: one candidate and attempt per answer, numbered in numeric warning order, each
     * shared result copied with its own IDs and paths. Returns its directory.
     */
    private static Path answerPackageEvaluation(Path merged) throws IOException {
        Path old = merged.getParent().resolve(OLD);
        Map<?, ?> evaluation = json(merged.resolve(Evaluator.RECORD));
        Map<String, Object> key = new LinkedHashMap<>();
        ((Map<?, ?>) evaluation.get("key")).forEach((k, v) -> key.put(String.valueOf(k), v));
        key.put("schemaVersion", 2);
        key.put("candidateModel", Candidates.ANSWER_PACKAGE_MODEL);
        key.put("candidatesSha256", "earlier");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("schemaVersion", Evaluator.ANSWER_PACKAGE_RECORD_SCHEMA_VERSION);
        record.put("evaluationId", OLD);
        record.put("round", key.get("round"));
        record.put("candidateModel", Candidates.ANSWER_PACKAGE_MODEL);
        record.put("key", key);
        write(old.resolve(Evaluator.RECORD), Json.write(record));
        copy(merged.resolve("baseline"), old.resolve("baseline"));
        Path result = old.resolve("baseline/result.json");
        write(result, Files.readString(result).replace(merged.toString(), old.toString())
                .replace((String) evaluation.get("evaluationId"), OLD));

        record Answer(Map<?, ?> candidate, Map<?, ?> origin) {
            long number() {
                return Long.parseLong(((String) origin.get("warningId")).substring(1));
            }
        }
        List<Answer> answers = new ArrayList<>();
        for (Object c : (List<?>) json(merged.resolve(Candidates.FILE)).get("candidates")) {
            for (Object o : (List<?>) ((Map<?, ?>) c).get("origins")) {
                answers.add(new Answer((Map<?, ?>) c, (Map<?, ?>) o));
            }
        }
        answers.sort(Comparator.comparingLong(Answer::number));
        List<Object> oldCandidates = new ArrayList<>();
        int introducedSequence = 0;
        for (int i = 0; i < answers.size(); i++) {
            String id = String.format("A%04d", i + 1);
            Answer a = answers.get(i);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", id);
            c.put("warningId", a.origin().get("warningId"));
            c.put("group", a.origin().get("group"));
            c.put("proposal", Map.of("answerSha256", a.origin().get("answerSha256")));
            c.put("edits", a.candidate().get("edits"));
            oldCandidates.add(c);

            Path from = merged.resolve("attempts").resolve((String) a.candidate().get("id"));
            Path to = old.resolve("attempts").resolve(id);
            if (!Files.exists(from.resolve(Evaluator.ATTEMPT))) {
                continue;
            }
            copy(from, to);
            Map<?, ?> r = json(from.resolve(Evaluator.ATTEMPT));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("schemaVersion", Evaluator.ANSWER_PACKAGE_RECORD_SCHEMA_VERSION);
            m.put("evaluationId", OLD);
            m.put("round", r.get("round"));
            m.put("candidateId", id);
            m.put("packageKey", "earlier");
            m.put("canonical", "package proposed for " + a.origin().get("warningId"));
            m.put("warningId", a.origin().get("warningId"));
            m.put("group", a.origin().get("group"));
            m.put("baselineState", "S0");
            m.put("edits", r.get("edits"));
            Map<String, String> renamed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : r.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (List.of("schemaVersion", "evaluationId", "round", "candidateId", "packageKey", "canonical",
                        "warningIds", "origins", "baselineState", "edits").contains(k)) {
                    continue;
                }
                Object v = e.getValue();
                if (k.equals("resultState")) {
                    v = "S0+" + id;
                } else if (k.equals("introduced")) {
                    List<Object> list = new ArrayList<>();
                    for (Object o : (List<?>) v) {
                        Map<String, Object> im = new LinkedHashMap<>();
                        ((Map<?, ?>) o).forEach((x, y) -> im.put(String.valueOf(x), y));
                        String newId = String.format("W1%03d", 500 + ++introducedSequence);
                        renamed.put((String) im.get("warningId"), newId);
                        im.put("warningId", newId);
                        list.add(im);
                    }
                    v = list;
                }
                m.put(k, v);
            }
            write(to.resolve(Evaluator.ATTEMPT), Json.write(m).replace(from.toString(), to.toString()));
            Path comparison = to.resolve(Evaluator.COMPARISON);
            if (Files.exists(comparison)) {
                String text = Files.readString(comparison).replace(from.toString(), to.toString())
                        .replace("\"S0+" + a.candidate().get("id") + "\"", "\"S0+" + id + "\"");
                for (Map.Entry<String, String> e : renamed.entrySet()) {
                    text = text.replace("\"" + e.getKey() + "\"", "\"" + e.getValue() + "\"");
                }
                write(comparison, text);
            }
        }
        Map<String, Object> cs = new LinkedHashMap<>();
        cs.put("schemaVersion", 2);
        cs.put("candidateModel", Candidates.ANSWER_PACKAGE_MODEL);
        cs.put("candidates", oldCandidates);
        write(old.resolve(Candidates.FILE), Json.write(cs));
        return old;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path p : files.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
    }

    /** A fresh merged evaluation, its graph text, and the earlier evaluation derived from it (merged one removed). */
    private record Rebuild(Path old, String freshGraph) {}

    private Rebuild rebuildFixture(ProposalFixture fx) throws Exception {
        assertEquals(ExitCode.COMPLETED, run(fx, new FakeChecker(EquivalentPackagesTest::rule)), output());
        Path merged = evaluation(fx);
        String graph = Files.readString(merged.resolve(Evaluator.GRAPH));
        Path old = answerPackageEvaluation(merged);
        EvaluationFiles.deleteTree(merged);
        return new Rebuild(old, graph);
    }

    private static Map<?, ?> reuseReport(Path dir) throws IOException {
        return json(dir.resolve(PackageReuse.REPORT_DIR).resolve(OLD + ".json"));
    }

    @Test
    void reuseImportsEachSharedResultOnceAndRebuildsTheGraphAFreshEvaluationWouldWrite() throws Exception {
        ProposalFixture fx = clazz();
        Rebuild rb = rebuildFixture(fx);
        String oldBefore = EvaluationFiles.treeHash(rb.old());
        FakeChecker checker = new FakeChecker(EquivalentPackagesTest::rule);

        assertEquals(ExitCode.COMPLETED, run(fx, checker, Optional.empty(), Optional.of(rb.old())), output());

        assertEquals(1, checker.calls(), "only the baseline, to verify it reproduces the earlier one");
        Path dir = evaluation(fx);
        assertEquals(rb.freshGraph(), Files.readString(dir.resolve(Evaluator.GRAPH)),
                "same nodes, IDs, edges and introduced warnings as a fresh evaluation");
        assertEquals(EvaluationFiles.treeHash(rb.old()), oldBefore, "the earlier evaluation is only read");
        Map<?, ?> report = reuseReport(dir);
        assertEquals(Map.of("A0001", "A0001", "A0002", "A0001", "A0003", "A0001", "A0004", "A0001", "A0005", "A0002"),
                report.get("candidateMapping"));
        Map<?, ?> shared = (Map<?, ?>) ((Map<?, ?>) report.get("packages")).get("A0001");
        assertEquals("IMPORTED", shared.get("status"));
        assertEquals(List.of("A0001", "A0002", "A0003", "A0004"), shared.get("equivalentAttempts"));
        Map<?, ?> r = json(dir.resolve("attempts/A0001/attempt.json"));
        Map<?, ?> from = (Map<?, ?>) r.get("importedFrom");
        assertEquals(OLD, from.get("evaluationId"));
        assertEquals(Map.of("W1501", "W1001", "W1502", "W1002", "W1503", "W1003", "W1504", "W1004", "W1505", "W1005"),
                from.get("introducedWarningIds"));
        assertEquals("S0+A0001", json(dir.resolve("attempts/A0001/comparison.json")).get("resultState"));
        assertTrue(Files.readString(dir.resolve("attempts/A0001/source/src/p/A.java")).contains("Nullable Object x;"));
        assertTrue(output().contains("imported from " + rb.old().resolve("attempts/A0001")), output());
        assertTrue(output().contains("reuse:      2 package result(s) imported"), output());

        FakeChecker again = new FakeChecker(EquivalentPackagesTest::rule);
        assertEquals(ExitCode.COMPLETED, run(fx, again), output());
        assertEquals(0, again.calls());
        assertTrue(output().contains("[reused (imported from " + rb.old()), output());
    }

    @Test
    void conflictingEarlierResultsAreReportedNeverChosenAndThePackageIsEvaluatedAgain() throws Exception {
        ProposalFixture fx = clazz();
        Rebuild rb = rebuildFixture(fx);
        Path third = rb.old().resolve("attempts/A0003/attempt.json");
        Map<String, Object> r = new LinkedHashMap<>();
        json(third).forEach((k, v) -> r.put(String.valueOf(k), v));
        r.put("resolved", List.of("W0001"));
        Files.writeString(third, Json.write(r));
        FakeChecker checker = new FakeChecker(EquivalentPackagesTest::rule);

        assertEquals(ExitCode.COMPLETED, run(fx, checker, Optional.empty(), Optional.of(rb.old())), output());

        assertEquals(2, checker.calls(), "the baseline and the conflicting package; A0002 is imported");
        Path dir = evaluation(fx);
        assertTrue(output().contains("conflict: the earlier results of A0001, A0002, A0003, A0004 differ in resolved"),
                output());
        Map<?, ?> entry = (Map<?, ?>) ((Map<?, ?>) reuseReport(dir).get("packages")).get("A0001");
        assertEquals("CONFLICT", entry.get("status"));
        assertEquals(List.of("resolved"), entry.get("differences"));
        assertNull(json(dir.resolve("attempts/A0001/attempt.json")).get("importedFrom"));
        assertEquals("IMPORTED", ((Map<?, ?>) ((Map<?, ?>) reuseReport(dir).get("packages")).get("A0002"))
                .get("status"));
        assertEquals(rb.freshGraph(), Files.readString(dir.resolve(Evaluator.GRAPH)));
    }

    @Test
    void reuseIsRefusedUnlessKeyBaselineAndCandidatesAreEquivalent() throws Exception {
        ProposalFixture fx = clazz();
        Rebuild rb = rebuildFixture(fx);
        Path record = rb.old().resolve(Evaluator.RECORD);
        String original = Files.readString(record);

        Files.writeString(record, original.replace("\"round\": 1", "\"round\": 2"));
        assertRefused(fx, rb, "differs in round");

        Files.writeString(record, original.replace("\"checkerHome\":", "\"other\": 1, \"checkerHome\":"));
        assertRefused(fx, rb, "differs in configuration");

        Files.writeString(record, original.replace("\"candidateModel\": \"answer-package-1\"", "\"x\": 0")
                .replace("\"schemaVersion\": 3", "\"schemaVersion\": 1"));
        assertRefused(fx, rb, "results of single-edit evaluations are never reused");
        Files.writeString(record, original);

        Path baseline = rb.old().resolve("baseline/result.json");
        String baselineText = Files.readString(baseline);
        Files.writeString(baseline, baselineText.replace("x warning 1", "x warning one"));
        assertRefused(fx, rb, "baseline findings differ");
        Files.writeString(baseline, baselineText);

        Path candidates = rb.old().resolve(Candidates.FILE);
        String candidatesText = Files.readString(candidates);
        Files.writeString(candidates, candidatesText.replaceFirst("\"member\": \"x\"", "\"member\": \"z\""));
        assertRefused(fx, rb, "is not an answer of the current submission with the same answer hash and edit set");
        Files.writeString(candidates, candidatesText);

        FakeChecker checker = new FakeChecker(EquivalentPackagesTest::rule);
        assertEquals(ExitCode.COMPLETED, run(fx, checker, Optional.empty(), Optional.of(rb.old())), output());
        assertEquals(0, checker.calls(), "restored: the baseline is reused, both packages imported");
    }

    private void assertRefused(ProposalFixture fx, Rebuild rb, String reason) throws IOException {
        FakeChecker checker = new FakeChecker(EquivalentPackagesTest::rule);
        assertEquals(ExitCode.EXECUTION_FAILURE, run(fx, checker, Optional.empty(), Optional.of(rb.old())),
                output());
        assertTrue(output().contains("nothing was reused from") && output().contains(reason), output());
        assertFalse(Files.exists(evaluation(fx).resolve("attempts")), "nothing imported or evaluated");
    }
}
