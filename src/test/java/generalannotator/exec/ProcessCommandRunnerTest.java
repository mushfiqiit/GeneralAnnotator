package generalannotator.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class ProcessCommandRunnerTest {

    @TempDir Path temp;

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    private CommandSpec spec(List<String> command, Duration timeout) {
        return new CommandSpec(command, temp, Map.of(), timeout);
    }

    @Test
    void capturesBothStreamsAndExitCodeAndArgumentWithSpaces() {
        CommandResult r = runner.run(spec(TestProcess.command("echo", "a b \"c\""), Duration.ofSeconds(60)));
        assertEquals(OptionalInt.of(0), r.exitCode());
        assertEquals("out:a b \"c\"", r.stdout());
        assertEquals("err:a b \"c\"", r.stderr());
        assertTrue(r.succeeded());
        assertFalse(r.timedOut());
        assertTrue(r.duration().toNanos() > 0);
    }

    @Test
    void reportsNonZeroExit() {
        CommandResult r = runner.run(spec(TestProcess.command("exit", "3"), Duration.ofSeconds(60)));
        assertEquals(OptionalInt.of(3), r.exitCode());
        assertFalse(r.succeeded());
        assertEquals("exit 3", r.describeStatus());
    }

    @Test
    void appliesEnvironmentOverridesAndWorkingDirectoryWithSpaces() throws IOException {
        Path dir = Files.createDirectories(temp.resolve("dir with spaces"));
        CommandResult env = runner.run(new CommandSpec(
                TestProcess.command("env", "GA_TEST_VALUE"), dir, Map.of("GA_TEST_VALUE", "v 1"),
                Duration.ofSeconds(60)));
        assertEquals("v 1", env.stdout());
        CommandResult cwd = runner.run(new CommandSpec(
                TestProcess.command("cwd"), dir, Map.of(), Duration.ofSeconds(60)));
        assertEquals(dir.toRealPath(), Path.of(cwd.stdout()).toRealPath());
    }

    @Test
    @Timeout(60)
    void largeOutputOnBothStreamsDoesNotDeadlock() {
        CommandResult r = runner.run(spec(TestProcess.command("flood"), Duration.ofSeconds(50)));
        assertEquals(OptionalInt.of(0), r.exitCode());
        assertEquals(2048 * 1024, r.stdout().length());
        assertEquals(2048 * 1024, r.stderr().length());
    }

    @Test
    @Timeout(60)
    void timeoutKillsProcessAndKeepsPartialOutput() {
        CommandResult r = runner.run(spec(TestProcess.command("sleep", "120000"), Duration.ofSeconds(3)));
        assertTrue(r.timedOut());
        assertEquals(OptionalInt.empty(), r.exitCode());
        assertFalse(r.succeeded());
        assertTrue(r.stdout().startsWith("started"), r.stdout());
        assertTrue(r.duration().compareTo(Duration.ofSeconds(50)) < 0);
    }

    @Test
    void startFailureIsReportedNotThrown() {
        CommandResult r = runner.run(spec(
                List.of(temp.resolve("no such program").toString()), Duration.ofSeconds(10)));
        assertTrue(r.startFailure().isPresent());
        assertEquals(OptionalInt.empty(), r.exitCode());
        assertFalse(r.succeeded());
        assertTrue(r.describeStatus().startsWith("failed to start"));
    }

    @Test
    void recordedCommandSavesRawOutput() throws IOException {
        CommandResult r = runner.run(spec(TestProcess.command("echo", "z"), Duration.ofSeconds(60)));
        RecordedCommand rec = RecordedCommand.save(temp.resolve("logs dir"), "probe", r);
        assertEquals("out:z", Files.readString(rec.stdoutFile()));
        assertEquals("err:z", Files.readString(rec.stderrFile()));
    }
}
