package generalannotator.inference.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.exec.CommandResult;
import generalannotator.exec.CommandRunner;
import generalannotator.exec.CommandSpec;
import generalannotator.inference.remote.SchedulerSnapshot.Category;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** ssh/rsync command lines and output parsing of {@link SshCluster}, with a scripted command runner. */
class SshClusterTest {

    static final String TARGET = "mc2399@wulver.njit.edu";
    static final String DIR = "/project/mjk76/mc2399/generalannotator/inference/run_1/20261119T120000Z-inference";

    /** Answers commands in order and records them. */
    static final class ScriptedRunner implements CommandRunner {
        final List<CommandSpec> specs = new ArrayList<>();
        final Deque<CommandResult> answers = new ArrayDeque<>();

        ScriptedRunner answer(int exit, String stdout, String stderr) {
            answers.add(result(OptionalInt.of(exit), stdout, stderr, false));
            return this;
        }

        ScriptedRunner timeout() {
            answers.add(result(OptionalInt.empty(), "", "", true));
            return this;
        }

        @Override
        public CommandResult run(CommandSpec spec) {
            specs.add(spec);
            CommandResult r = answers.isEmpty() ? result(OptionalInt.of(0), "", "", false) : answers.poll();
            return new CommandResult(spec, r.exitCode(), r.stdout(), r.stderr(), r.duration(), r.timedOut(),
                    r.startFailure());
        }

        private static CommandResult result(OptionalInt exit, String out, String err, boolean timedOut) {
            CommandSpec dummy = new CommandSpec(List.of("x"), Path.of("."), Map.of(), Duration.ofSeconds(1));
            return new CommandResult(dummy, exit, out, err, Duration.ZERO, timedOut, Optional.empty());
        }

        List<String> last() {
            return specs.get(specs.size() - 1).command();
        }
    }

    static RemoteSettings settings(RemoteSettings.ControlMode mode) {
        return new RemoteSettings(TARGET, mode,
                mode == RemoteSettings.ControlMode.EXPLICIT ? Optional.of("/home/u/.ssh/cm-wulver") : Optional.empty(),
                mode == RemoteSettings.ControlMode.MANAGED ? Optional.of(Path.of("/home/u/.ga-ssh")) : Optional.empty(),
                "4h", 15, 30, Duration.ofSeconds(300), Duration.ofSeconds(3600), Duration.ofSeconds(600),
                "/project/mjk76/mc2399", "generalannotator/inference", Duration.ofSeconds(300), 3, 100001, "ssh",
                "rsync");
    }

    private static SshCluster cluster(RemoteSettings.ControlMode mode, ScriptedRunner runner) {
        return new SshCluster(new SshConnection(settings(mode), runner, Path.of("/work")));
    }

    @Test
    void everyNonInteractiveCommandReusesTheSocketInBatchModeAndNothingDeletes() throws Exception {
        ScriptedRunner runner = new ScriptedRunner().answer(0, "motd banner\nGA-PROBE ABSENT\n", "");
        SshCluster c = cluster(RemoteSettings.ControlMode.MANAGED, runner);
        assertEquals(Cluster.DirectoryProbe.Kind.ABSENT, c.probe(DIR).kind());
        List<String> ssh = runner.last();
        assertEquals(List.of("ssh", "-o", "ControlPath=/home/u/.ga-ssh/%C", "-o", "ControlMaster=no", "-o",
                "BatchMode=yes", "-o", "ProxyCommand=false", "-o", "ConnectTimeout=30", "-T", TARGET, "bash", "-l",
                "-c"), ssh.subList(0, 16));
        assertEquals(17, ssh.size(), "the script is one quoted word");
        assertTrue(ssh.get(16).startsWith("'") && ssh.get(16).contains("d='\\''" + DIR + "'\\''"),
                ssh.get(16));

        c.upload(Path.of("/local/sub"), List.of("submission.json", "prompts/W0001.txt"), DIR);
        List<String> up = runner.last();
        String shell = "ssh -o ControlPath=/home/u/.ga-ssh/%C -o ControlMaster=no -o BatchMode=yes "
                + "-o ProxyCommand=false -o ConnectTimeout=30 -T";
        assertEquals(List.of("rsync", "-R", "-t", "--chmod=D755,F444", "-e", shell, "--", "submission.json",
                "prompts/W0001.txt", TARGET + ":" + DIR + "/"), up);
        assertEquals(Path.of("/local/sub"), runner.specs.get(1).workingDirectory());

        c.download(DIR, Path.of("/local/sub/remote/incoming"));
        assertEquals(List.of("rsync", "-r", "-t", "--exclude=*.tmp-*", "--include=/results/***", "--include=/logs/***",
                "--include=/.ga-sbatch/***", "--exclude=*", "-e", shell, "--", TARGET + ":" + DIR + "/",
                "/local/sub/remote/incoming/"), runner.last());

        for (CommandSpec spec : runner.specs) {
            String line = String.join(" ", spec.command());
            assertFalse(line.contains("--delete") || line.contains(" rm ") || line.contains("StrictHostKeyChecking")
                    || line.contains("ControlMaster=yes") || line.contains("ControlMaster=auto"), line);
        }
    }

    @Test
    void onlyTheManagedMasterCommandIsInteractive() {
        SshConnection managed = new SshConnection(settings(RemoteSettings.ControlMode.MANAGED), new ScriptedRunner(),
                Path.of("/work"));
        assertEquals(List.of("ssh", "-M", "-N", "-f", "-o", "ControlPath=/home/u/.ga-ssh/%C", "-o",
                "ControlPersist=4h", "-o", "ConnectTimeout=30", "-o", "ServerAliveInterval=15", "-o",
                "ServerAliveCountMax=3", TARGET), managed.masterCommand());
        assertFalse(managed.masterCommand().contains("BatchMode=yes"));
        assertFalse(managed.masterCommand().contains("ProxyCommand=false"), "the interactive login connects normally");
        assertEquals(List.of("ssh", "-o", "ControlPath=/home/u/.ga-ssh/%C", "-O", "check", TARGET),
                managed.checkCommand());

        SshConnection config = new SshConnection(settings(RemoteSettings.ControlMode.CONFIG), new ScriptedRunner(),
                Path.of("/work"));
        assertEquals(List.of("ssh", "-O", "check", TARGET), config.checkCommand());
        assertFalse(String.join(" ", config.remoteCommand("true")).contains("ControlPath"));
        assertTrue(config.remoteCommand("true").contains("BatchMode=yes"));
        assertThrows(IllegalStateException.class, config::masterCommand);

        SshConnection explicit = new SshConnection(settings(RemoteSettings.ControlMode.EXPLICIT), new ScriptedRunner(),
                Path.of("/work"));
        assertTrue(explicit.remoteCommand("true").contains("ControlPath=/home/u/.ssh/cm-wulver"));
        assertThrows(IllegalStateException.class, explicit::masterCommand);
    }

    @Test
    void checkAsksTheMasterAndNeverAuthenticates() {
        ScriptedRunner runner = new ScriptedRunner().answer(0, "", "Master running (pid=4242)\r\n")
                .answer(0, "GA-HEALTH ok\n", "")
                .answer(255, "", "Control socket connect(/home/u/.ssh/cm-wulver): No such file or directory\r\n");
        SshCluster c = cluster(RemoteSettings.ControlMode.EXPLICIT, runner);
        Cluster.ConnectionCheck alive = c.check();
        assertTrue(alive.alive());
        assertTrue(alive.detail().startsWith("Master running (pid=4242); remote round trip ok"), alive.detail());
        Cluster.ConnectionCheck gone = c.check();
        assertFalse(gone.alive());
        assertTrue(gone.detail().contains("No such file"));
        assertEquals(List.of("ssh", "-o", "ControlPath=/home/u/.ssh/cm-wulver", "-O", "check", TARGET), runner.last());

        // Managed mode without its private directory: not alive, and ssh is not even run.
        ScriptedRunner none = new ScriptedRunner();
        assertFalse(cluster(RemoteSettings.ControlMode.MANAGED, none).check().alive());
        assertEquals(List.of(), none.specs);
    }

    @Test
    void parsesSbatchOutcomes() {
        assertEquals(new SbatchOutcome.Submitted("123456", "123456"),
                SshCluster.parseSbatch("Welcome\nGA-SBATCH RC 0\nGA-OUT 123456\nGA-SBATCH END\n"));
        assertEquals("123456", ((SbatchOutcome.Submitted) SshCluster.parseSbatch(
                "GA-SBATCH RC 0\nGA-OUT 123456;wulver\nGA-SBATCH END\n")).jobId());
        SbatchOutcome rejected = SshCluster.parseSbatch("GA-SBATCH RC 1\nGA-OUT \nGA-ERR sbatch: error: Batch job "
                + "submission failed: Invalid account or account/partition combination specified\nGA-SBATCH END\n");
        assertInstanceOf(SbatchOutcome.Rejected.class, rejected);
        assertTrue(((SbatchOutcome.Rejected) rejected).reason().contains("Invalid account"));
        assertInstanceOf(SbatchOutcome.Duplicate.class, SshCluster.parseSbatch(
                "GA-SBATCH DUPLICATE\nGA-RECORD stdout: 777\nGA-RECORD exit: 0\n"));
        assertInstanceOf(SbatchOutcome.Rejected.class, SshCluster.parseSbatch(
                "GA-SBATCH NOT_RUN cannot enter the submission directory\n"));
        assertInstanceOf(SbatchOutcome.Unknown.class, SshCluster.parseSbatch(""));
        assertInstanceOf(SbatchOutcome.Unknown.class, SshCluster.parseSbatch("GA-SBATCH RC 0\nGA-SBATCH END\n"));
        assertInstanceOf(SbatchOutcome.Unknown.class, SshCluster.parseSbatch(
                "GA-SBATCH RC 0\nGA-OUT Submitted batch job 5\nGA-SBATCH END\n"));
    }

    @Test
    void sbatchWithALostConnectionOrTimeoutIsUnknown() {
        ScriptedRunner runner = new ScriptedRunner().answer(255, "", "Connection to wulver closed by remote host.")
                .timeout().answer(0, "", "Master running").answer(255, "", "Broken pipe") // health after the timeout
                .answer(0, "GA-SBATCH RC 0\nGA-OUT 99\nGA-SBATCH END\n", "");
        SshCluster c = cluster(RemoteSettings.ControlMode.EXPLICIT, runner);
        assertInstanceOf(SbatchOutcome.Unknown.class, c.sbatch(DIR, "job-001", "0-40%4", "GeneralAnnotator:s:job-001"));
        assertInstanceOf(SbatchOutcome.Unknown.class, c.sbatch(DIR, "job-002", "0-40%4", "GeneralAnnotator:s:job-002"));
        assertEquals(new SbatchOutcome.Submitted("99", "99"), c.sbatch(DIR, "job-003", "1,3-5%2", "c"));
        String script = runner.last().get(runner.last().size() - 1);
        assertTrue(script.contains("sbatch --parsable --array=1,3-5%2 --comment=c run_inference.sbatch"), script);
        assertTrue(script.contains("mkdir .ga-sbatch/job-003"), script);
        // Unsafe values never reach the cluster.
        int calls = runner.specs.size();
        assertInstanceOf(SbatchOutcome.Rejected.class, c.sbatch(DIR, "job;rm", "0", "c"));
        assertInstanceOf(SbatchOutcome.Rejected.class, c.sbatch(DIR, "job-004", "0;id", "c"));
        assertInstanceOf(SbatchOutcome.Rejected.class, c.sbatch(DIR + "/..", "job-004", "0", "c"));
        assertEquals(calls, runner.specs.size());
    }

    @Test
    void parsesSchedulerStatesAndTreatsSilenceAsUnknown() {
        String out = """
                GA-SQUEUE 0
                GA-Q 5000_3|RUNNING|None
                GA-Q 5000_5|PENDING|JobArrayTaskLimit
                GA-SACCT 0
                GA-A 5000_0|COMPLETED|0:0|00:12:01|n0042
                GA-A 5000_1|FAILED|1:0|00:01:00|n0042
                GA-A 5000_2|CANCELLED by 1234|0:15|00:00:10|n0043
                GA-A 5000_3|RUNNING|0:0|00:05:00|n0044
                GA-A 5000_[4-6%2]|PENDING|0:0|00:00:00|None assigned
                GA-END
                """;
        SchedulerSnapshot s = SshCluster.parseQuery(out);
        assertEquals(new SchedulerSnapshot.TaskState("COMPLETED", Category.TERMINAL, "sacct: exit 0:0, elapsed "
                + "00:12:01, node n0042"), s.state("5000", 0));
        assertEquals(Category.TERMINAL, s.state("5000", 1).category());
        assertEquals("CANCELLED", s.state("5000", 2).state());
        assertEquals("RUNNING", s.state("5000", 3).state());
        assertTrue(s.state("5000", 3).detail().startsWith("squeue"));
        assertEquals("PENDING", s.state("5000", 4).state());
        assertEquals("reason JobArrayTaskLimit", s.state("5000", 5).detail().substring("squeue: ".length()));
        assertEquals(Category.UNKNOWN, s.state("5000", 7).category());
        assertEquals(Category.UNKNOWN, s.state("6000", 0).category());

        // A job that left the queue: squeue's "invalid job id" is an answer, sacct has the history.
        SchedulerSnapshot gone = SshCluster.parseQuery("""
                GA-SQUEUE 1
                GA-Q slurm_load_jobs error: Invalid job id specified
                GA-SACCT 0
                GA-A 5000_0|TIMEOUT|0:0|02:00:00|n1
                GA-END
                """);
        assertTrue(gone.activeKnown());
        assertEquals(List.of(), gone.problems());
        assertEquals(Category.TERMINAL, gone.state("5000", 0).category());

        // sacct failed: tasks that squeue does not list are unknown, never finished.
        SchedulerSnapshot noHistory = SshCluster.parseQuery("""
                GA-SQUEUE 0
                GA-Q 5000_1|RUNNING|None
                GA-SACCT 1
                GA-A sacct: error: Problem talking to the database: Connection refused
                GA-END
                """);
        assertEquals(Category.ACTIVE, noHistory.state("5000", 1).category());
        assertEquals(Category.UNKNOWN, noHistory.state("5000", 0).category());
        assertTrue(noHistory.problems().get(0).contains("sacct failed"));

        // Output cut off (connection lost): everything unknown.
        SchedulerSnapshot cut = SshCluster.parseQuery("GA-SQUEUE 0\nGA-Q 5000_0|RUNNING|None\n");
        assertEquals(Category.UNKNOWN, cut.state("5000", 0).category());
        assertFalse(cut.activeKnown() || cut.historyKnown());

    }

    @Test
    void transportLossDuringASchedulerQueryIsADisconnectNotUnknownTasks() throws Exception {
        // The incident: the local master lives on, but its session request fails.
        ScriptedRunner broken = new ScriptedRunner().answer(255, "",
                "mux_client_request_session: read from master failed: Broken pipe\r\n");
        RemoteException e = assertThrows(RemoteException.class,
                () -> cluster(RemoteSettings.ControlMode.EXPLICIT, broken).query(List.of("5000")));
        assertEquals(RemoteException.Kind.DISCONNECTED, e.kind());
        assertTrue(e.getMessage().contains("Broken pipe"), e.getMessage());

        // A hanging command over a dead master: the timeout is followed by a health check that fails.
        ScriptedRunner hung = new ScriptedRunner().timeout()
                .answer(0, "", "Master running (pid=10315)").timeout();
        assertEquals(RemoteException.Kind.DISCONNECTED, assertThrows(RemoteException.class,
                () -> cluster(RemoteSettings.ControlMode.EXPLICIT, hung).query(List.of("5000"))).kind());

        // A slow scheduler over a healthy connection: unknown tasks, not a disconnect.
        ScriptedRunner slow = new ScriptedRunner().timeout()
                .answer(0, "", "Master running (pid=1)").answer(0, "GA-HEALTH ok\n", "");
        SchedulerSnapshot snapshot = cluster(RemoteSettings.ControlMode.EXPLICIT, slow).query(List.of("5000"));
        assertEquals(Category.UNKNOWN, snapshot.state("5000", 0).category());
        assertTrue(snapshot.problems().get(0).startsWith("TIMED_OUT"), snapshot.problems().toString());
    }

    @Test
    void healthNeedsARemoteRoundTripNotJustALiveLocalMaster() {
        // -O check succeeds, the remote probe fails (the incident's broken pipe).
        ScriptedRunner stale = new ScriptedRunner().answer(0, "", "Master running (pid=10315)\r\n")
                .answer(255, "", "mux_client_request_session: read from master failed: Broken pipe");
        SshConnection connection = new SshConnection(settings(RemoteSettings.ControlMode.EXPLICIT), stale,
                Path.of("/work"));
        Cluster.ConnectionCheck c = connection.health();
        assertFalse(c.alive());
        assertTrue(c.masterRunning());
        assertTrue(c.detail().contains("Broken pipe"), c.detail());
        List<String> probe = stale.last();
        assertEquals(List.of("ssh", "-o", "ControlPath=/home/u/.ssh/cm-wulver", "-o", "ControlMaster=no", "-o",
                "BatchMode=yes", "-o", "ProxyCommand=false", "-o", "ConnectTimeout=30", "-T", TARGET,
                "printf '%s-%s %s\\n' GA HEALTH ok"), probe);
        assertEquals(Duration.ofSeconds(45), stale.specs.get(1).timeout(), "bounded");

        // Exit 0 without the marker (for example a login banner only) is not healthy either.
        ScriptedRunner noMarker = new ScriptedRunner().answer(0, "", "Master running").answer(0, "Welcome\n", "");
        assertFalse(new SshConnection(settings(RemoteSettings.ControlMode.EXPLICIT), noMarker, Path.of("/w"))
                .health().alive());
        // A command that merely echoes the command text does not contain the assembled marker.
        assertFalse(String.join(" ", probe).contains(SshConnection.HEALTH_MARKER));

        ScriptedRunner healthy = new ScriptedRunner().answer(0, "", "Master running (pid=1)")
                .answer(0, "Welcome to Wulver\nGA-HEALTH ok\n", "");
        Cluster.ConnectionCheck ok = new SshConnection(settings(RemoteSettings.ControlMode.EXPLICIT), healthy,
                Path.of("/w")).health();
        assertTrue(ok.alive());

        // No master: the probe is not even attempted.
        ScriptedRunner none = new ScriptedRunner().answer(255, "", "Control socket connect: No such file");
        assertFalse(new SshConnection(settings(RemoteSettings.ControlMode.EXPLICIT), none, Path.of("/w")).health()
                .masterRunning());
        assertEquals(1, none.specs.size());
    }

    @Test
    void parsesArrayTaskFieldsAndCategorizesStates() {
        List<SchedulerSnapshot.Row> rows = SchedulerSnapshot.rows("123_[1-3,7%2]", "PENDING", "",
                SchedulerSnapshot.Source.SACCT);
        assertEquals(2, rows.size());
        assertEquals(1, rows.get(0).from());
        assertEquals(3, rows.get(0).to());
        assertEquals(7, rows.get(1).from());
        assertEquals(List.of(), SchedulerSnapshot.rows("123.batch", "COMPLETED", "", SchedulerSnapshot.Source.SACCT));
        assertEquals(List.of(), SchedulerSnapshot.rows("123", "COMPLETED", "", SchedulerSnapshot.Source.SACCT));
        assertEquals(Category.TERMINAL, SchedulerSnapshot.categorize("OUT_OF_MEMORY"));
        assertEquals(Category.ACTIVE, SchedulerSnapshot.categorize("COMPLETING"));
        assertEquals(Category.ACTIVE, SchedulerSnapshot.categorize("SOMETHING_NEW"), "unrecognized is not finished");
    }

    @Test
    void transferFailuresAreDisconnectsOnlyWhenTheMasterIsGone() {
        ScriptedRunner runner = new ScriptedRunner()
                .answer(12, "", "rsync: connection unexpectedly closed").answer(255, "", "no master")
                .answer(12, "", "rsync: connection unexpectedly closed").answer(0, "", "Master running")
                .answer(0, "GA-HEALTH ok\n", "")
                .answer(23, "", "rsync: some files could not be transferred");
        SshCluster c = cluster(RemoteSettings.ControlMode.EXPLICIT, runner);
        RemoteException lost = assertThrows(RemoteException.class,
                () -> c.upload(Path.of("/l"), List.of("submission.json"), DIR));
        assertEquals(RemoteException.Kind.DISCONNECTED, lost.kind());
        assertEquals(RemoteException.Kind.FAILED, assertThrows(RemoteException.class,
                () -> c.upload(Path.of("/l"), List.of("submission.json"), DIR)).kind());
        assertEquals(RemoteException.Kind.FAILED, assertThrows(RemoteException.class,
                () -> c.download(DIR, Path.of("/l/in"))).kind());
        assertEquals(RemoteException.Kind.TIMED_OUT, assertThrows(RemoteException.class,
                () -> cluster(RemoteSettings.ControlMode.EXPLICIT, new ScriptedRunner().timeout()
                        .answer(0, "", "Master running").answer(0, "GA-HEALTH ok\n", ""))
                        .upload(Path.of("/l"), List.of("submission.json"), DIR)).kind());
        assertEquals(RemoteException.Kind.FAILED, assertThrows(RemoteException.class,
                () -> c.upload(Path.of("/l"), List.of("../secret"), DIR)).kind());
        assertEquals(RemoteException.Kind.FAILED, assertThrows(RemoteException.class,
                () -> c.upload(Path.of("/l"), List.of(".hidden"), DIR)).kind());
    }

    @Test
    void parsesHashesAndRequiresCompleteOutput() throws Exception {
        String a = "a".repeat(64);
        ScriptedRunner runner = new ScriptedRunner()
                .answer(0, "GA-SHA " + a + "  submission.json\nGA-MISSING prompts/W0001.txt\nGA-DONE\n", "")
                .answer(0, "GA-SHA " + a + "  submission.json\n", "")
                .answer(0, "GA-NO-DIR\n", "")
                .answer(1, "", "bash: sha256sum: command not found");
        SshCluster c = cluster(RemoteSettings.ControlMode.EXPLICIT, runner);
        assertEquals(Map.of("submission.json", a), c.hashes(DIR, List.of("submission.json", "prompts/W0001.txt")));
        assertTrue(runner.last().get(runner.last().size() - 1).contains("'prompts/W0001.txt'"));
        assertEquals(RemoteException.Kind.FAILED, assertThrows(RemoteException.class,
                () -> c.hashes(DIR, List.of("submission.json"))).kind());
        assertEquals(Map.of(), c.hashes(DIR, List.of("submission.json")));
        assertEquals(RemoteException.Kind.FAILED, assertThrows(RemoteException.class,
                () -> c.hashes(DIR, List.of("submission.json"))).kind());
    }

    @Test
    void readsJobRecordsForVerification() throws Exception {
        ScriptedRunner runner = new ScriptedRunner().answer(0, """
                GA-SACCT 0
                GA-A 5000_0|ga-inference|%s/|COMPLETED
                GA-A 5000_[1-2%%2]|ga-inference|%s|PENDING
                GA-SQUEUE 0
                GA-Q 5000_1|ga-inference|%s|PENDING
                GA-END
                """.formatted(DIR, DIR, DIR), "").answer(0, "GA-SACCT 1\nGA-SQUEUE 1\nGA-END\n", "");
        SshCluster c = cluster(RemoteSettings.ControlMode.EXPLICIT, runner);
        List<Cluster.JobRecord> records = c.describe("5000");
        assertEquals(3, records.size());
        assertEquals(DIR, records.get(0).workDir(), "trailing slash removed");
        assertEquals("squeue", records.get(2).source());
        assertEquals(RemoteException.Kind.FAILED, assertThrows(RemoteException.class, () -> c.describe("5000")).kind());
        assertEquals(RemoteException.Kind.FAILED, assertThrows(RemoteException.class, () -> c.describe("5000;id"))
                .kind());
    }

    @Test
    void quotesForTheRemoteShell() {
        assertEquals("'it'\\''s'", SshConnection.quote("it's"));
        assertEquals("ssh -o ControlPath=/a/%C 'a b'", SshConnection.display(List.of("ssh", "-o",
                "ControlPath=/a/%C", "a b")));
    }

    @Test
    void readsAndValidatesRemoteSettings() {
        List<String> problems = new ArrayList<>();
        RemoteSettings s = RemoteSettings.fromEnvironment(Map.of("HOME", "/home/u"), problems);
        assertEquals(List.of(), problems);
        assertEquals(TARGET, s.sshTarget());
        assertEquals(RemoteSettings.ControlMode.MANAGED, s.controlMode());
        assertEquals(Optional.of("/home/u/.ga-ssh/%C"), s.effectiveControlPath());
        assertEquals(DIR, s.remoteDirectory("run 1", "20261119T120000Z-inference"));
        assertEquals(Duration.ofSeconds(300), s.pollInterval());
        assertEquals(100001, s.maxArraySize());

        Map<String, String> bad = new HashMap<>();
        bad.put("HOME", "/home/u");
        bad.put("GA_SSH_TARGET", "-oProxyCommand=evil");
        bad.put("GA_SSH_CONTROL_DIR", "/" + "x".repeat(60));
        bad.put("GA_REMOTE_BASE", "/project/../etc");
        bad.put("GA_REMOTE_SUBDIR", "/abs");
        bad.put("GA_REMOTE_POLL_SECONDS", "5");
        bad.put("GA_SSH_CONTROL_PERSIST", "forever");
        List<String> errors = new ArrayList<>();
        RemoteSettings.fromEnvironment(bad, errors);
        assertEquals(6, errors.size(), errors.toString());
        assertTrue(errors.toString().contains("too long for a socket path"), errors.toString());

        List<String> modes = new ArrayList<>();
        assertEquals(RemoteSettings.ControlMode.CONFIG, RemoteSettings.fromEnvironment(Map.of("GA_SSH_CONTROL_PATH",
                "config", "GA_SSH_TARGET", "wulver"), modes).controlMode());
        RemoteSettings.fromEnvironment(Map.of("GA_SSH_CONTROL_PATH", "/tmp/my socket"), modes);
        assertEquals(1, modes.size(), modes.toString());
        assertFalse(RemoteSettings.isSafeRemotePath("/a//b"));
        assertFalse(RemoteSettings.isSafeRemotePath("/a/./b"));
        assertTrue(RemoteSettings.isSafeRemotePath("/project/mjk76/mc2399"));
        assertEquals("_.hidden", RemoteSettings.safeName(".hidden"));
    }
}
