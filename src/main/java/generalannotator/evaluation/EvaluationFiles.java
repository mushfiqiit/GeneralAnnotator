package generalannotator.evaluation;

import generalannotator.inference.SubmissionPreparer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** File helpers of an evaluation directory: atomic writes, the writer lock and tree hashes. */
public final class EvaluationFiles {

    public static final String LOCK = "lock";

    private EvaluationFiles() {}

    /** Writes {@code text} to a temporary file beside {@code target}, forces it, then renames it into place. */
    public static void writeAtomically(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling("." + target.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Held by the one process allowed to write an evaluation directory. */
    public static final class Lock implements AutoCloseable {
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

    /** Another process (or thread) holds the lock. */
    public static final class BusyException extends Exception {
        private static final long serialVersionUID = 1L;

        BusyException(String message) {
            super(message);
        }
    }

    /** Takes the exclusive OS lock on {@code dir/lock} without waiting. */
    public static Lock lock(Path dir, String holder) throws IOException, BusyException {
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
            String recorded = "";
            try {
                recorded = Files.readString(file, StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                // ignore: the holder description is informational
            }
            channel.close();
            throw new BusyException("another GeneralAnnotator process is writing " + dir
                    + (recorded.isEmpty() ? "" : " (" + recorded + ")") + "; wait for it to finish");
        }
        channel.truncate(0);
        channel.write(ByteBuffer.wrap(holder.getBytes(StandardCharsets.UTF_8)), 0);
        return new Lock(channel, lock);
    }

    /**
     * SHA-256 over a directory tree: every regular file's relative path and content hash, sorted by path.
     * Symbolic links are not followed; their paths are included as links, so adding one changes the hash.
     */
    public static String treeHash(Path dir) throws IOException {
        List<String> lines = new ArrayList<>();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String rel = dir.relativize(file).toString().replace('\\', '/');
                if (attrs.isSymbolicLink()) {
                    lines.add(rel + "\0link");
                } else if (attrs.isRegularFile()) {
                    lines.add(rel + "\0" + SubmissionPreparer.sha256(Files.readAllBytes(file)));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        lines.sort(null);
        return SubmissionPreparer.sha256(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    /** SHA-256 of a file, or of a directory tree. */
    public static String hashPath(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return "tree:" + treeHash(path);
        }
        return SubmissionPreparer.sha256(Files.readAllBytes(path));
    }

    public static String sha256(String text) {
        return SubmissionPreparer.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Deletes a directory tree without following symbolic links; nothing happens if it is absent. */
    public static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
