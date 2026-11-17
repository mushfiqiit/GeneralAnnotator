package generalannotator;

import generalannotator.cli.CheckCommand;
import generalannotator.cli.ExitCode;
import generalannotator.exec.ProcessCommandRunner;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/** Command-line entry point. */
public final class Main {

    static final String USAGE = """
            Usage: GeneralAnnotator <command>

            Commands:
              check   Run the configured checker on an isolated copy of the target project
                      and write a report. Configuration is read from environment variables
                      (GA_CHECKER, GA_CHECKER_HOME, GA_TARGET_PROJECT, GA_TARGET_SOURCE_ROOTS, ...);
                      see README.md.

            Exit codes: 0 analysis completed, 2 usage/configuration error,
                        3 execution failure (incomplete analysis), 4 internal error.""";

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
