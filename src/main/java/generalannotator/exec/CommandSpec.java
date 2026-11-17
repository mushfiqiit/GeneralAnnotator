package generalannotator.exec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An external command to run: an argument list (never a shell string), a working directory,
 * environment overrides applied on top of the inherited environment, and a timeout.
 */
public record CommandSpec(
        List<String> command, Path workingDirectory, Map<String, String> environment, Duration timeout) {

    public CommandSpec {
        command = List.copyOf(command);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        environment = Map.copyOf(environment);
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
    }
}
