package generalannotator.cli;

import generalannotator.checker.Checker;
import generalannotator.config.ConfigurationException;
import generalannotator.edits.AnnotationApplier;
import generalannotator.evaluation.EvaluationConfig;
import generalannotator.evaluation.Evaluator;
import generalannotator.evaluation.matching.WarningMatcher;
import generalannotator.propagation.NullabilityPropagation;
import generalannotator.propagation.PropagationStage;
import generalannotator.report.JsonParser;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code propagate <directory>}: the single-pass, warning-guided nullability propagation stage (see {@link
 * PropagationStage}). For a pipeline directory the input is its completed combined round result; for a combined
 * round result directory, that result; for any other run, the run's own baseline. Checker settings as for {@code
 * evaluate-annotations}; the source roots are the input's own. No LLM is called.
 *
 * <p>Exit codes: 0 completed, no candidates, or nothing to combine; 2 invalid arguments, configuration or run;
 * 3 no completed and verified combined round result (for a pipeline), the evaluation baseline or the combined
 * check failed, the combined edit set was rejected, or the evaluation is busy or refused; 4 internal error.
 */
public final class PropagateCommand {

    private final String runArgument;
    private final Map<String, String> env;
    private final Path workingDirectory;
    private final Path defaultJavaHome;
    private final EvaluateAnnotationsCommand.CheckerFactory checkers;
    private final AnnotationApplier applier;
    private final WarningMatcher matcher;
    private final Clock clock;
    private final PrintStream out;
    private final PrintStream err;
    private final java.util.OptionalInt round;
    private PropagationStage.Result result;

    public PropagateCommand(String runArgument, Map<String, String> env, Path workingDirectory, Path defaultJavaHome,
            EvaluateAnnotationsCommand.CheckerFactory checkers, AnnotationApplier applier, WarningMatcher matcher,
            Clock clock, PrintStream out, PrintStream err) {
        this(runArgument, java.util.OptionalInt.empty(), env, workingDirectory, defaultJavaHome, checkers, applier,
                matcher, clock, out, err);
    }

    /**
     * @param round the propagation round to run, for a pipeline directory (round {@code n} reads the combined
     *     result of round {@code n - 1}); empty for the default (round 2 for a pipeline)
     */
    public PropagateCommand(String runArgument, java.util.OptionalInt round, Map<String, String> env,
            Path workingDirectory, Path defaultJavaHome, EvaluateAnnotationsCommand.CheckerFactory checkers,
            AnnotationApplier applier, WarningMatcher matcher, Clock clock, PrintStream out, PrintStream err) {
        this.round = round;
        this.runArgument = runArgument;
        this.env = Map.copyOf(env);
        this.workingDirectory = workingDirectory;
        this.defaultJavaHome = defaultJavaHome;
        this.checkers = checkers;
        this.applier = applier;
        this.matcher = matcher;
        this.clock = clock;
        this.out = out;
        this.err = err;
    }

    public ExitCode run() {
        Path runDir;
        try {
            runDir = workingDirectory.resolve(runArgument).normalize();
        } catch (InvalidPathException e) {
            return usage("'" + runArgument + "' is not a valid path");
        }
        if (!Files.isDirectory(runDir)) {
            return usage(runDir + " is not a directory");
        }
        PropagationStage.Input input;
        try {
            input = PropagationStage.Input.resolve(runDir, round);
        } catch (NullabilityPropagation.InvalidException e) {
            err.println("GeneralAnnotator: cannot propagate: " + e.getMessage());
            return ExitCode.EXECUTION_FAILURE;
        } catch (IOException e) {
            err.println("GeneralAnnotator: cannot read the propagation input: " + e.getMessage());
            return ExitCode.INTERNAL_ERROR;
        }
        Path report = input.dir().resolve("report.json");
        Path snapshot = input.snapshot();
        if (!Files.isRegularFile(report) || !Files.isDirectory(snapshot)) {
            return usage(input.dir() + " is not a completed run directory (runs/<run id>)");
        }
        List<String> roots = new ArrayList<>();
        try {
            Map<?, ?> r = (Map<?, ?>) JsonParser.parse(Files.readString(report, StandardCharsets.UTF_8));
            if (r.get("configuration") instanceof Map<?, ?> c && c.get("sourceRoots") instanceof List<?> l) {
                l.forEach(o -> roots.add(String.valueOf(o)));
            }
        } catch (IOException | RuntimeException e) {
            return usage("cannot read " + report + ": " + e.getMessage());
        }
        EvaluationConfig config;
        Checker checker;
        try {
            config = EvaluationConfig.load(env, workingDirectory, defaultJavaHome, snapshot, roots,
                    input.output().resolve(Evaluator.DIR));
            checker = checkers.create(config.analysis(), env);
        } catch (ConfigurationException e) {
            err.println("GeneralAnnotator: invalid configuration:");
            e.problems().forEach(p -> err.println("  - " + p));
            return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
        } catch (IOException e) {
            err.println("GeneralAnnotator: cannot fingerprint the configuration: " + e);
            return ExitCode.INTERNAL_ERROR;
        }
        out.println("GeneralAnnotator propagate (round " + input.round() + ", single pass, " + NullabilityPropagation.RULES
                + ", no LLM): " + runDir);
        try {
            result = new PropagationStage(input, config, checker, applier, matcher, clock, out).run();
        } catch (NullabilityPropagation.InvalidException e) {
            return usage(e.getMessage());
        } catch (IOException e) {
            err.println("GeneralAnnotator: propagation failed: " + e.getMessage());
            return ExitCode.INTERNAL_ERROR;
        }
        out.println();
        try {
            out.print(Files.readString(result.directory().resolve(PropagationStage.SUMMARY), StandardCharsets.UTF_8));
        } catch (IOException e) {
            out.println("  result: " + result.directory());
        }
        return switch (result.outcome()) {
            case COMPLETED, NO_CANDIDATES, NOTHING_TO_COMBINE -> ExitCode.COMPLETED;
            default -> ExitCode.EXECUTION_FAILURE;
        };
    }

    /**
     * {@code propagate --rebuild-graph <propagation directory>}: regenerates the extended graph from saved artifacts
     * ({@link PropagationStage#rebuildGraph}); no checker, LLM or inference runs, and no configuration is needed.
     * Exit codes: 0 rebuilt; 2 not a propagation directory; 3 no completed evaluation, or the input changed since
     * (rerun propagate); 4 internal error.
     */
    public static ExitCode rebuildGraph(String argument, Path workingDirectory, WarningMatcher matcher, Clock clock,
            PrintStream out, PrintStream err) {
        Path dir;
        try {
            dir = workingDirectory.resolve(argument).normalize();
        } catch (InvalidPathException e) {
            err.println("GeneralAnnotator: cannot rebuild the graph: '" + argument + "' is not a valid path");
            return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
        }
        if (!Files.isRegularFile(dir.resolve(PropagationStage.RESULT))) {
            err.println("GeneralAnnotator: cannot rebuild the graph: " + dir + " has no " + PropagationStage.RESULT
                    + "; pass a propagation directory (runs/<run>/propagation/<id>)");
            return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
        }
        try {
            PropagationStage.Result r = PropagationStage.rebuildGraph(dir, matcher, clock, out);
            out.println();
            out.print(Files.readString(r.directory().resolve(PropagationStage.SUMMARY), StandardCharsets.UTF_8));
            return ExitCode.COMPLETED;
        } catch (NullabilityPropagation.InvalidException e) {
            err.println("GeneralAnnotator: cannot rebuild the graph: " + e.getMessage());
            return ExitCode.EXECUTION_FAILURE;
        } catch (IOException | RuntimeException e) {
            err.println("GeneralAnnotator: cannot rebuild the graph: " + e.getMessage());
            return ExitCode.INTERNAL_ERROR;
        }
    }

    /** The stage result of the last {@link #run()}, if it ran. */
    public Optional<PropagationStage.Result> result() {
        return Optional.ofNullable(result);
    }

    private ExitCode usage(String problem) {
        err.println("GeneralAnnotator: cannot propagate: " + problem);
        return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
    }
}
