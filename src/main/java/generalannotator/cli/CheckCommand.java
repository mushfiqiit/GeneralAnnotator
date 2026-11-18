package generalannotator.cli;

import generalannotator.checker.AnalysisRequest;
import generalannotator.checker.Checker;
import generalannotator.checker.CheckerOutcome;
import generalannotator.checker.CheckerOutcome.ExecutionFailure;
import generalannotator.checker.CheckerRun;
import generalannotator.checker.Checkers;
import generalannotator.checker.FailureKind;
import generalannotator.config.AnalysisConfig;
import generalannotator.config.ConfigLoader;
import generalannotator.config.ConfigurationException;
import generalannotator.exec.CommandRunner;
import generalannotator.exec.RecordedCommand;
import generalannotator.isolation.IsolationException;
import generalannotator.isolation.WorkingCopy;
import generalannotator.isolation.WorkingCopyCreator;
import generalannotator.report.Json;
import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.graph.InitialWarningGraph;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.graph.WarningNode;
import generalannotator.report.RunReport;
import generalannotator.report.WarningsText;
import java.util.Optional;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * {@code check}: copy the target into an isolated working copy, run the configured checker on it,
 * write {@code report.json} plus raw logs into a new run directory, and print a summary.
 */
public final class CheckCommand {

    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final Map<String, String> env;
    private final Path workingDirectory;
    private final Path defaultJavaHome;
    private final CommandRunner runner;
    private final Clock clock;
    private final PrintStream out;
    private final PrintStream err;

    public CheckCommand(Map<String, String> env, Path workingDirectory, Path defaultJavaHome,
            CommandRunner runner, Clock clock, PrintStream out, PrintStream err) {
        this.env = Map.copyOf(env);
        this.workingDirectory = workingDirectory;
        this.defaultJavaHome = defaultJavaHome;
        this.runner = runner;
        this.clock = clock;
        this.out = out;
        this.err = err;
    }

    public ExitCode run() {
        Instant started = clock.instant();
        AnalysisConfig config;
        Checker checker;
        try {
            config = new ConfigLoader(env, workingDirectory, defaultJavaHome).load();
            checker = Checkers.create(config, env, runner);
        } catch (ConfigurationException e) {
            err.println("GeneralAnnotator: invalid configuration:");
            e.problems().forEach(p -> err.println("  - " + p));
            err.println("See README.md (\"Configuration\") for the required environment variables.");
            return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
        }

        Path runDir;
        String runId;
        try {
            Files.createDirectories(config.outputDirectory());
            String base = RUN_ID.format(started) + "-" + config.checkerId();
            runId = base;
            for (int i = 2; ; i++) {
                try {
                    runDir = Files.createDirectory(config.outputDirectory().resolve(runId));
                    break;
                } catch (FileAlreadyExistsException e) {
                    runId = base + "-" + i;
                }
            }
        } catch (IOException e) {
            err.println("GeneralAnnotator: cannot create run directory in " + config.outputDirectory() + ": " + e);
            return ExitCode.INTERNAL_ERROR;
        }

        WorkingCopy copy = null;
        List<RecordedCommand> isolationCommands = List.of();
        CheckerRun checkerRun = null;
        CheckerOutcome outcome;
        try {
            copy = new WorkingCopyCreator(runner, config.timeout())
                    .create(config.targetProject(), runDir.resolve("workspace").resolve("target"),
                            runDir.resolve("logs"));
            isolationCommands = copy.commands();
            Path copyRoot = copy.root();
            List<Path> classpath = config.classpath().stream()
                    .map(e -> e.relativeToTarget() ? copyRoot.resolve(e.path()) : e.path())
                    .toList();
            checkerRun = checker.analyze(new AnalysisRequest(copy.root(), config.sourceRoots(), classpath,
                    config.checkerJavaHome(), config.javacRelease(), runDir, config.timeout()));
            outcome = checkerRun.outcome();
        } catch (IsolationException e) {
            isolationCommands = e.commands();
            outcome = ExecutionFailure.before(FailureKind.ISOLATION_FAILED, e.getMessage());
        }

        ExitCode exit = outcome instanceof CheckerOutcome.CompletedAnalysis
                ? ExitCode.COMPLETED : ExitCode.EXECUTION_FAILURE;
        Path reportFile = runDir.resolve("report.json");
        Path warningsFile = runDir.resolve(WarningsText.FILE_NAME);
        Path graphFile = runDir.resolve(WarningGraphJson.FILE_NAME);
        Path copyRoot = copy == null ? null : copy.root();
        // The warning graph exists only for a completed analysis; a failure never yields an empty graph.
        Optional<WarningGraph> graph = outcome instanceof CompletedAnalysis completed
                ? Optional.of(InitialWarningGraph.build(runId, config.checkerId(), completed, copyRoot))
                : Optional.empty();
        try {
            if (graph.isPresent()) {
                WarningGraphJson.save(graph.get(), graphFile);
            }
            Map<String, Object> report = RunReport.build(new RunReport.Inputs(runId, runDir, started,
                    clock.instant(), config, copy, isolationCommands, checkerRun, outcome, exit.code(),
                    graph.isPresent() ? graphFile : null, graph.orElse(null)));
            Files.writeString(reportFile, Json.write(report), StandardCharsets.UTF_8);
            Files.writeString(warningsFile, WarningsText.format(config.checkerId(),
                    config.targetProject().toString(), outcome, copyRoot, graph), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            err.println("GeneralAnnotator: failed to write the run output in " + runDir + ": " + e);
            return ExitCode.INTERNAL_ERROR;
        }
        printSummary(config, copy, checkerRun, outcome, reportFile, warningsFile, graphFile, graph, exit);
        return exit;
    }

    private void printSummary(AnalysisConfig config, WorkingCopy copy, CheckerRun run, CheckerOutcome outcome,
            Path reportFile, Path warningsFile, Path graphFile, Optional<WarningGraph> graph, ExitCode exit) {
        out.println("GeneralAnnotator check");
        String checkerInfo = config.checkerId();
        if (run != null && run.details().get("nullawayVersion") != null) {
            checkerInfo += " (NullAway " + run.details().get("nullawayVersion") + " built from "
                    + config.checkerHome() + ")";
        } else {
            checkerInfo += " (" + config.checkerHome() + ")";
        }
        out.println("  checker:  " + checkerInfo);
        String targetInfo = config.targetProject().toString();
        if (copy != null) {
            targetInfo += " [" + copy.mechanism()
                    + copy.gitHead().map(h -> " " + h.substring(0, Math.min(12, h.length()))).orElse("")
                    + copy.gitStatusEntries().map(n -> ", " + n + " uncommitted/untracked entries").orElse("")
                    + "]";
        }
        out.println("  target:   " + targetInfo);
        if (outcome instanceof ExecutionFailure f) {
            out.println("  status:   EXECUTION FAILURE (" + f.kind() + "); findings are incomplete");
            f.reasons().stream().limit(5).forEach(r -> out.println("            - " + firstLine(r)));
            if (f.reasons().size() > 5) {
                out.println("            ... " + (f.reasons().size() - 5) + " more reason(s) in the report");
            }
        } else {
            out.println("  status:   COMPLETED");
        }
        Map<String, Integer> severities = RunReport.severityCounts(outcome.findings());
        out.println("  findings: " + outcome.findings().size()
                + (severities.isEmpty() ? "" : " " + severities)
                + (outcome instanceof ExecutionFailure ? " (observed before failure)" : ""));
        if (run != null) {
            var c = run.coverage();
            int outside = c.javaFilesOutsideSourceRoots().values().stream().mapToInt(Integer::intValue).sum();
            out.println("  coverage: " + c.analyzedSourceFiles() + " source file(s) in " + c.analyzedSourceRoots()
                    + (outside > 0 ? "; " + outside + " Java file(s) outside the source roots not analyzed" : ""));
        }
        if (graph.isPresent()) {
            out.println("  graph:    " + graphFile + " (" + graph.get().nodes().size() + " warning node(s), "
                    + graph.get().edges().size() + " edge(s))");
        } else {
            out.println("  graph:    not created (the analysis did not complete)");
        }
        out.println("  warnings: " + warningsFile);
        out.println("  report:   " + reportFile);
        out.println("  exit:     " + exit.code() + " (" + exit + ")");
        if (graph.isPresent()) {
            List<WarningNode> nodes = graph.get().warningNodes();
            out.println();
            out.println(nodes.isEmpty() ? "Warning graph: no findings, so no warning nodes."
                    : "Warning IDs (all roots, distance 0):");
            nodes.forEach(n -> out.println("  " + truncate(WarningsText.mappingLine(n), 160)));
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline) + " ...";
    }
}
