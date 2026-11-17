package generalannotator.exec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

/**
 * {@link CommandRunner} backed by {@link ProcessBuilder}.
 *
 * <p>stdout and stderr are drained concurrently on separate threads so a process that fills one
 * pipe cannot deadlock. On timeout the process and all of its descendants are destroyed forcibly.
 * stdin is closed immediately.
 */
public final class ProcessCommandRunner implements CommandRunner {

    /** How long to wait for a killed process and its output readers to finish. */
    private static final Duration KILL_GRACE = Duration.ofSeconds(10);

    @Override
    public CommandResult run(CommandSpec spec) {
        ProcessBuilder builder = new ProcessBuilder(spec.command())
                .directory(spec.workingDirectory().toFile())
                .redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        builder.environment().putAll(spec.environment());

        long start = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException | RuntimeException e) {
            return new CommandResult(spec, OptionalInt.empty(), "", "", elapsed(start), false,
                    Optional.of(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        StreamCollector stdout = StreamCollector.start(process.getInputStream(), "stdout");
        StreamCollector stderr = StreamCollector.start(process.getErrorStream(), "stderr");

        boolean finished;
        boolean interrupted = false;
        try {
            finished = process.waitFor(spec.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
            finished = false;
        }
        if (!finished) {
            destroyTree(process);
        }
        String out = stdout.await();
        String err = stderr.await();
        Duration duration = elapsed(start);
        if (interrupted) {
            Thread.currentThread().interrupt();
            return new CommandResult(spec, OptionalInt.empty(), out, err, duration, false,
                    Optional.of("interrupted while waiting for the process; it was killed"));
        }
        if (!finished) {
            return new CommandResult(spec, OptionalInt.empty(), out, err, duration, true, Optional.empty());
        }
        return new CommandResult(
                spec, OptionalInt.of(process.exitValue()), out, err, duration, false, Optional.empty());
    }

    private static void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name", "").startsWith("Windows") ? "NUL" : "/dev/null");
    }

    /** Drains one stream on its own thread. */
    private static final class StreamCollector {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final Thread thread;
        private volatile IOException failure;

        private StreamCollector(InputStream in, String name) {
            this.thread = Thread.ofPlatform().daemon().name("command-" + name).unstarted(() -> {
                try (in) {
                    in.transferTo(buffer);
                } catch (IOException e) {
                    failure = e;
                }
            });
        }

        static StreamCollector start(InputStream in, String name) {
            StreamCollector collector = new StreamCollector(in, name);
            collector.thread.start();
            return collector;
        }

        String await() {
            try {
                thread.join(KILL_GRACE.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String text;
            synchronized (buffer) {
                text = buffer.toString(StandardCharsets.UTF_8);
            }
            if (thread.isAlive()) {
                text += "\n[GeneralAnnotator: output capture did not finish; stream may be incomplete]\n";
            } else if (failure != null) {
                text += "\n[GeneralAnnotator: output capture failed: " + failure.getMessage() + "]\n";
            }
            return text;
        }
    }
}
