package generalannotator.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.checker.CheckerOutcome;
import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.checker.CheckerOutcome.ExecutionFailure;
import generalannotator.checker.FailureKind;
import generalannotator.cli.ExitCode;
import generalannotator.cli.PrepareInferenceCommandTest;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.edits.javaparser.JavaParserAnnotationApplier;
import generalannotator.evaluation.EvaluationFiles;
import generalannotator.evaluation.FakeChecker;
import generalannotator.evaluation.matching.AnchoredWarningMatcher;
import generalannotator.exec.CommandResult;
import generalannotator.exec.CommandRunner;
import generalannotator.graph.AnnotationNode;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.inference.remote.FakeCluster;
import generalannotator.proposals.ProposalFixture;
import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import generalannotator.slicing.FakeSpecimin;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code pipeline start | status | finish} end to end with fakes for NullAway, Specimin, ssh, the terminal
 * login, the cluster and the GPU worker. Nothing contacts a cluster or loads a model.
 */
class PipelineTest {

    @TempDir Path temp;

    private Path shortDir;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Map<String, String> env = new HashMap<>();
    private FakeCluster cluster;
    private FakeChecker checker;
    private Path target;

    // ssh and login fakes
    private boolean masterProcess;
    private boolean healthy;
    private int logins;
    private int loginExit;
    private final List<List<String>> sshCommands = new ArrayList<>();
    // Specimin fake
    private final Set<String> failingMembers = new java.util.HashSet<>();
    private int speciminRuns;

    @AfterEach
    void removeShortDir() throws IOException {
        if (shortDir != null) {
            try (Stream<Path> walk = Files.walk(shortDir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
    }

    /** A target with fields f1..fn and {@code get()} returning f1. */
    private void setUp(int fields) throws IOException {
        setUp(fields, s -> rule(s, fields));
    }

    private void setUp(int fields, FakeChecker.Rule rule) throws IOException {
        Files.createDirectories(temp.resolve(".git"));
        Files.writeString(temp.resolve(".gitignore"), "runs/\n");
        target = Files.createDirectories(temp.resolve("target/src/p")).getParent().getParent();
        StringBuilder source = new StringBuilder("package p;\n\n/** Fixture. */\npublic class A {\n");
        for (int i = 1; i <= fields; i++) {
            source.append("    Object f").append(i).append(";\n");
        }
        source.append("\n    Object get() { return f1; }\n}\n");
        Files.writeString(target.resolve("src/p/A.java"), source.toString());
        Files.createDirectories(temp.resolve("checker"));
        shortDir = Files.createTempDirectory(Path.of("/tmp"), "ga");
        env.put("GA_CHECKER", "nullaway");
        env.put("GA_CHECKER_HOME", temp.resolve("checker").toString());
        env.put("GA_TARGET_PROJECT", target.toString());
        env.put("GA_TARGET_SOURCE_ROOTS", "src");
        env.put("GA_OUTPUT_DIR", temp.resolve("runs").toString());
        env.put("GA_SPECIMIN_HOME", FakeSpecimin.checkout(temp.resolve("specimin")).toString());
        env.put("GA_SSH_CONTROL_DIR", shortDir.resolve("s").toString());
        env.put("HOME", temp.toString());
        env.put("GA_REMOTE_BASE", "/project/test");
        checker = new FakeChecker(rule);
        cluster = new FakeCluster(temp.resolve("remote-fs"));
        cluster.connected = false;
    }

    /** Unannotated fields are reported; annotating f1 makes {@code return f1} a finding. */
    static CheckerOutcome rule(FakeChecker.Source s, int fields) {
        List<Diagnostic> findings = new ArrayList<>();
        for (int i = 1; i <= fields; i++) {
            if (!s.annotated("Object f" + i + ";")) {
                findings.add(s.at("Object f" + i + ";", "@NonNull field 'f" + i + "' not initialized"));
            }
        }
        if (s.annotated("Object f1;")) {
            findings.add(s.at("return f1", "returning @Nullable expression from method with @NonNull return type"));
        }
        return new CompletedAnalysis(findings, List.of());
    }

    /** Processes: git (the target is not a repository), Specimin's build and runs, and ssh probes. */
    private final CommandRunner runner = spec -> {
        List<String> c = spec.command();
        try {
            if (c.get(0).equals("ssh")) {
                sshCommands.add(c);
                if (c.get(c.size() - 1).contains("HEALTH")) {
                    return result(spec, healthy ? 0 : 255, healthy ? "GA-HEALTH ok\n" : "", healthy ? ""
                            : "mux_client_request_session: read from master failed: Broken pipe");
                }
                if (c.contains("-O") && c.contains("exit")) {
                    masterProcess = false;
                    return result(spec, 0, "", "Exit request sent.");
                }
                return result(spec, masterProcess ? 0 : 255, "", masterProcess ? "Master running (pid=7)"
                        : "Control socket connect: No such file");
            }
            if (FakeSpecimin.isGit(spec)) {
                return c.contains("--is-inside-work-tree") ? result(spec, 128, "", "fatal: not a git repository")
                        : result(spec, 0, "abc\n", "");
            }
            if (FakeSpecimin.isInstallDist(spec)) {
                FakeSpecimin.install(Path.of(env.get("GA_SPECIMIN_HOME")), FakeSpecimin.MAIN_CLASS);
                return result(spec, 0, "BUILD SUCCESSFUL", "");
            }
            if (c.contains("--outputDirectory")) {
                speciminRuns++;
                String member = c.contains("--targetField") ? c.get(c.indexOf("--targetField") + 1)
                        : c.get(c.indexOf("--targetMethod") + 1);
                if (failingMembers.contains(member)) {
                    return result(spec, 1, "", "Specimin could not locate the following target fields");
                }
                Path output = Path.of(c.get(c.indexOf("--outputDirectory") + 1)).resolve("p/A.java");
                Files.createDirectories(output.getParent());
                Files.writeString(output, "package p; class A { Object x; }");
                return result(spec, 0, "", "");
            }
            throw new IllegalStateException("unexpected command " + c);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    };

    private final CommandRunner terminal = spec -> {
        logins++;
        if (loginExit == 0) {
            masterProcess = true;
            healthy = true;
            cluster.connected = true;
        }
        return result(spec, loginExit, "", "");
    };

    private static CommandResult result(generalannotator.exec.CommandSpec spec, int exit, String stdout,
            String stderr) {
        return new CommandResult(spec, OptionalInt.of(exit), stdout, stderr, Duration.ZERO, false, Optional.empty());
    }

    private Pipeline pipeline() {
        out.reset();
        err.reset();
        Function<generalannotator.inference.remote.SshConnection, generalannotator.inference.remote.Cluster> clusters =
                connection -> cluster;
        return new Pipeline(env, temp, Path.of(System.getProperty("java.home")), new Pipeline.Tools(runner, terminal,
                true, (c, e) -> checker, clusters, new JavaParserAnnotationApplier(), new AnchoredWarningMatcher(),
                PrepareInferenceCommandTest.CLOCK), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    private Path runDir() throws IOException {
        try (Stream<Path> s = Files.list(temp.resolve("runs"))) {
            List<Path> dirs = s.toList();
            assertEquals(1, dirs.size(), dirs.toString());
            return dirs.get(0);
        }
    }

    private static Map<?, ?> json(Path file) throws IOException {
        return (Map<?, ?>) JsonParser.parse(Files.readString(file));
    }

    private Map<?, ?> state() throws IOException {
        return json(runDir().resolve(Pipeline.STATE));
    }

    private Map<?, ?> stage(String name) throws IOException {
        return (Map<?, ?>) ((Map<?, ?>) state().get("stages")).get(name);
    }

    private Path submission() throws IOException {
        try (Stream<Path> s = Files.list(runDir().resolve("inference"))) {
            List<Path> subs = s.filter(p -> Files.isRegularFile(p.resolve("submission.json"))).toList();
            assertEquals(1, subs.size(), "one submission per pipeline");
            return subs.get(0);
        }
    }

    private String remoteDir() throws IOException {
        return (String) json(submission().resolve("remote/state.json")).get("remoteDir");
    }

    private static Map<String, Object> field(String member, String action) {
        return ProposalFixture.editJson(member, Map.of("kind", "FIELD"), action);
    }

    /** Plays the worker: a PROPOSED answer with these edits. */
    private void answer(String warning, List<Map<String, Object>> edits) throws IOException {
        Map<String, Object> a = new LinkedHashMap<>(ProposalFixture.answer(warning, "PROPOSED", edits));
        cluster.workerResult(remoteDir(), warning, 1, "COMPLETED_PROPOSED", null, Json.write(a));
    }

    private void allTasks(String state) {
        cluster.scheduler.keySet().forEach(job -> cluster.setAll(job, state));
    }

    // -------------------------------------------------------------------------------------- start

    @Test
    void startRunsEveryStageWithoutInterventionLogsInOnceSubmitsOnceAndReturns() throws Exception {
        setUp(12);
        String targetBefore = EvaluationFiles.treeHash(target);

        assertEquals(ExitCode.COMPLETED, pipeline().start(Optional.empty()), output());

        assertEquals("SUBMITTED", state().get("status"));
        for (String s : List.of("analysis", "slices", "context", "prompts", "preparation", "submission")) {
            assertEquals("COMPLETED", stage(s).get("status"), s);
        }
        assertEquals("PENDING", stage("collection").get("status"));
        assertEquals(12L, stage("analysis").get("warnings"));
        assertEquals(java.util.Collections.nCopies(12, 1L), stage("preparation").get("taskSizes"),
                "one prompt per array task by default");
        assertEquals(1800L, stage("preparation").get("startupTimeoutSeconds"));
        assertTrue(output().contains("1 prompt(s) per array task (each a separate LLM request), vLLM startup timeout "
                + "1800 s, at most 4 task(s) at once"), output());
        assertEquals(12, speciminRuns, "every warning is sliced separately");
        assertEquals(1, logins, "one interactive authentication");
        assertEquals(1, cluster.sbatchCalls.size(), "one Slurm array job");
        assertEquals(List.of(cluster.lastJob()), stage("submission").get("jobIds"));
        assertEquals(0, cluster.queryCalls, "no monitoring after submission");
        Path dir = runDir();
        for (String f : List.of("report.json", "warning-graph.json", "warning-targets.json", "slices/slice-results.json",
                "prompts/manifest.json", "context/W0001.txt", Pipeline.SUMMARY)) {
            assertTrue(Files.exists(dir.resolve(f)), f);
        }
        assertTrue(output().indexOf("[1/9]") < output().indexOf("[2/9]")
                && output().indexOf("[5/9]") < output().indexOf("[6/9]"), "stages ran in order");
        assertTrue(output().contains("no longer needs this terminal"), output());
        assertEquals(targetBefore, EvaluationFiles.treeHash(target), "the target checkout is only read");

        // Starting again from the recorded state submits nothing new.
        assertEquals(ExitCode.COMPLETED, pipeline().start(Optional.of(dir.toString())), output());
        assertTrue(output().contains("nothing is submitted again"), output());
        assertEquals(1, cluster.sbatchCalls.size());
    }

    @Test
    void aFreshStartWithoutAnyRunsDirectoryGivesFortyOneSinglePromptTasks() throws Exception {
        setUp(42);
        env.remove("GA_OUTPUT_DIR"); // the default: runs/ under the working directory, which does not exist
        failingMembers.add("p.A#f33");
        assertFalse(Files.exists(temp.resolve("runs")));

        assertEquals(ExitCode.COMPLETED, pipeline().start(Optional.empty()), output());

        assertEquals("SUBMITTED", state().get("status"));
        Map<?, ?> preparation = stage("preparation");
        assertEquals(41L, preparation.get("prompts"));
        assertEquals(java.util.Collections.nCopies(41, 1L), preparation.get("taskSizes"));
        assertEquals(1L, preparation.get("promptsPerTask"));
        assertEquals(1800L, preparation.get("startupTimeoutSeconds"));
        assertEquals(4L, preparation.get("arrayConcurrency"));
        Map<?, ?> submission = json(submission().resolve("submission.json"));
        Set<Object> assigned = new java.util.HashSet<>();
        for (Object t : (List<?>) submission.get("tasks")) {
            List<?> ids = (List<?>) ((Map<?, ?>) t).get("warningIds");
            assertEquals(1, ids.size());
            assertTrue(assigned.add(ids.get(0)));
        }
        assertEquals(41, assigned.size());
        assertFalse(assigned.contains("W0033"));
        Map<?, ?> settings = json(submission().resolve("settings.json"));
        assertEquals(1800L, ((Map<?, ?>) settings.get("server")).get("startupTimeoutSeconds"));
        assertEquals(List.of("job-001 0-40%4"), cluster.sbatchCalls, "one array job, 41 tasks, at most 4 at once");
    }

    @Test
    void explicitBatchAndTimeoutSettingsStillApply() throws Exception {
        setUp(12);
        env.put("GA_INFERENCE_PROMPTS_PER_TASK", "10");
        env.put("GA_INFERENCE_STARTUP_TIMEOUT_SECONDS", "2400");
        env.put("GA_INFERENCE_ARRAY_CONCURRENCY", "2");

        assertEquals(ExitCode.COMPLETED, pipeline().start(Optional.empty()), output());

        assertEquals(List.of(10L, 2L), stage("preparation").get("taskSizes"));
        assertEquals(2400L, stage("preparation").get("startupTimeoutSeconds"));
        assertEquals(List.of("job-001 0-1%2"), cluster.sbatchCalls);
        assertTrue(output().contains("10 prompt(s) per array task (each a separate LLM request), vLLM startup timeout "
                + "2400 s, at most 2 task(s) at once"), output());

        env.put("GA_INFERENCE_STARTUP_TIMEOUT_SECONDS", "5");
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, pipeline().start(Optional.empty()), output());
        assertTrue(output().contains("GA_INFERENCE_STARTUP_TIMEOUT_SECONDS"), output());
    }

    @Test
    void invalidConfigurationIsReportedBeforeAnythingStarts() throws Exception {
        setUp(2);
        env.remove("GA_SPECIMIN_HOME");

        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, pipeline().start(Optional.empty()), output());

        assertTrue(output().contains("GA_SPECIMIN_HOME"), output());
        assertFalse(Files.exists(temp.resolve("runs")), "no pipeline directory was created");
        assertEquals(0, checker.calls());
    }

    @Test
    void aFailedBaselineBlocksEverythingAfterIt() throws Exception {
        setUp(2, s -> ExecutionFailure.before(FailureKind.CRASHED, "javac crashed"));

        assertEquals(ExitCode.EXECUTION_FAILURE, pipeline().start(Optional.empty()), output());

        assertEquals("FAILED", state().get("status"));
        assertEquals("FAILED", stage("analysis").get("status"));
        assertFalse(Files.exists(runDir().resolve("slices")));
        assertTrue(sshCommands.isEmpty());
        assertEquals(0, logins);
    }

    @Test
    void noWarningsEndsExplicitlyWithoutSubmitting() throws Exception {
        setUp(2, s -> new CompletedAnalysis(List.of(), List.of()));

        assertEquals(ExitCode.COMPLETED, pipeline().start(Optional.empty()), output());

        assertEquals("NO_WARNINGS", state().get("status"));
        assertEquals("SKIPPED", stage("submission").get("status"));
        assertFalse(Files.exists(runDir().resolve("inference")));
        assertTrue(sshCommands.isEmpty());
    }

    @Test
    void excludedPromptsAreReportedAndTheRestIsSubmitted() throws Exception {
        setUp(3);
        failingMembers.add("p.A#f2");

        assertEquals(ExitCode.COMPLETED, pipeline().start(Optional.empty()), output());

        List<?> exclusions = (List<?>) state().get("exclusions");
        assertTrue(exclusions.stream().anyMatch(x -> ((Map<?, ?>) x).get("warningId").equals("W0002")
                && ((Map<?, ?>) x).get("stage").equals("slices") && ((Map<?, ?>) x).get("status").equals("FAILED")),
                exclusions.toString());
        assertTrue(exclusions.stream().anyMatch(x -> ((Map<?, ?>) x).get("warningId").equals("W0002")
                && ((Map<?, ?>) x).get("stage").equals("prompts")), exclusions.toString());
        assertEquals(2L, stage("preparation").get("prompts"));
        assertEquals("SUBMITTED", state().get("status"));
    }

    @Test
    void noEligiblePromptEndsExplicitlyWithoutAnEmptyJob() throws Exception {
        setUp(2);
        failingMembers.addAll(List.of("p.A#f1", "p.A#f2"));

        assertEquals(ExitCode.EXECUTION_FAILURE, pipeline().start(Optional.empty()), output());

        assertEquals("NO_ELIGIBLE_PROMPTS", state().get("status"));
        assertFalse(Files.exists(runDir().resolve("inference")));
        assertEquals(0, logins);
        assertTrue(cluster.sbatchCalls.isEmpty());
    }

    @Test
    void anInterruptedStartResumesWithoutRepeatingWorkAndRefusesChangedSettings() throws Exception {
        setUp(2);
        loginExit = 1; // the login fails: everything up to the submission is prepared and kept

        assertEquals(ExitCode.RECONNECT_REQUIRED, pipeline().start(Optional.empty()), output());

        assertEquals("RECONNECT_REQUIRED", state().get("status"));
        assertEquals("COMPLETED", stage("preparation").get("status"));
        assertTrue(cluster.sbatchCalls.isEmpty());
        Path dir = runDir();

        // Simulate an interruption in the middle of slicing: one result missing, the stage still RUNNING.
        Path results = dir.resolve("slices/slice-results.json");
        Map<String, Object> r = new LinkedHashMap<>();
        json(results).forEach((k, v) -> r.put(String.valueOf(k), v));
        r.put("results", ((List<?>) r.get("results")).stream()
                .filter(x -> !((Map<?, ?>) x).get("warningId").equals("W0002")).toList());
        Files.writeString(results, Json.write(r));
        EvaluationFiles.deleteTree(dir.resolve("slices/W0002"));
        String text = Files.readString(dir.resolve(Pipeline.STATE));
        Object preparedAt = stage("preparation").get("finishedAt");
        Files.writeString(dir.resolve(Pipeline.STATE), text.replaceFirst("(\"slices\": \\{\\s*\"status\": )\"COMPLETED\"",
                "$1\"RUNNING\""));
        int runs = speciminRuns;

        env.put("GA_PROMPT_MAX_CHARS", "99999");
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, pipeline().start(Optional.of(dir.toString())), output());
        assertTrue(output().contains("GA_PROMPT_MAX_CHARS"), output());
        env.remove("GA_PROMPT_MAX_CHARS");

        loginExit = 0;
        assertEquals(ExitCode.COMPLETED, pipeline().start(Optional.of(dir.toString())), output());

        assertEquals(runs + 1, speciminRuns, "only the missing slice was generated");
        assertEquals("SUBMITTED", state().get("status"));
        assertEquals(1, cluster.sbatchCalls.size());
        submission(); // still exactly one submission
        assertEquals(preparedAt, stage("preparation").get("finishedAt"), "the submission was not prepared again");
        assertEquals(2, logins, "the failed login and one successful login");
    }

    @Test
    void anUnknownSbatchOutcomeIsNeverResubmitted() throws Exception {
        setUp(2);
        cluster.sbatchMode = FakeCluster.SbatchMode.UNKNOWN_AFTER_SUBMIT;

        assertEquals(ExitCode.EXECUTION_FAILURE, pipeline().start(Optional.empty()), output());
        assertEquals("SUBMISSION_UNKNOWN", state().get("status"));
        assertTrue(output().contains("inference attach"), output());

        masterProcess = true;
        healthy = true;
        cluster.connected = true;
        assertEquals(ExitCode.EXECUTION_FAILURE, pipeline().start(Optional.of(runDir().toString())), output());
        assertEquals("SUBMISSION_UNKNOWN", state().get("status"));
        assertEquals(1, cluster.sbatchCalls.size(), "never submitted twice");

        assertEquals(ExitCode.EXECUTION_FAILURE, pipeline().finish(runDir().toString()), output());
        assertTrue(output().contains("no confirmed Slurm submission"), output());
        assertEquals(0, cluster.downloadCalls);
    }

    // -------------------------------------------------------------------------------------- status

    @Test
    void statusLabelsCachedInformationAndReconnectsOnlyWhenAsked() throws Exception {
        setUp(2);
        assertEquals(ExitCode.COMPLETED, pipeline().start(Optional.empty()), output());
        Path dir = runDir();
        // The laptop slept: the master and the VPN are gone.
        masterProcess = false;
        healthy = false;
        cluster.connected = false;

        assertEquals(ExitCode.RECONNECT_REQUIRED, pipeline().status(dir.toString(), false), output());
        assertTrue(output().contains("Recorded in pipeline.json"), output());
        assertTrue(output().contains("CACHED") && output().contains("transport"), output());
        assertEquals(1, logins, "status without --connect never prompts");

        assertEquals(ExitCode.COMPLETED, pipeline().status(dir.toString(), true), output());
        assertEquals(2, logins);
        assertTrue(output().contains("Scheduler (array tasks): {ACTIVE=2}"), output());
        assertTrue(output().contains("Prompt results: not collected yet"), output());
        assertEquals(1, cluster.sbatchCalls.size(), "a lost connection never resubmits");
    }

    // -------------------------------------------------------------------------------------- finish

    @Test
    void finishWhileTasksAreActiveReportsAndCollectsNothing() throws Exception {
        setUp(2);
        assertEquals(ExitCode.COMPLETED, pipeline().start(Optional.empty()), output());

        assertEquals(ExitCode.EXECUTION_FAILURE, pipeline().finish(runDir().toString()), output());

        assertEquals("WAITING_FOR_JOBS", state().get("status"));
        assertEquals("WAITING", stage("collection").get("status"));
        assertEquals(0, cluster.downloadCalls);
        assertFalse(Files.exists(runDir().resolve("evaluations")));
        assertEquals(1, logins, "the healthy master was reused");
    }

    @Test
    void finishCollectsEvaluatesEachUniquePackageThenAppliesTheWholeRoundAndIsIdempotent() throws Exception {
        setUp(4);
        assertEquals(ExitCode.COMPLETED, pipeline().start(Optional.empty()), output());
        Path dir = runDir();
        answer("W0001", List.of(field("f1", "ADD")));
        answer("W0002", List.of(field("f1", "ADD")));
        answer("W0003", List.of(field("f2", "ADD"), field("f3", "ADD")));
        cluster.workerResult(remoteDir(), "W0004", 1, "FAILED", "STARTUP_TIMEOUT");
        allTasks("COMPLETED");
        // Back after a sleep: the managed master still answers, but its connection is dead.
        masterProcess = true;
        healthy = false;
        cluster.connected = false;
        int before = checker.calls();

        assertEquals(ExitCode.COMPLETED, pipeline().finish(dir.toString()), output());

        assertTrue(sshCommands.stream().anyMatch(c -> c.contains("-O") && c.contains("exit")), "stale master stopped");
        assertEquals(2, logins, "one new login");
        assertEquals("PARTIAL", state().get("status"), "W0004's inference failed");
        assertTrue(((List<?>) state().get("exclusions")).stream().anyMatch(x -> ((Map<?, ?>) x).get("warningId")
                .equals("W0004") && ((Map<?, ?>) x).get("stage").equals("collection")));
        Map<?, ?> evaluation = stage("evaluation");
        assertEquals("COMPLETED", evaluation.get("status"));
        assertEquals(3L, evaluation.get("importedProposals"));
        assertEquals(2L, evaluation.get("uniquePackages"), "W0001 and W0002 share one package");
        Path graphFile = dir.resolve((String) ((Map<?, ?>) evaluation.get("artifacts")).get("graph"));
        WarningGraph graph = WarningGraphJson.load(graphFile);
        List<AnnotationNode> nodes = graph.nodes().stream().filter(AnnotationNode.class::isInstance)
                .map(AnnotationNode.class::cast).toList();
        assertEquals(2, nodes.size());
        assertEquals(List.of("W0001", "W0002"), nodes.get(0).proposals().stream().map(AnnotationNode.Proposal::warningId)
                .toList());
        assertEquals(2, nodes.get(1).edits().size(), "the two-edit answer is one atomic package");
        Map<?, ?> combined = stage("combined");
        assertEquals("COMPLETED", combined.get("status"));
        Map<?, ?> comparison = (Map<?, ?>) combined.get("comparison");
        assertEquals(3L, comparison.get("resolved"));
        assertEquals(1L, comparison.get("introduced"));
        assertEquals(1L, comparison.get("persisting"), "f4 had no package");
        Path warnings = dir.resolve((String) ((Map<?, ?>) combined.get("artifacts")).get("warnings"));
        assertTrue(Files.readString(warnings).contains("# count:   2"), Files.readString(warnings));
        // Independent evaluations ran before the combined check.
        List<Path> checked = checker.requests.subList(before, checker.requests.size()).stream()
                .map(r -> r.workingCopyRoot()).toList();
        int lastEvaluation = -1;
        int firstRound = Integer.MAX_VALUE;
        for (int i = 0; i < checked.size(); i++) {
            if (checked.get(i).toString().contains("/evaluations/")) {
                lastEvaluation = i;
            } else if (checked.get(i).toString().contains("/rounds/")) {
                firstRound = Math.min(firstRound, i);
            }
        }
        assertTrue(lastEvaluation >= 0 && lastEvaluation < firstRound, checked.toString());
        assertEquals(4, checked.size(), "evaluation baseline, two packages, the combined state (baseline reused)");
        assertTrue(Files.readString(dir.resolve(Pipeline.SUMMARY)).contains("PARTIAL"));

        // Repeating finish contacts nothing and duplicates nothing.
        String graphBefore = Files.readString(graphFile);
        int calls = checker.calls();
        int downloads = cluster.downloadCalls;
        assertEquals(ExitCode.COMPLETED, pipeline().finish(dir.toString()), output());
        assertEquals(calls, checker.calls());
        assertEquals(downloads, cluster.downloadCalls);
        assertEquals(graphBefore, Files.readString(graphFile));
        try (Stream<Path> rounds = Files.list(dir.resolve("rounds"))) {
            assertEquals(1, rounds.filter(p -> !p.getFileName().toString().startsWith(".")).count());
        }
        try (Stream<Path> evaluations = Files.list(dir.resolve("evaluations"))) {
            assertEquals(1, evaluations.count());
        }
        assertEquals(stage("analysis").get("snapshotTreeSha256"), EvaluationFiles.treeHash(dir.resolve(
                "workspace/target")), "the pipeline snapshot is never changed");
    }

    @Test
    void oneTasksStartupFailureExcludesOnlyItsOwnPrompt() throws Exception {
        setUp(3);
        assertEquals(ExitCode.COMPLETED, pipeline().start(Optional.empty()), output());
        answer("W0001", List.of(field("f1", "ADD")));
        cluster.workerResult(remoteDir(), "W0002", 1, "FAILED", "STARTUP_TIMEOUT");
        answer("W0003", List.of(field("f3", "ADD")));
        String job = cluster.lastJob();
        cluster.setAll(job, "COMPLETED");
        cluster.scheduler.get(job).put(1, "FAILED");

        assertEquals(ExitCode.COMPLETED, pipeline().finish(runDir().toString()), output());

        assertEquals("PARTIAL", state().get("status"));
        List<?> exclusions = (List<?>) state().get("exclusions");
        assertEquals(1, exclusions.size(), exclusions.toString());
        Map<?, ?> x = (Map<?, ?>) exclusions.get(0);
        assertEquals(List.of("W0002", "collection", "FAILED"), List.of(x.get("warningId"), x.get("stage"),
                x.get("status")));
        assertTrue(String.valueOf(x.get("reason")).contains("STARTUP_TIMEOUT"), x.toString());
        assertEquals(2L, stage("evaluation").get("importedProposals"));
        assertEquals("COMPLETED", stage("combined").get("status"));
    }

    @Test
    void aPipelineRecordedWithTheOldBatchDefaultStillFinishes() throws Exception {
        setUp(2);
        env.put("GA_INFERENCE_PROMPTS_PER_TASK", "10"); // as the earlier pipeline default did
        env.put("GA_INFERENCE_STARTUP_TIMEOUT_SECONDS", "1200");
        assertEquals(ExitCode.COMPLETED, pipeline().start(Optional.empty()), output());
        Path dir = runDir();
        // Earlier versions fingerprinted the inference settings; such a record must not block finish.
        Files.writeString(dir.resolve(Pipeline.STATE), Files.readString(dir.resolve(Pipeline.STATE))
                .replace("\"settings\": {", "\"settings\": {\n      \"GA_INFERENCE_PROMPTS_PER_TASK\": \"10\","));
        env.remove("GA_INFERENCE_PROMPTS_PER_TASK");
        env.remove("GA_INFERENCE_STARTUP_TIMEOUT_SECONDS");
        answer("W0001", List.of(field("f1", "ADD")));
        answer("W0002", List.of(field("f2", "ADD")));
        allTasks("COMPLETED");

        assertEquals(ExitCode.COMPLETED, pipeline().finish(dir.toString()), output());

        assertTrue(output().contains("prepared with 10 prompt(s) per task and a 1200 s startup timeout; it is "
                + "immutable"), output());
        assertEquals("COMPLETED", state().get("status"));
    }

    @Test
    void propagationRefusesToRunWithoutACompletedCombinedRoundAndNeverFallsBack() throws Exception {
        setUp(2);
        assertEquals(ExitCode.COMPLETED, pipeline().start(Optional.empty()), output());
        Path dir = runDir();
        int calls = checker.calls();

        assertEquals(ExitCode.EXECUTION_FAILURE, pipeline().propagate(dir.toString()), output());

        assertTrue(output().contains("combined stage is PENDING") && output().contains("pipeline finish"), output());
        assertFalse(Files.exists(dir.resolve("propagation")), "the original baseline is not analyzed instead");
        assertEquals(calls, checker.calls());
        assertEquals("SUBMITTED", state().get("status"));
    }

    @Test
    void propagationReadsTheCombinedRoundOneResultRecordedInThePipeline() throws Exception {
        setUp(2);
        assertEquals(ExitCode.COMPLETED, pipeline().start(Optional.empty()), output());
        Path dir = runDir();
        answer("W0001", List.of(field("f1", "ADD")));
        answer("W0002", List.of(field("f2", "ADD")));
        allTasks("COMPLETED");
        assertEquals(ExitCode.COMPLETED, pipeline().finish(dir.toString()), output());
        String combined = (String) ((Map<?, ?>) stage("combined").get("artifacts")).get("directory");
        Map<?, ?> round = json(dir.resolve(combined).resolve("round.json"));

        assertEquals(ExitCode.COMPLETED, pipeline().propagate(dir.toString()), output());

        Map<?, ?> propagation = stage("propagation");
        assertEquals("COMPLETED", propagation.get("status"));
        Map<?, ?> input = (Map<?, ?>) propagation.get("input");
        assertEquals(1L, input.get("sourceRound"));
        assertEquals(dir.resolve(combined).getFileName().toString(), input.get("state"));
        assertEquals(round.get("findingCount"), input.get("warnings"), "the combined result's warnings, not the 2 "
                + "original ones");
        assertTrue(output().contains("source round 1, state " + round.get("roundResultId")), output());
        assertTrue(output().contains("snapshot:   " + dir.resolve(combined).resolve("workspace/target")), output());
        assertEquals("PARTIAL".equals(state().get("status")) || "COMPLETED".equals(state().get("status")), true);
        assertEquals(1, cluster.sbatchCalls.size(), "nothing was submitted");
    }

    @Test
    void aConflictingRoundBlocksTheCombinedStageButKeepsTheIndependentEvaluations() throws Exception {
        setUp(2);
        assertEquals(ExitCode.COMPLETED, pipeline().start(Optional.empty()), output());
        answer("W0001", List.of(field("f1", "ADD")));
        answer("W0002", List.of(field("f1", "REMOVE")));
        allTasks("COMPLETED");

        assertEquals(ExitCode.EXECUTION_FAILURE, pipeline().finish(runDir().toString()), output());

        assertEquals("BLOCKED", state().get("status"));
        assertEquals("COMPLETED", stage("evaluation").get("status"));
        assertEquals("BLOCKED", stage("combined").get("status"));
        assertEquals("REJECTED", stage("combined").get("roundStatus"));
        Path evaluation = runDir().resolve((String) ((Map<?, ?>) stage("evaluation").get("artifacts")).get("directory"));
        assertTrue(Files.exists(evaluation.resolve("attempts/A0001/attempt.json")));
        assertTrue(Files.exists(evaluation.resolve("attempts/A0002/attempt.json")));
    }

    @Test
    void aFailedCombinedCheckIsAnExecutionFailureNotZeroWarnings() throws Exception {
        setUp(2, s -> s.annotated("Object f1;") && s.annotated("Object f2;")
                ? new ExecutionFailure(FailureKind.COMPILATION_FAILED, List.of("cannot find symbol"), List.of(),
                        List.of())
                : rule(s, 2));
        assertEquals(ExitCode.COMPLETED, pipeline().start(Optional.empty()), output());
        answer("W0001", List.of(field("f1", "ADD")));
        answer("W0002", List.of(field("f2", "ADD")));
        allTasks("COMPLETED");

        assertEquals(ExitCode.EXECUTION_FAILURE, pipeline().finish(runDir().toString()), output());

        assertEquals("FAILED", state().get("status"));
        assertEquals("COMPLETED", stage("evaluation").get("status"));
        assertEquals("FAILED", stage("combined").get("status"));
        assertEquals("CHECK_FAILED", stage("combined").get("roundStatus"));
    }

    // -------------------------------------------------------------------------------------- propagation rounds

    static final String CHAIN = """
            package p;

            /** Fixture. */
            public class A implements Src {
                Object f1;
                Object m;
                Object h;
                Object k;

                Object get() { return f1; }

                public Object val() { return get(); }

                void init() {
                    m = get();
                    h = get();
                    k = h;
                }
            }
            """;

    static final String ASSIGN = "assigning @Nullable expression to @NonNull field";
    static final String RETURN = "returning @Nullable expression from method with @NonNull return type";

    /**
     * Like NullAway on {@link #CHAIN}: round 1 annotates f1, which makes {@code return f1} warn; round 2 annotates
     * get(), which makes {@code h = get()} and {@code return get()} (in val(), implementing Src) warn and gives the
     * original {@code m = get()} a nullable origin; round 3 annotates m, h and val() (with Src#val), and h makes
     * {@code k = h} warn, which only a later round may take up.
     */
    static CheckerOutcome chain(FakeChecker.Source s) {
        List<Diagnostic> f = new ArrayList<>();
        if (!s.annotated("Object f1;")) {
            f.add(s.at("Object f1;", "@NonNull field 'f1' not initialized"));
        }
        if (!s.annotated("Object m;")) {
            f.add(s.at("m = get();", ASSIGN));
        }
        if (s.annotated("Object f1;") && !s.annotated("Object get()")) {
            f.add(s.at("return f1", RETURN));
        }
        if (s.annotated("Object get()")) {
            if (!s.annotated("Object h;")) {
                f.add(s.at("h = get();", ASSIGN));
            }
            if (!s.annotated("Object val()")) {
                f.add(s.at("return get();", RETURN));
            }
        }
        if (s.annotated("Object h;") && !s.annotated("Object k;")) {
            f.add(s.at("k = h;", ASSIGN));
        }
        return new CompletedAnalysis(f, List.of());
    }

    /** The chain target, pipeline round 1 finished (f1 annotated) and propagation round 2 completed. */
    private Path roundsOneAndTwo() throws Exception {
        setUp(1, PipelineTest::chain);
        Files.writeString(target.resolve("src/p/A.java"), CHAIN);
        Files.writeString(target.resolve("src/p/Src.java"), "package p;\n\ninterface Src {\n    Object val();\n}\n");
        Files.createDirectories(target.resolve("src/org/jspecify/annotations"));
        Files.writeString(target.resolve("src/org/jspecify/annotations/Nullable.java"),
                "package org.jspecify.annotations;\n\npublic @interface Nullable {}\n");
        assertEquals(ExitCode.COMPLETED, pipeline().start(Optional.empty()), output());
        Path dir = runDir();
        answer("W0001", List.of(field("f1", "ADD")));
        cluster.workerResult(remoteDir(), "W0002", 1, "FAILED", "STARTUP_TIMEOUT");
        allTasks("COMPLETED");
        assertEquals(ExitCode.COMPLETED, pipeline().finish(dir.toString()), output());
        assertEquals(ExitCode.COMPLETED, pipeline().propagate(dir.toString()), output());
        assertEquals("COMPLETED", stage("propagation").get("outcome"), output());
        return dir;
    }

    private static String artifact(Map<?, ?> stage, String key) {
        return (String) ((Map<?, ?>) stage.get("artifacts")).get(key);
    }

    private static List<String> edges(WarningGraph g) {
        return g.edges().stream().map(e -> e.source() + " " + e.kind() + " " + e.target()).toList();
    }

    @Test
    void roundThreeReadsTheCombinedRoundTwoResultAndExtendsItsGraphInOnePass() throws Exception {
        Path dir = roundsOneAndTwo();
        Map<?, ?> roundTwo = stage("propagation");
        String roundTwoRecord = Json.write(roundTwo);
        Path roundTwoCombined = dir.resolve(artifact(roundTwo, "combined"));
        Path roundTwoGraph = dir.resolve(artifact(roundTwo, "graph"));
        String roundTwoGraphText = Files.readString(roundTwoGraph);
        String roundTwoTree = EvaluationFiles.treeHash(roundTwoCombined);
        WarningGraph before = WarningGraphJson.load(roundTwoGraph);
        String targetBefore = EvaluationFiles.treeHash(target);

        assertEquals(ExitCode.COMPLETED, pipeline().propagate(dir.toString(), "3"), output());

        // round 2's record is untouched; round 3 has its own
        assertEquals(roundTwoRecord, Json.write(stage("propagation")), "round 3 never overwrites round 2's record");
        Map<?, ?> three = stage("propagation-r3");
        assertEquals("COMPLETED", three.get("status"), output());
        assertEquals(3L, three.get("round"));
        Map<?, ?> input = (Map<?, ?>) three.get("input");
        assertEquals(2L, input.get("sourceRound"));
        assertEquals(roundTwoCombined.getFileName().toString(), input.get("state"),
                "the combined round-2 result named in round 2's record, not round 1 or a package workspace");
        assertTrue(artifact(three, "directory").startsWith("propagation/propagation3-"), three.toString());
        assertTrue(artifact(three, "combined").startsWith("rounds/round3-"), three.toString());
        Map<?, ?> record = json(dir.resolve(artifact(three, "directory")).resolve("propagation.json"));
        assertEquals(3L, record.get("round"));

        // candidates: the persisting original m = get() (its origin became @Nullable in round 2), and round 2's
        // h = get() and return get(), the latter as one hierarchy package with Src#val
        Map<?, ?> analysis = json(dir.resolve(artifact(three, "directory")).resolve("analysis.json"));
        assertEquals(3, ((List<?>) analysis.get("matches")).size(), analysis.toString());
        List<?> packages = (List<?>) record.get("packages");
        assertEquals(List.of("P3-0001", "P3-0002", "P3-0003"), packages.stream()
                .map(p -> ((Map<?, ?>) p).get("id")).sorted().toList(), "round-3 package IDs never collide with "
                + "round 2's P0001");
        assertTrue(packages.stream().anyMatch(p -> ((Map<?, ?>) p).get("edits").toString().contains("p.Src#val()")
                && ((Map<?, ?>) p).get("edits").toString().contains("p.A#val()")), packages.toString());

        // the full annotated copy keeps every earlier round's annotations
        Path combined = dir.resolve(artifact(three, "combined"));
        String a = Files.readString(combined.resolve("workspace/target/src/p/A.java"));
        for (String annotated : List.of("@org.jspecify.annotations.Nullable Object f1;",
                "@org.jspecify.annotations.Nullable Object get()", "@org.jspecify.annotations.Nullable Object m;",
                "@org.jspecify.annotations.Nullable Object h;",
                "public @org.jspecify.annotations.Nullable Object val()")) {
            assertTrue(a.contains(annotated), annotated + " in\n" + a);
        }
        assertTrue(a.contains("    Object k;"), "one pass: k = h is introduced by round 3 and not propagated\n" + a);
        assertTrue(Files.readString(combined.resolve("workspace/target/src/p/Src.java"))
                .contains("@org.jspecify.annotations.Nullable Object val();"));
        for (String file : List.of("changes.diff", "warnings.txt", "report.json", "findings.json", "round.json")) {
            assertTrue(Files.isRegularFile(combined.resolve(file)), file);
        }
        Map<?, ?> comparison = (Map<?, ?>) ((Map<?, ?>) record.get("combined")).get("comparison");
        assertEquals(3L, comparison.get("resolved"), comparison.toString());
        assertEquals(1L, comparison.get("introduced"));

        // the graph: round 2's graph kept whole, round 3's packages and edges added, W3001 for k = h
        WarningGraph g = WarningGraphJson.load(dir.resolve(artifact(three, "graph")));
        before.nodes().forEach(n -> assertEquals(n, g.node(n.id()).orElseThrow(), "kept: " + n.id()));
        before.edges().forEach(e -> assertTrue(g.edges().contains(e), "kept: " + e));
        Set<String> ids = new java.util.HashSet<>();
        g.nodes().forEach(n -> assertTrue(ids.add(n.id()), "duplicate node " + n.id()));
        assertTrue(g.nodes().stream().allMatch(n -> n instanceof AnnotationNode
                || n instanceof generalannotator.graph.WarningNode));
        assertTrue(g.edges().stream().allMatch(e -> e.kind().equals("resolved-by") || e.kind().equals("introduces")));
        List<String> edges = edges(g);
        String mPackage = packages.stream().map(p -> (Map<?, ?>) p).filter(p -> p.get("edits").toString()
                .contains("p.A#m ")).findFirst().orElseThrow().get("id").toString();
        String hPackage = packages.stream().map(p -> (Map<?, ?>) p).filter(p -> p.get("edits").toString()
                .contains("p.A#h ")).findFirst().orElseThrow().get("id").toString();
        assertTrue(edges.contains("W0002 resolved-by " + mPackage), "an original (W0) warning persisting through two "
                + "rounds is resolved in round 3\n" + edges);
        assertTrue(edges.contains(hPackage + " introduces W3001"), edges.toString());
        assertTrue(edges.stream().anyMatch(e -> e.matches("W2\\d{3} resolved-by " + hPackage)),
                "round 2's introduced h = get() is resolved in round 3\n" + edges);
        assertEquals(1, g.warningNodes().stream().filter(w -> w.id().startsWith("W3")).count(),
                "only the genuinely new warning gets a round-3 ID");
        Map<?, ?> history = (Map<?, ?>) record.get("history");
        assertEquals(Map.of("linked-to-package-warning", 2L, "persisting-from-base", 1L), history.get("counts"));

        // nothing earlier was modified
        assertEquals(roundTwoGraphText, Files.readString(roundTwoGraph));
        assertEquals(roundTwoTree, EvaluationFiles.treeHash(roundTwoCombined));
        assertEquals(targetBefore, EvaluationFiles.treeHash(target));
        assertTrue(Files.readString(dir.resolve(Pipeline.SUMMARY)).contains("propagation-r3"));

        // repeating the command reuses everything and duplicates nothing
        int calls = checker.calls();
        String graphText = Files.readString(dir.resolve(artifact(three, "graph")));
        assertEquals(ExitCode.COMPLETED, pipeline().propagate(dir.toString(), "3"), output());
        assertEquals(calls, checker.calls(), "nothing is rechecked");
        assertEquals(graphText, Files.readString(dir.resolve(artifact(stage("propagation-r3"), "graph"))));
        assertEquals(List.of(), stage("propagation-r3").get("previous") == null ? List.of()
                : stage("propagation-r3").get("previous"), "an identical rerun is not archived as history");
        try (Stream<Path> rounds = Files.list(dir.resolve("rounds"))) {
            assertEquals(1, rounds.filter(p -> p.getFileName().toString().startsWith("round3-")).count());
        }

        // the graph can be rebuilt from saved artifacts alone, identically
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertEquals(ExitCode.COMPLETED, generalannotator.cli.PropagateCommand.rebuildGraph(
                dir.resolve(artifact(three, "directory")).toString(), temp, new AnchoredWarningMatcher(),
                PrepareInferenceCommandTest.CLOCK, sink, sink));
        assertEquals(graphText, Files.readString(dir.resolve(artifact(three, "graph"))));
        assertEquals(calls, checker.calls());

        // the same logic serves round 4: k = h, introduced in round 3, is taken up only now
        assertEquals(ExitCode.COMPLETED, pipeline().propagate(dir.toString(), "4"), output());
        Map<?, ?> four = stage("propagation-r4");
        assertEquals(artifact(three, "combined"), ((String) ((Map<?, ?>) four.get("input")).get("directory"))
                .substring(dir.toString().length() + 1));
        WarningGraph g4 = WarningGraphJson.load(dir.resolve(artifact(four, "graph")));
        assertTrue(edges(g4).contains("W3001 resolved-by P4-0001"), edges(g4).toString());
        assertTrue(g4.warningNodes().stream().noneMatch(w -> w.id().startsWith("W4")), "no W4 nodes: none introduced");
    }

    @Test
    void roundThreeRefusesAMissingIncompleteOrStaleRoundTwoWithoutFallingBack() throws Exception {
        setUp(1, PipelineTest::chain);
        Files.writeString(target.resolve("src/p/A.java"), CHAIN);
        Files.writeString(target.resolve("src/p/Src.java"), "package p;\n\ninterface Src {\n    Object val();\n}\n");
        assertEquals(ExitCode.COMPLETED, pipeline().start(Optional.empty()), output());
        Path dir = runDir();

        // no round 2 (and not even round 1): refused, nothing recorded or written
        assertEquals(ExitCode.EXECUTION_FAILURE, pipeline().propagate(dir.toString(), "3"), output());
        assertTrue(output().contains("pipeline stage propagation is PENDING") && output().contains("pipeline "
                + "propagate " + dir + "`"), output());
        assertFalse(((Map<?, ?>) state().get("stages")).containsKey("propagation-r3"));
        assertFalse(Files.exists(dir.resolve("propagation")));
        // round 1 is not a propagation round, nor is garbage
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, pipeline().propagate(dir.toString(), "1"), output());
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, pipeline().propagate(dir.toString(), "x"), output());
    }

    @Test
    void roundThreeRefusesAnInconsistentOrStaleRoundTwoRecord() throws Exception {
        Path dir = roundsOneAndTwo();
        Path state = dir.resolve(Pipeline.STATE);
        String original = Files.readString(state);
        Path record = dir.resolve(artifact(stage("propagation"), "directory")).resolve("propagation.json");
        String recordText = Files.readString(record);
        int calls = checker.calls();

        // the stage names another combined directory than its record: inconsistent
        String combined = artifact(stage("propagation"), "combined");
        Files.writeString(state, original.replace("\"combined\": \"" + combined + "\"",
                "\"combined\": \"rounds/" + json(state).get("pipelineId") + "\""));
        assertEquals(ExitCode.EXECUTION_FAILURE, pipeline().propagate(dir.toString(), "3"), output());
        assertTrue(output().contains("does not describe a completed round 2") || output().contains("missing"),
                output());
        Files.writeString(state, original);

        // round 2 was computed on another input than round 1 now resolves to: stale, refused
        Map<?, ?> input = (Map<?, ?>) json(record).get("input");
        Files.writeString(record, recordText.replace((String) input.get("identity"), "0".repeat(64)));
        assertEquals(ExitCode.EXECUTION_FAILURE, pipeline().propagate(dir.toString(), "3"), output());
        assertTrue(output().contains("stale") && output().contains("pipeline propagate " + dir + "`"), output());
        Files.writeString(record, recordText);

        // round 2's combined snapshot was modified: its warnings no longer describe it
        Path a = dir.resolve(combined).resolve("workspace/target/src/p/A.java");
        String source = Files.readString(a);
        Files.writeString(a, source + "\n// edited\n");
        assertEquals(ExitCode.EXECUTION_FAILURE, pipeline().propagate(dir.toString(), "3"), output());
        assertTrue(output().contains("no longer matches round.json"), output());
        Files.writeString(a, source);

        assertEquals(calls, checker.calls(), "nothing ran");
        assertFalse(((Map<?, ?>) state().get("stages")).containsKey("propagation-r3"));
        assertEquals(ExitCode.COMPLETED, pipeline().propagate(dir.toString(), "3"), "restored, it runs\n" + output());
    }

    @Test
    void theDefaultRoundIsStillRoundTwo() throws Exception {
        Path dir = roundsOneAndTwo();
        String record = Json.write(stage("propagation"));
        int calls = checker.calls();

        assertEquals(ExitCode.COMPLETED, pipeline().propagate(dir.toString(), "2"), output());

        assertEquals(calls, checker.calls(), "--round 2 is the same execution as the default");
        assertEquals(artifact((Map<?, ?>) JsonParser.parse(record), "directory"),
                artifact(stage("propagation"), "directory"));
        assertTrue(artifact(stage("propagation"), "directory").startsWith("propagation/propagation2-"));
        assertFalse(((Map<?, ?>) state().get("stages")).containsKey("propagation-r2"));
    }
}
