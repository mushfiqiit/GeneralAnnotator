package generalannotator.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import generalannotator.graph.GraphNode;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.graph.WarningNode;
import generalannotator.proposals.ProposalFixture;
import generalannotator.report.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Round-sequenced IDs of introduced warnings: allocation across packages, resume and overflow. */
class WarningIdRoundsTest {

    @TempDir Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Map<String, String> env = new HashMap<>();

    /** Annotating x introduces a finding at {@code return x}; annotating y one at {@code Object get()}. */
    static CheckerOutcome twoIntroductions(FakeChecker.Source s) {
        List<Diagnostic> f = new ArrayList<>();
        f.add(s.annotated("Object x;") ? s.at("return x", "returning @Nullable x")
                : s.at("x;", "@NonNull field 'x' not initialized"));
        f.add(s.annotated("Object y;") ? s.at("Object get()", "y may be @Nullable in get")
                : s.at("y;", "@NonNull field 'y' not initialized"));
        return new CompletedAnalysis(f, List.of());
    }

    /** W0001 proposes x, W0002 proposes y: candidates A0001 (x) and A0002 (y). */
    private ProposalFixture fixture(Path root) throws Exception {
        ProposalFixture fx = new ProposalFixture(root, 2);
        Files.createDirectories(temp.resolve("checker"));
        env.put("GA_CHECKER", "nullaway");
        env.put("GA_CHECKER_HOME", temp.resolve("checker").toString());
        fx.completed("W0001", ProposalFixture.answer("W0001", "PROPOSED", List.of(
                ProposalFixture.editJson("x", Map.of("kind", "FIELD"), "ADD"))));
        fx.completed("W0002", ProposalFixture.answer("W0002", "PROPOSED", List.of(
                ProposalFixture.editJson("y", Map.of("kind", "FIELD"), "ADD"))));
        fx.collection();
        return fx;
    }

    private ExitCode run(ProposalFixture fx, FakeChecker checker, int round, String... warning) {
        out.reset();
        err.reset();
        return new EvaluateAnnotationsCommand(fx.submission.toString(),
                warning.length == 0 ? java.util.Optional.empty() : java.util.Optional.of(warning[0]), env, temp,
                Path.of(System.getProperty("java.home")), (c, e) -> checker, new JavaParserAnnotationApplier(),
                new AnchoredWarningMatcher(), round, PrepareInferenceCommandTest.CLOCK,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
                .run();
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    private static Path evaluation(ProposalFixture fx) throws IOException {
        try (Stream<Path> s = Files.list(fx.runDir.resolve("evaluations"))) {
            return s.sorted().findFirst().orElseThrow();
        }
    }

    private static Map<?, ?> json(Path file) throws IOException {
        return (Map<?, ?>) JsonParser.parse(Files.readString(file));
    }

    private static WarningGraph graph(Path dir) throws IOException {
        return WarningGraphJson.load(dir.resolve(Evaluator.GRAPH));
    }

    private static List<String> edges(WarningGraph g) {
        return g.edges().stream().map(e -> e.source() + " -" + e.kind() + "-> " + e.target()).toList();
    }

    private static List<String> introducedIds(Path dir, String candidate) throws IOException {
        return Evaluator.introduced(json(dir.resolve("attempts/" + candidate + "/attempt.json"))).stream()
                .map(m -> (String) m.get("warningId")).toList();
    }

    @Test
    void attemptsOfOneRoundShareOneSequenceAndAllReferencesUseIt() throws Exception {
        ProposalFixture fx = fixture(temp);

        assertEquals(ExitCode.COMPLETED, run(fx, new FakeChecker(WarningIdRoundsTest::twoIntroductions), 1),
                output());

        Path dir = evaluation(fx);
        assertEquals(List.of("W1001"), introducedIds(dir, "A0001"));
        assertEquals(List.of("W1002"), introducedIds(dir, "A0002"));
        Map<?, ?> cmp = json(dir.resolve("attempts/A0002/comparison.json"));
        assertEquals(List.of(Map.of("resultId", "R002", "warningId", "W1002", "round", 1L)),
                cmp.get("introducedWarnings"));
        assertEquals(List.of(Map.of("baseline", "W0001", "result", "R001")), cmp.get("persisting"),
                "the persisting warning keeps its baseline ID");
        WarningGraph g = graph(dir);
        assertEquals(List.of("W0001 -resolved-by-> A0001", "A0001 -introduces-> W1001",
                "W0002 -resolved-by-> A0002", "A0002 -introduces-> W1002"), edges(g));
        assertEquals(List.of("W0001", "W0002", "A0001", "W1001", "A0002", "W1002"),
                g.nodes().stream().map(GraphNode::id).toList(), "no node for persisting findings");
        WarningNode w0001 = (WarningNode) g.node("W0001").orElseThrow();
        WarningNode w1002 = (WarningNode) g.node("W1002").orElseThrow();
        assertEquals(OptionalInt.of(0), w0001.round());
        assertEquals(OptionalInt.of(1), w1002.round());
        assertEquals(OptionalInt.of(1), ((AnnotationNode) g.node("A0002").orElseThrow()).round());
        assertEquals(1, w1002.distance(), "the round is not the graph distance");
        assertEquals(1L, json(dir.resolve(Evaluator.RECORD)).get("round"));
        Map<?, ?> seq = (Map<?, ?>) json(dir.resolve(Evaluator.SUMMARY)).get("warningIdSequence");
        assertEquals(2L, seq.get("lastSequence"));
        assertEquals("W1003", seq.get("next"));
    }

    @Test
    void resumingContinuesTheSavedSequenceWithoutRenumbering() throws Exception {
        ProposalFixture fx = fixture(temp);
        // W0002's candidate (A0002) is evaluated first, so it takes W1001.
        assertEquals(ExitCode.COMPLETED, run(fx, new FakeChecker(WarningIdRoundsTest::twoIntroductions), 1, "W0002"),
                output());
        Path dir = evaluation(fx);
        assertEquals(List.of("W1001"), introducedIds(dir, "A0002"));

        assertEquals(ExitCode.COMPLETED, run(fx, new FakeChecker(WarningIdRoundsTest::twoIntroductions), 1), output());
        assertEquals(List.of("W1001"), introducedIds(dir, "A0002"), "existing IDs are never renumbered");
        assertEquals(List.of("W1002"), introducedIds(dir, "A0001"));
        String graph = Files.readString(dir.resolve(Evaluator.GRAPH));

        // Another rerun reuses everything and changes nothing.
        assertEquals(ExitCode.COMPLETED, run(fx, new FakeChecker(WarningIdRoundsTest::twoIntroductions), 1), output());
        assertEquals(graph, Files.readString(dir.resolve(Evaluator.GRAPH)));

        // A fresh, unfiltered evaluation numbers in candidate order, the same every time.
        ProposalFixture fresh = fixture(temp.resolve("fresh"));
        assertEquals(ExitCode.COMPLETED, run(fresh, new FakeChecker(WarningIdRoundsTest::twoIntroductions), 1),
                output());
        assertEquals(List.of("W1001"), introducedIds(evaluation(fresh), "A0001"));
        assertEquals(List.of("W1002"), introducedIds(evaluation(fresh), "A0002"));
    }

    @Test
    void anInterruptedAttemptLeavesNoIdBehindAndCreatesNoDuplicate() throws Exception {
        ProposalFixture fx = fixture(temp);
        AtomicBoolean crash = new AtomicBoolean(true);
        FakeChecker crashing = new FakeChecker(s -> {
            if (s.annotated("Object y;") && crash.getAndSet(false)) {
                throw new IllegalStateException("killed");
            }
            return twoIntroductions(s);
        });
        assertThrows(IllegalStateException.class, () -> run(fx, crashing, 1));

        assertEquals(ExitCode.COMPLETED, run(fx, new FakeChecker(WarningIdRoundsTest::twoIntroductions), 1), output());

        Path dir = evaluation(fx);
        assertEquals(List.of("W1001"), introducedIds(dir, "A0001"));
        assertEquals(List.of("W1002"), introducedIds(dir, "A0002"));
        Set<String> ids = new HashSet<>();
        graph(dir).nodes().forEach(n -> assertTrue(ids.add(n.id()), n.id()));
    }

    @Test
    void laterAndMultiDigitRoundsUseTheirOwnSequenceFromExplicitMetadata() throws Exception {
        ProposalFixture two = fixture(temp.resolve("two"));
        assertEquals(ExitCode.COMPLETED, run(two, new FakeChecker(WarningIdRoundsTest::twoIntroductions), 2), output());
        assertEquals(List.of("W2001"), introducedIds(evaluation(two), "A0001"));
        assertEquals(List.of("W2002"), introducedIds(evaluation(two), "A0002"));

        ProposalFixture twelve = fixture(temp.resolve("twelve"));
        assertEquals(ExitCode.COMPLETED, run(twelve, new FakeChecker(WarningIdRoundsTest::twoIntroductions), 12),
                output());
        Path dir = evaluation(twelve);
        assertEquals(List.of("W12001"), introducedIds(dir, "A0001"));
        assertEquals(12L, json(dir.resolve(Evaluator.RECORD)).get("round"));
        WarningNode n = (WarningNode) graph(dir).node("W12001").orElseThrow();
        assertEquals(OptionalInt.of(12), n.round());
        assertEquals("W12003", ((Map<?, ?>) json(dir.resolve(Evaluator.SUMMARY)).get("warningIdSequence")).get("next"));

        // The round is part of the evaluation identity: another round never reuses round 12's results.
        String before = Files.readString(dir.resolve(Evaluator.GRAPH));
        FakeChecker checker = new FakeChecker(WarningIdRoundsTest::twoIntroductions);
        assertEquals(ExitCode.COMPLETED, run(twelve, checker, 1), output());
        assertEquals(3, checker.calls(), "a new baseline and both packages");
        try (Stream<Path> dirs = Files.list(twelve.runDir.resolve("evaluations"))) {
            assertEquals(2, dirs.count());
        }
        assertEquals(before, Files.readString(dir.resolve(Evaluator.GRAPH)));
    }

    @Test
    void exhaustingTheRoundStopsWithoutProducingAnIdOfAnotherRound() throws Exception {
        ProposalFixture fx = fixture(temp);
        FakeChecker flood = new FakeChecker(s -> {
            if (!s.annotated("Object x;")) {
                return twoIntroductions(s);
            }
            List<Diagnostic> f = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                f.add(s.at("return x", "finding " + i));
            }
            return new CompletedAnalysis(f, List.of());
        });

        assertEquals(ExitCode.EXECUTION_FAILURE, run(fx, flood, 1, "W0001"), output());

        assertTrue(output().contains("round 1 has used all 999 introduced-warning IDs"), output());
        Path dir = evaluation(fx);
        assertFalse(Files.exists(dir.resolve("attempts/A0001/attempt.json")), "nothing was recorded");
        assertFalse(Files.exists(dir.resolve(Evaluator.GRAPH)));
    }
}
