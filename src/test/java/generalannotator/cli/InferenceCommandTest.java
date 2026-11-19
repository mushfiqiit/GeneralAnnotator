package generalannotator.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.exec.CommandResult;
import generalannotator.exec.CommandRunner;
import generalannotator.exec.CommandSpec;
import generalannotator.inference.InferenceSettings;
import generalannotator.inference.SubmissionPreparer;
import generalannotator.inference.remote.SshCluster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code inference connect} and the exit codes of {@code inference} operations, with recorded
 * commands instead of ssh. Nothing contacts a cluster.
 */
class InferenceCommandTest {

    @TempDir Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    /** Exit codes of successive {@code ssh -O check} calls; other commands exit 0. */
    private final List<Integer> checks = new ArrayList<>();
    private final List<List<String>> commands = new ArrayList<>();
    private final List<List<String>> terminal = new ArrayList<>();
    private int terminalExit = 0;
    private Path shortDir;

    @AfterEach
    void removeShortDir() throws IOException {
        if (shortDir != null) {
            try (Stream<Path> walk = Files.walk(shortDir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
    }

    private final CommandRunner runner = spec -> {
        commands.add(spec.command());
        int exit = spec.command().contains("-O") && !checks.isEmpty() ? checks.remove(0) : 0;
        return result(spec, exit, exit == 0 ? "Master running (pid=7)" : "Control socket connect: No such file");
    };

    private final CommandRunner terminalRunner = spec -> {
        terminal.add(spec.command());
        return result(spec, terminalExit, "");
    };

    private static CommandResult result(CommandSpec spec, int exit, String stderr) {
        return new CommandResult(spec, OptionalInt.of(exit), "", stderr, Duration.ZERO, false, Optional.empty());
    }

    private ExitCode run(Map<String, String> env, boolean interactive, String... args) {
        Function<generalannotator.inference.remote.SshConnection, generalannotator.inference.remote.Cluster> factory =
                SshCluster::new;
        return new InferenceCommand(List.of(args), env, temp, runner, terminalRunner, interactive,
                PrepareInferenceCommandTest.CLOCK, d -> { }, factory, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run();
    }

    /** Managed mode needs a short socket directory; the JUnit temp path is too long for a socket name. */
    private Map<String, String> managed() throws IOException {
        shortDir = Files.createTempDirectory(Path.of("/tmp"), "ga");
        return Map.of("GA_SSH_CONTROL_DIR", shortDir.resolve("s").toString(), "HOME", temp.toString());
    }

    private String text() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void connectReusesARunningMasterWithoutAnyLogin() {
        checks.add(0);
        assertEquals(ExitCode.COMPLETED, run(Map.of("GA_SSH_CONTROL_PATH", "/home/u/.ssh/cm"), true, "connect"));
        assertTrue(text().contains("Reusing the SSH master"), text());
        assertEquals(List.of(), terminal);
        assertEquals(List.of("ssh", "-o", "ControlPath=/home/u/.ssh/cm", "-O", "check", "mc2399@wulver.njit.edu"),
                commands.get(0));
    }

    @Test
    void connectOpensOneManagedMasterInThePrivateDirectoryThroughTheTerminal() throws IOException {
        Map<String, String> env = managed();
        checks.add(0); // after the login; before it the socket directory does not exist, so ssh is not asked
        assertEquals(ExitCode.COMPLETED, run(env, true, "connect"), text());
        Path dir = shortDir.resolve("s");
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
        assertEquals(1, terminal.size(), "exactly one interactive login");
        assertEquals(List.of("ssh", "-M", "-N", "-f", "-o", "ControlPath=" + dir + "/%C", "-o", "ControlPersist=4h",
                "-o", "ConnectTimeout=30", "mc2399@wulver.njit.edu"), terminal.get(0));
        assertTrue(text().contains("does not see or store what you type"), text());
        assertTrue(text().contains("-O exit"), text());
        for (List<String> c : commands) {
            assertFalse(c.contains("-M"), "only the terminal command creates a master: " + c);
        }
    }

    @Test
    void connectRefusesAnInsecureSocketDirectory() throws IOException {
        Map<String, String> env = managed();
        Path dir = Files.createDirectory(shortDir.resolve("s"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        checks.add(255);
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(env, true, "connect"));
        assertTrue(text().contains("must be private"), text());
        assertEquals(List.of(), terminal);
    }

    @Test
    void withoutATerminalConnectPrintsTheExactLoginCommand() throws IOException {
        Map<String, String> env = managed();
        checks.add(255);
        assertEquals(ExitCode.RECONNECT_REQUIRED, run(env, false, "connect"));
        assertTrue(text().contains("ssh -M -N -f -o ControlPath=" + shortDir.resolve("s") + "/%C"), text());
        assertTrue(text().contains("installDist"), text());
        assertEquals(List.of(), terminal);
    }

    @Test
    void failedLoginAsksForReconnectionOnce() throws IOException {
        Map<String, String> env = managed();
        checks.add(255);
        checks.add(255);
        terminalExit = 255;
        assertEquals(ExitCode.RECONNECT_REQUIRED, run(env, true, "connect"));
        assertEquals(1, terminal.size(), "no repeated prompting");
    }

    @Test
    void userOwnedConnectionsAreOnlyReused() {
        checks.add(255);
        assertEquals(ExitCode.RECONNECT_REQUIRED, run(Map.of("GA_SSH_CONTROL_PATH", "config", "GA_SSH_TARGET",
                "wulver"), true, "connect"));
        assertTrue(text().contains("only reuses a connection you own"), text());
        assertTrue(text().contains("ssh -M -N -f wulver"), text());
        assertEquals(List.of(), terminal);
    }

    @Test
    void operationsWithoutAConnectionExitWithFiveAndRunNeverPromptsWithoutATerminal() throws Exception {
        Path runDir = PrepareInferenceCommandTest.makeRun(temp.resolve("runs/run 1"), 2);
        List<String> problems = new ArrayList<>();
        Path submission = SubmissionPreparer.prepare(runDir, Optional.empty(),
                InferenceSettings.fromEnvironment(Map.of(), problems), PrepareInferenceCommandTest.CLOCK).directory();
        Map<String, String> env = new HashMap<>(Map.of("GA_SSH_CONTROL_PATH", "/home/u/.ssh/cm"));
        for (int i = 0; i < 4; i++) {
            checks.add(255);
        }
        assertEquals(ExitCode.RECONNECT_REQUIRED, run(env, false, "upload", temp.relativize(submission).toString()));
        assertEquals(ExitCode.RECONNECT_REQUIRED, run(env, false, "collect", submission.toString()));
        assertEquals(ExitCode.RECONNECT_REQUIRED, run(env, false, "run", submission.toString()));
        assertEquals(List.of(), terminal);
        assertTrue(Files.exists(submission.resolve("remote/lock")));
        assertFalse(Files.exists(submission.resolve("remote/state.json")), "nothing happened remotely");
        assertTrue(commands.stream().allMatch(c -> c.contains("-O")), "only connection checks ran: " + commands);
    }

    @Test
    void rejectsBadArgumentsAndSettings() throws IOException {
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), true));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), true, "upload"));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), true, "cancel", "x"));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), true, "status", "missing"));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), true, "attach", "x"));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), true, "connect", "extra"));
        Files.createDirectories(temp.resolve("not-a-submission"));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of("GA_SSH_CONTROL_PATH", "/s"), true, "status",
                "not-a-submission"));
        assertTrue(text().contains("not a prepared submission"), text());
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of("GA_REMOTE_BASE", "relative"), true,
                "connect"));
        assertEquals(List.of(), terminal);
    }
}
