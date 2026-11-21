package generalannotator;

import generalannotator.cli.ApplyProposalCommand;
import generalannotator.cli.ApplyRoundCommand;
import generalannotator.cli.CheckCommand;
import generalannotator.checker.Checkers;
import generalannotator.cli.ContextCommand;
import generalannotator.cli.EvaluateAnnotationsCommand;
import generalannotator.cli.ExitCode;
import generalannotator.cli.InferenceCommand;
import generalannotator.cli.MigrateWarningIdsCommand;
import generalannotator.cli.PrepareInferenceCommand;
import generalannotator.cli.PromptsCommand;
import generalannotator.cli.SliceCommand;
import generalannotator.cli.TargetsCommand;
import generalannotator.cli.VisualizeCommand;
import generalannotator.edits.javaparser.JavaParserAnnotationApplier;
import generalannotator.evaluation.matching.AnchoredWarningMatcher;
import generalannotator.exec.ProcessCommandRunner;
import generalannotator.exec.TerminalCommandRunner;
import generalannotator.pipeline.Pipeline;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Command-line entry point. */
public final class Main {

    static final String USAGE = """
            Usage: GeneralAnnotator <command>

            Commands:
              check   Run the configured checker on an isolated copy of the target project
                      and write a report. Configuration is read from environment variables
                      (GA_CHECKER, GA_CHECKER_HOME, GA_TARGET_PROJECT, GA_TARGET_SOURCE_ROOTS, ...);
                      see README.md.
              visualize <warning-graph.json | run directory>
                      Render a saved warning graph with Graphviz (writes .dot and .svg
                      beside the JSON; the checker is not run). GA_GRAPHVIZ_DOT overrides
                      the dot executable (default: dot on PATH).
              targets <run directory>
                      Determine the Specimin target member (targetFile, targetMethod or
                      targetField) of every warning of a completed run and write
                      warning-targets.json. Neither the checker nor Specimin is run.
              slice <run directory> [--warning <ID>]
                      Generate one Specimin slice per warning (or only <ID>) from the run's
                      warning-targets.json into slices/<ID>/. Needs GA_SPECIMIN_HOME. Slices
                      are not validated: the checker is not rerun on them.
              context <run directory> [--warning <ID>]
                      Extract usage context (call sites, arguments, parameter uses, returns,
                      field reads/writes, null checks, annotations) for every warning (or only
                      <ID>) from the run's snapshot into context/<ID>.json and .txt. Uses javac
                      symbols; no checker, Specimin or LLM is run. Limits: GA_CONTEXT_MAX_ITEMS,
                      GA_CONTEXT_MAX_SNIPPET_LINES, GA_CONTEXT_MAX_CONDITIONS, GA_CONTEXT_MAX_LOCAL_USES.
              prompts <run directory> [--warning <ID>]
                      Assemble one LLM prompt unit per warning (or only <ID>) from the run's warning
                      graph, targets, Specimin slice and compact usage context into prompts/<ID>.txt
                      and prompts/manifest.json. No LLM is called; nothing earlier is rerun.
                      Limits: GA_PROMPT_MAX_CHARS, GA_PROMPT_MAX_FILE_CHARS.
              prepare-inference <run directory> [--warning <ID>[,<ID>...]]
                      Prepare an immutable inference submission (inference/<id>/) from the
                      GENERATED prompts (or only the listed ones): copied prompts with SHA-256 hashes, task assignments,
                      settings, a Slurm array batch script and the GPU worker. Local only:
                      nothing is uploaded or submitted. Settings: GA_SLURM_*, GA_INFERENCE_*.
              inference connect
                      Reuse a running OpenSSH master to the cluster, or open one with a single
                      interactive login (password and Duo) in this terminal. Settings: GA_SSH_*.
              inference upload|submit|status|collect|retry|run <submission dir>
                      upload: copy the submission's inputs to a unique remote directory and verify
                      their SHA-256; submit: one sbatch array job (never twice); status: squeue/sacct
                      state per array task; collect: download and verify results into remote/;
                      retry: resubmit failed or missing prompts as new attempts once every task has
                      finished; run: connect, upload, submit, then monitor and collect
                      (GA_REMOTE_POLL_SECONDS). Settings: GA_SSH_*, GA_REMOTE_*.
              inference attach <submission dir> <job id> | --not-submitted
                      Resolve an sbatch call whose outcome is UNKNOWN by attaching a job verified
                      with the scheduler, or by verifying that no job was created.
              apply-proposal <submission dir> --warning <ID>
                      Import the collected LLM proposal of one warning and apply its annotation edits,
                      all or nothing, to a fresh copy of the run snapshot in applications/<id>/
                      (application.json, changes.diff, workspace/). The checker is not rerun.
              evaluate-annotations <submission dir> [--warning <ID>] [--reuse-from <evaluation dir>]
                      Evaluate every unique annotation package of the collected answers (answers with
                      the same complete edit set share one; or the package proposed for <ID>) alone
                      against one baseline analysis of the run snapshot: apply it to a fresh copy,
                      rerun the checker once on all source roots, match findings, and write
                      evaluations/<id>/ with attempts, summary.json and evaluation-graph.json.
                      --reuse-from imports verified-equivalent results of an earlier
                      one-package-per-answer evaluation (read only) instead of rerunning them.
                      Checker settings as for check (GA_CHECKER, GA_CHECKER_HOME, GA_TARGET_CLASSPATH,
                      ...); GA_TARGET_PROJECT and GA_TARGET_SOURCE_ROOTS are not used. The answers
                      are inference round 1; warnings introduced in round 1 are W1001, W1002, ...
              apply-round <submission dir> --round 1
                      Apply every validated package of the collected answers together to one fresh
                      copy of the run snapshot, check that combined state once on the configured
                      source roots, and write rounds/<id>/ with the annotated workspace, changes.diff,
                      every finding (findings.json, warnings.txt) and its comparison with the
                      baseline. A conflict rejects the whole round. Checker settings as for
                      evaluate-annotations. The result directory can be used as the next round's run.
              pipeline start [--resume <pipeline dir>]
                      Run the whole round-1 front half in one command: check (snapshot, findings,
                      graph, targets), slice, context, prompts, prepare-inference (10 prompts per
                      array task unless GA_INFERENCE_PROMPTS_PER_TASK is set), connect (one login
                      if needed), upload and submit once. Returns after the job ID is saved; the
                      run directory is the pipeline directory (pipeline.json, pipeline-summary.txt).
                      --resume continues an interrupted start without submitting twice.
              pipeline status <pipeline dir> [--connect]
                      Recorded stages, then the job's scheduler states (fresh, or cached and
                      labeled); --connect reconnects first (may prompt once).
              pipeline finish <pipeline dir>
                      Connect, check the job; once every task has ended: collect, evaluate each
                      unique package (evaluate-annotations), apply all packages together and
                      recheck once (apply-round). Safe to repeat.
              migrate-warning-ids <evaluation dir>
                      Convert an evaluation written with candidate-local introduced-warning IDs
                      (A0001-N1) to round IDs (W1001, ...), with a backup. Nothing is rerun.

            Exit codes: 0 success (check: analysis completed), 2 usage/configuration error,
                        3 execution failure (check: incomplete analysis; visualize: Graphviz
                        missing or failed; slice: setup failed or a slice FAILED;
                        context: attribution impossible or a target FAILED;
                        inference: incomplete, refused or unknown; apply-proposal: no applicable
                        proposal or it was rejected; evaluate-annotations: nothing to evaluate,
                        baseline failed, evaluation busy, needs migration, --reuse-from not
                        equivalent or IDs exhausted;
                        apply-round: nothing to apply, rejected, check failed or not comparable;
                        pipeline: failed, blocked, waiting for jobs, unknown submission or
                        nothing to submit/evaluate;
                        migrate-warning-ids: refused), 4 internal error,
                        5 inference: the shared SSH connection is not available (reconnect).""";

    private Main() {}

    /**
     * Parses {@code --name value} pairs from {@code args[from]} on; empty if an option is unknown,
     * repeated or has no value.
     */
    static Optional<Map<String, String>> options(String[] args, int from, Set<String> allowed) {
        Map<String, String> options = new java.util.LinkedHashMap<>();
        for (int i = from; i < args.length; i += 2) {
            if (i + 1 >= args.length || !allowed.contains(args[i]) || options.containsKey(args[i])) {
                return Optional.empty();
            }
            options.put(args[i], args[i + 1]);
        }
        return Optional.of(options);
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, System.err).code());
    }

    static ExitCode run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        if (args.length == 1 && args[0].equals("check")) {
            try {
                return new CheckCommand(env, Path.of("").toAbsolutePath(), Path.of(System.getProperty("java.home")),
                        new ProcessCommandRunner(), Clock.systemUTC(), out, err).run();
            } catch (RuntimeException e) {
                err.println("GeneralAnnotator: internal error:");
                e.printStackTrace(err);
                return ExitCode.INTERNAL_ERROR;
            }
        }
        if (args.length == 2 && args[0].equals("visualize")) {
            try {
                return new VisualizeCommand(args[1], env, Path.of("").toAbsolutePath(), new ProcessCommandRunner(),
                        out, err).run();
            } catch (RuntimeException e) {
                err.println("GeneralAnnotator: internal error:");
                e.printStackTrace(err);
                return ExitCode.INTERNAL_ERROR;
            }
        }
        if (args.length == 2 && args[0].equals("targets")) {
            try {
                return new TargetsCommand(args[1], Path.of("").toAbsolutePath(), out, err).run();
            } catch (RuntimeException e) {
                err.println("GeneralAnnotator: internal error:");
                e.printStackTrace(err);
                return ExitCode.INTERNAL_ERROR;
            }
        }
        if (args.length >= 2 && args[0].equals("slice")
                && (args.length == 2 || (args.length == 4 && args[2].equals("--warning")))) {
            try {
                return new SliceCommand(args[1], args.length == 4 ? Optional.of(args[3]) : Optional.empty(), env,
                        Path.of("").toAbsolutePath(), Path.of(System.getProperty("java.home")),
                        new ProcessCommandRunner(), out, err).run();
            } catch (RuntimeException e) {
                err.println("GeneralAnnotator: internal error:");
                e.printStackTrace(err);
                return ExitCode.INTERNAL_ERROR;
            }
        }
        if (args.length >= 2 && args[0].equals("context")
                && (args.length == 2 || (args.length == 4 && args[2].equals("--warning")))) {
            try {
                return new ContextCommand(args[1], args.length == 4 ? Optional.of(args[3]) : Optional.empty(), env,
                        Path.of("").toAbsolutePath(), out, err).run();
            } catch (RuntimeException e) {
                err.println("GeneralAnnotator: internal error:");
                e.printStackTrace(err);
                return ExitCode.INTERNAL_ERROR;
            }
        }
        if (args.length >= 2 && args[0].equals("prompts")
                && (args.length == 2 || (args.length == 4 && args[2].equals("--warning")))) {
            try {
                return new PromptsCommand(args[1], args.length == 4 ? Optional.of(args[3]) : Optional.empty(), env,
                        Path.of("").toAbsolutePath(), out, err).run();
            } catch (RuntimeException e) {
                err.println("GeneralAnnotator: internal error:");
                e.printStackTrace(err);
                return ExitCode.INTERNAL_ERROR;
            }
        }
        if (args.length >= 2 && args[0].equals("prepare-inference")
                && (args.length == 2 || (args.length == 4 && args[2].equals("--warning")))) {
            try {
                return new PrepareInferenceCommand(args[1], args.length == 4 ? Optional.of(args[3]) : Optional.empty(),
                        env, Path.of("").toAbsolutePath(), Clock.systemUTC(), out, err).run();
            } catch (RuntimeException e) {
                err.println("GeneralAnnotator: internal error:");
                e.printStackTrace(err);
                return ExitCode.INTERNAL_ERROR;
            }
        }
        if (args.length == 4 && args[0].equals("apply-proposal") && args[2].equals("--warning")) {
            try {
                return new ApplyProposalCommand(args[1], args[3], Path.of("").toAbsolutePath(),
                        new JavaParserAnnotationApplier(), Clock.systemUTC(), out, err).run();
            } catch (RuntimeException e) {
                err.println("GeneralAnnotator: internal error:");
                e.printStackTrace(err);
                return ExitCode.INTERNAL_ERROR;
            }
        }
        Optional<Map<String, String>> evaluateOptions = args.length >= 2 && args[0].equals("evaluate-annotations")
                ? options(args, 2, Set.of("--warning", "--reuse-from")) : Optional.empty();
        if (evaluateOptions.isPresent()) {
            try {
                return new EvaluateAnnotationsCommand(args[1], Optional.ofNullable(evaluateOptions.get().get("--warning")),
                        Optional.ofNullable(evaluateOptions.get().get("--reuse-from")), env,
                        Path.of("").toAbsolutePath(), Path.of(System.getProperty("java.home")),
                        (config, e) -> Checkers.create(config, e, new ProcessCommandRunner()),
                        new JavaParserAnnotationApplier(), new AnchoredWarningMatcher(), 1, Clock.systemUTC(), out,
                        err).run();
            } catch (RuntimeException e) {
                err.println("GeneralAnnotator: internal error:");
                e.printStackTrace(err);
                return ExitCode.INTERNAL_ERROR;
            }
        }
        Optional<Map<String, String>> roundOptions = args.length >= 2 && args[0].equals("apply-round")
                ? options(args, 2, Set.of("--round")).filter(o -> o.containsKey("--round")) : Optional.empty();
        if (roundOptions.isPresent()) {
            try {
                return new ApplyRoundCommand(args[1], roundOptions.get().get("--round"), env,
                        Path.of("").toAbsolutePath(), Path.of(System.getProperty("java.home")),
                        (config, e) -> Checkers.create(config, e, new ProcessCommandRunner()),
                        new JavaParserAnnotationApplier(), new AnchoredWarningMatcher(), Clock.systemUTC(), out, err)
                        .run();
            } catch (RuntimeException e) {
                err.println("GeneralAnnotator: internal error:");
                e.printStackTrace(err);
                return ExitCode.INTERNAL_ERROR;
            }
        }
        if (args.length >= 2 && args[0].equals("pipeline")) {
            boolean start = args[1].equals("start") && (args.length == 2 || (args.length == 4
                    && args[2].equals("--resume")));
            boolean status = args[1].equals("status") && (args.length == 3 || (args.length == 4
                    && args[3].equals("--connect")));
            boolean finish = args[1].equals("finish") && args.length == 3;
            if (start || status || finish) {
                try {
                    Pipeline pipeline = new Pipeline(env, Path.of("").toAbsolutePath(),
                            Path.of(System.getProperty("java.home")), new Pipeline.Tools(new ProcessCommandRunner(),
                                    new TerminalCommandRunner(), System.console() != null,
                                    (config, e) -> Checkers.create(config, e, new ProcessCommandRunner()),
                                    InferenceCommand.sshClusters(), new JavaParserAnnotationApplier(),
                                    new AnchoredWarningMatcher(), Clock.systemUTC()), out, err);
                    return start ? pipeline.start(args.length == 4 ? Optional.of(args[3]) : Optional.empty())
                            : status ? pipeline.status(args[2], args.length == 4) : pipeline.finish(args[2]);
                } catch (RuntimeException e) {
                    err.println("GeneralAnnotator: internal error:");
                    e.printStackTrace(err);
                    return ExitCode.INTERNAL_ERROR;
                }
            }
        }
        if (args.length == 2 && args[0].equals("migrate-warning-ids")) {
            try {
                return new MigrateWarningIdsCommand(args[1], Path.of("").toAbsolutePath(), Clock.systemUTC(), out, err)
                        .run();
            } catch (RuntimeException e) {
                err.println("GeneralAnnotator: internal error:");
                e.printStackTrace(err);
                return ExitCode.INTERNAL_ERROR;
            }
        }
        if (args.length >= 1 && args[0].equals("inference")) {
            try {
                return new InferenceCommand(List.of(args).subList(1, args.length), env, Path.of("").toAbsolutePath(),
                        new ProcessCommandRunner(), new TerminalCommandRunner(), System.console() != null,
                        Clock.systemUTC(), d -> Thread.sleep(d.toMillis()), InferenceCommand.sshClusters(), out,
                        err).run();
            } catch (RuntimeException e) {
                err.println("GeneralAnnotator: internal error:");
                e.printStackTrace(err);
                return ExitCode.INTERNAL_ERROR;
            }
        }
        if (args.length == 1 && (args[0].equals("help") || args[0].equals("--help"))) {
            out.println(USAGE);
            return ExitCode.COMPLETED;
        }
        err.println(args.length == 0 ? "GeneralAnnotator: no command given." : "GeneralAnnotator: unknown arguments: "
                + String.join(" ", args));
        err.println(USAGE);
        return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
    }
}
