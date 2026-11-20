package generalannotator.cli;

import generalannotator.evaluation.WarningIdMigration;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * {@code migrate-warning-ids <evaluation directory>}: convert an evaluation written with candidate-local
 * introduced-warning IDs ({@code A0003-N1}) to round-sequenced IDs ({@code W1001, ...}), keeping a backup
 * ({@link WarningIdMigration}). Neither inference nor the checker is run.
 *
 * <p>Exit codes: 0 migrated, or already current (nothing changed); 2 not an evaluation directory;
 * 3 refused (the round cannot be established, overflow, inconsistent records) or another process is
 * writing the directory; 4 internal error.
 */
public final class MigrateWarningIdsCommand {

    private final String argument;
    private final Path workingDirectory;
    private final Clock clock;
    private final PrintStream out;
    private final PrintStream err;

    public MigrateWarningIdsCommand(String argument, Path workingDirectory, Clock clock, PrintStream out,
            PrintStream err) {
        this.argument = argument;
        this.workingDirectory = workingDirectory;
        this.clock = clock;
        this.out = out;
        this.err = err;
    }

    public ExitCode run() {
        Path dir;
        try {
            dir = workingDirectory.resolve(argument).normalize();
        } catch (InvalidPathException e) {
            return usage("'" + argument + "' is not a valid path");
        }
        if (!Files.isDirectory(dir)) {
            return usage(dir + " is not an evaluation directory (runs/<run>/evaluations/<id>)");
        }
        WarningIdMigration.Result result;
        try {
            result = WarningIdMigration.migrate(dir, clock);
        } catch (IOException | RuntimeException e) {
            err.println("GeneralAnnotator: migration failed: " + e.getMessage());
            err.println("Files changed so far can be restored from the backup; running the command again completes an "
                    + "interrupted migration.");
            return ExitCode.INTERNAL_ERROR;
        }
        out.println("GeneralAnnotator migrate-warning-ids " + dir);
        switch (result.status()) {
            case CURRENT -> {
                out.println("  " + result.message());
                return ExitCode.COMPLETED;
            }
            case BUSY, REFUSED -> {
                if (result.message().contains("not an evaluation directory")) {
                    return usage(result.message());
                }
                err.println("GeneralAnnotator: migration refused: " + result.message());
                return ExitCode.EXECUTION_FAILURE;
            }
            case MIGRATED -> { }
        }
        out.println("  " + result.message());
        for (Map.Entry<String, String> e : result.renames().entrySet()) {
            out.println("    " + e.getKey() + " -> " + e.getValue());
        }
        result.backup().ifPresent(b -> out.println("  backup:    " + b));
        out.println("  Baseline and annotation IDs are unchanged. The SVG was removed because it showed the old IDs;");
        out.println("  render it again with: visualize '" + dir + "'");
        return ExitCode.COMPLETED;
    }

    private ExitCode usage(String problem) {
        err.println("GeneralAnnotator: cannot migrate warning IDs: " + problem);
        return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
    }
}
