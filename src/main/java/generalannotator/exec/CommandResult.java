package generalannotator.exec;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What happened when a {@link CommandSpec} was run.
 *
 * @param spec the command that was run
 * @param exitCode the exit code; empty if the process could not start or was killed on timeout
 * @param stdout captured standard output (UTF-8 decoded)
 * @param stderr captured standard error (UTF-8 decoded)
 * @param duration wall-clock duration
 * @param timedOut whether the process was killed because it exceeded the timeout
 * @param startFailure why the process could not be started, if it could not
 */
public record CommandResult(
        CommandSpec spec,
        OptionalInt exitCode,
        String stdout,
        String stderr,
        Duration duration,
        boolean timedOut,
        Optional<String> startFailure) {

    public CommandResult {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(exitCode, "exitCode");
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(startFailure, "startFailure");
    }

    /** The process started, finished within the timeout, and exited with code 0. */
    public boolean succeeded() {
        return !timedOut && startFailure.isEmpty() && exitCode.isPresent() && exitCode.getAsInt() == 0;
    }

    /** Short human-readable status, for example {@code exit 1}, {@code timed out}. */
    public String describeStatus() {
        if (startFailure.isPresent()) {
            return "failed to start: " + startFailure.get();
        }
        if (timedOut) {
            return "timed out after " + spec.timeout().toSeconds() + "s";
        }
        return "exit " + exitCode.orElseThrow();
    }
}
