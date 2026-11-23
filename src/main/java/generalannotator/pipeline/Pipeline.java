package generalannotator.pipeline;

import generalannotator.cli.ApplyRoundCommand;
import generalannotator.cli.CheckCommand;
import generalannotator.cli.ContextCommand;
import generalannotator.cli.EvaluateAnnotationsCommand;
import generalannotator.cli.ExitCode;
import generalannotator.cli.InferenceCommand;
import generalannotator.cli.PrepareInferenceCommand;
import generalannotator.cli.PromptsCommand;
import generalannotator.cli.PropagateCommand;
import generalannotator.cli.SliceCommand;
import generalannotator.config.ConfigLoader;
import generalannotator.config.ConfigurationException;
import generalannotator.edits.AnnotationApplier;
import generalannotator.evaluation.EvaluationConfig;
import generalannotator.evaluation.EvaluationFiles;
import generalannotator.evaluation.Evaluator;
import generalannotator.evaluation.RoundApplication;
import generalannotator.evaluation.matching.WarningMatcher;
import generalannotator.exec.CommandRunner;
import generalannotator.graph.WarningGraphJson;
import generalannotator.inference.InferenceSettings;
import generalannotator.inference.remote.Cluster;
import generalannotator.inference.remote.RemoteSettings;
import generalannotator.inference.remote.SshConnection;
import generalannotator.prompts.PromptManifestJson;
import generalannotator.propagation.PropagationStage;
import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import generalannotator.slicing.SliceResultsJson;
import generalannotator.targets.WarningTargetsJson;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * One resumable round-1 pipeline over the existing stages ({@code pipeline start | status | finish}).
 * It calls the existing commands in-process, in order, and records their outcomes in {@code pipeline.json}
 * inside the run directory that {@code check} creates (the pipeline directory):
 *
 * <ol>
 *   <li>{@code start}: {@code check} (snapshot, findings, warning graph, targets), {@code slice}, {@code
 *       context}, {@code prompts}, {@code prepare-inference} (by default one prompt per array task and an 1800 s startup timeout), then
 *       {@code inference connect} (reuse a healthy master or one interactive login), {@code upload} and
 *       {@code submit}. It returns once the job ID is saved; it never monitors.
 *   <li>{@code status}: the recorded stages, then {@code inference status} (a fresh scheduler query, or the
 *       cached one labeled as such); {@code --connect} first runs {@code inference connect}.
 *   <li>{@code finish}: {@code connect}, {@code status}; only when every array task is terminal, {@code
 *       collect}, {@code evaluate-annotations} (merged packages, one node each, expanded graph) and {@code
 *       apply-round} (all packages together, one check, every finding).
 * </ol>
 *
 * <p>Stages are resumed only while the recorded configuration fingerprint (every {@code GA_*} setting
 * except transport, inference and Slurm settings) and the snapshot tree hash are unchanged. A stage interrupted while
 * RUNNING is redone; slicing continues per warning, because slice results are never overwritten. An sbatch
 * call is never repeated: a recorded job is reused, and an UNKNOWN outcome must be resolved with {@code
 * inference attach}. Later stages are idempotent (collection merges, evaluations and round results are
 * reused by key), so a repeated {@code finish} duplicates nothing. One writer at a time holds the lock.
 */
public final class Pipeline {

    public static final String STATE = "pipeline.json";
    public static final String SUMMARY = "pipeline-summary.txt";
    public static final int SCHEMA_VERSION = 1;
    public static final int ROUND = 1;
    /**
     * Settings that are not fingerprinted: how the cluster is reached, and the inference and Slurm settings,
     * which only shape the submission. A prepared submission is immutable and keeps its own settings.json;
     * the pipeline adds no defaults of its own (prompts per task, startup timeout and concurrency are
     * {@link InferenceSettings}' defaults unless set).
     */
    static final List<String> NOT_FINGERPRINTED = List.of("GA_SSH_", "GA_RSYNC_", "GA_REMOTE_POLL_SECONDS",
            "GA_REMOTE_MAX_UNKNOWN_POLLS", "GA_INFERENCE_", "GA_SLURM_");
    static final List<String> STAGES = List.of("analysis", "slices", "context", "prompts", "preparation",
            "submission", "collection", "evaluation", "combined", "propagation");

    /** Pipeline outcomes recorded as {@code status}. */
    public enum Status {
        RUNNING, FAILED, NO_WARNINGS, NO_ELIGIBLE_PROMPTS, RECONNECT_REQUIRED, SUBMISSION_UNKNOWN, SUBMITTED,
        WAITING_FOR_JOBS, NO_PACKAGES, BLOCKED, PARTIAL, COMPLETED
    }

    /** External effects, replaced in tests. */
    public record Tools(CommandRunner runner, CommandRunner terminalRunner, boolean interactive,
            EvaluateAnnotationsCommand.CheckerFactory checkers, Function<SshConnection, Cluster> clusters,
            AnnotationApplier applier, WarningMatcher matcher, Clock clock) {}

    private final Map<String, String> env;
    private final Path workingDirectory;
    private final Path defaultJavaHome;
    private final Tools tools;
    private final PrintStream out;
    private final PrintStream err;

    public Pipeline(Map<String, String> env, Path workingDirectory, Path defaultJavaHome, Tools tools, PrintStream out,
            PrintStream err) {
        this.env = Map.copyOf(env);
        this.workingDirectory = workingDirectory;
        this.defaultJavaHome = defaultJavaHome;
        this.tools = tools;
        this.out = out;
        this.err = err;
    }

    // ============================================================== start

    /** Analyzes, prepares and submits; with {@code resume}, continues an existing pipeline. */
    @SuppressWarnings("try")
    public ExitCode start(Optional<String> resume) {
        List<String> problems = validate();
        if (!problems.isEmpty()) {
            err.println("GeneralAnnotator: invalid pipeline configuration (nothing was started):");
            problems.forEach(p -> err.println("  - " + p));
            err.println("See README.md (\"Running the whole pipeline\") for a complete setup example.");
            return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
        }
        if (resume.isPresent()) {
            Optional<Path> dir = pipelineDirectory(resume.get());
            if (dir.isEmpty()) {
                return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
            }
            return locked(dir.get(), false, state -> {
                out.println("GeneralAnnotator pipeline start --resume " + dir.get());
                Optional<ExitCode> refused = verifyInputs(dir.get(), state);
                return refused.isPresent() ? refused.get() : prepareAndSubmit(dir.get(), state);
            });
        }
        out.println("GeneralAnnotator pipeline start (round " + ROUND + ")");
        stageHeader(1, "analysis: snapshot, NullAway, warning graph and targets (check)");
        CheckCommand check = new CheckCommand(env, workingDirectory, defaultJavaHome, tools.runner(), tools.checkers(),
                tools.clock(), out, err);
        ExitCode checked = check.run();
        if (check.runDirectory().isEmpty()) {
            return checked;
        }
        Path dir = check.runDirectory().get();
        return locked(dir, true, state -> {
            Map<String, Object> analysis = stage(state, "analysis");
            Path graph = dir.resolve(WarningGraphJson.FILE_NAME);
            artifacts(analysis, Map.of("report", "report.json", "warnings", "warnings.txt", "snapshot",
                    "workspace/target", "graph", WarningGraphJson.FILE_NAME, "targets", WarningTargetsJson.FILE_NAME));
            Map<?, ?> report = Files.isRegularFile(dir.resolve("report.json")) ? parse(dir.resolve("report.json"))
                    : Map.of();
            state.put("checker", report.get("checker"));
            state.put("coverage", report.get("coverage"));
            if (checked != ExitCode.COMPLETED || !Files.isRegularFile(graph)) {
                finishStage(analysis, "FAILED", "the baseline analysis did not complete (" + checked + "); see "
                        + dir.resolve("report.json") + ". Nothing is submitted.");
                return conclude(dir, state, Status.FAILED, ExitCode.EXECUTION_FAILURE);
            }
            int warnings = ((List<?>) parse(graph).get("nodes")).size();
            analysis.put("snapshotTreeSha256", EvaluationFiles.treeHash(dir.resolve("workspace").resolve("target")));
            analysis.put("warnings", warnings);
            finishStage(analysis, "COMPLETED", warnings + " warning(s) in the baseline");
            save(dir, state);
            return prepareAndSubmit(dir, state);
        });
    }

    private ExitCode prepareAndSubmit(Path dir, Map<String, Object> state) throws IOException {
        Map<String, Object> analysis = stage(state, "analysis");
        if (!"COMPLETED".equals(analysis.get("status"))) {
            out.println("The analysis of this pipeline did not complete; start a new pipeline.");
            return conclude(dir, state, Status.FAILED, ExitCode.EXECUTION_FAILURE);
        }
        if (((Number) analysis.get("warnings")).intValue() == 0) {
            out.println("The baseline has no warnings: there is nothing to slice, prompt or submit.");
            for (String s : List.of("slices", "context", "prompts", "preparation", "submission")) {
                finishStage(stage(state, s), "SKIPPED", "no warnings");
            }
            return conclude(dir, state, Status.NO_WARNINGS, ExitCode.COMPLETED);
        }

        // 2. Slices: per warning, never overwriting existing results.
        Map<String, Object> slices = stage(state, "slices");
        if (!"COMPLETED".equals(slices.get("status"))) {
            stageHeader(2, "Specimin slices (slice)");
            beginStage(dir, state, slices);
            List<String> targets = targetIds(dir);
            Set<String> done = sliceResults(dir).keySet();
            if (done.isEmpty()) {
                slice(dir, Optional.empty());
            } else {
                for (String id : targets) {
                    if (!done.contains(id)) {
                        slice(dir, Optional.of(id));
                    }
                }
            }
            Map<String, Map<?, ?>> results = sliceResults(dir);
            if (!results.keySet().containsAll(targets)) {
                finishStage(slices, "FAILED", "slicing did not produce a result for every warning (for example "
                        + "Specimin setup failed); see the output above and " + dir.resolve("slices"));
                return conclude(dir, state, Status.FAILED, ExitCode.EXECUTION_FAILURE);
            }
            clearExclusions(state, "slices");
            Map<String, Integer> counts = new TreeMap<>();
            for (Map<?, ?> r : results.values()) {
                String status = String.valueOf(r.get("status"));
                counts.merge(status, 1, Integer::sum);
                if (!status.equals("GENERATED")) {
                    exclude(state, "slices", String.valueOf(r.get("warningId")), status,
                            String.valueOf(r.get("failureReason")));
                }
            }
            slices.put("counts", counts);
            artifacts(slices, Map.of("results", "slices/" + SliceResultsJson.FILE_NAME));
            finishStage(slices, "COMPLETED", counts + "; slices are NOT_VALIDATED (reproduction is not checked)");
            save(dir, state);
        }

        // 3. Usage context.
        Map<String, Object> context = stage(state, "context");
        if (!"COMPLETED".equals(context.get("status"))) {
            stageHeader(3, "usage context (context)");
            beginStage(dir, state, context);
            ExitCode e = new ContextCommand(dir.toString(), Optional.empty(), env, workingDirectory, out, err).run();
            if (e == ExitCode.USAGE_OR_CONFIGURATION_ERROR || e == ExitCode.INTERNAL_ERROR) {
                finishStage(context, "FAILED", "context extraction could not run (" + e + ")");
                return conclude(dir, state, Status.FAILED, ExitCode.EXECUTION_FAILURE);
            }
            artifacts(context, Map.of("directory", "context"));
            finishStage(context, "COMPLETED", e == ExitCode.COMPLETED ? "context for every resolved target"
                    : "some targets have no context (" + e + "); their prompts are not generated");
            save(dir, state);
        }

        // 4. Prompts.
        Map<String, Object> prompts = stage(state, "prompts");
        if (!"COMPLETED".equals(prompts.get("status"))) {
            stageHeader(4, "prompt units (prompts)");
            beginStage(dir, state, prompts);
            ExitCode e = new PromptsCommand(dir.toString(), Optional.empty(), env, workingDirectory, out, err).run();
            Path manifest = dir.resolve(PromptsCommand.DIRECTORY).resolve(PromptManifestJson.FILE_NAME);
            if (!Files.isRegularFile(manifest)) {
                finishStage(prompts, "FAILED", "no prompt manifest was written (" + e + ")");
                return conclude(dir, state, Status.FAILED, ExitCode.EXECUTION_FAILURE);
            }
            clearExclusions(state, "prompts");
            List<String> generated = new ArrayList<>();
            Map<String, Integer> counts = new TreeMap<>();
            for (Object o : (List<?>) parse(manifest).get("prompts")) {
                Map<?, ?> p = (Map<?, ?>) o;
                String status = String.valueOf(p.get("status"));
                counts.merge(status, 1, Integer::sum);
                if (status.equals("GENERATED")) {
                    generated.add(String.valueOf(p.get("warningId")));
                } else {
                    exclude(state, "prompts", String.valueOf(p.get("warningId")), status, String.valueOf(p.get(
                            "reasons")));
                }
            }
            prompts.put("counts", counts);
            prompts.put("generated", generated);
            artifacts(prompts, Map.of("manifest", "prompts/" + PromptManifestJson.FILE_NAME));
            finishStage(prompts, "COMPLETED", counts.toString());
            save(dir, state);
        }
        if (((List<?>) prompts.get("generated")).isEmpty()) {
            out.println("No eligible prompt (none GENERATED): nothing is submitted. Exclusions are listed in "
                    + dir.resolve(STATE) + ".");
            for (String s : List.of("preparation", "submission")) {
                finishStage(stage(state, s), "SKIPPED", "no eligible prompt");
            }
            return conclude(dir, state, Status.NO_ELIGIBLE_PROMPTS, ExitCode.EXECUTION_FAILURE);
        }

        // 5. Submission preparation (one immutable submission per pipeline).
        Map<String, Object> preparation = stage(state, "preparation");
        if (!"COMPLETED".equals(preparation.get("status"))) {
            InferenceSettings settings = InferenceSettings.fromEnvironment(env, new ArrayList<>());
            stageHeader(5, "inference submission (prepare-inference): " + effective(settings.promptsPerTask(),
                    settings.startupTimeoutSeconds(), settings.arrayConcurrency()));
            beginStage(dir, state, preparation);
            Optional<Path> existing = submission(dir);
            if (existing.isEmpty()) {
                ExitCode e = new PrepareInferenceCommand(dir.toString(), Optional.empty(), env, workingDirectory,
                        tools.clock(), out, err).run();
                if (e != ExitCode.COMPLETED) {
                    finishStage(preparation, "FAILED", "prepare-inference failed (" + e + ")");
                    return conclude(dir, state, Status.FAILED, ExitCode.EXECUTION_FAILURE);
                }
                existing = submission(dir);
            } else {
                out.println("  reusing the prepared submission " + existing.get() + " (not prepared again)");
            }
            Path sub = existing.orElseThrow(() -> new IOException("prepare-inference left no submission in " + dir));
            Map<?, ?> json = parse(sub.resolve("submission.json"));
            Map<Integer, Integer> tasks = new TreeMap<>();
            for (Object o : (List<?>) json.get("prompts")) {
                tasks.merge(((Number) ((Map<?, ?>) o).get("taskIndex")).intValue(), 1, Integer::sum);
            }
            state.put("submission", new LinkedHashMap<>(Map.of("id", sub.getFileName().toString(), "directory",
                    dir.relativize(sub).toString())));
            Map<?, ?> prepared = parse(sub.resolve("settings.json"));
            int perTask = ((Number) map(prepared.get("slurm")).get("promptsPerTask")).intValue();
            int startup = ((Number) map(prepared.get("server")).get("startupTimeoutSeconds")).intValue();
            int concurrency = ((Number) map(prepared.get("slurm")).get("arrayConcurrency")).intValue();
            preparation.put("prompts", ((List<?>) json.get("prompts")).size());
            preparation.put("promptsPerTask", perTask);
            preparation.put("startupTimeoutSeconds", startup);
            preparation.put("arrayConcurrency", concurrency);
            preparation.put("taskSizes", List.copyOf(tasks.values()));
            out.println("  effective:  " + effective(perTask, startup, concurrency) + " (from the submission's "
                    + "settings.json)");
            artifacts(preparation, Map.of("submission", dir.relativize(sub).toString()));
            finishStage(preparation, "COMPLETED", ((List<?>) json.get("prompts")).size() + " prompt(s) in "
                    + tasks.size() + " array task(s); " + effective(perTask, startup, concurrency));
            save(dir, state);
        }
        Path sub = dir.resolve(String.valueOf(map(state.get("submission")).get("directory")));

        // 6. Connect, upload, submit once.
        Map<String, Object> submission = stage(state, "submission");
        if ("COMPLETED".equals(submission.get("status"))) {
            out.println("Already submitted (job " + submission.get("jobIds") + "); nothing is submitted again.");
            return conclude(dir, state, Status.SUBMITTED, ExitCode.COMPLETED);
        }
        stageHeader(6, "connect, upload and submit (inference connect, upload, submit)");
        beginStage(dir, state, submission);
        Jobs before = jobs(sub);
        if (!before.submitted().isEmpty()) {
            return submitted(dir, state, submission, before);
        }
        if (before.unknown()) {
            return unknownSubmission(dir, state, submission, sub);
        }
        ExitCode connected = inference("connect");
        if (connected != ExitCode.COMPLETED) {
            finishStage(submission, "PENDING", "no usable SSH connection (" + connected + "); prepared work is kept");
            out.println("Reconnect (VPN), then resume with: pipeline start --resume " + dir);
            return conclude(dir, state, Status.RECONNECT_REQUIRED, connected);
        }
        ExitCode uploaded = inference("upload", sub.toString());
        if (uploaded != ExitCode.COMPLETED) {
            finishStage(submission, uploaded == ExitCode.RECONNECT_REQUIRED ? "PENDING" : "FAILED",
                    "upload did not complete and verify (" + uploaded + "); nothing was submitted");
            return conclude(dir, state, uploaded == ExitCode.RECONNECT_REQUIRED ? Status.RECONNECT_REQUIRED
                    : Status.FAILED, uploaded == ExitCode.RECONNECT_REQUIRED ? uploaded : ExitCode.EXECUTION_FAILURE);
        }
        ExitCode submitted = inference("submit", sub.toString());
        Jobs after = jobs(sub);
        if (!after.submitted().isEmpty()) {
            return submitted(dir, state, submission, after);
        }
        if (after.unknown()) {
            return unknownSubmission(dir, state, submission, sub);
        }
        finishStage(submission, submitted == ExitCode.RECONNECT_REQUIRED ? "PENDING" : "FAILED",
                "sbatch did not submit a job (" + submitted + "); see " + sub.resolve("remote/state.json"));
        return conclude(dir, state, submitted == ExitCode.RECONNECT_REQUIRED ? Status.RECONNECT_REQUIRED
                : Status.FAILED, submitted == ExitCode.RECONNECT_REQUIRED ? submitted : ExitCode.EXECUTION_FAILURE);
    }

    private ExitCode submitted(Path dir, Map<String, Object> state, Map<String, Object> submission, Jobs jobs)
            throws IOException {
        submission.put("jobIds", jobs.submitted());
        map(state.get("submission")).put("jobIds", jobs.submitted());
        finishStage(submission, "COMPLETED", "submitted as Slurm job " + String.join(", ", jobs.submitted()));
        out.println();
        out.println("Submitted. Remote inference no longer needs this terminal, the SSH master, the VPN or this "
                + "computer. Nothing is monitored. Later:");
        out.println("  pipeline status " + dir + " [--connect]");
        out.println("  pipeline finish " + dir);
        return conclude(dir, state, Status.SUBMITTED, ExitCode.COMPLETED);
    }

    private ExitCode unknownSubmission(Path dir, Map<String, Object> state, Map<String, Object> submission, Path sub)
            throws IOException {
        finishStage(submission, "UNKNOWN", "it is unknown whether sbatch created a job; it is never resubmitted "
                + "automatically");
        out.println("The sbatch outcome is UNKNOWN. Verify it with the scheduler (nothing is resubmitted):");
        out.println("  inference attach " + sub + " <job id>      # a job exists");
        out.println("  inference attach " + sub + " --not-submitted   # verified that none exists");
        out.println("then: pipeline start --resume " + dir);
        return conclude(dir, state, Status.SUBMISSION_UNKNOWN, ExitCode.EXECUTION_FAILURE);
    }

    /** The effective batch size, startup deadline and concurrency, as printed and recorded. */
    static String effective(int promptsPerTask, int startupTimeoutSeconds, int arrayConcurrency) {
        return promptsPerTask + " prompt(s) per array task (each a separate LLM request), vLLM startup timeout "
                + startupTimeoutSeconds + " s, at most " + arrayConcurrency + " task(s) at once";
    }

    private void slice(Path dir, Optional<String> warning) {
        new SliceCommand(dir.toString(), warning, env, workingDirectory, defaultJavaHome, tools.runner(), out, err)
                .run();
    }

    // ============================================================== status

    /** Recorded stages, then the job's scheduler states (fresh if possible, otherwise cached and labeled). */
    public ExitCode status(String argument, boolean connect) {
        Optional<Path> found = pipelineDirectory(argument);
        if (found.isEmpty()) {
            return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
        }
        Path dir = found.get();
        Map<String, Object> state;
        try {
            state = load(dir);
        } catch (IOException e) {
            err.println("GeneralAnnotator: " + e.getMessage());
            return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
        }
        out.println("GeneralAnnotator pipeline status " + dir);
        out.println("Recorded in " + STATE + " (as of " + state.get("updatedAt") + "):");
        printStages(state);
        Map<String, Object> submission = stage(state, "submission");
        if (!"COMPLETED".equals(submission.get("status"))) {
            out.println("No confirmed Slurm submission; nothing to query.");
            return ExitCode.COMPLETED;
        }
        Path sub = dir.resolve(String.valueOf(map(state.get("submission")).get("directory")));
        if (connect) {
            ExitCode c = inference("connect");
            if (c != ExitCode.COMPLETED) {
                out.println("Could not connect (" + c + "); showing cached information only.");
            }
        }
        out.println();
        ExitCode code = inference("status", sub.toString());
        out.println();
        printRemoteSummary(sub);
        return code;
    }

    private void printRemoteSummary(Path sub) {
        Path status = sub.resolve("remote/status.json");
        try {
            if (Files.isRegularFile(status)) {
                Map<?, ?> s = parse(status);
                Map<String, Integer> categories = new TreeMap<>();
                for (Object o : (List<?>) s.get("tasks")) {
                    categories.merge(String.valueOf(((Map<?, ?>) o).get("category")), 1, Integer::sum);
                }
                boolean cached = s.get("refreshFailure") != null;
                out.println("Scheduler (array tasks): " + categories + (cached ? " — CACHED from " + s.get("queriedAt")
                        + "; the latest query failed (transport): " + map(s.get("refreshFailure")).get("reason")
                        : " — queried at " + s.get("queriedAt")));
            } else {
                out.println("Scheduler (array tasks): never queried successfully.");
            }
            Path collection = sub.resolve("remote/collection.json");
            if (Files.isRegularFile(collection)) {
                Map<?, ?> c = parse(collection);
                out.println("Prompt results (CACHED from the last collection at " + c.get("collectedAt") + "): "
                        + c.get("summary"));
            } else {
                out.println("Prompt results: not collected yet (pipeline finish collects them once all tasks ended).");
            }
        } catch (IOException e) {
            out.println("Cannot read the remote bookkeeping: " + e.getMessage());
        }
    }

    // ============================================================== finish

    /** Collects once every array task has ended, then evaluates packages and applies the whole round. */
    public ExitCode finish(String argument) {
        List<String> problems = validate();
        if (!problems.isEmpty()) {
            err.println("GeneralAnnotator: invalid pipeline configuration:");
            problems.forEach(p -> err.println("  - " + p));
            return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
        }
        Optional<Path> found = pipelineDirectory(argument);
        if (found.isEmpty()) {
            return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
        }
        Path dir = found.get();
        return locked(dir, false, state -> {
            out.println("GeneralAnnotator pipeline finish " + dir);
            Optional<ExitCode> refused = verifyInputs(dir, state);
            if (refused.isPresent()) {
                return refused.get();
            }
            Map<String, Object> submission = stage(state, "submission");
            if (!"COMPLETED".equals(submission.get("status"))) {
                out.println("This pipeline has no confirmed Slurm submission (status " + state.get("status")
                        + "); there is nothing to collect. See: pipeline status " + dir);
                return ExitCode.EXECUTION_FAILURE;
            }
            Path sub = dir.resolve(String.valueOf(map(state.get("submission")).get("directory")));

            Map<String, Object> collection = stage(state, "collection");
            if (!"COMPLETED".equals(collection.get("status"))) {
                stageHeader(7, "job state and collection (inference connect, status, collect)");
                beginStage(dir, state, collection);
                ExitCode connected = inference("connect");
                if (connected != ExitCode.COMPLETED) {
                    finishStage(collection, "PENDING", "no usable SSH connection (" + connected + ")");
                    return conclude(dir, state, Status.RECONNECT_REQUIRED, connected);
                }
                ExitCode queried = inference("status", sub.toString());
                if (queried == ExitCode.RECONNECT_REQUIRED) {
                    finishStage(collection, "PENDING", "the connection was lost during the scheduler query");
                    return conclude(dir, state, Status.RECONNECT_REQUIRED, queried);
                }
                if (jobs(sub).unknown()) {
                    finishStage(collection, "PENDING", "an sbatch outcome is UNKNOWN; resolve it with inference attach");
                    return conclude(dir, state, Status.SUBMISSION_UNKNOWN, ExitCode.EXECUTION_FAILURE);
                }
                Map<?, ?> status = parse(sub.resolve("remote/status.json"));
                Map<String, Integer> categories = new TreeMap<>();
                for (Object o : (List<?>) status.get("tasks")) {
                    categories.merge(String.valueOf(((Map<?, ?>) o).get("category")), 1, Integer::sum);
                }
                collection.put("scheduler", categories);
                int terminal = categories.getOrDefault("TERMINAL", 0);
                if (status.get("refreshFailure") != null || categories.isEmpty()
                        || terminal != categories.values().stream().mapToInt(Integer::intValue).sum()) {
                    finishStage(collection, "WAITING", "array tasks " + categories + "; not every task has ended (or "
                            + "the scheduler gave no information), so nothing was collected or evaluated");
                    out.println("Not finished yet: " + categories + ". Nothing is resubmitted; run pipeline finish "
                            + dir + " again later.");
                    return conclude(dir, state, Status.WAITING_FOR_JOBS, ExitCode.EXECUTION_FAILURE);
                }
                ExitCode collected = inference("collect", sub.toString());
                if (collected == ExitCode.RECONNECT_REQUIRED) {
                    finishStage(collection, "PENDING", "the connection was lost during collection; collected results "
                            + "are kept");
                    return conclude(dir, state, Status.RECONNECT_REQUIRED, collected);
                }
                Path file = sub.resolve("remote/collection.json");
                if (!Files.isRegularFile(file)) {
                    finishStage(collection, "FAILED", "collection wrote no collection.json (" + collected + ")");
                    return conclude(dir, state, Status.FAILED, ExitCode.EXECUTION_FAILURE);
                }
                Map<?, ?> c = parse(file);
                clearExclusions(state, "collection");
                for (Object o : (List<?>) c.get("prompts")) {
                    Map<?, ?> p = (Map<?, ?>) o;
                    String s = String.valueOf(p.get("status"));
                    if (!s.equals("COMPLETED_PROPOSED")) {
                        exclude(state, "collection", String.valueOf(p.get("warningId")), s, p.get("error") == null
                                ? String.valueOf(p.get("problems")) : String.valueOf(p.get("error")));
                    }
                }
                collection.put("summary", c.get("summary"));
                collection.put("conflicts", c.get("conflicts"));
                artifacts(collection, Map.of("collection", dir.relativize(file).toString(), "results",
                        dir.relativize(sub.resolve("remote/collected")).toString()));
                finishStage(collection, "COMPLETED", "every array task ended; " + c.get("summary")
                        + (((List<?>) c.get("conflicts")).isEmpty() ? "" : "; conflicting results were kept out"));
                save(dir, state);
            } else {
                out.println("Results were already collected after every task ended; the cluster is not contacted.");
            }

            // 8. Independent package evaluation and the expanded graph.
            stageHeader(8, "independent package evaluation and expanded graph (evaluate-annotations)");
            Map<String, Object> evaluation = stage(state, "evaluation");
            beginStage(dir, state, evaluation);
            EvaluateAnnotationsCommand evaluate = new EvaluateAnnotationsCommand(sub.toString(), Optional.empty(),
                    Optional.empty(), env, workingDirectory, defaultJavaHome, tools.checkers(), tools.applier(),
                    tools.matcher(), ROUND, tools.clock(), out, err);
            ExitCode evaluated = evaluate.run();
            Optional<Path> evaluationDir = evaluate.resultDirectory();
            if (evaluated == ExitCode.EXECUTION_FAILURE && evaluationDir.isEmpty()) {
                finishStage(evaluation, "SKIPPED", "no answer yielded an applicable package");
                finishStage(stage(state, "combined"), "SKIPPED", "no package to apply");
                return conclude(dir, state, Status.NO_PACKAGES, ExitCode.EXECUTION_FAILURE);
            }
            if (evaluated != ExitCode.COMPLETED || evaluationDir.isEmpty()
                    || !Files.isRegularFile(evaluationDir.get().resolve(Evaluator.SUMMARY))) {
                finishStage(evaluation, "FAILED", "evaluate-annotations did not complete (" + evaluated + ")");
                return conclude(dir, state, Status.FAILED, ExitCode.EXECUTION_FAILURE);
            }
            Map<?, ?> summary = parse(evaluationDir.get().resolve(Evaluator.SUMMARY));
            clearExclusions(state, "answers");
            Set<String> collectionExcluded = new LinkedHashSet<>();
            for (Object o : list(state.get("exclusions"))) {
                collectionExcluded.add(String.valueOf(map(o).get("warningId")));
            }
            for (Object o : (List<?>) summary.get("excluded")) {
                Map<?, ?> x = (Map<?, ?>) o;
                if (!collectionExcluded.contains(String.valueOf(x.get("warningId")))) {
                    exclude(state, "answers", String.valueOf(x.get("warningId")), String.valueOf(x.get("status")),
                            String.valueOf(x.get("reason")));
                }
            }
            evaluation.put("importedProposals", summary.get("importedProposals"));
            evaluation.put("uniquePackages", summary.get("uniquePackages"));
            evaluation.put("outcomes", summary.get("outcomes"));
            evaluation.put("graph", summary.get("graph"));
            artifacts(evaluation, Map.of("directory", dir.relativize(evaluationDir.get()).toString(), "graph",
                    dir.relativize(evaluationDir.get().resolve(Evaluator.GRAPH)).toString(), "summary",
                    dir.relativize(evaluationDir.get().resolve(Evaluator.SUMMARY)).toString()));
            finishStage(evaluation, "COMPLETED", summary.get("importedProposals") + " proposal(s) -> "
                    + summary.get("uniquePackages") + " unique package(s), each evaluated alone: " + summary.get(
                            "outcomes"));
            save(dir, state);

            // 9. All packages together, one check of the combined state.
            stageHeader(9, "combined application of every package and one NullAway check (apply-round)");
            Map<String, Object> combined = stage(state, "combined");
            beginStage(dir, state, combined);
            ApplyRoundCommand round = new ApplyRoundCommand(sub.toString(), String.valueOf(ROUND), env,
                    workingDirectory, defaultJavaHome, tools.checkers(), tools.applier(), tools.matcher(),
                    tools.clock(), out, err);
            ExitCode applied = round.run();
            Optional<RoundApplication.Result> result = round.result();
            if (result.isEmpty()) {
                finishStage(combined, "FAILED", "apply-round did not run (" + applied + ")");
                return conclude(dir, state, Status.FAILED, ExitCode.EXECUTION_FAILURE);
            }
            Map<?, ?> record = result.get().record();
            combined.put("roundStatus", result.get().status().name());
            combined.put("detail", record.get("detail"));
            combined.put("comparison", record.get("comparison"));
            combined.put("findingCount", record.get("findingCount"));
            artifacts(combined, Map.of("directory", dir.relativize(result.get().directory()).toString()));
            switch (result.get().status()) {
                case COMPLETED -> {
                    artifacts(combined, Map.of("directory", dir.relativize(result.get().directory()).toString(),
                            "workspace", dir.relativize(result.get().directory().resolve("workspace/target")).toString(),
                            "warnings", dir.relativize(result.get().directory().resolve(RoundApplication.WARNINGS))
                                    .toString(),
                            "findings", dir.relativize(result.get().directory().resolve(RoundApplication.FINDINGS))
                                    .toString(),
                            "comparison", dir.relativize(result.get().directory().resolve(
                                    RoundApplication.COMPARISON)).toString(),
                            "diff", dir.relativize(result.get().directory().resolve(RoundApplication.DIFF)).toString()));
                    finishStage(combined, "COMPLETED", String.valueOf(record.get("detail")));
                }
                case REJECTED -> {
                    finishStage(combined, "BLOCKED", "the combined edit set was rejected (" + list(record.get(
                            "conflicts")).size() + " conflict(s)); nothing was applied. The independent evaluations "
                            + "are kept.");
                    return conclude(dir, state, Status.BLOCKED, ExitCode.EXECUTION_FAILURE);
                }
                default -> {
                    finishStage(combined, "FAILED", result.get().status() + ": " + record.get("detail"));
                    return conclude(dir, state, Status.FAILED, ExitCode.EXECUTION_FAILURE);
                }
            }
            boolean partial = list(state.get("exclusions")).stream()
                    .anyMatch(x -> !String.valueOf(map(x).get("status")).contains("INSUFFICIENT"));
            return conclude(dir, state, partial ? Status.PARTIAL : Status.COMPLETED, ExitCode.COMPLETED);
        });
    }

    // ============================================================== propagation

    /**
     * The explicitly selected, deterministic nullability-propagation stage on the pipeline's baseline (see
     * {@link PropagationStage}). It needs only a completed analysis, calls no LLM, and leaves the LLM stages
     * and the pipeline status unchanged.
     */
    public ExitCode propagate(String argument) {
        Optional<Path> found = pipelineDirectory(argument);
        if (found.isEmpty()) {
            return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
        }
        Path dir = found.get();
        return locked(dir, false, state -> {
            out.println("GeneralAnnotator pipeline propagate " + dir);
            Optional<ExitCode> refused = verifyInputs(dir, state);
            if (refused.isPresent()) {
                return refused.get();
            }
            if (!"COMPLETED".equals(stage(state, "combined").get("status"))) {
                out.println("Propagation reads the combined round-1 result (its annotated code and warnings), but this "
                        + "pipeline's combined stage is " + stage(state, "combined").get("status") + ". Run `pipeline "
                        + "finish " + dir + "` until it completes, then rerun `pipeline propagate " + dir + "`. Nothing "
                        + "falls back to the original baseline.");
                return ExitCode.EXECUTION_FAILURE;
            }
            out.println();
            out.println("== [propagation] single-pass nullability propagation (propagate; deterministic, no LLM)");
            Map<String, Object> propagation = stage(state, "propagation");
            if (propagation.get("outcome") != null) {
                // an earlier result (possibly for another input, for example the original baseline) is kept as history
                List<Object> previous = list(propagation.get("previous"));
                Map<String, Object> copy = new LinkedHashMap<>(propagation);
                copy.remove("previous");
                previous.add(copy);
                propagation.put("previous", previous);
                propagation.remove("input");
            }
            beginStage(dir, state, propagation);
            PropagateCommand command = new PropagateCommand(dir.toString(), env, workingDirectory, defaultJavaHome,
                    tools.checkers(), tools.applier(), tools.matcher(), tools.clock(), out, err);
            ExitCode code = command.run();
            Optional<PropagationStage.Result> result = command.result();
            if (result.isEmpty()) {
                finishStage(propagation, "FAILED", "propagate did not run (" + code + ")");
            } else {
                Map<String, Object> record = result.get().record();
                propagation.put("outcome", result.get().outcome().name());
                propagation.put("input", record.get("input"));
                propagation.put("counts", record.get("counts"));
                propagation.put("graphModel", record.get("graphModel"));
                Map<String, String> files = new LinkedHashMap<>();
                files.put("directory", dir.relativize(result.get().directory()).toString());
                files.put("summary", dir.relativize(result.get().directory().resolve(PropagationStage.SUMMARY))
                        .toString());
                if (record.get("evaluation") instanceof Map<?, ?> e) {
                    files.put("graph", String.valueOf(e.get("extendedGraph") != null ? e.get("extendedGraph")
                            : e.get("graph")));
                    files.put("evaluations", String.valueOf(e.get("directory")));
                }
                if (record.get("combined") instanceof Map<?, ?> c) {
                    files.put("combined", String.valueOf(c.get("directory")));
                }
                artifacts(propagation, files);
                finishStage(propagation, code == ExitCode.COMPLETED ? "COMPLETED" : "FAILED",
                        result.get().outcome() + ": " + record.get("detail"));
            }
            return conclude(dir, state, Status.valueOf(String.valueOf(state.get("status"))), code);
        });
    }

    // ============================================================== configuration

    /** Every setting the stages need, validated before anything starts. */
    List<String> validate() {
        List<String> problems = new ArrayList<>();
        try {
            var config = new ConfigLoader(env, workingDirectory, defaultJavaHome).load();
            try {
                EvaluationConfig.load(env, workingDirectory, defaultJavaHome, config.targetProject(),
                        config.sourceRoots().stream().map(Path::toString).toList(), config.outputDirectory());
            } catch (ConfigurationException e) {
                problems.addAll(e.problems());
            } catch (IOException e) {
                problems.add("cannot fingerprint the checker configuration: " + e.getMessage());
            }
        } catch (ConfigurationException e) {
            problems.addAll(e.problems());
        }
        SliceCommand.configurationProblems(env, defaultJavaHome).forEach(problems::add);
        InferenceSettings.fromEnvironment(env, problems);
        RemoteSettings.fromEnvironment(env, problems);
        return problems;
    }

    /** The fingerprinted settings: every {@code GA_*} variable except {@link #NOT_FINGERPRINTED} ones. */
    Map<String, String> fingerprintedSettings() {
        Map<String, String> m = new TreeMap<>();
        env.forEach((k, v) -> {
            if (fingerprinted(k)) {
                m.put(k, v);
            }
        });
        return m;
    }

    static boolean fingerprinted(String key) {
        return key.startsWith("GA_") && NOT_FINGERPRINTED.stream().noneMatch(key::startsWith);
    }

    private Optional<ExitCode> verifyInputs(Path dir, Map<String, Object> state) throws IOException {
        Map<?, ?> recorded = map(map(state.get("configuration")).get("settings"));
        Map<String, String> now = fingerprintedSettings();
        Set<String> changed = new LinkedHashSet<>();
        Set<String> keys = new java.util.TreeSet<>();
        // pipelines recorded before inference settings stopped being fingerprinted may list them; they are ignored
        recorded.keySet().stream().map(String::valueOf).filter(Pipeline::fingerprinted).forEach(keys::add);
        keys.addAll(now.keySet());
        for (String k : keys) {
            if (!String.valueOf(recorded.get(k)).equals(String.valueOf(now.get(k)))) {
                changed.add(k);
            }
        }
        if (!changed.isEmpty()) {
            err.println("GeneralAnnotator: the configuration differs from the one this pipeline was started with ("
                    + String.join(", ", changed) + "); completed stages would not match. Restore it, or start a new "
                    + "pipeline.");
            return Optional.of(ExitCode.USAGE_OR_CONFIGURATION_ERROR);
        }
        Object hash = stage(state, "analysis").get("snapshotTreeSha256");
        if (state.get("submission") instanceof Map<?, ?> s && s.get("directory") != null
                && Files.isRegularFile(dir.resolve(String.valueOf(s.get("directory"))).resolve("settings.json"))) {
            Map<?, ?> prepared = parse(dir.resolve(String.valueOf(s.get("directory"))).resolve("settings.json"));
            InferenceSettings current = InferenceSettings.fromEnvironment(env, new ArrayList<>());
            Object perTask = map(prepared.get("slurm")).get("promptsPerTask");
            Object startup = map(prepared.get("server")).get("startupTimeoutSeconds");
            if (perTask instanceof Number p && startup instanceof Number t
                    && (p.intValue() != current.promptsPerTask() || t.intValue() != current.startupTimeoutSeconds())) {
                out.println("Note: this pipeline's submission was prepared with " + p + " prompt(s) per task and a "
                        + t + " s startup timeout; it is immutable and keeps them (the current settings would give "
                        + current.promptsPerTask() + " and " + current.startupTimeoutSeconds() + " s for a new "
                        + "pipeline).");
            }
        }
        if (hash != null && !hash.equals(EvaluationFiles.treeHash(dir.resolve("workspace").resolve("target")))) {
            err.println("GeneralAnnotator: the pipeline snapshot " + dir.resolve("workspace/target") + " changed after "
                    + "the analysis; its results no longer describe it. Start a new pipeline.");
            return Optional.of(ExitCode.EXECUTION_FAILURE);
        }
        return Optional.empty();
    }

    // ============================================================== state

    @FunctionalInterface
    private interface Body {
        ExitCode run(Map<String, Object> state) throws IOException;
    }

    /** Runs {@code body} with the writer lock held and the state loaded (or created, for a new pipeline). */
    @SuppressWarnings("try")
    private ExitCode locked(Path dir, boolean create, Body body) {
        try (EvaluationFiles.Lock lock = EvaluationFiles.lock(dir.resolve(".pipeline"), "pid "
                + ProcessHandle.current().pid() + " since " + tools.clock().instant())) {
            Map<String, Object> state = create && !Files.exists(dir.resolve(STATE)) ? create(dir) : load(dir);
            return body.run(state);
        } catch (EvaluationFiles.BusyException e) {
            err.println("GeneralAnnotator: " + e.getMessage());
            return ExitCode.EXECUTION_FAILURE;
        } catch (IOException | RuntimeException e) {
            err.println("GeneralAnnotator: pipeline error: " + e.getMessage() + ". Completed stages are kept in "
                    + dir.resolve(STATE) + ".");
            return ExitCode.INTERNAL_ERROR;
        }
    }

    private Map<String, Object> create(Path dir) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("schemaVersion", SCHEMA_VERSION);
        state.put("pipelineId", dir.getFileName().toString());
        state.put("round", ROUND);
        state.put("directory", dir.toString());
        state.put("createdAt", tools.clock().instant().toString());
        state.put("updatedAt", tools.clock().instant().toString());
        state.put("status", Status.RUNNING.name());
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("settings", fingerprintedSettings());
        configuration.put("settingsSha256", EvaluationFiles.sha256(Json.write(fingerprintedSettings())));
        configuration.put("note", "Every GA_* setting except " + NOT_FINGERPRINTED + " (transport settings, and the "
                + "inference and Slurm settings, which only shape the immutable submission; see its settings.json).");
        state.put("configuration", configuration);
        Map<String, Object> stages = new LinkedHashMap<>();
        for (String s : STAGES) {
            stages.put(s, new LinkedHashMap<>(Map.of("status", "PENDING")));
        }
        state.put("stages", stages);
        state.put("exclusions", new ArrayList<>());
        state.put("errors", new ArrayList<>());
        save(dir, state);
        return state;
    }

    private Map<String, Object> load(Path dir) throws IOException {
        Path file = dir.resolve(STATE);
        if (!Files.isRegularFile(file)) {
            throw new IOException(dir + " is not a pipeline directory (no " + STATE + ")");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) mutable(parse(file));
        @SuppressWarnings("unchecked")
        Map<String, Object> stages = (Map<String, Object>) state.get("stages");
        for (String s : STAGES) {
            stages.putIfAbsent(s, new LinkedHashMap<>(Map.of("status", "PENDING"))); // stages added by later versions
        }
        return state;
    }

    private static Object mutable(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), mutable(v)));
            return copy;
        }
        if (value instanceof List<?> l) {
            List<Object> copy = new ArrayList<>();
            l.forEach(v -> copy.add(mutable(v)));
            return copy;
        }
        return value;
    }

    private void save(Path dir, Map<String, Object> state) throws IOException {
        state.put("updatedAt", tools.clock().instant().toString());
        EvaluationFiles.writeAtomically(dir.resolve(STATE), Json.write(state));
    }

    private ExitCode conclude(Path dir, Map<String, Object> state, Status status, ExitCode code) throws IOException {
        state.put("status", status.name());
        save(dir, state);
        String summary = summary(dir, state);
        EvaluationFiles.writeAtomically(dir.resolve(SUMMARY), summary);
        out.println();
        out.print(summary);
        return code;
    }

    private String summary(Path dir, Map<String, Object> state) {
        StringBuilder b = new StringBuilder();
        b.append("Pipeline ").append(state.get("pipelineId")).append(" (round ").append(state.get("round"))
                .append("): ").append(state.get("status")).append('\n');
        b.append("  directory: ").append(dir).append('\n');
        for (String s : STAGES) {
            Map<String, Object> st = stage(state, s);
            b.append(String.format("  %-12s %-10s %s%n", s, st.get("status"), st.get("detail") == null ? ""
                    : st.get("detail")));
        }
        List<Object> exclusions = list(state.get("exclusions"));
        if (!exclusions.isEmpty()) {
            b.append("  exclusions (").append(exclusions.size()).append("):\n");
            for (Object o : exclusions) {
                Map<?, ?> x = map(o);
                b.append("    ").append(x.get("warningId")).append("  ").append(x.get("stage")).append(' ')
                        .append(x.get("status")).append(": ").append(firstLine(String.valueOf(x.get("reason"))))
                        .append('\n');
            }
        }
        if (state.get("coverage") instanceof Map<?, ?> c) {
            b.append("  analyzed scope: ").append(c.get("analyzedSourceRoots")).append("; not analyzed (outside the "
                    + "source roots): ").append(c.get("javaFilesOutsideSourceRoots")).append('\n');
        }
        Map<String, Object> combined = stage(state, "combined");
        if (combined.get("artifacts") instanceof Map<?, ?> a && a.get("warnings") != null) {
            b.append("  updated warnings: ").append(dir.resolve(String.valueOf(a.get("warnings")))).append('\n');
        }
        Map<String, Object> evaluation = stage(state, "evaluation");
        if (evaluation.get("artifacts") instanceof Map<?, ?> a && a.get("graph") != null) {
            b.append("  expanded graph:   ").append(dir.resolve(String.valueOf(a.get("graph")))).append('\n');
        }
        b.append("  state: ").append(dir.resolve(STATE)).append('\n');
        return b.toString();
    }

    private void printStages(Map<String, Object> state) {
        out.println("  status: " + state.get("status"));
        for (String s : STAGES) {
            Map<String, Object> st = stage(state, s);
            out.println(String.format("  %-12s %-10s %s", s, st.get("status"), st.get("detail") == null ? ""
                    : st.get("detail")));
        }
        if (state.get("submission") instanceof Map<?, ?> sub) {
            out.println("  submission: " + sub.get("id") + (sub.get("jobIds") == null ? "" : ", Slurm job "
                    + sub.get("jobIds")));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stage(Map<String, Object> state, String name) {
        return (Map<String, Object>) ((Map<String, Object>) state.get("stages")).get(name);
    }

    private void beginStage(Path dir, Map<String, Object> state, Map<String, Object> stage) throws IOException {
        stage.put("status", "RUNNING");
        stage.put("startedAt", tools.clock().instant().toString());
        stage.remove("detail");
        save(dir, state);
    }

    private void finishStage(Map<String, Object> stage, String status, String detail) {
        stage.put("status", status);
        stage.put("detail", detail);
        stage.put("finishedAt", tools.clock().instant().toString());
    }

    @SuppressWarnings("unchecked")
    private static void artifacts(Map<String, Object> stage, Map<String, String> files) {
        Map<String, Object> a = stage.get("artifacts") instanceof Map<?, ?> m ? (Map<String, Object>) m
                : new LinkedHashMap<>();
        a.putAll(new TreeMap<>(files));
        stage.put("artifacts", a);
    }

    private static void exclude(Map<String, Object> state, String stage, String warningId, String status,
            String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", stage);
        m.put("warningId", warningId);
        m.put("status", status);
        m.put("reason", reason);
        list(state.get("exclusions")).add(m);
    }

    private static void clearExclusions(Map<String, Object> state, String stage) {
        list(state.get("exclusions")).removeIf(x -> stage.equals(map(x).get("stage")));
    }

    // ============================================================== helpers

    private ExitCode inference(String... args) {
        return new InferenceCommand(List.of(args), env, workingDirectory, tools.runner(), tools.terminalRunner(),
                tools.interactive(), tools.clock(), d -> { }, tools.clusters(), out, err).run();
    }

    /** The recorded sbatch calls of a submission. */
    private record Jobs(List<String> submitted, boolean unknown) {}

    private static Jobs jobs(Path sub) throws IOException {
        Path file = sub.resolve("remote/state.json");
        if (!Files.isRegularFile(file)) {
            return new Jobs(List.of(), false);
        }
        List<String> submitted = new ArrayList<>();
        boolean unknown = false;
        Object jobs = parse(file).get("jobs");
        for (Object o : jobs instanceof List<?> l ? l : List.of()) {
            Map<?, ?> j = (Map<?, ?>) o;
            String status = String.valueOf(j.get("status"));
            if (status.equals("SUBMITTED") && j.get("jobId") != null) {
                submitted.add(String.valueOf(j.get("jobId")));
            }
            unknown |= status.equals("UNKNOWN") || status.equals("SUBMITTING");
        }
        return new Jobs(submitted, unknown);
    }

    /** The one complete submission of the pipeline, if prepared. */
    private static Optional<Path> submission(Path dir) throws IOException {
        Path inference = dir.resolve("inference");
        if (!Files.isDirectory(inference)) {
            return Optional.empty();
        }
        try (var s = Files.list(inference)) {
            List<Path> subs = s.filter(p -> !p.getFileName().toString().startsWith(".")
                    && Files.isRegularFile(p.resolve("submission.json"))).sorted().toList();
            if (subs.size() > 1) {
                throw new IOException(inference + " holds " + subs.size() + " submissions; a pipeline uses one");
            }
            return subs.stream().findFirst();
        }
    }

    private static List<String> targetIds(Path dir) throws IOException {
        List<String> ids = new ArrayList<>();
        for (var t : WarningTargetsJson.load(dir.resolve(WarningTargetsJson.FILE_NAME)).targets()) {
            ids.add(t.warningId());
        }
        return ids;
    }

    private static Map<String, Map<?, ?>> sliceResults(Path dir) throws IOException {
        Path file = dir.resolve("slices").resolve(SliceResultsJson.FILE_NAME);
        Map<String, Map<?, ?>> results = new LinkedHashMap<>();
        if (Files.isRegularFile(file) && parse(file).get("results") instanceof List<?> l) {
            for (Object o : l) {
                results.put(String.valueOf(((Map<?, ?>) o).get("warningId")), (Map<?, ?>) o);
            }
        }
        return results;
    }

    private Optional<Path> pipelineDirectory(String argument) {
        Path dir;
        try {
            dir = workingDirectory.resolve(argument).normalize();
        } catch (InvalidPathException e) {
            err.println("GeneralAnnotator: '" + argument + "' is not a valid path");
            return Optional.empty();
        }
        if (!Files.isRegularFile(dir.resolve(STATE))) {
            err.println("GeneralAnnotator: " + dir + " is not a pipeline directory (no " + STATE + ")");
            return Optional.empty();
        }
        return Optional.of(dir);
    }

    private void stageHeader(int n, String text) {
        out.println();
        out.println("== [" + n + "/9] " + text);
    }

    private static Map<?, ?> parse(Path file) throws IOException {
        try {
            return (Map<?, ?>) JsonParser.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException(file + " is not valid JSON: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline) + " ...";
    }
}
