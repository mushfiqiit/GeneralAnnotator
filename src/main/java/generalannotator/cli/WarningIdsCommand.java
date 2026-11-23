package generalannotator.cli;

import generalannotator.evaluation.EvaluationFiles;
import generalannotator.evaluation.RegistryMigration;
import generalannotator.evaluation.WarningIntegrity;
import generalannotator.evaluation.WarningRegistry;
import generalannotator.evaluation.matching.WarningMatcher;
import generalannotator.propagation.NullabilityPropagation;
import generalannotator.propagation.PropagationStage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;

/**
 * {@code warning-ids check|rebuild|migrate <run>}: the run's warning registry ({@link WarningRegistry}).
 * <ul>
 *   <li>{@code check}: the integrity check ({@link WarningIntegrity}); exit 0 consistent, 3 problems found.
 *   <li>{@code rebuild}: rebuilds {@value WarningRegistry#FILE} from the saved artifacts (deterministic; refuses a
 *       legacy run whose artifacts reuse IDs); exit 0, or 3 refused.
 *   <li>{@code migrate}: migrates a legacy run ({@link RegistryMigration}) under the pipeline lock; no checker,
 *       LLM or inference runs. Exit 0 migrated (or already consistent), 3 refused or restored after a failed
 *       validation.
 * </ul>
 * Exit 2 for an invalid directory, 4 for an internal error.
 */
public final class WarningIdsCommand {

    private WarningIdsCommand() {}

    @SuppressWarnings("try")
    public static ExitCode run(String action, String argument, Path workingDirectory, WarningMatcher matcher,
            Clock clock, PrintStream out, PrintStream err) {
        Path dir;
        try {
            dir = workingDirectory.resolve(argument).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            err.println("GeneralAnnotator: '" + argument + "' is not a valid path");
            return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
        }
        if (!Files.isRegularFile(dir.resolve("report.json"))) {
            err.println("GeneralAnnotator: " + dir + " is not a run directory (runs/<run id>)");
            return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
        }
        try {
            switch (action) {
                case "check" -> {
                    out.println("GeneralAnnotator warning-ids check " + dir);
                    return print(WarningIntegrity.check(dir), out);
                }
                case "rebuild" -> {
                    out.println("GeneralAnnotator warning-ids rebuild " + dir + " (from saved artifacts only)");
                    WarningRegistry reg;
                    try {
                        reg = WarningRegistry.rebuild(dir);
                    } catch (WarningRegistry.Conflict e) {
                        out.println("  refused: " + e.getMessage());
                        return ExitCode.EXECUTION_FAILURE;
                    }
                    reg.save();
                    out.println("  " + dir.resolve(WarningRegistry.FILE) + ": " + reg.warnings().size() + " warning(s), "
                            + reg.occurrences().size() + " occurrence(s), counters " + reg.counters());
                    return ExitCode.COMPLETED;
                }
                case "migrate" -> {
                    out.println("GeneralAnnotator warning-ids migrate " + dir + " (saved artifacts only; no checker, "
                            + "LLM or inference)");
                    try (EvaluationFiles.Lock lock = EvaluationFiles.lock(dir.resolve(".pipeline"), "warning-ids "
                            + "migrate, pid " + ProcessHandle.current().pid())) {
                        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
                        RegistryMigration.Result r = RegistryMigration.migrate(dir, matcher, clock, out, stage -> {
                            try {
                                PropagationStage.rebuildGraph(stage, matcher, clock, quiet);
                            } catch (NullabilityPropagation.InvalidException e) {
                                throw new RegistryMigration.Superseded(e.getMessage());
                            }
                        });
                        if (!r.migrated()) {
                            return ExitCode.COMPLETED;
                        }
                        out.println("  renamed:    " + r.renames());
                        out.println("  ambiguities left as separate warnings: " + r.ambiguities().size());
                        r.ambiguities().forEach(a -> out.println("    " + a));
                        if (!r.ok()) {
                            out.println("  FAILED; nothing changed. Problems:");
                            r.problems().forEach(p -> out.println("    - " + p));
                            out.println("  report:     " + r.report());
                            return ExitCode.EXECUTION_FAILURE;
                        }
                        out.println("  validated:  every report, comparison and graph agrees with "
                                + dir.resolve(WarningRegistry.FILE));
                        out.println("  report:     " + r.report());
                        out.println("  Then: GeneralAnnotator visualize <evaluation or propagation directory>");
                        return ExitCode.COMPLETED;
                    } catch (EvaluationFiles.BusyException e) {
                        err.println("GeneralAnnotator: " + e.getMessage());
                        return ExitCode.EXECUTION_FAILURE;
                    }
                }
                default -> {
                    err.println("GeneralAnnotator: unknown warning-ids action '" + action + "' (check, rebuild, migrate)");
                    return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
                }
            }
        } catch (IOException | RuntimeException e) {
            err.println("GeneralAnnotator: warning-ids " + action + " failed: " + e.getMessage());
            return ExitCode.INTERNAL_ERROR;
        }
    }

    private static ExitCode print(WarningIntegrity.Report r, PrintStream out) {
        out.println("  counts:     " + r.counts());
        r.notes().forEach(n -> out.println("  note:       " + n));
        if (r.ok()) {
            out.println("  OK: one public ID names one warning in the registry, every report and every graph");
            return ExitCode.COMPLETED;
        }
        out.println("  " + r.problems().size() + " problem(s):");
        r.problems().forEach(p -> out.println("    - " + p));
        return ExitCode.EXECUTION_FAILURE;
    }
}
