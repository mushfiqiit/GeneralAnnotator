package generalannotator.slicing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.exec.CommandResult;
import generalannotator.exec.CommandRunner;
import generalannotator.exec.CommandSpec;
import generalannotator.targets.MemberKind;
import generalannotator.targets.WarningTarget;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpeciminSlicerTest {

    @TempDir Path temp;

    private Path snapshotRoot;
    private Path sliceDir;
    private final List<CommandSpec> specs = new ArrayList<>();
    private final SpeciminDistribution distribution = new SpeciminDistribution(Path.of("/specimin home"),
            List.of(Path.of("/specimin home/lib/a.jar"), Path.of("/specimin home/lib/specimin.jar")),
            "org.checkerframework.specimin.SpeciminRunner", Optional.of("abc123"), List.of());

    @BeforeEach
    void setUp() throws IOException {
        snapshotRoot = Files.createDirectories(temp.resolve("run dir/workspace/target/lib src"));
        Files.createDirectories(snapshotRoot.resolve("a/b"));
        Files.writeString(snapshotRoot.resolve("a/b/Box.java"), "package a.b; class Box {}\n");
        sliceDir = temp.resolve("run dir/slices/W0001");
    }

    static WarningTarget.Resolved target(String id, MemberKind kind, String target, boolean disableRootValidation) {
        return new WarningTarget.Resolved(id, "lib src/a/b/Box.java:1:1", "lib src", "/unused", "a/b/Box.java", kind,
                "a.b.Box", target, Optional.of("lib src"), disableRootValidation, List.of());
    }

    private SliceRequest request(WarningTarget.Resolved target) {
        return new SliceRequest(target, snapshotRoot, sliceDir, "nullaway", Duration.ofSeconds(42));
    }

    /** Fake runner: records the spec, lets {@code behaviour} act, and returns its result. */
    private CommandRunner fake(Function<CommandSpec, CommandResult> behaviour) {
        return spec -> {
            specs.add(spec);
            return behaviour.apply(spec);
        };
    }

    private static CommandResult exit(CommandSpec spec, int code, String stderr) {
        return new CommandResult(spec, OptionalInt.of(code), "", stderr, Duration.ofMillis(5), false, Optional.empty());
    }

    private static Path argAfter(CommandSpec spec, String option) {
        return Path.of(spec.command().get(spec.command().indexOf(option) + 1));
    }

    /** Writes the given Java files into Specimin's --outputDirectory. */
    private static void writeOutput(CommandSpec spec, String... files) {
        Path out = argAfter(spec, "--outputDirectory");
        try {
            for (String f : files) {
                Path p = out.resolve(f);
                Files.createDirectories(p.getParent());
                Files.writeString(p, "class X {}\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private SpeciminSlicer slicer(CommandRunner runner, Optional<Path> jarPath) {
        return new SpeciminSlicer(distribution, Path.of("/jdk 21"), jarPath, runner);
    }

    @Test
    void methodCommandIsAnArgumentListPreservingSpacesAndGenerics() {
        var t = target("W0001", MemberKind.METHOD, "a.b.Box#post(Object, Map<String, List<?>>)", false);
        List<String> command = slicer(fake(s -> null), Optional.empty()).command(request(t));

        List<String> expected = new ArrayList<>();
        expected.add("/jdk 21/bin/java");
        expected.addAll(SpeciminDistribution.JVM_EXPORTS);
        expected.addAll(List.of("-cp", "/specimin home/lib/a.jar:/specimin home/lib/specimin.jar",
                "org.checkerframework.specimin.SpeciminRunner",
                "--root", sliceDir.resolve("root-copy").toString(),
                "--targetFile", "a/b/Box.java",
                "--targetMethod", "a.b.Box#post(Object, Map<String, List<?>>)",
                "--modularityModel", "nullaway",
                "--outputDirectory", sliceDir.resolve("output").toString()));
        assertEquals(expected, command);
    }

    @Test
    void constructorFieldJarPathAndRootValidationOptions() {
        List<String> ctor = slicer(fake(s -> null), Optional.empty())
                .command(request(target("W0001", MemberKind.CONSTRUCTOR, "a.b.Box#Box(int)", false)));
        assertEquals("a.b.Box#Box(int)", ctor.get(ctor.indexOf("--targetMethod") + 1));
        assertFalse(ctor.contains("--targetField"));

        List<String> field = slicer(fake(s -> null), Optional.of(Path.of("/deps dir")))
                .command(request(target("W0001", MemberKind.FIELD, "a.b.Box#value", true)));
        assertEquals("a.b.Box#value", field.get(field.indexOf("--targetField") + 1));
        assertFalse(field.contains("--targetMethod"));
        assertEquals("/deps dir", field.get(field.indexOf("--jarPath") + 1));
        assertEquals("--disable-root-validation", field.getLast());
        assertFalse(ctor.contains("--disable-root-validation"));
        assertFalse(ctor.contains("--jarPath"));
    }

    @Test
    void successRequiresJavaOutputAndUsesADisposableCopy() throws IOException {
        var result = slicer(fake(spec -> {
            Path root = argAfter(spec, "--root");
            assertTrue(Files.isRegularFile(root.resolve("a/b/Box.java")), "Specimin gets a copy of the snapshot root");
            try {
                Files.writeString(root.resolve("a/b/Box.java"), "modified by the tool");   // e.g. jar decompilation
                Files.writeString(root.resolve("Decompiled.java"), "class Decompiled {}");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            writeOutput(spec, "a/b/Box.java", "a/b/Helper.java");
            return exit(spec, 0, "");
        }), Optional.empty()).slice(request(target("W0001", MemberKind.METHOD, "a.b.Box#m()", false)));

        assertEquals(SliceStatus.GENERATED, result.status());
        assertEquals(List.of("a/b/Box.java", "a/b/Helper.java"), result.outputFiles());
        assertEquals(Optional.of(sliceDir.resolve("output")), result.outputDirectory());
        assertEquals(Optional.empty(), result.failureReason());
        assertEquals(sliceDir, specs.getFirst().workingDirectory());
        assertEquals(Duration.ofSeconds(42), specs.getFirst().timeout());
        assertTrue(Files.exists(sliceDir.resolve("logs/specimin.stderr.txt")));
        assertFalse(Files.exists(sliceDir.resolve("root-copy")), "the disposable copy is deleted");
        assertEquals("package a.b; class Box {}\n", Files.readString(snapshotRoot.resolve("a/b/Box.java")),
                "the snapshot is untouched");
        assertFalse(Files.exists(snapshotRoot.resolve("Decompiled.java")));
    }

    @Test
    void nonZeroExitIsFailedWithTheExceptionMessage() {
        var result = slicer(fake(spec -> exit(spec, 1, "Exception in thread \"main\" java.lang.RuntimeException: "
                + "Specimin could not locate the following target methods\n\tat x.y(Z.java:1)\n")), Optional.empty())
                .slice(request(target("W0001", MemberKind.METHOD, "a.b.Box#nope()", false)));

        assertEquals(SliceStatus.FAILED, result.status());
        assertTrue(result.failureReason().orElseThrow().startsWith("Specimin exited with 1: Exception in thread"),
                result.failureReason().orElseThrow());
        assertTrue(result.failureReason().orElseThrow().contains("could not locate"));
        assertTrue(result.command().isPresent());
        assertFalse(Files.exists(sliceDir.resolve("root-copy")));
    }

    @Test
    void emptyOutputIsFailedEvenWithExitZero() {
        var result = slicer(fake(spec -> {
            writeOutput(spec);   // creates nothing
            return exit(spec, 0, "");
        }), Optional.empty()).slice(request(target("W0001", MemberKind.FIELD, "a.b.Box#f", false)));
        assertEquals(SliceStatus.FAILED, result.status());
        assertTrue(result.failureReason().orElseThrow().contains("wrote no Java files"));
    }

    @Test
    void timeoutAndStartFailureAreFailed() {
        var timedOut = slicer(fake(spec -> new CommandResult(spec, OptionalInt.empty(), "", "", Duration.ofSeconds(42),
                true, Optional.empty())), Optional.empty())
                .slice(request(target("W0001", MemberKind.METHOD, "a.b.Box#m()", false)));
        assertEquals(SliceStatus.FAILED, timedOut.status());
        assertTrue(timedOut.failureReason().orElseThrow().contains("timeout of 42s"));

        sliceDir = temp.resolve("run dir/slices/W0002");
        var notStarted = slicer(fake(spec -> new CommandResult(spec, OptionalInt.empty(), "", "", Duration.ZERO, false,
                Optional.of("IOException: no java"))), Optional.empty())
                .slice(request(target("W0002", MemberKind.METHOD, "a.b.Box#m()", false)));
        assertTrue(notStarted.failureReason().orElseThrow().startsWith("Specimin could not be started"));
    }

    @Test
    void missingSnapshotRootIsFailedWithoutRunningSpecimin() {
        snapshotRoot = temp.resolve("does not exist");
        var result = slicer(fake(spec -> exit(spec, 0, "")), Optional.empty())
                .slice(request(target("W0001", MemberKind.METHOD, "a.b.Box#m()", false)));
        assertEquals(SliceStatus.FAILED, result.status());
        assertTrue(result.failureReason().orElseThrow().contains("could not copy the snapshot source root"));
        assertTrue(specs.isEmpty());
    }
}
