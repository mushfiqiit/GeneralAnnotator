package generalannotator.checker;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Inputs to one checker run. All paths refer to the isolated working copy or to GeneralAnnotator's
 * own run directory, never to the original target project.
 *
 * @param workingCopyRoot root of the isolated copy of the target
 * @param sourceRoots source roots relative to {@code workingCopyRoot}
 * @param classpath resolved compile classpath entries
 * @param javaHome JDK used to run the compiler
 * @param javacRelease javac {@code --release} value, if any
 * @param runDirectory this run's output directory (for generated files and logs)
 * @param timeout per-command timeout
 */
public record AnalysisRequest(
        Path workingCopyRoot,
        List<Path> sourceRoots,
        List<Path> classpath,
        Path javaHome,
        OptionalInt javacRelease,
        Path runDirectory,
        Duration timeout) {

    public AnalysisRequest {
        Objects.requireNonNull(workingCopyRoot, "workingCopyRoot");
        sourceRoots = List.copyOf(sourceRoots);
        classpath = List.copyOf(classpath);
        Objects.requireNonNull(javaHome, "javaHome");
        Objects.requireNonNull(javacRelease, "javacRelease");
        Objects.requireNonNull(runDirectory, "runDirectory");
        Objects.requireNonNull(timeout, "timeout");
    }

    public Path logsDirectory() {
        return runDirectory.resolve("logs");
    }

    public Path generatedDirectory() {
        return runDirectory.resolve("generated");
    }
}
