package generalannotator.isolation;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Copies a run's isolated snapshot ({@code workspace/target}) to a fresh directory, so later stages
 * can change the copy without touching the snapshot. Every regular file is copied; symbolic links are
 * neither copied nor followed and are reported. Unlike {@link WorkingCopyCreator}, nothing is
 * excluded and git is not consulted: the snapshot already is the analyzed file set (and lives inside
 * a run directory that git ignores).
 */
public final class SnapshotCopier {

    /** What was copied. */
    public record Copy(int filesCopied, List<String> skippedSymlinks) {
        public Copy {
            skippedSymlinks = List.copyOf(skippedSymlinks);
        }
    }

    private SnapshotCopier() {}

    /** Copies {@code snapshot} into {@code destination}, which must not exist. */
    public static Copy copy(Path snapshot, Path destination) throws IOException {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(destination + " already exists");
        }
        if (Files.isSymbolicLink(snapshot) || !Files.isDirectory(snapshot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(snapshot + " is not a directory");
        }
        Files.createDirectories(destination);
        List<String> skipped = new ArrayList<>();
        int[] copied = {0};
        Files.walkFileTree(snapshot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(destination.resolve(snapshot.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String rel = snapshot.relativize(file).toString().replace('\\', '/');
                if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                    skipped.add(rel);
                    return FileVisitResult.CONTINUE;
                }
                Files.copy(file, destination.resolve(rel), StandardCopyOption.COPY_ATTRIBUTES,
                        LinkOption.NOFOLLOW_LINKS);
                copied[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        return new Copy(copied[0], skipped);
    }
}
