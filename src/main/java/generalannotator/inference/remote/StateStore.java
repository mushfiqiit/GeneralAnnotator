package generalannotator.inference.remote;

import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/**
 * The local {@code remote/} directory of a submission: {@code state.json} (written atomically),
 * {@code lock} (an exclusive OS file lock that keeps two GeneralAnnotator processes from changing the
 * same submission at once) and {@code events.log} (an append-only record of remote actions).
 */
final class StateStore {

    static final String DIR = "remote";
    static final String STATE = "state.json";
    static final String LOCK = "lock";
    static final String EVENTS = "events.log";

    private final Path dir;
    private final Clock clock;

    StateStore(Path submissionDir, Clock clock) {
        this.dir = submissionDir.resolve(DIR);
        this.clock = clock;
    }

    Path dir() {
        return dir;
    }

    Optional<RemoteState> load() throws IOException {
        Path file = dir.resolve(STATE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Object root = JsonParser.parse(Files.readString(file, StandardCharsets.UTF_8));
        if (!(root instanceof Map<?, ?> m)) {
            throw new IOException(file + " is not a JSON object");
        }
        try {
            return Optional.of(RemoteState.fromJson(m));
        } catch (RuntimeException e) {
            throw new IOException(file + " is invalid: " + e.getMessage(), e);
        }
    }

    void save(RemoteState state) throws IOException {
        writeAtomically(dir.resolve(STATE), Json.write(state.toJson()));
    }

    void event(String message) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(EVENTS), clock.instant() + "  " + message.replace('\n', ' ') + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Writes {@code text} to a temporary file beside {@code target}, then renames it into place. */
    static void writeAtomically(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling("." + target.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Held while a command changes the submission's remote state. */
    final class Lock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private Lock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    /** Thrown when another process holds the lock. */
    static final class BusyException extends Exception {
        private static final long serialVersionUID = 1L;

        BusyException(String message) {
            super(message);
        }
    }

    Lock lock() throws IOException, BusyException {
        Files.createDirectories(dir);
        Path file = dir.resolve(LOCK);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            String holder = "";
            try {
                holder = Files.readString(file, StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                // ignore
            }
            channel.close();
            throw new BusyException("another GeneralAnnotator process is working on this submission"
                    + (holder.isEmpty() ? "" : " (" + holder + ")") + "; wait for it or stop it first");
        }
        channel.truncate(0);
        channel.write(ByteBuffer.wrap(("pid " + ProcessHandle.current().pid() + " since " + clock.instant())
                .getBytes(StandardCharsets.UTF_8)), 0);
        return new Lock(channel, lock);
    }
}
