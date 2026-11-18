package generalannotator.slicing;

import generalannotator.exec.CommandResult;
import generalannotator.exec.CommandRunner;
import generalannotator.exec.CommandSpec;
import generalannotator.exec.RecordedCommand;
import generalannotator.targets.MemberKind;
import generalannotator.targets.WarningTarget;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@link Slicer} backed by Specimin.
 *
 * <p>For each request: the snapshot's source root is copied to {@code <slice dir>/root-copy} (Specimin
 * may write into {@code --root}, for example when decompiling {@code --jarPath} jars), Specimin runs
 * with that copy as {@code --root} and {@code <slice dir>/output} as {@code --outputDirectory}, its
 * output is saved to {@code <slice dir>/logs}, and the copy is deleted. A slice is {@link
 * SliceStatus#GENERATED} only if Specimin exited with 0 within the timeout and wrote at least one
 * {@code .java} file.
 */
public final class SpeciminSlicer implements Slicer {

    static final String ROOT_COPY = "root-copy";
    static final String OUTPUT = "output";
    static final String LOGS = "logs";

    private final SpeciminDistribution distribution;
    private final Path javaHome;
    private final Optional<Path> jarPath;
    private final CommandRunner runner;

    /**
     * @param javaHome JDK whose {@code bin/java} runs Specimin
     * @param jarPath directory of dependency jars for Specimin's {@code --jarPath}, if configured
     */
    public SpeciminSlicer(SpeciminDistribution distribution, Path javaHome, Optional<Path> jarPath,
            CommandRunner runner) {
        this.distribution = Objects.requireNonNull(distribution, "distribution");
        this.javaHome = Objects.requireNonNull(javaHome, "javaHome");
        this.jarPath = Objects.requireNonNull(jarPath, "jarPath");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /** The Specimin command for a request (an argument list; no shell involved). */
    public List<String> command(SliceRequest request) {
        WarningTarget.Resolved t = request.target();
        Path dir = request.sliceDirectory();
        List<String> command = new ArrayList<>();
        command.add(javaHome.resolve("bin").resolve("java").toString());
        command.addAll(SpeciminDistribution.JVM_EXPORTS);
        command.add("-cp");
        command.add(distribution.classpath().stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        command.add(distribution.mainClass());
        command.addAll(List.of("--root", dir.resolve(ROOT_COPY).toString()));
        command.addAll(List.of("--targetFile", t.targetFile()));
        command.addAll(List.of(t.memberKind() == MemberKind.FIELD ? "--targetField" : "--targetMethod", t.target()));
        command.addAll(List.of("--modularityModel", request.modularityModel()));
        command.addAll(List.of("--outputDirectory", dir.resolve(OUTPUT).toString()));
        jarPath.ifPresent(p -> command.addAll(List.of("--jarPath", p.toString())));
        if (t.requiresDisableRootValidation()) {
            command.add("--disable-root-validation");
        }
        return command;
    }

    @Override
    public SliceResult slice(SliceRequest request) {
        Path dir = request.sliceDirectory();
        Path rootCopy = dir.resolve(ROOT_COPY);
        Path output = dir.resolve(OUTPUT);
        try {
            Files.createDirectories(dir);
            copyTree(request.snapshotSourceRoot(), rootCopy);
        } catch (IOException | UncheckedIOException e) {
            deleteQuietly(rootCopy);
            return failed(request, Optional.empty(), "could not copy the snapshot source root "
                    + request.snapshotSourceRoot() + ": " + e.getMessage());
        }
        try {
            CommandResult result = runner.run(new CommandSpec(command(request), dir, Map.of(), request.timeout()));
            RecordedCommand recorded = RecordedCommand.save(dir.resolve(LOGS), "specimin", result);
            Optional<RecordedCommand> cmd = Optional.of(recorded);
            if (result.startFailure().isPresent()) {
                return failed(request, cmd, "Specimin could not be started: " + result.startFailure().get());
            }
            if (result.timedOut()) {
                return failed(request, cmd, "Specimin exceeded the timeout of " + request.timeout().toSeconds()
                        + "s and was killed");
            }
            if (!result.succeeded()) {
                return failed(request, cmd, "Specimin exited with " + result.exitCode().orElseThrow()
                        + firstErrorLine(result.stderr()));
            }
            List<String> javaFiles = javaFiles(output);
            if (javaFiles.isEmpty()) {
                return failed(request, cmd, "Specimin exited with 0 but wrote no Java files to " + output);
            }
            return new SliceResult(request.warningId(), request.target(), SliceStatus.GENERATED, cmd,
                    Optional.of(output), javaFiles, Optional.empty());
        } catch (IOException e) {
            return failed(request, Optional.empty(), "I/O error while running Specimin: " + e.getMessage());
        } finally {
            deleteQuietly(rootCopy);
        }
    }

    private static SliceResult failed(SliceRequest request, Optional<RecordedCommand> command, String reason) {
        Path output = request.sliceDirectory().resolve(OUTPUT);
        List<String> partial;
        try {
            partial = javaFiles(output);
        } catch (IOException e) {
            partial = List.of();
        }
        return new SliceResult(request.warningId(), request.target(), SliceStatus.FAILED, command,
                command.isPresent() ? Optional.of(output) : Optional.empty(), partial, Optional.of(reason));
    }

    /** The most informative stderr line: an exception message if present, else the first line. */
    static String firstErrorLine(String stderr) {
        List<String> lines = stderr.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        if (lines.isEmpty()) {
            return "";
        }
        String best = lines.stream().filter(l -> l.startsWith("Exception in thread") || l.startsWith("Caused by:"))
                .findFirst().orElse(lines.getFirst());
        return ": " + (best.length() > 500 ? best.substring(0, 500) + "..." : best);
    }

    static List<String> javaFiles(Path output) throws IOException {
        if (!Files.isDirectory(output)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(output)) {
            return files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".java"))
                    .map(p -> output.relativize(p).toString().replace(File.separatorChar, '/'))
                    .sorted()
                    .toList();
        }
    }

    /** Copies regular files and directories; symbolic links are not followed or copied. */
    static void copyTree(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(source + " is not a directory");
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    Files.copy(file, target.resolve(source.relativize(file).toString()),
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteQuietly(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            // Best effort: a leftover disposable copy is harmless and is reported by its presence.
        }
    }
}
