package generalannotator.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Validated, checker-independent configuration for one analysis run.
 *
 * <p>Checker-specific options (for example NullAway's annotated packages) are read and validated
 * by the checker adapter, not here.
 *
 * @param checkerId the selected checker identifier
 * @param checkerHome the checker implementation checkout (absolute, normalized)
 * @param targetProject the target project root (absolute, normalized)
 * @param sourceRoots source roots relative to the target root, in configuration order
 * @param classpath extra compile classpath entries: absolute paths, or paths relative to the
 *     target root (those are resolved inside the isolated copy)
 * @param checkerJavaHome the JDK used to build the checker and run the compiler
 * @param javacRelease value for javac {@code --release}, if configured
 * @param timeout per-command timeout for external commands
 * @param outputDirectory directory that receives one sub-directory per run
 */
public record AnalysisConfig(
        String checkerId,
        Path checkerHome,
        Path targetProject,
        List<Path> sourceRoots,
        List<ClasspathEntry> classpath,
        Path checkerJavaHome,
        OptionalInt javacRelease,
        Duration timeout,
        Path outputDirectory) {

    /** A classpath entry; relative entries are relative to the target root. */
    public record ClasspathEntry(Path path, boolean relativeToTarget) {
        public ClasspathEntry {
            Objects.requireNonNull(path, "path");
        }
    }

    public AnalysisConfig {
        Objects.requireNonNull(checkerId, "checkerId");
        Objects.requireNonNull(checkerHome, "checkerHome");
        Objects.requireNonNull(targetProject, "targetProject");
        sourceRoots = List.copyOf(sourceRoots);
        classpath = List.copyOf(classpath);
        Objects.requireNonNull(checkerJavaHome, "checkerJavaHome");
        Objects.requireNonNull(javacRelease, "javacRelease");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
    }
}
