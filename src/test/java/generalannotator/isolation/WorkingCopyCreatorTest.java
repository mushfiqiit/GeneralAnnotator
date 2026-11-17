package generalannotator.isolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.exec.CommandResult;
import generalannotator.exec.CommandSpec;
import generalannotator.exec.ProcessCommandRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkingCopyCreatorTest {

    @TempDir Path temp;

    private final ProcessCommandRunner runner = new ProcessCommandRunner();
    private final WorkingCopyCreator creator = new WorkingCopyCreator(runner, Duration.ofSeconds(60));

    private void git(Path dir, String... args) {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-c", "user.name=t",
                "-c", "user.email=t@example.invalid", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        CommandResult r = runner.run(new CommandSpec(command, dir, Map.of(), Duration.ofSeconds(60)));
        assertTrue(r.succeeded(), "git " + String.join(" ", args) + ": " + r.stderr());
    }

    private Path gitProject() throws IOException {
        Path project = Files.createDirectories(temp.resolve("my project"));
        Files.createDirectories(project.resolve("src/p"));
        Files.writeString(project.resolve("src/p/A.java"), "package p; class A {}\n");
        Files.writeString(project.resolve("src/p/Gone.java"), "package p; class Gone {}\n");
        Files.writeString(project.resolve(".gitignore"), "build/\n");
        git(project, "init", "-q");
        git(project, "add", ".");
        git(project, "commit", "-q", "-m", "init");
        // Working-tree state that differs from HEAD:
        Files.writeString(project.resolve("src/p/A.java"), "package p; class A { int edited; }\n");
        Files.delete(project.resolve("src/p/Gone.java"));
        Files.writeString(project.resolve("src/p/New File.java"), "package p; class NewFile {}\n");
        Files.createDirectories(project.resolve("build/classes"));
        Files.writeString(project.resolve("build/classes/Stale.class"), "stale");
        Files.createSymbolicLink(project.resolve("src/p/Link.java"), project.resolve("src/p/A.java"));
        return project;
    }

    private static List<Path> snapshot(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.sorted().toList();
        }
    }

    @Test
    void gitCopyReflectsCurrentWorkingTreeAndExcludesIgnoredFiles() throws Exception {
        Path project = gitProject();
        List<Path> before = snapshot(project);
        String editedBefore = Files.readString(project.resolve("src/p/A.java"));

        WorkingCopy copy = creator.create(project, temp.resolve("copy dir/target"), temp.resolve("logs"));

        assertEquals("git", copy.mechanism());
        assertTrue(copy.gitHead().isPresent());
        assertTrue(copy.gitStatusEntries().orElseThrow() >= 3);
        Path root = copy.root();
        assertEquals("package p; class A { int edited; }\n", Files.readString(root.resolve("src/p/A.java")),
                "uncommitted edit is copied");
        assertTrue(Files.exists(root.resolve("src/p/New File.java")), "untracked file is copied");
        assertFalse(Files.exists(root.resolve("src/p/Gone.java")), "deleted file is not resurrected");
        assertFalse(Files.exists(root.resolve("build")), "ignored build output is not copied");
        assertFalse(Files.exists(root.resolve("src/p/Link.java")), "symlink is not copied");
        assertEquals(List.of("src/p/Link.java"), copy.skippedSymlinks());
        assertTrue(copy.skippedOther().stream().anyMatch(s -> s.startsWith("src/p/Gone.java")));
        assertFalse(copy.commands().isEmpty());

        // Isolation: editing the copy does not touch the original, and the original is unchanged.
        Files.writeString(root.resolve("src/p/A.java"), "changed in copy");
        assertEquals(editedBefore, Files.readString(project.resolve("src/p/A.java")));
        assertEquals(before, snapshot(project));
    }

    @Test
    void nonGitDirectoryUsesFilesystemWalkWithExclusions() throws Exception {
        Path project = Files.createDirectories(temp.resolve("plain project"));
        Files.createDirectories(project.resolve("src/q"));
        Files.writeString(project.resolve("src/q/B.java"), "package q; class B {}\n");
        Files.createDirectories(project.resolve("build"));
        Files.writeString(project.resolve("build/out.txt"), "x");
        Files.createDirectories(project.resolve(".gradle"));
        Files.writeString(project.resolve(".gradle/cache"), "x");

        WorkingCopy copy = creator.create(project, temp.resolve("copy"), temp.resolve("logs"));

        // temp is not a git work tree, so git reports "not a repository" and the walk is used.
        assertEquals("filesystem-walk", copy.mechanism());
        assertEquals(Optional.empty(), copy.gitHead());
        assertEquals(1, copy.filesCopied());
        assertTrue(Files.exists(copy.root().resolve("src/q/B.java")));
        assertFalse(Files.exists(copy.root().resolve("build")));
    }

    @Test
    void nonEmptyDestinationIsRejected() throws IOException {
        Path project = Files.createDirectories(temp.resolve("p"));
        Path dest = Files.createDirectories(temp.resolve("dest"));
        Files.writeString(dest.resolve("existing"), "x");
        assertThrows(IsolationException.class, () -> creator.create(project, dest, temp.resolve("logs")));
    }

    @Test
    void countsPorcelainEntriesIncludingRenames() {
        assertEquals(3, WorkingCopyCreator.countStatusEntries(" M a\0R  new\0old\0?? c\0"));
        assertEquals(0, WorkingCopyCreator.countStatusEntries(""));
    }
}
