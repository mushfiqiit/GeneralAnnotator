package generalannotator.inference.remote;

import generalannotator.exec.CommandResult;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link Cluster} over one shared OpenSSH connection: remote commands run as fixed bash scripts
 * through {@code ssh -T ... bash -l -c}, transfers use rsync with the same socket. Every
 * interpolated value is validated (safe paths, numeric job IDs, safe labels) and single-quoted;
 * prompt contents are only ever copied as files. Scripts print {@code GA-} marker lines, so output
 * from login scripts cannot be mistaken for results. ssh's exit code 255 means the connection
 * failed. No command deletes remote files, and transfers never use {@code --delete}.
 */
public final class SshCluster implements Cluster {

    private static final Pattern JOB_ID = Pattern.compile("[0-9]+");
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern ARRAY = Pattern.compile("[0-9,\\-]+(%[0-9]+)?");
    private static final Pattern COMMENT = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final Pattern RELATIVE_FILE = Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*");
    private static final Pattern SHA = Pattern.compile("GA-SHA ([0-9a-f]{64}) [ *](.+)");
    private static final Pattern PARSABLE = Pattern.compile("([0-9]+)(;[A-Za-z0-9_.-]+)?");
    /** rsync exit codes that usually mean the transport (ssh) failed. */
    private static final List<Integer> RSYNC_TRANSPORT = List.of(10, 12, 30, 35, 255);

    private final SshConnection connection;

    public SshCluster(SshConnection connection) {
        this.connection = connection;
    }

    private RemoteSettings settings() {
        return connection.settings();
    }

    @Override
    public ConnectionCheck check() {
        return connection.health();
    }

    @Override
    public DirectoryProbe probe(String remoteDir) throws RemoteException {
        String out = exec("""
                d=%s
                if [ -f "$d/submission.json" ]; then
                    echo "GA-PROBE HAS_SUBMISSION"; sha256sum -- "$d/submission.json" | sed 's/^/GA-SHA /'
                elif [ -d "$d" ] && [ -z "$(ls -A -- "$d")" ]; then echo "GA-PROBE EMPTY"
                elif [ -e "$d" ]; then echo "GA-PROBE OTHER"
                else echo "GA-PROBE ABSENT"; fi
                """.formatted(path(remoteDir)));
        List<String> kind = marked(out, "GA-PROBE ");
        if (kind.size() != 1) {
            throw new RemoteException(RemoteException.Kind.FAILED, "unexpected probe output: " + clip(out));
        }
        DirectoryProbe.Kind k;
        try {
            k = DirectoryProbe.Kind.valueOf(kind.get(0).strip());
        } catch (IllegalArgumentException e) {
            throw new RemoteException(RemoteException.Kind.FAILED, "unexpected probe output: " + clip(out));
        }
        Optional<String> sha = Optional.empty();
        for (String line : out.split("\n")) {
            Matcher m = SHA.matcher(line.strip());
            if (m.matches()) {
                sha = Optional.of(m.group(1));
            }
        }
        if (k == DirectoryProbe.Kind.HAS_SUBMISSION && sha.isEmpty()) {
            throw new RemoteException(RemoteException.Kind.FAILED, "cannot hash the remote submission.json: "
                    + clip(out));
        }
        return new DirectoryProbe(k, sha);
    }

    @Override
    public String createDirectory(String remoteDir) throws RemoteException {
        String out = exec("""
                d=%s
                mkdir -p -- "$d/logs" || exit 3
                cd -- "$d" && echo "GA-DIR $(pwd -P)"
                """.formatted(path(remoteDir)));
        List<String> dir = marked(out, "GA-DIR ");
        if (dir.size() != 1 || !dir.get(0).startsWith("/")) {
            throw new RemoteException(RemoteException.Kind.FAILED, "cannot create " + remoteDir + ": " + clip(out));
        }
        return dir.get(0).strip();
    }

    @Override
    public void upload(Path localDir, List<String> files, String remoteDir) throws RemoteException {
        path(remoteDir);
        List<String> command = new ArrayList<>(List.of(settings().rsyncExecutable(), "-R", "-t",
                "--chmod=D755,F444", "-e", connection.rsyncShell(), "--"));
        for (String f : files) {
            command.add(relative(f));
        }
        command.add(settings().sshTarget() + ":" + remoteDir + "/");
        rsync(command, localDir, "upload");
    }

    @Override
    public Map<String, String> hashes(String remoteDir, List<String> files) throws RemoteException {
        StringBuilder names = new StringBuilder();
        for (String f : files) {
            names.append(' ').append(SshConnection.quote(relative(f)));
        }
        String out = exec("""
                cd -- %s || { echo "GA-NO-DIR"; exit 0; }
                for f in%s; do
                    if [ -f "$f" ] && [ ! -L "$f" ]; then sha256sum -- "$f" | sed 's/^/GA-SHA /'
                    else echo "GA-MISSING $f"; fi
                done
                echo "GA-DONE"
                """.formatted(path(remoteDir), names));
        Map<String, String> hashes = new LinkedHashMap<>();
        if (out.contains("GA-NO-DIR")) {
            return hashes;
        }
        if (!out.contains("GA-DONE")) {
            throw new RemoteException(RemoteException.Kind.FAILED, "incomplete hash output: " + clip(out));
        }
        for (String line : out.split("\n")) {
            Matcher m = SHA.matcher(line.strip());
            if (m.matches()) {
                hashes.put(m.group(2), m.group(1));
            }
        }
        return hashes;
    }

    @Override
    public SbatchOutcome sbatch(String remoteDir, String label, String arraySpec, String comment) {
        String script;
        try {
            check(LABEL, label, "label");
            check(ARRAY, arraySpec, "array specification");
            check(COMMENT, comment, "comment");
            script = """
                    cd -- %1$s || { echo "GA-SBATCH NOT_RUN cannot enter the submission directory"; exit 0; }
                    mkdir -p .ga-sbatch || { echo "GA-SBATCH NOT_RUN cannot create .ga-sbatch"; exit 0; }
                    if ! mkdir .ga-sbatch/%2$s 2>/dev/null; then
                        echo "GA-SBATCH DUPLICATE"
                        sed 's/^/GA-RECORD stdout: /' .ga-sbatch/%2$s/stdout 2>/dev/null
                        sed 's/^/GA-RECORD exit: /' .ga-sbatch/%2$s/exit 2>/dev/null
                        exit 0
                    fi
                    out=$(sbatch --parsable --array=%3$s --comment=%4$s run_inference.sbatch 2>.ga-sbatch/%2$s/stderr)
                    rc=$?
                    printf '%%s\\n' "$out" > .ga-sbatch/%2$s/stdout
                    printf '%%s\\n' "$rc" > .ga-sbatch/%2$s/exit
                    echo "GA-SBATCH RC $rc"
                    printf '%%s\\n' "$out" | sed 's/^/GA-OUT /'
                    sed 's/^/GA-ERR /' .ga-sbatch/%2$s/stderr
                    echo "GA-SBATCH END"
                    """.formatted(path(remoteDir), label, arraySpec, comment);
        } catch (RemoteException e) {
            return new SbatchOutcome.Rejected(e.getMessage());
        }
        String out;
        try {
            out = exec(script);
        } catch (RemoteException e) {
            if (e.kind() == RemoteException.Kind.FAILED && e.getMessage().startsWith("cannot run")) {
                return new SbatchOutcome.Rejected(e.getMessage());
            }
            return new SbatchOutcome.Unknown(e.kind() + ": " + e.getMessage());
        }
        return parseSbatch(out);
    }

    /** Interprets the marker output of the sbatch script. */
    static SbatchOutcome parseSbatch(String out) {
        List<String> status = marked(out, "GA-SBATCH ");
        if (status.isEmpty()) {
            return new SbatchOutcome.Unknown("no sbatch result in the output (connection lost?): " + clip(out));
        }
        String first = status.get(0);
        if (first.startsWith("NOT_RUN")) {
            return new SbatchOutcome.Rejected("sbatch was not run: " + first.substring("NOT_RUN".length()).strip());
        }
        if (first.equals("DUPLICATE")) {
            return new SbatchOutcome.Duplicate(String.join("\n", marked(out, "GA-RECORD ")));
        }
        if (!first.startsWith("RC ")) {
            return new SbatchOutcome.Unknown("unexpected sbatch output: " + clip(out));
        }
        int rc;
        try {
            rc = Integer.parseInt(first.substring(3).strip());
        } catch (NumberFormatException e) {
            return new SbatchOutcome.Unknown("unexpected sbatch output: " + clip(out));
        }
        List<String> stdout = marked(out, "GA-OUT ").stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        String stderr = String.join("\n", marked(out, "GA-ERR "));
        if (rc != 0) {
            return new SbatchOutcome.Rejected("sbatch exited with " + rc + (stderr.isBlank() ? "" : ": " + stderr));
        }
        if (stdout.isEmpty()) {
            return new SbatchOutcome.Unknown("sbatch exited with 0 but printed no job ID (output incomplete?)");
        }
        Matcher m = PARSABLE.matcher(stdout.get(stdout.size() - 1));
        if (!m.matches()) {
            return new SbatchOutcome.Unknown("sbatch exited with 0 but its output is not a job ID: "
                    + String.join(" | ", stdout));
        }
        return new SbatchOutcome.Submitted(m.group(1), String.join("\n", stdout));
    }

    @Override
    public SchedulerSnapshot query(List<String> jobIds) throws RemoteException {
        if (jobIds.isEmpty()) {
            return new SchedulerSnapshot(true, true, List.of(), List.of());
        }
        for (String id : jobIds) {
            if (!JOB_ID.matcher(id).matches()) {
                return SchedulerSnapshot.unavailable("invalid job ID " + id);
            }
        }
        String ids = String.join(",", jobIds);
        String out;
        try {
            out = exec("""
                    out=$(squeue -h -r -j %1$s -o '%%i|%%T|%%r' 2>&1); rc=$?
                    echo "GA-SQUEUE $rc"; printf '%%s\\n' "$out" | sed 's/^/GA-Q /'
                    out=$(sacct -X -n -P -j %1$s --format=JobID,State,ExitCode,Elapsed,NodeList 2>&1); rc=$?
                    echo "GA-SACCT $rc"; printf '%%s\\n' "$out" | sed 's/^/GA-A /'
                    echo "GA-END"
                    """.formatted(ids));
        } catch (RemoteException e) {
            if (e.kind() == RemoteException.Kind.DISCONNECTED) {
                throw e; // says nothing about the jobs
            }
            return SchedulerSnapshot.unavailable(e.kind() + ": " + e.getMessage());
        }
        return parseQuery(out);
    }

    /** Interprets the marker output of the squeue/sacct script. */
    static SchedulerSnapshot parseQuery(String out) {
        List<String> problems = new ArrayList<>();
        List<SchedulerSnapshot.Row> rows = new ArrayList<>();
        boolean complete = out.contains("GA-END");
        List<String> squeueRc = marked(out, "GA-SQUEUE ");
        List<String> sacctRc = marked(out, "GA-SACCT ");
        List<String> q = marked(out, "GA-Q ");
        List<String> a = marked(out, "GA-A ");
        boolean activeKnown = false;
        if (squeueRc.size() == 1 && complete) {
            if (squeueRc.get(0).strip().equals("0")) {
                activeKnown = true;
            } else if (String.join("\n", q).toLowerCase().contains("invalid job id")) {
                activeKnown = true; // the job has left the queue
                q = List.of();
            } else {
                problems.add("squeue failed (exit " + squeueRc.get(0).strip() + "): " + clip(String.join(" ", q)));
                q = List.of();
            }
        } else {
            problems.add("no squeue result in the output");
            q = List.of();
        }
        boolean historyKnown = false;
        if (sacctRc.size() == 1 && complete) {
            if (sacctRc.get(0).strip().equals("0")) {
                historyKnown = true;
            } else {
                problems.add("sacct failed (exit " + sacctRc.get(0).strip() + "): " + clip(String.join(" ", a)));
                a = List.of();
            }
        } else {
            problems.add("no sacct result in the output");
            a = List.of();
        }
        for (String line : q) {
            String[] f = line.split("\\|", -1);
            if (f.length >= 2) {
                rows.addAll(SchedulerSnapshot.rows(f[0], f[1].strip(), f.length > 2 ? "reason " + f[2].strip() : "",
                        SchedulerSnapshot.Source.SQUEUE));
            }
        }
        for (String line : a) {
            String[] f = line.split("\\|", -1);
            if (f.length >= 2) {
                String detail = f.length >= 5 ? "exit " + f[2].strip() + ", elapsed " + f[3].strip()
                        + (f[4].isBlank() || f[4].strip().equals("None assigned") ? "" : ", node " + f[4].strip())
                        : "";
                rows.addAll(SchedulerSnapshot.rows(f[0], f[1].strip(), detail, SchedulerSnapshot.Source.SACCT));
            }
        }
        return new SchedulerSnapshot(activeKnown, historyKnown, rows, problems);
    }

    @Override
    public List<JobRecord> describe(String jobId) throws RemoteException {
        check(JOB_ID, jobId, "job ID");
        String out = exec("""
                out=$(sacct -X -n -P -j %1$s --format=JobID,JobName,WorkDir,State 2>&1); rc=$?
                echo "GA-SACCT $rc"; printf '%%s\\n' "$out" | sed 's/^/GA-A /'
                out=$(squeue -h -r -j %1$s -o '%%i|%%j|%%Z|%%T' 2>&1); rc=$?
                echo "GA-SQUEUE $rc"; printf '%%s\\n' "$out" | sed 's/^/GA-Q /'
                echo "GA-END"
                """.formatted(jobId));
        return records(out);
    }

    @Override
    public List<JobRecord> findJobs(String jobName, LocalDate since) throws RemoteException {
        check(LABEL, jobName, "job name");
        String out = exec("""
                out=$(sacct -X -n -P -S %1$s --name=%2$s --format=JobID,JobName,WorkDir,State 2>&1); rc=$?
                echo "GA-SACCT $rc"; printf '%%s\\n' "$out" | sed 's/^/GA-A /'
                out=$(squeue -h -r -u "$USER" -n %2$s -o '%%i|%%j|%%Z|%%T' 2>&1); rc=$?
                echo "GA-SQUEUE $rc"; printf '%%s\\n' "$out" | sed 's/^/GA-Q /'
                echo "GA-END"
                """.formatted(since, jobName));
        return records(out);
    }

    /** Job records from the sacct/squeue marker output; fails if neither answered. */
    static List<JobRecord> records(String out) throws RemoteException {
        if (!out.contains("GA-END")) {
            throw new RemoteException(RemoteException.Kind.FAILED, "incomplete scheduler output: " + clip(out));
        }
        boolean sacct = marked(out, "GA-SACCT ").equals(List.of("0"));
        boolean squeue = marked(out, "GA-SQUEUE ").equals(List.of("0"));
        if (!sacct && !squeue) {
            throw new RemoteException(RemoteException.Kind.FAILED, "neither sacct nor squeue answered: " + clip(out));
        }
        List<JobRecord> records = new ArrayList<>();
        if (sacct) {
            for (String line : marked(out, "GA-A ")) {
                String[] f = line.split("\\|", -1);
                if (f.length >= 4) {
                    records.add(new JobRecord(f[0].strip(), f[1].strip(), trimSlash(f[2].strip()),
                            f[3].strip().split("\\s+")[0], "sacct"));
                }
            }
        }
        if (squeue) {
            for (String line : marked(out, "GA-Q ")) {
                String[] f = line.split("\\|", -1);
                if (f.length >= 4) {
                    records.add(new JobRecord(f[0].strip(), f[1].strip(), trimSlash(f[2].strip()), f[3].strip(),
                            "squeue"));
                }
            }
        }
        return records;
    }

    @Override
    public Optional<String> sbatchRecord(String remoteDir, String label) throws RemoteException {
        check(LABEL, label, "label");
        String out = exec("""
                cd -- %1$s 2>/dev/null || { echo "GA-RECORD-NONE"; exit 0; }
                if [ -d .ga-sbatch/%2$s ]; then
                    echo "GA-RECORD-EXISTS"
                    for f in stdout exit stderr; do [ -f ".ga-sbatch/%2$s/$f" ] && sed "s/^/GA-RECORD $f: /" ".ga-sbatch/%2$s/$f"; done
                else echo "GA-RECORD-NONE"; fi
                echo "GA-END"
                """.formatted(path(remoteDir), label));
        if (!out.contains("GA-END")) {
            throw new RemoteException(RemoteException.Kind.FAILED, "incomplete output: " + clip(out));
        }
        if (!out.contains("GA-RECORD-EXISTS")) {
            return Optional.empty();
        }
        return Optional.of(String.join("\n", marked(out, "GA-RECORD ")));
    }

    @Override
    public void download(String remoteDir, Path incoming) throws RemoteException {
        // Not quoted: rsync passes the path to the remote rsync itself (validated to safe characters).
        path(remoteDir);
        List<String> command = List.of(settings().rsyncExecutable(), "-r", "-t",
                "--exclude=*.tmp-*", "--include=/results/***", "--include=/logs/***", "--include=/.ga-sbatch/***",
                "--exclude=*", "-e", connection.rsyncShell(), "--",
                settings().sshTarget() + ":" + remoteDir + "/", incoming.toString() + "/");
        rsync(command, incoming, "download");
    }

    // ------------------------------------------------------------------ helpers

    private void rsync(List<String> command, Path workingDirectory, String what) throws RemoteException {
        // Upload paths are relative (rsync -R), so rsync runs in the local directory it copies from.
        CommandResult r = connection.run(command, workingDirectory, settings().transferTimeout());
        if (r.startFailure().isPresent()) {
            throw new RemoteException(RemoteException.Kind.FAILED, "cannot run rsync: " + r.startFailure().get());
        }
        if (r.timedOut()) {
            throw lostOr(RemoteException.Kind.TIMED_OUT, what + " timed out after "
                    + settings().transferTimeout().toSeconds() + "s");
        }
        int code = r.exitCode().getAsInt();
        if (code == 0) {
            return;
        }
        if (RSYNC_TRANSPORT.contains(code)) {
            throw lostOr(RemoteException.Kind.FAILED, what + " failed (rsync exit " + code + "): "
                    + clip(r.stderr()));
        }
        throw new RemoteException(RemoteException.Kind.FAILED, what + " failed (rsync exit " + code + "): "
                + clip(r.stderr()));
    }

    private String exec(String script) throws RemoteException {
        return exec(script, settings().commandTimeout());
    }

    private String exec(String script, Duration timeout) throws RemoteException {
        CommandResult r = connection.run(connection.remoteCommand(script), timeout);
        if (r.startFailure().isPresent()) {
            throw new RemoteException(RemoteException.Kind.FAILED, "cannot run ssh: " + r.startFailure().get());
        }
        if (r.timedOut()) {
            throw lostOr(RemoteException.Kind.TIMED_OUT, "the remote command timed out after " + timeout.toSeconds()
                    + "s");
        }
        int code = r.exitCode().getAsInt();
        if (code == 255) {
            throw new RemoteException(RemoteException.Kind.DISCONNECTED, "ssh could not use the shared connection: "
                    + clip(r.stderr()));
        }
        if (code != 0) {
            throw new RemoteException(RemoteException.Kind.FAILED, "the remote command exited with " + code + ": "
                    + clip(r.stderr() + r.stdout()));
        }
        return r.stdout();
    }

    /**
     * A failure that may be caused by a dead connection: DISCONNECTED if the connection is no longer
     * healthy (for example a master whose network path vanished, where commands hang), otherwise
     * {@code kind}.
     */
    private RemoteException lostOr(RemoteException.Kind kind, String message) {
        Cluster.ConnectionCheck health = connection.health();
        if (!health.alive()) {
            return new RemoteException(RemoteException.Kind.DISCONNECTED, message + "; the shared connection is not "
                    + "usable: " + health.detail());
        }
        return new RemoteException(kind, message);
    }

    /** Lines that start with {@code prefix}, without it. */
    static List<String> marked(String out, String prefix) {
        List<String> lines = new ArrayList<>();
        for (String line : out.split("\n", -1)) {
            String l = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (l.startsWith(prefix)) {
                lines.add(l.substring(prefix.length()));
            } else if (l.equals(prefix.strip())) {
                lines.add("");
            }
        }
        return lines;
    }

    private static String path(String remoteDir) throws RemoteException {
        if (!RemoteSettings.isSafeRemotePath(remoteDir)) {
            throw new RemoteException(RemoteException.Kind.FAILED, "unsafe remote path " + remoteDir);
        }
        return SshConnection.quote(remoteDir);
    }

    private static String relative(String file) throws RemoteException {
        if (!RELATIVE_FILE.matcher(file).matches() || List.of(file.split("/")).contains("..")
                || file.startsWith(".")) {
            throw new RemoteException(RemoteException.Kind.FAILED, "unsafe file name " + file);
        }
        return file;
    }

    private static void check(Pattern p, String value, String what) throws RemoteException {
        if (!p.matcher(value).matches()) {
            throw new RemoteException(RemoteException.Kind.FAILED, "invalid " + what + " '" + value + "'");
        }
    }

    private static String trimSlash(String s) {
        return s.length() > 1 && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    static String clip(String s) {
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() > 400 ? t.substring(0, 400) + "…" : t;
    }
}
