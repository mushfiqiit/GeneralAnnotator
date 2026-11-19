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
 * rsync invocation uses the same control socket with {@code ControlMaster=no} and {@code
 * BatchMode=yes}: it can only reuse the master and never prompts, so a lost connection fails fast
 * instead of asking for a password or Duo code again. Host-key checking is left to the user's SSH
 * configuration (it is never disabled). Only {@link #masterCommand()} is interactive; it runs in the
 * user's terminal and GeneralAnnotator never sees what is typed.
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

    /** Options of every non-interactive ssh: reuse the master only, never prompt. */
    List<String> batchOptions() {
        List<String> options = controlOptions();
        options.addAll(List.of("-o", "ControlMaster=no", "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=" + settings.connectTimeoutSeconds()));
        return options;
    }

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
                "-o", "ConnectTimeout=" + settings.connectTimeoutSeconds(), settings.sshTarget()));
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

    /** Whether the master is running, asked through the control socket without authenticating. */
    public Cluster.ConnectionCheck check() {
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
