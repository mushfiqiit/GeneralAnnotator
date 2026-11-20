package generalannotator.cli;

import generalannotator.config.ConfigurationException;
import generalannotator.edits.AnnotationApplier;
import generalannotator.evaluation.Candidates;
import generalannotator.evaluation.EvaluationConfig;
import generalannotator.evaluation.RoundApplication;
import generalannotator.evaluation.matching.WarningMatcher;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * {@code apply-round <submission directory> --round 1}: apply every validated annotation package of the
 * submission together to one copy of the run snapshot, check the combined state once and save all its
 * findings and their comparison with the baseline under {@code runs/<run>/rounds/} (see {@link
 * RoundApplication}). Checker settings as for {@code evaluate-annotations}.
 *
 * <p>Only round 1 (answers about the run's initial warnings) is supported. Exit codes: 0 the combined
 * state was checked and compared (or an earlier identical result was reported); 2 invalid arguments,
 * configuration or artifacts; 3 nothing to apply, an edit was rejected or conflicts, the baseline or the
 * combined analysis failed, the checker build differed (not comparable), or another process is writing
 * the result; 4 internal error.
 */
public final class ApplyRoundCommand {

    private final String submissionArgument;
    private final String roundArgument;
    private final Map<String, String> env;
    private final Path workingDirectory;
    private final Path defaultJavaHome;
    private final EvaluateAnnotationsCommand.CheckerFactory checkers;
    private final AnnotationApplier applier;
    private final WarningMatcher matcher;
    private final Clock clock;
    private final PrintStream out;
    private final PrintStream err;

    public ApplyRoundCommand(String submissionArgument, String roundArgument, Map<String, String> env,
            Path workingDirectory, Path defaultJavaHome, EvaluateAnnotationsCommand.CheckerFactory checkers,
            AnnotationApplier applier, WarningMatcher matcher, Clock clock, PrintStream out, PrintStream err) {
        this.submissionArgument = submissionArgument;
        this.roundArgument = roundArgument;
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
        if (!roundArgument.equals("1")) {
            return usage("--round " + roundArgument + " is not supported; only round 1 (answers about the run's "
                    + "initial warnings) can be applied");
        }
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
        out.println("GeneralAnnotator apply-round (round 1, submission " + candidates.submissionId() + ", run "
                + candidates.runId() + ")");
        out.println("  packages:   " + candidates.importedProposals() + " validated proposal(s) -> "
                + candidates.candidates().size() + " unique package(s), " + candidates.editCount()
                + " package edit(s); all are applied together, none is left out for its individual result");
        if (!candidates.excluded().isEmpty()) {
            out.println("  excluded:   " + candidates.excluded().size() + " answer(s) yielded no package, so round 1 "
                    + "does not cover every warning:");
            candidates.excluded().forEach(x -> out.println("    " + x.warningId() + " (" + x.status() + "): "
                    + firstLine(x.reason())));
        }
        if (candidates.candidates().isEmpty()) {
            out.println("  Nothing to apply: no answer proposed an applicable edit.");
            return ExitCode.EXECUTION_FAILURE;
        }
        EvaluationConfig config;
        generalannotator.checker.Checker checker;
        try {
            config = EvaluationConfig.load(env, workingDirectory, defaultJavaHome, candidates.snapshot(),
                    candidates.sourceRoots(), candidates.runDir().resolve(RoundApplication.DIR));
            checker = checkers.create(config.analysis(), env);
        } catch (ConfigurationException e) {
            err.println("GeneralAnnotator: invalid configuration:");
            e.problems().forEach(p -> err.println("  - " + p));
            err.println("See README.md (\"Applying a whole round\") for the settings.");
            return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
        } catch (IOException e) {
            err.println("GeneralAnnotator: cannot fingerprint the configuration: " + e);
            return ExitCode.INTERNAL_ERROR;
        }
        out.println("  checker:    " + config.analysis().checkerId() + " (" + config.analysis().checkerHome()
                + "), source roots " + candidates.sourceRoots() + ", classpath "
                + config.analysis().classpath().stream().map(e -> e.path().toString()).toList());

        RoundApplication.Result result;
        try {
            result = new RoundApplication(candidates, config, checker, applier, matcher, 1, clock, out).run();
        } catch (IOException e) {
            err.println("GeneralAnnotator: apply-round failed: " + e.getMessage());
            return ExitCode.INTERNAL_ERROR;
        }
        if (result.status() == RoundApplication.Status.BUSY) {
            return ExitCode.EXECUTION_FAILURE;
        }
        report(result);
        return result.status() == RoundApplication.Status.COMPLETED ? ExitCode.COMPLETED : ExitCode.EXECUTION_FAILURE;
    }

    private void report(RoundApplication.Result result) {
        Map<?, ?> r = result.record();
        Path dir = result.directory();
        out.println("  result:     " + dir + (result.reused() ? " (an earlier result for the same baseline, "
                + "configuration, round and edit set; reused, nothing was rerun)" : ""));
        out.println("  state:      " + r.get("stateId") + " = " + r.get("stateDescription"));
        if (r.get("counts") instanceof Map<?, ?> c) {
            out.println("  edits:      " + c.get("distinctEdits") + " distinct edit(s) (" + c.get("sharedEdits")
                    + " shared by several packages)" + (c.get("appliedEdits") == null ? ""
                    : ": " + c.get("appliedEdits") + " applied, " + c.get("noOpEdits") + " already present (NO_OP)"));
        }
        out.println("  status:     " + r.get("status") + (r.get("detail") == null ? "" : ": " + r.get("detail")));
        if (r.get("conflicts") instanceof List<?> conflicts) {
            for (Object o : conflicts) {
                Map<?, ?> x = (Map<?, ?>) o;
                out.println("    " + x.get("kind") + " " + x.get("edit"));
                out.println("      " + x.get("detail"));
                out.println("      from package(s) " + x.get("packages") + ", answers " + ((List<?>) x.get("origins"))
                        .stream().map(y -> String.valueOf(((Map<?, ?>) y).get("group"))).toList());
            }
            out.println("  Nothing was applied; resolve the conflicting proposals (no edit is dropped or ordered "
                    + "away automatically).");
            return;
        }
        if (r.get("check") instanceof Map<?, ?> check && check.get("coverage") instanceof Map<?, ?> cov) {
            out.println("  analyzed:   source roots " + cov.get("analyzedSourceRoots") + " ("
                    + cov.get("analyzedSourceFiles") + " file(s)); the whole repository was copied, but Java files "
                    + "outside them were NOT checked: " + cov.get("javaFilesOutsideSourceRoots"));
        }
        String status = String.valueOf(r.get("status"));
        if (status.equals("COMPLETED") || status.equals("NOT_COMPARABLE")) {
            out.println("  warnings:   " + r.get("findingCount") + " finding(s) in the combined state (complete): "
                    + dir.resolve(RoundApplication.WARNINGS));
            out.println("              " + dir.resolve(RoundApplication.FINDINGS));
        }
        if (status.equals("COMPLETED")) {
            out.println("  comparison: " + dir.resolve(RoundApplication.COMPARISON));
            out.println("  diff:       " + dir.resolve(RoundApplication.DIFF));
            out.println("  next round: " + dir + " is laid out like a run directory (report.json, "
                    + "warning-graph.json, workspace/target); for example: targets '" + dir + "'");
        } else if (status.equals("CHECK_FAILED") || status.equals("BASELINE_FAILED")) {
            out.println("  The analysis did not complete; its findings are incomplete and were not compared. Logs and "
                    + "the record are kept in " + dir + "; rerunning tries again.");
        }
        out.println("  Combined effects are not attributed to individual packages or annotation nodes.");
    }

    private ExitCode usage(String problem) {
        err.println("GeneralAnnotator: cannot apply the round: " + problem);
        return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline) + " ...";
    }
}
