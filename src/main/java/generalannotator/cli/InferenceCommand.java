package generalannotator.cli;

import generalannotator.exec.CommandResult;
import generalannotator.exec.CommandRunner;
import generalannotator.exec.CommandSpec;
import generalannotator.inference.remote.Cluster;
import generalannotator.inference.remote.RemoteInference;
import generalannotator.inference.remote.RemoteSettings;
import generalannotator.inference.remote.SshCluster;
import generalannotator.inference.remote.SshConnection;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@code inference <operation>}: the remote half of the inference stage on a prepared submission.
 * <ul>
 *   <li>{@code connect}: reuse a running OpenSSH master, or open one with a single interactive login
 *       in this terminal (managed socket mode only);
 *   <li>{@code upload}, {@code submit}, {@code attach}, {@code status}, {@code collect}, {@code
 *       retry}: one step each;
 *   <li>{@code run}: connect if needed, upload, submit once, then monitor and collect.
 * </ul>
 * Only {@code connect} (and {@code run} through it) can prompt; every other ssh and rsync call uses
 * the shared connection with {@code BatchMode=yes}.
 *
 * <p>Exit codes: 0 done (for {@code run}: every prompt completed); 2 invalid arguments, settings or
 * submission; 3 incomplete or refused (failed or missing prompts, unknown scheduler or sbatch outcome,
 * hash mismatch, transfer failure, another process holds the submission); 5 the shared SSH connection
 * is not available (reconnect, then rerun; progress is kept); 4 internal error.
 */
public final class InferenceCommand {

    static final String USAGE = "inference connect | inference (upload|submit|status|collect|retry|run) "
            + "<submission dir> | inference attach <submission dir> (<job id>|--not-submitted)";

    private final List<String> args;
    private final Map<String, String> env;
    private final Path workingDirectory;
    private final CommandRunner runner;
    private final CommandRunner terminalRunner;
    private final boolean interactive;
    private final Clock clock;
    private final RemoteInference.Sleeper sleeper;
    private final Function<SshConnection, Cluster> clusterFactory;
    private final PrintStream out;
    private final PrintStream err;

    public InferenceCommand(List<String> args, Map<String, String> env, Path workingDirectory, CommandRunner runner,
            CommandRunner terminalRunner, boolean interactive, Clock clock, RemoteInference.Sleeper sleeper,
            Function<SshConnection, Cluster> clusterFactory, PrintStream out, PrintStream err) {
        this.args = List.copyOf(args);
        this.env = Map.copyOf(env);
        this.workingDirectory = workingDirectory;
        this.runner = runner;
        this.terminalRunner = terminalRunner;
        this.interactive = interactive;
        this.clock = clock;
        this.sleeper = sleeper;
        this.clusterFactory = clusterFactory;
        this.out = out;
        this.err = err;
    }

    /** Production wiring. */
    public static Function<SshConnection, Cluster> sshClusters() {
        return SshCluster::new;
    }

    public ExitCode run() {
        if (args.isEmpty()) {
            return usage("no inference operation given");
        }
        String op = args.get(0);
        List<String> problems = new ArrayList<>();
        RemoteSettings settings = RemoteSettings.fromEnvironment(env, problems);
        if (!problems.isEmpty()) {
            err.println("GeneralAnnotator: invalid remote settings:");
            problems.forEach(p -> err.println("  - " + p));
            return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
        }
        SshConnection connection = new SshConnection(settings, runner, workingDirectory);
        if (op.equals("connect")) {
            return args.size() == 1 ? connect(connection) : usage("connect takes no arguments");
        }
        boolean attach = op.equals("attach");
        if (!(attach ? args.size() == 3 : args.size() == 2)
                || !List.of("upload", "submit", "status", "collect", "retry", "run", "attach").contains(op)) {
            return usage("unknown or incomplete inference operation: " + String.join(" ", args));
        }
        Path dir;
        try {
            dir = workingDirectory.resolve(args.get(1)).normalize();
        } catch (InvalidPathException e) {
            return usage("'" + args.get(1) + "' is not a valid path");
        }
        if (!Files.isDirectory(dir)) {
            return usage(dir + " is not a submission directory (runs/<run id>/inference/<submission id>)");
        }
        RemoteInference remote = new RemoteInference(dir, settings, clusterFactory.apply(connection), clock, sleeper,
                out);
        out.println("GeneralAnnotator inference " + op + ": " + dir + " via " + settings.sshTarget());
        RemoteInference.Outcome outcome = switch (op) {
            case "upload" -> remote.upload();
            case "submit" -> remote.submit();
            case "status" -> remote.status();
            case "collect" -> remote.collect();
            case "retry" -> remote.retry();
            case "attach" -> args.get(2).equals("--not-submitted") ? remote.attachNone() : remote.attach(args.get(2));
            case "run" -> {
                ExitCode connected = connect(connection);
                if (connected != ExitCode.COMPLETED) {
                    yield null;
                }
                Thread notice = new Thread(() -> System.err.println("\nGeneralAnnotator: local monitoring stopped. "
                        + "Remote jobs keep running; state is saved. Resume with: inference run " + dir));
                Runtime.getRuntime().addShutdownHook(notice);
                try {
                    yield remote.run();
                } finally {
                    try {
                        Runtime.getRuntime().removeShutdownHook(notice);
                    } catch (IllegalStateException e) {
                        // shutting down already
                    }
                }
            }
            default -> throw new IllegalStateException(op);
        };
        if (outcome == null) {
            return ExitCode.RECONNECT_REQUIRED;
        }
        return switch (outcome) {
            case DONE -> ExitCode.COMPLETED;
            case INCOMPLETE -> ExitCode.EXECUTION_FAILURE;
            case RECONNECT -> ExitCode.RECONNECT_REQUIRED;
            case INVALID -> ExitCode.USAGE_OR_CONFIGURATION_ERROR;
        };
    }

    /**
     * Reuses a healthy master (it answers and a remote round trip through it works), or, in managed
     * mode, retires a stale managed master and opens a new one with a single interactive login in a
     * terminal. Passwords and Duo codes go from the terminal straight to ssh. User-owned masters are
     * never stopped or created; the user gets instructions instead.
     */
    ExitCode connect(SshConnection connection) {
        RemoteSettings s = connection.settings();
        Cluster.ConnectionCheck check = connection.health();
        if (check.alive()) {
            out.println("Reusing the SSH master for " + s.sshTarget() + " (" + check.detail() + "). Remote commands "
                    + "share it and cannot prompt or log in afresh; no new login is needed.");
            return ExitCode.COMPLETED;
        }
        if (check.masterRunning() && s.controlMode() != RemoteSettings.ControlMode.MANAGED) {
            err.println("GeneralAnnotator: your SSH master for " + s.sshTarget() + " is running but unusable ("
                    + check.detail() + ").");
            err.println("It is your connection (GA_SSH_CONTROL_PATH), so GeneralAnnotator does not stop it. Close it "
                    + "and log in again, for example:");
            err.println("  " + SshConnection.display(connection.exitCommand()));
            err.println("  " + SshConnection.display(userMaster(s)));
            return ExitCode.RECONNECT_REQUIRED;
        }
        if (check.masterRunning()) {
            // Our own managed master: its local process answers, but its connection is dead (for
            // example after a VPN change or sleep). Ask it to exit so a new login can take its place.
            out.println("The managed SSH master is stale (" + check.detail() + "); stopping it:");
            out.println("  " + SshConnection.display(connection.exitCommand()));
            runner.run(new CommandSpec(connection.exitCommand(), workingDirectory, Map.of(),
                    java.time.Duration.ofSeconds(s.connectTimeoutSeconds() + 10L)));
            Cluster.ConnectionCheck after = connection.checkMaster();
            if (after.masterRunning()) {
                err.println("GeneralAnnotator: the stale master did not exit (" + after.detail() + "). Stop that ssh "
                        + "process yourself (the pid is shown above), then run `inference connect` again.");
                return ExitCode.RECONNECT_REQUIRED;
            }
            out.println("Stopped. Jobs on the cluster are not affected.");
        }
        if (s.controlMode() != RemoteSettings.ControlMode.MANAGED) {
            err.println("GeneralAnnotator: no running SSH master for " + s.sshTarget() + " (" + check.detail() + ").");
            err.println("GA_SSH_CONTROL_PATH=" + (s.controlMode() == RemoteSettings.ControlMode.CONFIG ? "config"
                    : s.controlPath().orElse("")) + " means GeneralAnnotator only reuses a connection you own. Open it"
                    + " yourself, for example:");
            err.println("  " + SshConnection.display(userMaster(s)));
            return ExitCode.RECONNECT_REQUIRED;
        }
        Optional<String> problem;
        try {
            problem = connection.prepareControlDirectory();
        } catch (IOException e) {
            problem = Optional.of("cannot create " + s.controlDirectory().orElseThrow() + ": " + e.getMessage());
        }
        if (problem.isPresent()) {
            err.println("GeneralAnnotator: " + problem.get());
            return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
        }
        String command = SshConnection.display(connection.masterCommand());
        if (!interactive) {
            err.println("GeneralAnnotator: no SSH master for " + s.sshTarget() + ", and this process has no terminal "
                    + "to log in from (for example under ./gradlew run).");
            err.println("Log in once in a terminal, either with the installed launcher:");
            err.println("  ./gradlew installDist && build/install/GeneralAnnotator/bin/GeneralAnnotator inference connect");
            err.println("or with this exact command (same socket):");
            err.println("  " + command);
            return ExitCode.RECONNECT_REQUIRED;
        }
        out.println("Opening a shared SSH connection to " + s.sshTarget() + ":");
        out.println("  " + command);
        out.println("Authenticate below (password, then Duo). GeneralAnnotator does not see or store what you type.");
        out.flush();
        CommandResult r = terminalRunner.run(new CommandSpec(connection.masterCommand(), workingDirectory, Map.of(),
                s.authenticationTimeout()));
        Cluster.ConnectionCheck after = connection.health();
        if (!r.succeeded() || !after.alive()) {
            err.println("GeneralAnnotator: the SSH login did not open a master (" + r.describeStatus() + "; "
                    + after.detail() + "). Nothing else was done; run `inference connect` again when ready.");
            return ExitCode.RECONNECT_REQUIRED;
        }
        out.println("Connected (" + after.detail() + "). The master stays open while used and for "
                + s.controlPersist() + " when idle (GA_SSH_CONTROL_PERSIST). Close it early with:");
        List<String> exit = new ArrayList<>(List.of(s.sshExecutable()));
        s.effectiveControlPath().ifPresent(p -> exit.addAll(List.of("-o", "ControlPath=" + p)));
        exit.addAll(List.of("-O", "exit", s.sshTarget()));
        out.println("  " + SshConnection.display(exit));
        return ExitCode.COMPLETED;
    }

    /** The command a user runs to open their own master. */
    private static List<String> userMaster(RemoteSettings s) {
        List<String> example = new ArrayList<>(List.of(s.sshExecutable(), "-M", "-N", "-f"));
        s.controlPath().ifPresent(p -> example.addAll(List.of("-o", "ControlPath=" + p)));
        example.add(s.sshTarget());
        return example;
    }

    private ExitCode usage(String problem) {
        err.println("GeneralAnnotator: " + problem);
        err.println("Usage: " + USAGE);
        return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
    }
}
