package generalannotator.inference.remote;

import generalannotator.exec.CommandResult;
import generalannotator.exec.CommandRunner;
import generalannotator.exec.CommandSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Command lines for one shared OpenSSH connection (ControlMaster). Every non-interactive ssh and
 * rsync invocation uses the same control socket and can only reuse the master:
 * <ul>
 *   <li>{@code ControlMaster=no}: never becomes a master itself;
 *   <li>{@code BatchMode=yes}: never prompts;
 *   <li>{@code ProxyCommand=false}: if the socket is missing or refuses the session, OpenSSH silently
 *       falls back to a fresh connection, which could still authenticate with keys or an agent.
 *       A session multiplexed over a master never uses ProxyCommand, so this option only makes that
 *       fallback fail at once (exit 255).
 * </ul>
 * A lost connection therefore fails fast instead of logging in again. Host-key checking is left to
 * the user's SSH configuration (it is never disabled). Only {@link #masterCommand()} is
 * interactive; it runs in the user's terminal and GeneralAnnotator never sees what is typed.
 *
 * <p>A master that answers {@code ssh -O check} is not necessarily usable: after a VPN change or
 * sleep its TCP connection can be dead while the local process lives on. {@link #health()}
 * therefore also needs a remote round trip through the master.
 */
public final class SshConnection {

    private final RemoteSettings settings;
    private final CommandRunner runner;
    private final Path workingDirectory;

    public SshConnection(RemoteSettings settings, CommandRunner runner, Path workingDirectory) {
        this.settings = settings;
        this.runner = runner;
        this.workingDirectory = workingDirectory;
    }

    public RemoteSettings settings() {
        return settings;
    }

    private List<String> controlOptions() {
        List<String> options = new ArrayList<>();
        settings.effectiveControlPath().ifPresent(p -> {
            options.add("-o");
            options.add("ControlPath=" + p);
        });
        return options;
    }

    /** Options of every non-interactive ssh: reuse the master only, never prompt, never log in afresh. */
    List<String> batchOptions() {
        List<String> options = controlOptions();
        options.addAll(List.of("-o", "ControlMaster=no", "-o", "BatchMode=yes", "-o", "ProxyCommand=false",
                "-o", "ConnectTimeout=" + settings.connectTimeoutSeconds()));
        return options;
    }

    /** {@code ssh -O exit}: asks the master to exit (used only for a stale managed master). */
    public List<String> exitCommand() {
        List<String> command = new ArrayList<>(List.of(settings.sshExecutable()));
        command.addAll(controlOptions());
        command.addAll(List.of("-O", "exit", settings.sshTarget()));
        return command;
    }

    /** The remote round trip of {@link #health()}: prints {@link #HEALTH_MARKER} on the cluster. */
    List<String> probeCommand() {
        List<String> command = new ArrayList<>(List.of(settings.sshExecutable()));
        command.addAll(batchOptions());
        command.add("-T");
        command.add(settings.sshTarget());
        // The marker is assembled by printf on the cluster, so it cannot come from the command text.
        command.add("printf '%s-%s %s\\n' GA HEALTH ok");
        return command;
    }

    static final String HEALTH_MARKER = "GA-HEALTH ok";

    /** {@code ssh -O check}: asks the master whether it is running; never authenticates. */
    public List<String> checkCommand() {
        List<String> command = new ArrayList<>(List.of(settings.sshExecutable()));
        command.addAll(controlOptions());
        command.addAll(List.of("-O", "check", settings.sshTarget()));
        return command;
    }

    /**
     * The interactive command that opens a master in the background after authentication ({@link
     * RemoteSettings.ControlMode#MANAGED} only).
     */
    public List<String> masterCommand() {
        if (settings.controlMode() != RemoteSettings.ControlMode.MANAGED) {
            throw new IllegalStateException("only a managed master is created by GeneralAnnotator");
        }
        List<String> command = new ArrayList<>(List.of(settings.sshExecutable(), "-M", "-N", "-f"));
        command.addAll(controlOptions());
        command.addAll(List.of("-o", "ControlPersist=" + settings.controlPersist(),
                "-o", "ConnectTimeout=" + settings.connectTimeoutSeconds()));
        if (settings.serverAliveSeconds() > 0) {
            // A master whose network path is gone (VPN off, sleep) then exits by itself.
            command.addAll(List.of("-o", "ServerAliveInterval=" + settings.serverAliveSeconds(),
                    "-o", "ServerAliveCountMax=3"));
        }
        command.add(settings.sshTarget());
        return command;
    }

    /** ssh running {@code script} with {@code bash -l -c} on the cluster. */
    List<String> remoteCommand(String script) {
        List<String> command = new ArrayList<>(List.of(settings.sshExecutable()));
        command.addAll(batchOptions());
        command.add("-T");
        command.add(settings.sshTarget());
        // ssh joins the words with spaces for the remote login shell, so the script is quoted once.
        command.addAll(List.of("bash", "-l", "-c", quote(script)));
        return command;
    }

    /** The {@code rsync -e} value: the same socket and options as {@link #remoteCommand}. */
    String rsyncShell() {
        List<String> shell = new ArrayList<>(List.of(settings.sshExecutable()));
        shell.addAll(batchOptions());
        shell.add("-T");
        return String.join(" ", shell);
    }

    /** POSIX single-quoting. */
    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    CommandResult run(List<String> command, Duration timeout) {
        return run(command, workingDirectory, timeout);
    }

    CommandResult run(List<String> command, Path directory, Duration timeout) {
        return runner.run(new CommandSpec(command, directory, Map.of(), timeout));
    }

    /**
     * Whether the connection is usable: the master answers {@code ssh -O check} and a bounded remote
     * round trip through it prints the health marker. Never authenticates.
     */
    public Cluster.ConnectionCheck health() {
        Cluster.ConnectionCheck master = checkMaster();
        if (!master.alive()) {
            return master;
        }
        Duration timeout = Duration.ofSeconds(settings.connectTimeoutSeconds() + 15L);
        CommandResult r = run(probeCommand(), timeout);
        boolean marker = r.stdout().lines().anyMatch(l -> l.strip().equals(HEALTH_MARKER));
        if (r.succeeded() && marker) {
            return new Cluster.ConnectionCheck(true, true, master.detail() + "; remote round trip ok");
        }
        String why = r.timedOut() ? "no answer within " + timeout.toSeconds() + "s"
                : r.startFailure().isPresent() ? r.describeStatus()
                : (r.stderr().strip().isEmpty() ? r.describeStatus() + (r.succeeded() ? " without the marker" : "")
                        : r.stderr().strip().replaceAll("\\s+", " "));
        return new Cluster.ConnectionCheck(false, true, master.detail()
                + ", but a remote command through it failed (" + why + ")");
    }

    /** Whether the master process answers through the control socket (local only; never authenticates). */
    public Cluster.ConnectionCheck checkMaster() {
        Optional<String> problem = controlDirectoryProblem();
        if (problem.isPresent()) {
            return new Cluster.ConnectionCheck(false, problem.get());
        }
        CommandResult r = run(checkCommand(), Duration.ofSeconds(settings.connectTimeoutSeconds() + 10L));
        String detail = (r.stderr() + r.stdout()).strip();
        if (r.succeeded()) {
            return new Cluster.ConnectionCheck(true, detail.isEmpty() ? "master running" : detail);
        }
        return new Cluster.ConnectionCheck(false, detail.isEmpty() ? r.describeStatus() : detail);
    }

    /**
     * Creates the managed socket directory with mode 0700 if it is missing, or checks that an existing
     * one is a private directory of this user. Nothing is done in the other modes.
     */
    public Optional<String> prepareControlDirectory() throws IOException {
        if (settings.controlMode() != RemoteSettings.ControlMode.MANAGED) {
            return Optional.empty();
        }
        Path dir = settings.controlDirectory().orElseThrow();
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(dir.getParent());
            if (Files.getFileStore(dir.getParent()).supportsFileAttributeView("posix")) {
                Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(
                        "rwx------")));
            } else {
                Files.createDirectory(dir);
            }
        }
        return controlDirectoryProblem();
    }

    /** Why the managed socket directory must not be used, if it must not. */
    Optional<String> controlDirectoryProblem() {
        if (settings.controlMode() != RemoteSettings.ControlMode.MANAGED) {
            return Optional.empty();
        }
        Path dir = settings.controlDirectory().orElseThrow();
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of("no control socket directory " + dir + " yet (run: inference connect)");
        }
        try {
            if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(dir + " is not a directory (symbolic links are not accepted)");
            }
            if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) {
                return Optional.empty();
            }
            String user = System.getProperty("user.name");
            String owner = Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS).getName();
            if (user != null && !owner.equals(user)) {
                return Optional.of(dir + " belongs to " + owner + ", not " + user);
            }
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS);
            for (PosixFilePermission p : perms) {
                if (!p.name().startsWith("OWNER_")) {
                    return Optional.of(dir + " is accessible by other users (" + PosixFilePermissions.toString(perms)
                            + "); it must be private (chmod 700 " + dir + ")");
                }
            }
        } catch (IOException e) {
            return Optional.of("cannot check " + dir + ": " + e.getMessage());
        }
        return Optional.empty();
    }

    /** A command line the user can paste, for messages. */
    public static String display(List<String> command) {
        List<String> words = new ArrayList<>();
        for (String w : command) {
            words.add(w.matches("[A-Za-z0-9._/@%:=,+-]+") ? w : quote(w));
        }
        return String.join(" ", words);
    }
}
