package generalannotator.isolation;

import generalannotator.exec.CommandResult;
import generalannotator.exec.CommandRunner;
import generalannotator.exec.CommandSpec;
import generalannotator.exec.RecordedCommand;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Creates an isolated copy of a target project's current working tree (the smallest isolation
 * mechanism for this milestone; see project-spec §6 for the open question of a long-term one).
 *
 * <p>If the target is inside a git work tree, the file list is {@code git ls-files --cached
 * --others --exclude-standard}: tracked files with their current working-tree contents (including
 * uncommitted edits) plus untracked files that are not ignored. Ignored files such as build output
 * are therefore not copied. Otherwise the tree is walked, skipping {@link #WALK_EXCLUDED_DIRECTORIES}.
 *
 * <p>Symbolic links are never copied or followed (a link could point back into the original
 * project and break isolation); they are reported in {@link WorkingCopy#skippedSymlinks()}. Tracked
 * files deleted from the working tree and directory entries such as submodules are reported in
 * {@link WorkingCopy#skippedOther()}.
 */
public final class WorkingCopyCreator {

    /** Directory names skipped by the non-git fallback walk. */
    public static final Set<String> WALK_EXCLUDED_DIRECTORIES =
            Set.of(".git", ".gradle", ".idea", "build", "target", "out", "bin");

    private final CommandRunner runner;
    private final Duration timeout;

    public WorkingCopyCreator(CommandRunner runner, Duration timeout) {
        this.runner = runner;
        this.timeout = timeout;
    }

    /**
     * Copies {@code target} to {@code destination}, which must not exist yet or be empty.
     *
     * @param logsDir where git command output is saved
     */
    public WorkingCopy create(Path target, Path destination, Path logsDir) throws IsolationException {
        List<RecordedCommand> commands = new ArrayList<>();
        try {
            Path realTarget = target.toRealPath();
            if (Files.exists(destination)) {
                try (var entries = Files.list(destination)) {
                    if (entries.findAny().isPresent()) {
                        throw new IsolationException(
                                "Working-copy destination is not empty: " + destination, commands, null);
                    }
                }
            }
            Files.createDirectories(destination);

            CommandResult inside = git(target, logsDir, "isolation-git-check", commands,
                    "rev-parse", "--is-inside-work-tree");
            boolean useGit = inside.succeeded() && inside.stdout().strip().equals("true");

            List<String> files;
            Optional<String> head = Optional.empty();
            Optional<Integer> statusEntries = Optional.empty();
            if (useGit) {
                CommandResult list = git(target, logsDir, "isolation-git-ls-files", commands,
                        "ls-files", "-z", "--cached", "--others", "--exclude-standard");
                if (!list.succeeded()) {
                    throw new IsolationException("git ls-files failed in " + target + " ("
                            + list.describeStatus() + "): " + list.stderr().strip(), commands, null);
                }
                files = splitNul(list.stdout());
                CommandResult rev = git(target, logsDir, "isolation-git-head", commands, "rev-parse", "HEAD");
                if (rev.succeeded()) {
                    head = Optional.of(rev.stdout().strip());
                }
                CommandResult status = git(target, logsDir, "isolation-git-status", commands,
                        "status", "--porcelain=v1", "-z", "--untracked-files=all", "--", ".");
                if (status.succeeded()) {
                    statusEntries = Optional.of(countStatusEntries(status.stdout()));
                }
            } else {
                files = walk(realTarget);
            }

            int copied = 0;
            List<String> symlinks = new ArrayList<>();
            List<String> other = new ArrayList<>();
            for (String relative : new LinkedHashSet<>(files)) {
                Path rel = Path.of(relative);
                if (rel.isAbsolute() || rel.normalize().startsWith("..")) {
                    other.add(relative + " (path outside the project)");
                    continue;
                }
                Path source = target.resolve(rel);
                if (Files.isSymbolicLink(source)) {
                    symlinks.add(relative);
                    continue;
                }
                if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                    other.add(relative + " (listed by git but missing from the working tree)");
                    continue;
                }
                if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                    other.add(relative + " (directory entry, e.g. a git submodule; not copied)");
                    continue;
                }
                if (!source.toRealPath().startsWith(realTarget)) {
                    symlinks.add(relative + " (reached through a symbolic link directory)");
                    continue;
                }
                Path dest = destination.resolve(rel);
                Files.createDirectories(dest.getParent());
                Files.copy(source, dest, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                copied++;
            }
            return new WorkingCopy(target, destination, useGit ? "git" : "filesystem-walk", head,
                    statusEntries, copied, symlinks, other, commands);
        } catch (IOException e) {
            throw new IsolationException("Could not create working copy of " + target + ": " + e, commands, e);
        }
    }

    private CommandResult git(Path dir, Path logsDir, String purpose, List<RecordedCommand> commands,
            String... args) throws IOException {
        // --no-optional-locks keeps `git status` from refreshing (writing) the target's index.
        List<String> command = new ArrayList<>(List.of("git", "--no-optional-locks", "-c", "core.quotepath=off"));
        command.addAll(List.of(args));
        CommandResult result = runner.run(new CommandSpec(command, dir, Map.of(), timeout));
        commands.add(RecordedCommand.save(logsDir, purpose, result));
        return result;
    }

    private static List<String> splitNul(String text) {
        List<String> parts = new ArrayList<>();
        for (String part : text.split("\0")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts;
    }

    /** Counts entries in {@code git status --porcelain=v1 -z} output (renames carry an extra path). */
    static int countStatusEntries(String porcelainZ) {
        List<String> parts = splitNul(porcelainZ);
        int count = 0;
        for (int i = 0; i < parts.size(); i++) {
            String entry = parts.get(i);
            count++;
            if (entry.length() >= 2 && (entry.charAt(0) == 'R' || entry.charAt(0) == 'C')) {
                i++; // the original path of a rename/copy follows as a separate field
            }
        }
        return count;
    }

    private static List<String> walk(Path root) throws IOException {
        List<String> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && WALK_EXCLUDED_DIRECTORIES.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                files.add(root.relativize(file).toString());
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(null);
        return files;
    }
}
