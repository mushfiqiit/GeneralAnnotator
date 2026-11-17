package generalannotator.report;

import generalannotator.checker.CheckerOutcome;
import generalannotator.checker.CheckerOutcome.ExecutionFailure;
import generalannotator.checker.CheckerRun;
import generalannotator.checker.Coverage;
import generalannotator.config.AnalysisConfig;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.exec.CommandResult;
import generalannotator.exec.RecordedCommand;
import generalannotator.isolation.WorkingCopy;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Builds the machine-readable {@code report.json} (schema version {@value #SCHEMA_VERSION}).
 * The schema is documented in README.md.
 */
public final class RunReport {

    public static final int SCHEMA_VERSION = 1;

    private RunReport() {}

    /** Everything a report is built from. Absent parts are {@code null}. */
    public record Inputs(
            String runId,
            Path runDirectory,
            Instant startedAt,
            Instant finishedAt,
            AnalysisConfig config,
            WorkingCopy workingCopy,
            List<RecordedCommand> isolationCommands,
            CheckerRun checkerRun,
            CheckerOutcome outcome,
            int exitCode) {}

    public static Map<String, Object> build(Inputs in) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", SCHEMA_VERSION);
        report.put("tool", "GeneralAnnotator");
        report.put("command", "check");
        report.put("runId", in.runId());
        report.put("runDirectory", in.runDirectory().toString());
        report.put("startedAt", in.startedAt().toString());
        report.put("finishedAt", in.finishedAt().toString());
        boolean completed = in.outcome() instanceof CheckerOutcome.CompletedAnalysis;
        report.put("status", completed ? "COMPLETED" : "EXECUTION_FAILURE");
        report.put("exitCode", in.exitCode());
        report.put("configuration", configuration(in.config()));
        report.put("isolation", isolation(in.workingCopy(), in.config()));

        List<Object> commands = new ArrayList<>();
        in.isolationCommands().forEach(c -> commands.add(command(c, in.runDirectory())));
        if (in.checkerRun() != null) {
            in.checkerRun().commands().forEach(c -> commands.add(command(c, in.runDirectory())));
        }
        report.put("commands", commands);
        report.put("checker", in.checkerRun() == null ? null : in.checkerRun().details());
        report.put("analysis", analysis(in.outcome(), in.workingCopy()));
        report.put("coverage", in.checkerRun() == null ? null : coverage(in.checkerRun().coverage()));
        return report;
    }

    private static Map<String, Object> configuration(AnalysisConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checker", c.checkerId());
        m.put("checkerHome", c.checkerHome().toString());
        m.put("targetProject", c.targetProject().toString());
        m.put("sourceRoots", c.sourceRoots().stream().map(Path::toString).toList());
        m.put("classpath", c.classpath().stream()
                .map(e -> (e.relativeToTarget() ? "<target>/" : "") + e.path()).toList());
        m.put("checkerJavaHome", c.checkerJavaHome().toString());
        m.put("javacRelease", c.javacRelease().isPresent() ? c.javacRelease().getAsInt() : null);
        m.put("timeoutSeconds", c.timeout().toSeconds());
        m.put("outputDirectory", c.outputDirectory().toString());
        return m;
    }

    private static Map<String, Object> isolation(WorkingCopy copy, AnalysisConfig config) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("originalProject", config.targetProject().toString());
        if (copy == null) {
            m.put("workingCopy", null);
            return m;
        }
        m.put("workingCopy", copy.root().toString());
        m.put("mechanism", copy.mechanism());
        m.put("gitHead", copy.gitHead().orElse(null));
        m.put("gitStatusEntries", copy.gitStatusEntries().orElse(null));
        m.put("filesCopied", copy.filesCopied());
        m.put("skippedSymlinks", copy.skippedSymlinks());
        m.put("skippedOther", copy.skippedOther());
        return m;
    }

    private static Map<String, Object> command(RecordedCommand rc, Path runDir) {
        CommandResult r = rc.result();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("purpose", rc.purpose());
        m.put("command", r.spec().command());
        m.put("workingDirectory", r.spec().workingDirectory().toString());
        m.put("environmentOverrides", new TreeMap<>(r.spec().environment()));
        m.put("exitCode", r.exitCode().isPresent() ? r.exitCode().getAsInt() : null);
        m.put("durationMillis", r.duration().toMillis());
        m.put("timedOut", r.timedOut());
        m.put("timeoutSeconds", r.spec().timeout().toSeconds());
        m.put("startFailure", r.startFailure().orElse(null));
        m.put("stdoutFile", runDir.relativize(rc.stdoutFile()).toString());
        m.put("stderrFile", runDir.relativize(rc.stderrFile()).toString());
        return m;
    }

    private static Map<String, Object> analysis(CheckerOutcome outcome, WorkingCopy copy) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean completed = outcome instanceof CheckerOutcome.CompletedAnalysis;
        m.put("complete", completed);
        m.put("findingsComplete", completed);
        if (outcome instanceof ExecutionFailure f) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("kind", f.kind().name());
            failure.put("reasons", f.reasons());
            m.put("failure", failure);
        } else {
            m.put("failure", null);
        }
        m.put("findingCount", outcome.findings().size());
        m.put("severityCounts", severityCounts(outcome.findings()));
        Path root = copy == null ? null : copy.root();
        m.put("findings", outcome.findings().stream().map(d -> diagnostic(d, root)).toList());
        m.put("otherDiagnostics", outcome.otherDiagnostics().stream().map(d -> diagnostic(d, root)).toList());
        return m;
    }

    /** Counts of findings per severity label, in severity order. */
    public static Map<String, Integer> severityCounts(List<Diagnostic> findings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Diagnostic d : findings) {
            counts.merge(d.severity().label(), 1, Integer::sum);
        }
        return counts;
    }

    /** Maps a diagnostic path in the isolated copy back to a target-relative path. */
    public static Optional<String> targetRelativePath(String reportedPath, Path workingCopyRoot) {
        if (workingCopyRoot == null) {
            return Optional.empty();
        }
        try {
            Path p = Path.of(reportedPath);
            Path absolute = p.isAbsolute() ? p.normalize() : workingCopyRoot.resolve(p).normalize();
            if (absolute.startsWith(workingCopyRoot)) {
                return Optional.of(workingCopyRoot.relativize(absolute).toString());
            }
        } catch (InvalidPathException e) {
            // fall through
        }
        return Optional.empty();
    }

    private static Map<String, Object> diagnostic(Diagnostic d, Path root) {
        Map<String, Object> m = new LinkedHashMap<>();
        Optional<String> relative = targetRelativePath(d.sourcePath(), root);
        m.put("targetPath", relative.orElse(null));
        m.put("isolatedPath", relative.isPresent() && root != null
                ? root.resolve(relative.get()).toString() : d.sourcePath());
        m.put("reportedPath", d.sourcePath());
        m.put("line", d.line());
        m.put("column", d.column().isPresent() ? d.column().getAsInt() : null);
        m.put("severity", d.severity().label());
        m.put("checker", d.checkerName().orElse(null));
        m.put("message", d.message());
        m.put("sourceSnippet", d.sourceSnippet().orElse(null));
        return m;
    }

    private static Map<String, Object> coverage(Coverage c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("analyzedSourceRoots", c.analyzedSourceRoots());
        m.put("analyzedSourceFiles", c.analyzedSourceFiles());
        m.put("javaFilesOutsideSourceRoots", c.javaFilesOutsideSourceRoots());
        m.put("limitations", c.limitations());
        return m;
    }
}
