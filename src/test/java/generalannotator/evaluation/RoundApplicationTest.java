package generalannotator.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.checker.CheckerOutcome;
import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.checker.CheckerOutcome.ExecutionFailure;
import generalannotator.checker.FailureKind;
import generalannotator.cli.ApplyRoundCommand;
import generalannotator.cli.EvaluateAnnotationsCommand;
import generalannotator.cli.ExitCode;
import generalannotator.cli.PrepareInferenceCommandTest;
import generalannotator.cli.TargetsCommand;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.edits.javaparser.JavaParserAnnotationApplier;
import generalannotator.evaluation.matching.AnchoredWarningMatcher;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code apply-round}: every package applied together, one check of the combined state, all findings saved. */
class RoundApplicationTest {

    private static final Map<String, Object> FIELD = Map.of("kind", "FIELD");
    private static final Map<String, Object> RETURN = Map.of("kind", "RETURN");
    private static final String NULLABLE = "@org.jspecify.annotations.Nullable";

    @TempDir Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Map<String, String> env = new HashMap<>();

    private ProposalFixture fixture(int warnings) throws Exception {
        ProposalFixture fx = new ProposalFixture(temp, warnings);
        Files.createDirectories(temp.resolve("checker"));
        env.put("GA_CHECKER", "nullaway");
        env.put("GA_CHECKER_HOME", temp.resolve("checker").toString());
        return fx;
    }

    private static Map<String, Object> field(String member, String action) {
        return ProposalFixture.editJson(member, FIELD, action);
    }

    private static Map<String, Object> getReturn() {
        return ProposalFixture.editJson("get()", RETURN, "ADD");
    }

    private static void answer(ProposalFixture fx, String warning, List<Map<String, Object>> edits)
            throws IOException {
        fx.completed(warning, ProposalFixture.answer(warning, "PROPOSED", edits));
    }

    /**
     * An unrelated finding on the class always; x and y until annotated; annotating x makes {@code return x}
     * a finding unless {@code get()} returns {@code @Nullable} too: the two edits interact.
     */
    static CheckerOutcome interacting(FakeChecker.Source s) {
        List<Diagnostic> f = new ArrayList<>();
        f.add(s.at("public class A", "unrelated finding"));
        boolean x = s.annotated("Object x;");
        if (!x) {
            f.add(s.at("x;", "@NonNull field 'x' not initialized"));
        } else if (!s.annotated("Object get()")) {
            f.add(s.at("return x", "returning @Nullable expression from method with @NonNull return type"));
        }
        if (!s.annotated("Object y;")) {
            f.add(s.at("y;", "@NonNull field 'y' not initialized"));
        }
        return new CompletedAnalysis(f, List.of());
    }

    private ExitCode applyRound(ProposalFixture fx, FakeChecker checker) {
        return applyRound(fx, checker, "1");
    }

    private ExitCode applyRound(ProposalFixture fx, FakeChecker checker, String round) {
        out.reset();
        err.reset();
        return new ApplyRoundCommand(fx.submission.toString(), round, env, temp,
                Path.of(System.getProperty("java.home")), (c, e) -> checker, new JavaParserAnnotationApplier(),
                new AnchoredWarningMatcher(), PrepareInferenceCommandTest.CLOCK,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
                .run();
    }

    private ExitCode evaluate(ProposalFixture fx, FakeChecker checker) {
        out.reset();
        err.reset();
        return new EvaluateAnnotationsCommand(fx.submission.toString(), Optional.empty(), env, temp,
                Path.of(System.getProperty("java.home")), (c, e) -> checker, new JavaParserAnnotationApplier(),
                new AnchoredWarningMatcher(), 1, PrepareInferenceCommandTest.CLOCK,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
                .run();
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    private static List<Path> rounds(ProposalFixture fx) throws IOException {
        Path dir = fx.runDir.resolve(RoundApplication.DIR);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> !p.getFileName().toString().startsWith(".")).sorted().toList();
        }
    }

    private static Path round(ProposalFixture fx) throws IOException {
        List<Path> dirs = rounds(fx);
        assertEquals(1, dirs.size(), dirs.toString());
        return dirs.get(0);
    }

    private static Map<?, ?> json(Path file) throws IOException {
        return (Map<?, ?>) JsonParser.parse(Files.readString(file));
    }

    private static List<?> list(Map<?, ?> m, String key) {
        return (List<?>) m.get(key);
    }

    private static Map<?, ?> map(Object o) {
        return (Map<?, ?>) o;
    }

    @Test
    void everyPackageIsAppliedToOneWorkspaceBeforeOneCheckAndSharedEditsOnce() throws Exception {
        ProposalFixture fx = fixture(4);
        answer(fx, "W0001", List.of(field("x", "ADD")));
        answer(fx, "W0002", List.of(field("x", "ADD")));
        answer(fx, "W0003", List.of(field("y", "ADD"), getReturn()));
        answer(fx, "W0004", List.of(field("x", "ADD"), field("y", "ADD")));
        fx.collection();
        String snapshotBefore = EvaluationFiles.treeHash(fx.runDir.resolve("workspace/target"));
        String inferenceBefore = EvaluationFiles.treeHash(fx.submission);
        FakeChecker checker = new FakeChecker(RoundApplicationTest::interacting);

        assertEquals(ExitCode.COMPLETED, applyRound(fx, checker), output());

        assertEquals(2, checker.calls(), "one baseline and one check of the combined state");
        Path dir = round(fx);
        assertTrue(dir.getFileName().toString().startsWith("round1-"), dir.toString());
        Map<?, ?> r = json(dir.resolve(RoundApplication.RECORD));
        assertEquals("COMPLETED", r.get("status"));
        Map<?, ?> counts = map(r.get("counts"));
        assertEquals(4L, counts.get("importedProposals"));
        assertEquals(3L, counts.get("packages"), "W0001 and W0002 share one package");
        assertEquals(5L, counts.get("packageEdits"));
        assertEquals(3L, counts.get("distinctEdits"));
        assertEquals(2L, counts.get("sharedEdits"));
        assertEquals(3L, counts.get("appliedEdits"));
        List<?> edits = list(r, "edits");
        Map<?, ?> x = edits.stream().map(RoundApplicationTest::map)
                .filter(e -> map(e.get("edit")).get("member").equals("x")).findFirst().orElseThrow();
        assertEquals(List.of("A0001", "A0003"), x.get("packages"));
        assertEquals(List.of("W0001@1", "W0002@1", "W0004@1"), list(x, "origins").stream()
                .map(o -> map(o).get("group")).toList());
        assertEquals("APPLIED", map(x.get("applier")).get("outcome"));
        Map<?, ?> multi = list(r, "packages").stream().map(RoundApplicationTest::map)
                .filter(p -> p.get("id").equals("A0002")).findFirst().orElseThrow();
        assertEquals(2, list(multi, "edits").size(), "the multi-edit package keeps both edits");

        // The second check ran on one workspace holding every edit exactly once.
        Path checked = checker.requests.get(1).workingCopyRoot();
        assertTrue(checked.getParent().getParent().getFileName().toString().startsWith(".tmp-round1-"), checked
                .toString());
        String source = Files.readString(dir.resolve("workspace/target/src/p/A.java"));
        assertEquals(3, source.split(java.util.regex.Pattern.quote(NULLABLE), -1).length - 1, source);
        assertTrue(source.contains(NULLABLE + " Object x;") && source.contains(NULLABLE + " Object y;")
                && source.contains(NULLABLE + " Object get()"), source);
        String diff = Files.readString(dir.resolve(RoundApplication.DIFF));
        assertEquals(3, diff.lines().filter(l -> l.startsWith("+") && l.contains(NULLABLE)).count(), diff);
        Map<?, ?> changed = map(list(r, "changedFiles").get(0));
        assertEquals("src/p/A.java", changed.get("path"));
        assertEquals(EvaluationFiles.sha256(source), changed.get("sha256After"));
        assertEquals(EvaluationFiles.sha256(ProposalFixture.SOURCE), changed.get("sha256Before"));

        // The combined state: x and y resolved, nothing introduced, the unrelated finding persists.
        Map<?, ?> comparison = map(r.get("comparison"));
        assertEquals(2L, comparison.get("resolved"));
        assertEquals(0L, comparison.get("introduced"));
        assertEquals(1L, comparison.get("persisting"));
        assertTrue(output().contains("analyzed:   source roots [src]"), output());
        assertEquals(snapshotBefore, EvaluationFiles.treeHash(fx.runDir.resolve("workspace/target")));
        assertEquals(inferenceBefore, EvaluationFiles.treeHash(fx.submission));
        assertEquals(ProposalFixture.SOURCE, Files.readString(fx.runDir.resolve("workspace/target/src/p/A.java")));
    }

    @Test
    void interactingEditsAreCheckedTogetherNotUnionedFromIndependentResultsAndTheBaselineIsReused()
            throws Exception {
        ProposalFixture fx = fixture(2);
        answer(fx, "W0001", List.of(field("x", "ADD")));
        answer(fx, "W0002", List.of(getReturn()));
        fx.collection();
        assertEquals(ExitCode.COMPLETED, evaluate(fx, new FakeChecker(RoundApplicationTest::interacting)), output());
        Path evaluation;
        try (Stream<Path> s = Files.list(fx.runDir.resolve(Evaluator.DIR))) {
            evaluation = s.findFirst().orElseThrow();
        }
        Map<?, ?> alone = json(evaluation.resolve("attempts/A0001/attempt.json"));
        assertEquals(1L, map(alone.get("counts")).get("introduced"), "x alone introduces the return finding");
        String evaluationBefore = EvaluationFiles.treeHash(evaluation);
        FakeChecker checker = new FakeChecker(RoundApplicationTest::interacting);

        assertEquals(ExitCode.COMPLETED, applyRound(fx, checker), output());

        assertEquals(1, checker.calls(), "the verified evaluation baseline is reused; only the combined state runs");
        Map<?, ?> r = json(round(fx).resolve(RoundApplication.RECORD));
        assertEquals("reused", map(r.get("baseline")).get("mode"));
        assertEquals(0L, map(r.get("comparison")).get("introduced"), "together, get() @Nullable removes it");
        assertEquals(List.of("W0002"), list(json(round(fx).resolve(RoundApplication.COMPARISON)), "resolved")
                .stream().map(o -> map(o).get("warningId")).toList());
        assertEquals(evaluationBefore, EvaluationFiles.treeHash(evaluation), "earlier evaluations are only read");
    }

    @Test
    void allFindingsOfTheCombinedStateAreSavedWithTheirComparisonCategories() throws Exception {
        ProposalFixture fx = fixture(1);
        answer(fx, "W0001", List.of(field("x", "ADD")));
        fx.collection();

        assertEquals(ExitCode.COMPLETED, applyRound(fx, new FakeChecker(FakeChecker::nullness)), output());

        Path dir = round(fx);
        Map<?, ?> findings = json(dir.resolve(RoundApplication.FINDINGS));
        assertEquals(true, findings.get("complete"));
        List<?> all = list(findings, "findings");
        assertEquals(2, all.size(), "every finding, not only the introduced ones");
        Map<?, ?> persisting = map(all.stream().filter(f -> map(f).get("status").equals("PERSISTING")).findFirst()
                .orElseThrow());
        assertEquals("W0002", persisting.get("warningId"));
        assertEquals(0L, persisting.get("round"));
        Map<?, ?> introduced = map(all.stream().filter(f -> map(f).get("status").equals("INTRODUCED")).findFirst()
                .orElseThrow());
        assertEquals("W1001", introduced.get("warningId"));
        assertEquals(1L, introduced.get("round"));
        assertTrue(String.valueOf(map(introduced.get("diagnostic")).get("sourcePath")).startsWith(dir.toString()),
                "paths point into the published directory, not the temporary one");
        Map<?, ?> comparison = json(dir.resolve(RoundApplication.COMPARISON));
        assertEquals(List.of("W0001"), list(comparison, "resolved").stream().map(o -> map(o).get("warningId"))
                .toList());
        String text = Files.readString(dir.resolve(RoundApplication.WARNINGS));
        assertTrue(text.contains("# count:   2") && text.contains("[PERSISTING]") && text.contains("W1001  src/p/A.java"),
                text);
        WarningGraph graph = WarningGraphJson.load(dir.resolve(WarningGraphJson.FILE_NAME));
        assertEquals(List.of("W0002", "W1001"), graph.warningNodes().stream().map(WarningNode::id).sorted().toList());
        assertEquals(1L, json(dir.resolve(WarningGraphJson.FILE_NAME)).get("schemaVersion"));
        assertTrue(output().contains("2 finding(s) in the combined state (complete)"), output());
    }

    @Test
    void undecidableFindingsAreAmbiguousNotResolved() throws Exception {
        ProposalFixture fx = fixture(1);
        answer(fx, "W0001", List.of(field("x", "ADD")));
        fx.collection();
        FakeChecker checker = new FakeChecker(s -> {
            Diagnostic d = s.at("return x", "unboxing a @Nullable value");
            return new CompletedAnalysis(s.annotated("Object x;") ? List.of(d) : List.of(d, d), List.of());
        });

        assertEquals(ExitCode.COMPLETED, applyRound(fx, checker), output());

        Map<?, ?> r = json(round(fx).resolve(RoundApplication.RECORD));
        assertEquals(0L, map(r.get("comparison")).get("resolved"));
        assertEquals(1L, map(r.get("comparison")).get("ambiguousResult"));
        Map<?, ?> f = map(list(json(round(fx).resolve(RoundApplication.FINDINGS)), "findings").get(0));
        assertEquals("AMBIGUOUS", f.get("status"));
    }

    @Test
    void conflictingAndUnresolvableEditsRejectTheWholeRoundWithTheirPackages() throws Exception {
        ProposalFixture fx = fixture(3);
        answer(fx, "W0001", List.of(field("x", "ADD")));
        answer(fx, "W0002", List.of(field("x", "REMOVE"), field("y", "ADD")));
        answer(fx, "W0003", List.of(field("missing", "ADD")));
        fx.collection();
        FakeChecker checker = new FakeChecker(FakeChecker::nullness);

        assertEquals(ExitCode.EXECUTION_FAILURE, applyRound(fx, checker), output());

        assertEquals(0, checker.calls(), "validated before any checker run");
        Path dir = round(fx);
        Map<?, ?> r = json(dir.resolve(RoundApplication.RECORD));
        assertEquals("REJECTED", r.get("status"));
        assertFalse(Files.exists(dir.resolve("workspace")), "no partially applied workspace is published");
        assertFalse(Files.exists(dir.resolve(WarningGraphJson.FILE_NAME)));
        List<?> conflicts = list(r, "conflicts");
        assertEquals(3, conflicts.size(), "ADD x, REMOVE x and the missing member");
        Map<?, ?> remove = conflicts.stream().map(RoundApplicationTest::map)
                .filter(c -> String.valueOf(c.get("edit")).startsWith("REMOVE")).findFirst().orElseThrow();
        assertEquals(List.of("A0002"), remove.get("packages"));
        assertTrue(String.valueOf(remove.get("detail")).contains("conflicting ADD and REMOVE"), remove.toString());
        assertTrue(output().contains("from package(s) [A0001], answers [W0001@1]"), output());
        assertTrue(output().contains("Nothing was applied"), output());
        Map<?, ?> y = list(r, "edits").stream().map(RoundApplicationTest::map)
                .filter(e -> map(e.get("edit")).get("member").equals("y")).findFirst().orElseThrow();
        assertEquals("APPLIED", map(y.get("applier")).get("outcome"), "valid edits are listed, not dropped");

        // The rejection is the result for these inputs: reported again, never overwritten.
        assertEquals(ExitCode.EXECUTION_FAILURE, applyRound(fx, checker), output());
        assertTrue(output().contains("reused, nothing was rerun"), output());
    }

    @Test
    void anEditThatWouldSwitchCheckingOffIsUnsupported() throws Exception {
        ProposalFixture fx = fixture(2);
        Map<String, Object> suppress = field("y", "ADD");
        suppress.put("annotation", "java.lang.SuppressWarnings");
        answer(fx, "W0001", List.of(field("x", "ADD")));
        answer(fx, "W0002", List.of(suppress));
        fx.collection();

        assertEquals(ExitCode.EXECUTION_FAILURE, applyRound(fx, new FakeChecker(FakeChecker::nullness)), output());

        Map<?, ?> conflict = map(list(json(round(fx).resolve(RoundApplication.RECORD)), "conflicts").get(0));
        assertEquals("UNSUPPORTED", conflict.get("kind"));
        assertEquals(List.of("A0002"), conflict.get("packages"));
    }

    @Test
    void alreadyPresentAnnotationsAreNoOpsAndTheRestIsApplied() throws Exception {
        ProposalFixture fx = fixture(2);
        Path file = fx.runDir.resolve("workspace/target/src/p/A.java");
        Files.writeString(file, ProposalFixture.SOURCE.replace("    Object y;", "    " + NULLABLE + " Object y;"));
        answer(fx, "W0001", List.of(field("y", "ADD")));
        answer(fx, "W0002", List.of(field("x", "ADD")));
        fx.collection();

        assertEquals(ExitCode.COMPLETED, applyRound(fx, new FakeChecker(FakeChecker::nullness)), output());

        Map<?, ?> r = json(round(fx).resolve(RoundApplication.RECORD));
        assertEquals(1L, map(r.get("counts")).get("appliedEdits"));
        assertEquals(1L, map(r.get("counts")).get("noOpEdits"));
        assertEquals(List.of("APPLIED", "NO_OP"), list(r, "edits").stream()
                .map(e -> map(map(e).get("applier")).get("outcome")).toList());
    }

    @Test
    void sourcesUnderAnIgnoredParentGitDirectoryAreCopiedAndOriginalsStayUnchanged() throws Exception {
        Files.createDirectories(temp.resolve(".git"));
        Files.writeString(temp.resolve(".gitignore"), "runs/\n");
        ProposalFixture fx = fixture(1);
        answer(fx, "W0001", List.of(field("x", "ADD")));
        fx.collection();
        Path snapshot = fx.runDir.resolve("workspace/target");
        String snapshotBefore = EvaluationFiles.treeHash(snapshot);
        byte[] graphBefore = Files.readAllBytes(fx.runDir.resolve("warning-graph.json"));

        assertEquals(ExitCode.COMPLETED, applyRound(fx, new FakeChecker(FakeChecker::nullness)), output());

        Path dir = round(fx);
        assertTrue(Files.readString(dir.resolve("workspace/target/src/p/A.java")).contains(NULLABLE + " Object x;"));
        assertEquals(ProposalFixture.SOURCE, Files.readString(dir.resolve("baseline/source/src/p/A.java")));
        assertEquals(snapshotBefore, EvaluationFiles.treeHash(snapshot));
        assertTrue(java.util.Arrays.equals(graphBefore, Files.readAllBytes(fx.runDir.resolve("warning-graph.json"))));
    }

    @Test
    void aFailedCombinedCheckIsKeptAsIncompleteAndRetriedNeverZeroWarnings() throws Exception {
        ProposalFixture fx = fixture(1);
        answer(fx, "W0001", List.of(field("x", "ADD")));
        fx.collection();
        FakeChecker failing = new FakeChecker(s -> s.annotated("Object x;")
                ? new ExecutionFailure(FailureKind.COMPILATION_FAILED, List.of("cannot find symbol Nullable"),
                        List.of(), List.of())
                : FakeChecker.nullness(s));

        assertEquals(ExitCode.EXECUTION_FAILURE, applyRound(fx, failing), output());

        Path failed = round(fx);
        assertTrue(failed.getFileName().toString().contains("-failed-"), failed.toString());
        Map<?, ?> r = json(failed.resolve(RoundApplication.RECORD));
        assertEquals("CHECK_FAILED", r.get("status"));
        assertEquals("COMPILATION_FAILED", map(r.get("failure")).get("kind"));
        assertEquals(false, map(r.get("comparison")).get("available"));
        assertFalse(Files.exists(failed.resolve(WarningGraphJson.FILE_NAME)), "not usable as a next-round run");
        assertTrue(Files.readString(failed.resolve(RoundApplication.WARNINGS)).contains("INCOMPLETE"));
        assertTrue(output().contains("findings are incomplete"), output());

        FakeChecker working = new FakeChecker(FakeChecker::nullness);
        assertEquals(ExitCode.COMPLETED, applyRound(fx, working), output());
        assertEquals(2, working.calls());
        assertEquals(2, rounds(fx).size(), "the failed result is kept beside the completed one");
    }

    @Test
    void aFailedBaselineStopsBeforeTheCombinedCheck() throws Exception {
        ProposalFixture fx = fixture(1);
        answer(fx, "W0001", List.of(field("x", "ADD")));
        fx.collection();
        FakeChecker broken = new FakeChecker(s -> ExecutionFailure.before(FailureKind.CRASHED, "javac crashed"));

        assertEquals(ExitCode.EXECUTION_FAILURE, applyRound(fx, broken), output());

        assertEquals(1, broken.calls());
        assertEquals("BASELINE_FAILED", json(round(fx).resolve(RoundApplication.RECORD)).get("status"));
    }

    @Test
    void repeatedInvocationsReuseTheResultAndDetectAChangedWorkspace() throws Exception {
        ProposalFixture fx = fixture(1);
        answer(fx, "W0001", List.of(field("x", "ADD")));
        fx.collection();
        assertEquals(ExitCode.COMPLETED, applyRound(fx, new FakeChecker(FakeChecker::nullness)), output());
        Path dir = round(fx);
        String recordBefore = Files.readString(dir.resolve(RoundApplication.RECORD));

        FakeChecker again = new FakeChecker(FakeChecker::nullness);
        assertEquals(ExitCode.COMPLETED, applyRound(fx, again), output());
        assertEquals(0, again.calls());
        assertTrue(output().contains("reused, nothing was rerun"), output());
        assertEquals(recordBefore, Files.readString(dir.resolve(RoundApplication.RECORD)));

        Path jar = Files.writeString(temp.resolve("jspecify.jar"), "not really a jar");
        env.put("GA_TARGET_CLASSPATH", jar.toString());
        FakeChecker other = new FakeChecker(FakeChecker::nullness);
        assertEquals(ExitCode.COMPLETED, applyRound(fx, other), output());
        assertEquals(2, other.calls(), "another configuration: its own baseline and result");
        assertEquals(2, rounds(fx).size());
        env.remove("GA_TARGET_CLASSPATH");

        Files.writeString(dir.resolve("workspace/target/src/p/A.java"), "changed");
        assertEquals(ExitCode.INTERNAL_ERROR, applyRound(fx, new FakeChecker(FakeChecker::nullness)), output());
        assertTrue(output().contains("was changed after the round was applied"), output());
    }

    @Test
    void aDifferentCheckerBuildSavesTheFindingsButMakesNoComparison() throws Exception {
        ProposalFixture fx = fixture(1);
        answer(fx, "W0001", List.of(field("x", "ADD")));
        fx.collection();
        FakeChecker checker = new FakeChecker(FakeChecker::nullness);
        checker.details = n -> Map.of("nullawayVersion", n == 1 ? "0.12.1" : "0.12.2");

        assertEquals(ExitCode.EXECUTION_FAILURE, applyRound(fx, checker), output());

        Path dir = round(fx);
        assertEquals("NOT_COMPARABLE", json(dir.resolve(RoundApplication.RECORD)).get("status"));
        assertEquals("NOT_COMPARED", map(list(json(dir.resolve(RoundApplication.FINDINGS)), "findings").get(0))
                .get("status"));
        assertFalse(Files.exists(dir.resolve(RoundApplication.COMPARISON)));
        assertFalse(Files.exists(dir.resolve(WarningGraphJson.FILE_NAME)));
    }

    @Test
    void aCompletedRoundIsAcceptedAsTheRunOfTheNextRound() throws Exception {
        ProposalFixture fx = fixture(1);
        answer(fx, "W0001", List.of(field("x", "ADD")));
        fx.collection();
        assertEquals(ExitCode.COMPLETED, applyRound(fx, new FakeChecker(FakeChecker::nullness)), output());
        Path dir = round(fx);
        out.reset();

        ExitCode targets = new TargetsCommand(dir.toString(), temp, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run();

        assertEquals(ExitCode.COMPLETED, targets, output());
        String file = Files.readString(dir.resolve("warning-targets.json"));
        assertTrue(file.contains("\"W1001\"") && file.contains("\"W0002\""), file);
        Map<?, ?> report = json(dir.resolve("report.json"));
        assertEquals(dir.resolve("workspace/target").toString(), map(report.get("isolation")).get("workingCopy"));
        assertEquals(fx.runDir.toString(), map(report.get("derivedFrom")).get("runDirectory"));
    }

    @Test
    void onlyRoundOneIsSupported() throws Exception {
        ProposalFixture fx = fixture(1);
        answer(fx, "W0001", List.of(field("x", "ADD")));
        fx.collection();

        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, applyRound(fx, new FakeChecker(FakeChecker::nullness), "2"));
        assertTrue(output().contains("only round 1"), output());
        assertEquals(List.of(), rounds(fx));
    }
}
