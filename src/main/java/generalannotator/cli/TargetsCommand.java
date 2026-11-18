package generalannotator.cli;

import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.report.JsonParser;
import generalannotator.targets.TargetExtractor;
import generalannotator.targets.WarningTarget;
import generalannotator.targets.WarningTargetsJson;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * {@code targets <run directory>}: determine the Specimin target member of every warning of an
 * existing run, without rerunning the checker or running Specimin.
 *
 * <p>Reads {@code warning-graph.json}, the configured source roots and {@code --release} from {@code
 * report.json}, and resolves against the run's own isolated snapshot ({@code workspace/target}).
 * Writes {@code warning-targets.json} beside the graph and prints the mapping. Exit codes: 0 written
 * (even if some warnings are unresolved); 2 bad argument or missing/invalid run files; 4 internal error.
 */
public final class TargetsCommand {

    private final String argument;
    private final Path workingDirectory;
    private final PrintStream out;
    private final PrintStream err;

    public TargetsCommand(String argument, Path workingDirectory, PrintStream out, PrintStream err) {
        this.argument = argument;
        this.workingDirectory = workingDirectory;
        this.out = out;
        this.err = err;
    }

    /** {@code N resolved, M unresolved}. */
    public static String summary(List<WarningTarget> targets) {
        long resolved = targets.stream().filter(WarningTarget.Resolved.class::isInstance).count();
        return resolved + " resolved, " + (targets.size() - resolved) + " unresolved";
    }

    public ExitCode run() {
        Path runDir;
        try {
            runDir = workingDirectory.resolve(argument).normalize();
        } catch (InvalidPathException e) {
            return usage("'" + argument + "' is not a valid path.");
        }
        if (Files.isRegularFile(runDir) && runDir.getFileName().toString().equals(WarningGraphJson.FILE_NAME)) {
            runDir = runDir.getParent();
        }
        if (!Files.isDirectory(runDir)) {
            return usage(runDir + " is not a run directory. Pass runs/<run id> (list them with: ls runs).");
        }
        Path graphFile = runDir.resolve(WarningGraphJson.FILE_NAME);
        Path reportFile = runDir.resolve("report.json");
        Path copyRoot = runDir.resolve("workspace").resolve("target");
        if (!Files.isRegularFile(graphFile)) {
            return usage("No warning graph at " + graphFile + " (failed runs have none).");
        }
        if (!Files.isRegularFile(reportFile)) {
            return usage("No report.json in " + runDir + "; it is needed for the configured source roots.");
        }
        if (!Files.isDirectory(copyRoot)) {
            return usage("The run's isolated working copy " + copyRoot + " is missing; targets must be resolved "
                    + "against the snapshot that produced the warnings.");
        }

        WarningGraph graph;
        List<Path> sourceRoots = new ArrayList<>();
        OptionalInt release = OptionalInt.empty();
        try {
            graph = WarningGraphJson.load(graphFile);
            Object report = JsonParser.parse(Files.readString(reportFile, StandardCharsets.UTF_8));
            if (!(report instanceof Map<?, ?> r) || !(r.get("configuration") instanceof Map<?, ?> config)
                    || !(config.get("sourceRoots") instanceof List<?> roots)) {
                return usage(reportFile + " has no configuration.sourceRoots.");
            }
            for (Object root : roots) {
                sourceRoots.add(Path.of(String.valueOf(root)));
            }
            if (config.get("javacRelease") instanceof Long value) {
                release = OptionalInt.of(Math.toIntExact(value));
            }
        } catch (IOException | IllegalArgumentException e) {
            return usage("Cannot read the run files: " + e.getMessage());
        }

        List<WarningTarget> targets = new TargetExtractor(copyRoot, sourceRoots, release).extract(graph);
        Path targetsFile = runDir.resolve(WarningTargetsJson.FILE_NAME);
        try {
            WarningTargetsJson.save(targetsFile, graph.runId(), copyRoot, targets);
        } catch (IOException e) {
            err.println("GeneralAnnotator: cannot write " + targetsFile + ": " + e);
            return ExitCode.INTERNAL_ERROR;
        }

        out.println("GeneralAnnotator targets");
        out.println("  graph:   " + graphFile + " (run " + graph.runId() + ", " + graph.nodes().size()
                + " warning node(s))");
        out.println("  targets: " + targetsFile + " (" + summary(targets) + ")");
        out.println("  Specimin was not run; a target does not establish that a slice reproduces the warning.");
        out.println();
        for (WarningTarget target : targets) {
            out.println(line(target));
            target.notes().forEach(n -> out.println("         note: " + n));
        }
        return ExitCode.COMPLETED;
    }

    /** One mapping line, for example {@code W0001  FIELD        a.B#f  [src: a/B.java]}. */
    static String line(WarningTarget target) {
        return switch (target) {
            case WarningTarget.Resolved r -> String.format("%s  %-11s  %s  [root %s, file %s%s]", r.warningId(),
                    r.memberKind(), r.target(), r.sourceRoot(), r.targetFile(),
                    r.requiresDisableRootValidation() ? ", needs --disable-root-validation" : "");
            case WarningTarget.Unresolved u -> String.format("%s  %-11s  %s (at %s)", u.warningId(), "UNRESOLVED",
                    u.reason(), u.location());
        };
    }

    private ExitCode usage(String message) {
        err.println("GeneralAnnotator: " + message);
        return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
    }
}
