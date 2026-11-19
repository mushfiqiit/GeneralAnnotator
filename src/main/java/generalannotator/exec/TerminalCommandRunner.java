package generalannotator.exec;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

/**
 * {@link CommandRunner} whose process shares this process's terminal: stdin, stdout and stderr are
 * inherited, so a program such as {@code ssh} can ask the user for a password or a second factor
 * directly. Nothing the user types or the process prints passes through GeneralAnnotator; the
 * returned {@link CommandResult} therefore has empty stdout and stderr. On timeout the process and
 * its descendants are destroyed.
 */
public final class TerminalCommandRunner implements CommandRunner {

    @Override
    public CommandResult run(CommandSpec spec) {
        ProcessBuilder builder = new ProcessBuilder(spec.command())
                .directory(spec.workingDirectory().toFile())
                .inheritIO();
        builder.environment().putAll(spec.environment());
        long start = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException | RuntimeException e) {
            return new CommandResult(spec, OptionalInt.empty(), "", "", elapsed(start), false,
                    Optional.of(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
        try {
            if (!process.waitFor(spec.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return new CommandResult(spec, OptionalInt.empty(), "", "", elapsed(start), true, Optional.empty());
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new CommandResult(spec, OptionalInt.empty(), "", "", elapsed(start), false,
                    Optional.of("interrupted while waiting for the process; it was killed"));
        }
        return new CommandResult(spec, OptionalInt.of(process.exitValue()), "", "", elapsed(start), false,
                Optional.empty());
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
