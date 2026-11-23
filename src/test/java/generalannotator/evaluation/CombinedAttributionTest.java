package generalannotator.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.checker.CheckerOutcome;
import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.checker.CheckerOutcome.ExecutionFailure;
import generalannotator.checker.FailureKind;
import generalannotator.cli.PrepareInferenceCommandTest;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.edits.AnnotationEdit;
import generalannotator.edits.AnnotationPosition;
import generalannotator.edits.EditAction;
import generalannotator.edits.EditOrigin;
import generalannotator.edits.MemberRef;
import generalannotator.edits.Proposal;
import generalannotator.edits.javaparser.JavaParserAnnotationApplier;
import generalannotator.evaluation.matching.AnchoredWarningMatcher;
import generalannotator.graph.InitialWarningGraph;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Leave-one-out attribution of combined warnings: four packages A0001 {a}, A0002 {b}, A0003 {c}, A0004 {a, y} (A0001's
 * edit is shared with A0004). Combined, every field is annotated, and the fake checker reports
 * <ul>
 *   <li>{@code multi}: (a and not c) or (b and not c) or (a and b and c): only A0002 is verified (A0001's edit is
 *       shared, so excluding it changes nothing; without A0004 the shared edit {@code a} stays and the warning too);
 *   <li>{@code both}: (b and not y) or (c and not y) or (b and c and y): A0002 and A0003 both contribute;
 *   <li>{@code solo}: y: A0004 contributes (its counterfactual keeps the shared {@code a});
 *   <li>{@code amb}: once with c, twice (indistinguishable) with b but not c: without A0003 the comparison is
 *       ambiguous, so nothing is established.
 * </ul>
 */
class CombinedAttributionTest {

    static final String SOURCE = """
            package p;

            /** Fixture. */
            public class A {
                Object a;
                Object b;
                Object c;
                Object y;

                void multi() {}
                void both() {}
                void solo() {}
                void amb() {}
            }
            """;

    static boolean failing;

    static CheckerOutcome rule(FakeChecker.Source s) {
        if (failing) {
            return new ExecutionFailure(FailureKind.COMPILATION_FAILED, List.of("cannot find symbol"), List.of(),
                    List.of());
        }
        boolean a = s.annotated("Object a;");
        boolean b = s.annotated("Object b;");
        boolean c = s.annotated("Object c;");
        boolean y = s.annotated("Object y;");
        List<Diagnostic> f = new ArrayList<>();
        for (String field : List.of("a", "b", "c", "y")) {
            if (!s.annotated("Object " + field + ";")) {
                f.add(s.at("Object " + field + ";", "@NonNull field '" + field + "' not initialized"));
            }
        }
        if ((a && !c) || (b && !c) || (a && b && c)) {
            f.add(s.at("void multi()", "multi effect"));
        }
        if ((b && !y) || (c && !y) || (b && c && y)) {
            f.add(s.at("void both()", "both effect"));
        }
        if (y) {
            f.add(s.at("void solo()", "solo effect"));
        }
        if (c) {
            f.add(s.at("void amb()", "amb effect"));
        } else if (b) {
            f.add(s.at("void amb()", "amb effect"));
            f.add(s.at("void amb()", "amb effect"));
        }
        return new CompletedAnalysis(f, List.of());
    }

    @TempDir Path temp;

    private Path run;
    private FakeChecker checker;
    private EvaluationConfig config;
    private Path round;
    private Path evaluation;

    @BeforeEach
    void roundOne() throws Exception {
        failing = false;
        run = temp.resolve("runs/run1");
        Path snapshot = run.resolve("workspace/target");
        Path file = snapshot.resolve("src/p/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        Files.writeString(snapshot.resolve("src/p/Nullable.java"), "package p;\n\npublic @interface Nullable {}\n");
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("sourceRoots", List.of("src"));
        cfg.put("classpath", List.of());
        cfg.put("javacRelease", null);
        Files.writeString(run.resolve("report.json"), Json.write(Map.of("runId", "run1", "configuration", cfg)));
        WarningGraphJson.save(InitialWarningGraph.build("run1", "nullaway", (CompletedAnalysis) rule(
                new FakeChecker.Source(snapshot, file, SOURCE)), snapshot), run.resolve(WarningGraphJson.FILE_NAME));
        Files.createDirectories(temp.resolve("checker"));
        Map<String, String> env = new HashMap<>();
        env.put("GA_CHECKER", "nullaway");
        env.put("GA_CHECKER_HOME", temp.resolve("checker").toString());
        checker = new FakeChecker(CombinedAttributionTest::rule);
        WarningGraph initial = WarningGraphJson.load(run.resolve(WarningGraphJson.FILE_NAME));
        List<Proposal> answers = new ArrayList<>();
        for (List<String> fields : List.of(List.of("a"), List.of("b"), List.of("c"), List.of("a", "y"))) {
            String target = fields.get(fields.size() - 1);
            String warning = initial.warningNodes().stream()
                    .filter(w -> w.diagnostic().message().startsWith("@NonNull field '" + target + "'"))
                    .findFirst().orElseThrow().id();
            List<AnnotationEdit> edits = fields.stream().map(m -> new AnnotationEdit("src", "p/A.java", "p.A",
                    MemberRef.parse(m), AnnotationPosition.field(), EditAction.ADD, "p.Nullable")).toList();
            List<EditOrigin> origins = new ArrayList<>();
            for (int i = 0; i < edits.size(); i++) {
                origins.add(new EditOrigin(warning, i, Optional.of("u")));
            }
            answers.add(new Proposal(new Proposal.ProposalSource("run1", "sub", warning, 1, "prompt",
                    run.resolve("answer-" + target + ".json"), "answer-" + target), Proposal.deduplicate(edits, origins),
                    "because", "some"));
        }
        Candidates llm = Candidates.fromProposals("sub", "run1", run, snapshot, List.of("src"), answers,
                Candidates.LLM_GENERATOR, "1");
        config = EvaluationConfig.load(env, temp, Path.of(System.getProperty("java.home")), snapshot, List.of("src"),
                run.resolve(Evaluator.DIR));
        PrintStream sink = sink();
        evaluation = new Evaluator(llm, config, checker, new JavaParserAnnotationApplier(), new AnchoredWarningMatcher(),
                1, PrepareInferenceCommandTest.CLOCK, sink).run(Optional.empty()).directory();
        RoundApplication.Result combined = new RoundApplication(llm, config, checker, new JavaParserAnnotationApplier(),
                new AnchoredWarningMatcher(), 1, PrepareInferenceCommandTest.CLOCK, sink).run();
        assertEquals(RoundApplication.Status.COMPLETED, combined.status());
        round = combined.directory();
    }

    private static PrintStream sink() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private Map<?, ?> attribute(boolean local) throws IOException {
        Path dir = new CombinedAttribution(run, round, evaluation, local ? Optional.of(config) : Optional.empty(),
                local ? Optional.of(checker) : Optional.empty(), new JavaParserAnnotationApplier(),
                new AnchoredWarningMatcher(), PrepareInferenceCommandTest.CLOCK, sink()).run();
        return (Map<?, ?>) JsonParser.parse(Files.readString(dir.resolve(CombinedAttribution.RECORD)));
    }

    /** The record's entry for the combined warning reported on the line containing {@code marker}. */
    private static Map<?, ?> warning(Map<?, ?> record, String marker) {
        return ((List<?>) record.get("warnings")).stream().map(o -> (Map<?, ?>) o)
                .filter(w -> String.valueOf(w.get("site")).contains(marker)).findFirst().orElseThrow();
    }

    private static Map<String, String> verdicts(Map<?, ?> w) {
        Map<String, String> m = new java.util.TreeMap<>();
        ((List<?>) w.get("candidates")).forEach(o -> m.put(String.valueOf(((Map<?, ?>) o).get("package")),
                String.valueOf(((Map<?, ?>) o).get("status"))));
        return m;
    }

    private String packageOf(String member) throws IOException {
        for (Object o : (List<?>) ((Map<?, ?>) JsonParser.parse(Files.readString(evaluation.resolve(Candidates.FILE))))
                .get("candidates")) {
            Map<?, ?> c = (Map<?, ?>) o;
            List<String> members = ((List<?>) c.get("edits")).stream()
                    .map(e -> String.valueOf(((Map<?, ?>) ((Map<?, ?>) e).get("edit")).get("member"))).sorted().toList();
            if (String.join(",", members).equals(member)) {
                return String.valueOf(c.get("id"));
            }
        }
        throw new IllegalStateException(member);
    }

    @Test
    void contributionsAreVerifiedByLeavingOnePackageOutWithSharedEditsKept() throws Exception {
        String pa = packageOf("a");
        String pb = packageOf("b");
        String pc = packageOf("c");
        String pay = packageOf("a,y");
        int before = checker.calls();

        Map<?, ?> r = attribute(true);

        assertEquals(3, checker.calls() - before, "one local check per counterfactual state; none for the shared one");
        // one verified contributor; the shared-edit package is inconclusive, the other candidate not a contributor
        Map<?, ?> multi = warning(r, "multi");
        assertEquals("ATTRIBUTED", multi.get("status"));
        assertEquals(List.of(pb), multi.get("contributors"));
        assertEquals(Map.of(pa, "INCONCLUSIVE", pb, "VERIFIED", pay, "NOT_A_CONTRIBUTOR"), verdicts(multi));
        String inconclusive = ((List<?>) multi.get("candidates")).stream().map(o -> (Map<?, ?>) o)
                .filter(v -> v.get("package").equals(pa)).findFirst().orElseThrow().get("evidence").toString();
        assertTrue(inconclusive.contains("shared"), inconclusive);
        // several contributors are all kept, none claimed to be the unique cause
        Map<?, ?> both = warning(r, "both");
        assertEquals(List.of(pb, pc), both.get("contributors"));
        assertTrue(String.valueOf(both.get("reason")).contains("none is claimed to be the unique cause"));
        // without A0004 the shared edit a stays in the counterfactual source, y goes
        Map<?, ?> solo = warning(r, "solo");
        assertEquals(List.of(pay), solo.get("contributors"));
        String key = ((List<?>) solo.get("candidates")).stream().map(o -> (Map<?, ?>) o).findFirst().orElseThrow()
                .get("counterfactual").toString();
        String cfSource = Files.readString(run.resolve("attributions/counterfactuals").resolve(key)
                .resolve("source/src/p/A.java"));
        assertTrue(cfSource.contains("@p.Nullable Object a;") && cfSource.contains("    Object y;"), cfSource);
        // an undecided comparison establishes nothing
        Map<?, ?> amb = warning(r, "amb");
        assertEquals("UNATTRIBUTED", amb.get("status"));
        assertEquals(Map.of(pb, "NOT_A_CONTRIBUTOR", pc, "AMBIGUOUS"), verdicts(amb));
        assertEquals(List.of(), amb.get("contributors"));

        // deterministic and cached: the same record, no further check
        String text = Files.readString(run.resolve("attributions").resolve(((String) r.get("roundResultId")) + "-"
                + run.resolve("attributions").toFile().list((d, n) -> n.startsWith("round1-"))[0]
                .substring(((String) r.get("roundResultId")).length() + 1)).resolve(CombinedAttribution.RECORD));
        int calls = checker.calls();
        assertEquals(r, attribute(true));
        assertEquals(calls, checker.calls());
        assertTrue(text.contains("\"model\": \"" + CombinedAttribution.MODEL + "\""));
        // nothing of the round, the evaluation or the warning IDs changed
        assertTrue(WarningIntegrity.check(run).ok(), WarningIntegrity.check(run).problems().toString());
    }

    @Test
    void failedChecksEstablishNothingAndAreRetried() throws Exception {
        String pb = packageOf("b");
        failing = true;
        Map<?, ?> r = attribute(true);
        Map<?, ?> multi = warning(r, "multi");
        assertEquals("UNATTRIBUTED", multi.get("status"));
        assertEquals("FAILED", verdicts(multi).get(pb));
        assertTrue(((List<?>) r.get("warnings")).stream().allMatch(w -> ((List<?>) ((Map<?, ?>) w).get("contributors"))
                .isEmpty()), "a compilation failure is no evidence of absence");

        failing = false;
        int calls = checker.calls();
        Map<?, ?> again = attribute(true);
        assertEquals(3, checker.calls() - calls, "the failed states are checked again");
        assertEquals(List.of(pb), warning(again, "multi").get("contributors"));
    }

    @Test
    void savedEvidenceOnlyClaimsNothingThatNeedsANewCheck() throws Exception {
        int calls = checker.calls();
        Map<?, ?> r = attribute(false);
        assertEquals(calls, checker.calls(), "no checker run");
        assertTrue(((List<?>) r.get("warnings")).stream().allMatch(w -> ((List<?>) ((Map<?, ?>) w).get("contributors"))
                .isEmpty()), r.toString());
        assertTrue(verdicts(warning(r, "multi")).containsValue("INCONCLUSIVE"));
        // once the local checks exist, saved-evidence mode reuses them
        attribute(true);
        Map<?, ?> reused = attribute(false);
        assertEquals(List.of(packageOf("b")), warning(reused, "multi").get("contributors"));
        try (Stream<Path> s = Files.list(run.resolve("attributions"))) {
            assertEquals(3, s.count(), "a saved-only and a checked record, and the counterfactual cache");
        }
        assertFalse(Files.exists(round.resolve("attribution.json")), "the round result is never changed");
    }
}
