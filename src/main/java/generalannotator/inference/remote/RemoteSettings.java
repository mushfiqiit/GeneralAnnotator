package generalannotator.inference.remote;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * How GeneralAnnotator reaches the cluster: the SSH target, which OpenSSH control socket it shares,
 * where submissions live remotely, timeouts and the monitoring interval. Read from {@code GA_SSH_*}
 * and {@code GA_REMOTE_*} environment variables; defaults are the observed NJIT Wulver setup.
 *
 * <p>Control socket modes ({@code GA_SSH_CONTROL_PATH}):
 * <ul>
 *   <li>unset: a socket in a private directory ({@code GA_SSH_CONTROL_DIR}, default {@code
 *       ~/.ga-ssh}, mode 0700) that {@code inference connect} can create interactively;
 *   <li>{@code config}: the ControlPath of the user's own SSH configuration for the target (for
 *       example a host alias); GeneralAnnotator only reuses it and never creates or closes it;
 *   <li>an absolute path: an existing user-owned socket, likewise only reused.
 * </ul>
 * The SSH configuration is never edited.
 */
public record RemoteSettings(
        String sshTarget,
        ControlMode controlMode,
        Optional<String> controlPath,
        Optional<Path> controlDirectory,
        String controlPersist,
        int connectTimeoutSeconds,
        Duration commandTimeout,
        Duration transferTimeout,
        Duration authenticationTimeout,
        String remoteBase,
        String remoteSubdirectory,
        Duration pollInterval,
        int maxUnknownPolls,
        int maxArraySize,
        String sshExecutable,
        String rsyncExecutable) {

    /** Which control socket is used. */
    public enum ControlMode {
        /** A socket in GeneralAnnotator's private directory; {@code connect} may create it. */
        MANAGED,
        /** An explicit user-owned socket path; reused only. */
        EXPLICIT,
        /** The ControlPath from the user's SSH configuration; reused only. */
        CONFIG
    }

    /** Longest socket path OpenSSH can bind on macOS/Linux, minus the temporary suffix it appends. */
    static final int MAX_SOCKET_PATH = 104 - 1 - 17;
    /** {@code %C} expands to 40 hexadecimal characters. */
    static final int CONTROL_HASH_LENGTH = 40;
    public static final int MIN_POLL_SECONDS = 60;

    private static final Pattern TARGET = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*(@[A-Za-z0-9][A-Za-z0-9._-]*)?");
    private static final Pattern REMOTE_PATH = Pattern.compile("/[A-Za-z0-9._/+@-]+");
    private static final Pattern RELATIVE = Pattern.compile("[A-Za-z0-9._+@-]+(/[A-Za-z0-9._+@-]+)*");
    /** Local paths passed to {@code rsync -e}, which splits on whitespace and interprets quotes. */
    private static final Pattern LOCAL_SOCKET = Pattern.compile("/[A-Za-z0-9._/+@%:=,-]+");
    private static final Pattern PERSIST = Pattern.compile("yes|no|[0-9]+[smhdwSMHDW]?");
    private static final Pattern EXECUTABLE = Pattern.compile("[A-Za-z0-9._/+-]+");

    /** Variable name, default value, description. */
    public record Variable(String name, String defaultValue, String meaning) {}

    public static final List<Variable> VARIABLES = List.of(
            new Variable("GA_SSH_TARGET", "mc2399@wulver.njit.edu", "SSH destination: user@host or a host alias"),
            new Variable("GA_SSH_CONTROL_PATH", "", "unset: private managed socket; 'config': the SSH config's "
                    + "ControlPath; or an existing socket path (reused, never closed)"),
            new Variable("GA_SSH_CONTROL_DIR", "~/.ga-ssh", "private socket directory of the managed mode (0700)"),
            new Variable("GA_SSH_CONTROL_PERSIST", "4h", "how long a managed master stays open when idle"),
            new Variable("GA_SSH_CONNECT_TIMEOUT_SECONDS", "30", "SSH ConnectTimeout"),
            new Variable("GA_SSH_COMMAND_TIMEOUT_SECONDS", "300", "timeout of one remote command"),
            new Variable("GA_SSH_TRANSFER_TIMEOUT_SECONDS", "3600", "timeout of one rsync transfer"),
            new Variable("GA_SSH_AUTH_TIMEOUT_SECONDS", "600", "time allowed for the interactive login"),
            new Variable("GA_SSH_EXECUTABLE", "ssh", "ssh executable"),
            new Variable("GA_RSYNC_EXECUTABLE", "rsync", "rsync executable"),
            new Variable("GA_REMOTE_BASE", "/project/mjk76/mc2399", "remote base directory"),
            new Variable("GA_REMOTE_SUBDIR", "generalannotator/inference",
                    "submissions go to <base>/<subdir>/<run id>/<submission id>"),
            new Variable("GA_REMOTE_POLL_SECONDS", "300", "monitoring interval of 'inference run' (minimum 60)"),
            new Variable("GA_REMOTE_MAX_UNKNOWN_POLLS", "3",
                    "'inference run' stops after this many polls in a row with unknown scheduler state"),
            new Variable("GA_SLURM_MAX_ARRAY_SIZE", "100001", "the cluster's MaxArraySize (largest index + 1)"));

    public RemoteSettings {
        if (controlMode == ControlMode.MANAGED && controlDirectory.isEmpty()) {
            throw new IllegalArgumentException("managed mode needs a control directory");
        }
    }

    /**
     * The ControlPath passed to ssh and rsync, or empty in {@link ControlMode#CONFIG} mode (ssh then
     * uses the configuration's ControlPath).
     */
    public Optional<String> effectiveControlPath() {
        return switch (controlMode) {
            case MANAGED -> Optional.of(controlDirectory.orElseThrow().resolve("%C").toString());
            case EXPLICIT -> controlPath;
            case CONFIG -> Optional.empty();
        };
    }

    /** {@code <base>/<subdir>/<run id>/<submission id>}; the run ID is reduced to safe characters. */
    public String remoteDirectory(String runId, String submissionId) {
        return remoteBase + "/" + remoteSubdirectory + "/" + safeName(runId) + "/" + safeName(submissionId);
    }

    static String safeName(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() || safe.startsWith(".") ? "_" + safe : safe;
    }

    /** Whether a remote path is acceptable (absolute, safe characters, no {@code .}/{@code ..} segments). */
    public static boolean isSafeRemotePath(String path) {
        if (!REMOTE_PATH.matcher(path).matches() || path.contains("//") || path.endsWith("/")) {
            return false;
        }
        for (String segment : path.substring(1).split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** Reads and validates the settings; problems are added to {@code problems}. */
    public static RemoteSettings fromEnvironment(Map<String, String> env, List<String> problems) {
        String target = value(env, "GA_SSH_TARGET");
        if (!TARGET.matcher(target).matches()) {
            problems.add("GA_SSH_TARGET='" + target + "' must be user@host or a host alias (letters, digits, '.', "
                    + "'_', '-')");
        }
        String rawPath = value(env, "GA_SSH_CONTROL_PATH");
        ControlMode mode;
        Optional<String> controlPath = Optional.empty();
        Optional<Path> controlDir = Optional.empty();
        if (rawPath.isEmpty()) {
            mode = ControlMode.MANAGED;
            String dir = value(env, "GA_SSH_CONTROL_DIR");
            if (dir.equals("~") || dir.startsWith("~/")) {
                String home = env.getOrDefault("HOME", "");
                if (home.isEmpty()) {
                    problems.add("GA_SSH_CONTROL_DIR uses ~ but HOME is not set");
                }
                dir = home + dir.substring(1);
            }
            if (!LOCAL_SOCKET.matcher(dir).matches()) {
                problems.add("GA_SSH_CONTROL_DIR='" + dir + "' must be an absolute path without spaces or quotes");
            } else if (dir.length() + 1 + CONTROL_HASH_LENGTH > MAX_SOCKET_PATH) {
                problems.add("GA_SSH_CONTROL_DIR='" + dir + "' is too long for a socket path; choose a directory of "
                        + "at most " + (MAX_SOCKET_PATH - 1 - CONTROL_HASH_LENGTH) + " characters");
            }
            controlDir = Optional.of(Path.of(dir.isEmpty() ? "/" : dir));
        } else if (rawPath.equals("config")) {
            mode = ControlMode.CONFIG;
        } else {
            mode = ControlMode.EXPLICIT;
            if (!LOCAL_SOCKET.matcher(rawPath).matches()) {
                problems.add("GA_SSH_CONTROL_PATH='" + rawPath + "' must be 'config' or an absolute path without "
                        + "spaces or quotes");
            }
            controlPath = Optional.of(rawPath);
        }
        String persist = value(env, "GA_SSH_CONTROL_PERSIST");
        if (!PERSIST.matcher(persist).matches()) {
            problems.add("GA_SSH_CONTROL_PERSIST='" + persist + "' must be yes, no, or a time such as 4h or 600");
        }
        String base = value(env, "GA_REMOTE_BASE");
        if (!isSafeRemotePath(base)) {
            problems.add("GA_REMOTE_BASE='" + base + "' must be an absolute path of letters, digits and ._/+@- "
                    + "without '.' or '..' segments or a trailing '/'");
        }
        String subdir = value(env, "GA_REMOTE_SUBDIR");
        if (!RELATIVE.matcher(subdir).matches() || List.of(subdir.split("/")).stream()
                .anyMatch(s -> s.equals(".") || s.equals(".."))) {
            problems.add("GA_REMOTE_SUBDIR='" + subdir + "' must be a relative path of letters, digits and ._+@- "
                    + "without '.' or '..' segments");
        }
        String ssh = value(env, "GA_SSH_EXECUTABLE");
        String rsync = value(env, "GA_RSYNC_EXECUTABLE");
        for (String[] exe : new String[][] {{"GA_SSH_EXECUTABLE", ssh}, {"GA_RSYNC_EXECUTABLE", rsync}}) {
            if (!EXECUTABLE.matcher(exe[1]).matches()) {
                problems.add(exe[0] + "='" + exe[1] + "' must be a command name or path without spaces");
            }
        }
        return new RemoteSettings(
                target,
                mode,
                controlPath,
                controlDir,
                persist,
                integer(env, "GA_SSH_CONNECT_TIMEOUT_SECONDS", 1, 600, problems),
                Duration.ofSeconds(integer(env, "GA_SSH_COMMAND_TIMEOUT_SECONDS", 10, 86_400, problems)),
                Duration.ofSeconds(integer(env, "GA_SSH_TRANSFER_TIMEOUT_SECONDS", 10, 86_400, problems)),
                Duration.ofSeconds(integer(env, "GA_SSH_AUTH_TIMEOUT_SECONDS", 30, 3600, problems)),
                base,
                subdir,
                Duration.ofSeconds(integer(env, "GA_REMOTE_POLL_SECONDS", MIN_POLL_SECONDS, 86_400, problems)),
                integer(env, "GA_REMOTE_MAX_UNKNOWN_POLLS", 1, 1000, problems),
                integer(env, "GA_SLURM_MAX_ARRAY_SIZE", 1, Integer.MAX_VALUE, problems),
                ssh,
                rsync);
    }

    private static String value(Map<String, String> env, String name) {
        String v = env.get(name);
        if (v == null) {
            v = VARIABLES.stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow().defaultValue();
        }
        return v.strip();
    }

    private static int integer(Map<String, String> env, String name, int min, int max, List<String> problems) {
        String v = value(env, name);
        try {
            int n = Integer.parseInt(v);
            if (n >= min && n <= max) {
                return n;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        problems.add(name + "='" + v + "' must be an integer from " + min + " to " + max);
        return min;
    }
}
