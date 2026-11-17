package generalannotator.exec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A command result together with its purpose and the files its raw output was saved to.
 *
 * @param purpose short identifier such as {@code nullaway-setup} or {@code javac}
 * @param result the command result
 * @param stdoutFile where stdout was saved
 * @param stderrFile where stderr was saved
 */
public record RecordedCommand(String purpose, CommandResult result, Path stdoutFile, Path stderrFile) {

    public RecordedCommand {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(stdoutFile, "stdoutFile");
        Objects.requireNonNull(stderrFile, "stderrFile");
    }

    /** Saves stdout and stderr as {@code <purpose>.stdout.txt} / {@code .stderr.txt} in {@code logsDir}. */
    public static RecordedCommand save(Path logsDir, String purpose, CommandResult result) throws IOException {
        Files.createDirectories(logsDir);
        Path out = logsDir.resolve(purpose + ".stdout.txt");
        Path err = logsDir.resolve(purpose + ".stderr.txt");
        Files.writeString(out, result.stdout(), StandardCharsets.UTF_8);
        Files.writeString(err, result.stderr(), StandardCharsets.UTF_8);
        return new RecordedCommand(purpose, result, out, err);
    }
}
