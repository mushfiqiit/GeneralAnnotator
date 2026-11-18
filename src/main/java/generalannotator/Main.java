package generalannotator;

import generalannotator.cli.CheckCommand;
import generalannotator.cli.ContextCommand;
import generalannotator.cli.ExitCode;
import generalannotator.cli.SliceCommand;
import generalannotator.cli.TargetsCommand;
import generalannotator.cli.VisualizeCommand;
import generalannotator.exec.ProcessCommandRunner;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;

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

            Exit codes: 0 success (check: analysis completed), 2 usage/configuration error,
                        3 execution failure (check: incomplete analysis; visualize: Graphviz
                        missing or failed; slice: setup failed or a slice FAILED;
                        context: attribution impossible or a target FAILED),
                        4 internal error.""";

    private Main() {}

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
