package generalannotator.cli;

import generalannotator.checker.Checker;
import generalannotator.config.AnalysisConfig;
import generalannotator.config.ConfigurationException;
import generalannotator.edits.AnnotationApplier;
import generalannotator.evaluation.AttemptOutcome;
import generalannotator.evaluation.Candidates;
import generalannotator.evaluation.EvaluationConfig;
import generalannotator.evaluation.Evaluator;
import generalannotator.evaluation.matching.WarningMatcher;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code evaluate-annotations <submission directory> [--warning <ID>]}: evaluate every candidate edit
 * from the collected answers of one submission independently against one baseline analysis of the
 * run snapshot, and record the results and the expanded graph under {@code runs/<run>/evaluations/}
 * (see {@link Evaluator}). The checker settings come from the {@code check} environment variables;
 * the target and source roots are the run's own. {@code --warning} selects the candidates proposed for
 * that warning; it never narrows the checker's scope or the comparison.
 *
 * <p>Exit codes: 0 every selected candidate has a recorded outcome (which may be a failure of that
 * attempt); 2 invalid arguments, configuration or artifacts; 3 nothing to evaluate, the baseline
 * analysis failed, or another process is writing the evaluation; 4 internal error.
 */
public final class EvaluateAnnotationsCommand {

    /** Creates the checker for the validated configuration. */
    @FunctionalInterface
    public interface CheckerFactory {
        Checker create(AnalysisConfig config, Map<String, String> env) throws ConfigurationException;
    }

    private final String submissionArgument;
    private final Optional<String> warning;
    private final Map<String, String> env;
    private final Path workingDirectory;
    private final Path defaultJavaHome;
    private final CheckerFactory checkers;
    private final AnnotationApplier applier;
    private final WarningMatcher matcher;
    private final Clock clock;
    private final PrintStream out;
    private final PrintStream err;

    public EvaluateAnnotationsCommand(String submissionArgument, Optional<String> warning, Map<String, String> env,
            Path workingDirectory, Path defaultJavaHome, CheckerFactory checkers, AnnotationApplier applier,
            WarningMatcher matcher, Clock clock, PrintStream out, PrintStream err) {
        this.submissionArgument = submissionArgument;
        this.warning = warning;
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
        Path dir;
        try {
            dir = workingDirectory.resolve(submissionArgument).normalize();
        } catch (InvalidPathException e) {
            return usage("'" + submissionArgument + "' is not a valid path");
        }
        if (!Files.isDirectory(dir)) {
            return usage(dir + " is not a submission directory (runs/<run id>/inference/<submission id>)");
        }
        Candidates candidates;
        try {
            candidates = Candidates.load(dir);
        } catch (Candidates.InvalidException e) {
            return usage(e.getMessage());
        } catch (IOException e) {
            err.println("GeneralAnnotator: cannot read the inference artifacts: " + e);
            return ExitCode.INTERNAL_ERROR;
        }
        out.println("GeneralAnnotator evaluate-annotations (submission " + candidates.submissionId() + ", run "
                + candidates.runId() + ")");
        out.println("  candidates: " + candidates.candidates().size() + " distinct edit(s) from "
                + candidates.groups().size() + " proposed answer(s); " + candidates.excluded().size()
                + " answer(s) excluded");
        candidates.excluded().forEach(x -> out.println("    excluded " + x.warningId() + " (" + x.status() + "): "
                + firstLine(x.reason())));
        if (warning.isPresent()) {
            String w = warning.get();
            boolean known = candidates.groups().stream().anyMatch(g -> g.warningId().equals(w))
                    || candidates.excluded().stream().anyMatch(x -> x.warningId().equals(w));
            if (!known) {
                return usage("warning " + w + " is not in submission " + candidates.submissionId());
            }
            long selected = candidates.candidates().stream().filter(c -> c.proposedFor(w)).count();
            if (selected == 0) {
                out.println("  Nothing to evaluate: the answer for " + w + " yielded no candidate edits.");
                return ExitCode.EXECUTION_FAILURE;
            }
            out.println("  selected:   " + selected + " candidate(s) proposed for " + w + " (the checker still "
                    + "analyzes every source root, and comparisons use every finding)");
        } else if (candidates.candidates().isEmpty()) {
            out.println("  Nothing to evaluate: no answer proposed an applicable edit.");
            return ExitCode.EXECUTION_FAILURE;
        }

        EvaluationConfig config;
        Checker checker;
        try {
            config = EvaluationConfig.load(env, workingDirectory, defaultJavaHome, candidates.snapshot(),
                    candidates.sourceRoots(), candidates.runDir().resolve(Evaluator.DIR));
            checker = checkers.create(config.analysis(), env);
        } catch (ConfigurationException e) {
            err.println("GeneralAnnotator: invalid configuration:");
            e.problems().forEach(p -> err.println("  - " + p));
            err.println("See README.md (\"Evaluating annotations\") for the settings.");
            return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
        } catch (IOException e) {
            err.println("GeneralAnnotator: cannot fingerprint the configuration: " + e);
            return ExitCode.INTERNAL_ERROR;
        }
        out.println("  checker:    " + config.analysis().checkerId() + " (" + config.analysis().checkerHome()
                + "), source roots " + candidates.sourceRoots() + ", classpath "
                + config.analysis().classpath().stream().map(e -> e.path().toString()).toList());

        Evaluator.Result result;
        try {
            result = new Evaluator(candidates, config, checker, applier, matcher, clock, out).run(warning);
        } catch (IOException e) {
            err.println("GeneralAnnotator: evaluation failed: " + e.getMessage());
            return ExitCode.INTERNAL_ERROR;
        }
        switch (result.status()) {
            case BUSY -> {
                err.println("GeneralAnnotator: " + result.message());
                return ExitCode.EXECUTION_FAILURE;
            }
            case BASELINE_FAILED -> {
                out.println("  Evaluation blocked: " + result.message() + ". No candidate was evaluated.");
                return ExitCode.EXECUTION_FAILURE;
            }
            case COMPLETED -> { }
        }
        Map<AttemptOutcome, Integer> counts = new EnumMap<>(AttemptOutcome.class);
        result.attempts().forEach(a -> counts.merge(a.outcome(), 1, Integer::sum));
        out.println("  outcomes:   " + counts);
        long reductions = result.attempts().stream().filter(a -> a.outcome().compared() && a.counts().isPresent()
                && a.counts().get().resultFindings() < a.counts().get().baselineFindings()).count();
        out.println("  " + reductions + " edit(s) reduced the finding count in a completed analysis; counts are not "
                + "reported for incomplete analyses.");
        out.println("  graph:      " + result.directory().resolve(Evaluator.GRAPH));
        out.println("  summary:    " + result.directory().resolve(Evaluator.SUMMARY));
        out.println("  visualize:  ./gradlew run --args=\"visualize '" + result.directory() + "'\"");
        out.println("  Each edit was evaluated alone; independently successful edits are not shown to combine "
                + "safely, and no fixes were inferred for introduced warnings.");
        return ExitCode.COMPLETED;
    }

    private ExitCode usage(String problem) {
        err.println("GeneralAnnotator: cannot evaluate annotations: " + problem);
        return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline) + " ...";
    }
}
