package generalannotator.isolation;

import generalannotator.exec.RecordedCommand;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An isolated copy of a target project's current working tree.
 *
 * @param source the original target project (never modified)
 * @param root the root of the copy
 * @param mechanism how the file list was determined: {@code git} or {@code filesystem-walk}
 * @param gitHead the target's {@code HEAD} commit, if the target is a git work tree with commits
 * @param gitStatusEntries number of {@code git status --porcelain} entries (uncommitted changes and
 *     untracked files) at copy time, if git was used
 * @param filesCopied number of regular files copied
 * @param skippedSymlinks target-relative paths of symbolic links that were not copied
 * @param skippedOther target-relative paths listed but not copied, with a reason
 * @param commands external commands run to build the copy (git), in order
 */
public record WorkingCopy(
        Path source,
        Path root,
        String mechanism,
        Optional<String> gitHead,
        Optional<Integer> gitStatusEntries,
        int filesCopied,
        List<String> skippedSymlinks,
        List<String> skippedOther,
        List<RecordedCommand> commands) {

    public WorkingCopy {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(mechanism, "mechanism");
        Objects.requireNonNull(gitHead, "gitHead");
        Objects.requireNonNull(gitStatusEntries, "gitStatusEntries");
        skippedSymlinks = List.copyOf(skippedSymlinks);
        skippedOther = List.copyOf(skippedOther);
        commands = List.copyOf(commands);
    }
}
