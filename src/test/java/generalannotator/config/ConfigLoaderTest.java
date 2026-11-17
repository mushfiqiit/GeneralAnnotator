package generalannotator.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    @TempDir Path temp;

    private Path checkerHome;
    private Path target;
    private Path jdk;
    private Map<String, String> env;

    @BeforeEach
    void setUp() throws IOException {
        checkerHome = Files.createDirectories(temp.resolve("checker home"));
        target = Files.createDirectories(temp.resolve("target project"));
        Files.createDirectories(target.resolve("core/src main"));
        Files.createDirectories(target.resolve("other"));
        jdk = Files.createDirectories(temp.resolve("fake jdk"));
        Path javac = Files.createDirectories(jdk.resolve("bin")).resolve("javac");
        Files.writeString(javac, "");
        Files.setPosixFilePermissions(javac, PosixFilePermissions.fromString("rwxr-xr-x"));

        env = new HashMap<>();
        env.put(ConfigLoader.CHECKER, "nullaway");
        env.put(ConfigLoader.CHECKER_HOME, checkerHome.toString());
        env.put(ConfigLoader.TARGET_PROJECT, target.toString());
        env.put(ConfigLoader.TARGET_SOURCE_ROOTS, "core/src main");
    }

    private AnalysisConfig load() throws ConfigurationException {
        return new ConfigLoader(env, temp.resolve("work dir"), jdk).load();
    }

    private List<String> problems() {
        return assertThrows(ConfigurationException.class, this::load).problems();
    }

    private static boolean anyContains(List<String> problems, String text) {
        return problems.stream().anyMatch(p -> p.contains(text));
    }

    @Test
    void validConfigurationWithSpacesInPaths() throws Exception {
        AnalysisConfig config = load();
        assertEquals("nullaway", config.checkerId());
        assertEquals(checkerHome, config.checkerHome());
        assertEquals(target, config.targetProject());
        assertEquals(List.of(Path.of("core/src main")), config.sourceRoots());
        assertEquals(jdk, config.checkerJavaHome());
        assertEquals(temp.resolve("work dir/runs"), config.outputDirectory());
        assertEquals(ConfigLoader.DEFAULT_TIMEOUT, config.timeout());
        assertEquals(OptionalInt.empty(), config.javacRelease());
    }

    @Test
    void missingRequiredVariablesAreAllReported() {
        env.clear();
        List<String> problems = problems();
        assertTrue(anyContains(problems, "GA_CHECKER is not set"));
        assertTrue(anyContains(problems, "GA_CHECKER_HOME is not set"));
        assertTrue(anyContains(problems, "GA_TARGET_PROJECT is not set"));
        assertTrue(anyContains(problems, "GA_TARGET_SOURCE_ROOTS is not set"));
    }

    @Test
    void blankValuesAreRejected() {
        env.put(ConfigLoader.CHECKER_HOME, "   ");
        env.put(ConfigLoader.TIMEOUT_SECONDS, "");
        List<String> problems = problems();
        assertTrue(anyContains(problems, "GA_CHECKER_HOME is blank"));
        assertTrue(anyContains(problems, "GA_TIMEOUT_SECONDS is set but blank"));
    }

    @Test
    void unsupportedCheckerIsRejectedExplicitly() {
        env.put(ConfigLoader.CHECKER, "checkerframework");
        List<String> problems = problems();
        assertTrue(anyContains(problems, "'checkerframework' is not a supported checker"));
        assertTrue(anyContains(problems, "Supported: nullaway"));
    }

    @Test
    void checkerIdIsCaseSensitive() {
        env.put(ConfigLoader.CHECKER, "NullAway");
        assertTrue(anyContains(problems(), "not a supported checker"));
    }

    @Test
    void nonexistentAndNonDirectoryPathsAreRejected() throws IOException {
        env.put(ConfigLoader.TARGET_PROJECT, temp.resolve("no such dir").toString());
        Path file = Files.writeString(temp.resolve("a file"), "x");
        env.put(ConfigLoader.CHECKER_HOME, file.toString());
        List<String> problems = problems();
        assertTrue(anyContains(problems, "no such dir' does not exist"));
        assertTrue(anyContains(problems, "a file' is not a directory"));
    }

    @Test
    void relativeProjectPathIsRejected() {
        env.put(ConfigLoader.TARGET_PROJECT, "relative/dir");
        assertTrue(anyContains(problems(), "must be an absolute path"));
    }

    @Test
    void sourceRootsMustBeRelativeExistingAndInside() {
        env.put(ConfigLoader.TARGET_SOURCE_ROOTS,
                String.join(File.pathSeparator, "/abs", "../escape", "missing"));
        List<String> problems = problems();
        assertTrue(anyContains(problems, "'/abs' must be relative"));
        assertTrue(anyContains(problems, "'../escape' must name a directory inside"));
        assertTrue(anyContains(problems, "'missing' is not a directory"));
    }

    @Test
    void overlappingSourceRootsAreRejected() {
        env.put(ConfigLoader.TARGET_SOURCE_ROOTS, "core" + File.pathSeparator + "core/src main");
        assertTrue(anyContains(problems(), "overlap"));
    }

    @Test
    void multipleSourceRootsAndClasspath() throws Exception {
        Path lib = Files.writeString(temp.resolve("lib dir.jar"), "");
        Files.writeString(target.resolve("other/local.jar"), "");
        env.put(ConfigLoader.TARGET_SOURCE_ROOTS, "core/src main" + File.pathSeparator + "other");
        env.put(ConfigLoader.TARGET_CLASSPATH, lib + File.pathSeparator + "other/local.jar");
        AnalysisConfig config = load();
        assertEquals(2, config.sourceRoots().size());
        assertEquals(new AnalysisConfig.ClasspathEntry(lib, false), config.classpath().get(0));
        assertEquals(
                new AnalysisConfig.ClasspathEntry(Path.of("other/local.jar"), true),
                config.classpath().get(1));
    }

    @Test
    void missingClasspathEntryIsRejected() {
        env.put(ConfigLoader.TARGET_CLASSPATH, temp.resolve("gone.jar").toString());
        assertTrue(anyContains(problems(), "gone.jar' does not exist"));
    }

    @Test
    void invalidNumbersAreRejected() {
        env.put(ConfigLoader.TIMEOUT_SECONDS, "soon");
        env.put(ConfigLoader.JAVAC_RELEASE, "7");
        List<String> problems = problems();
        assertTrue(anyContains(problems, "GA_TIMEOUT_SECONDS='soon' is not an integer"));
        assertTrue(anyContains(problems, "GA_JAVAC_RELEASE='7' must be 8 or greater"));
    }

    @Test
    void numericOptionsAreParsed() throws Exception {
        env.put(ConfigLoader.TIMEOUT_SECONDS, "30");
        env.put(ConfigLoader.JAVAC_RELEASE, "11");
        AnalysisConfig config = load();
        assertEquals(Duration.ofSeconds(30), config.timeout());
        assertEquals(OptionalInt.of(11), config.javacRelease());
    }

    @Test
    void javaHomeWithoutJavacIsRejected() throws IOException {
        env.put(ConfigLoader.CHECKER_JAVA_HOME, Files.createDirectories(temp.resolve("not a jdk")).toString());
        assertTrue(anyContains(problems(), "is not a JDK"));
    }

    @Test
    void outputDirectoryInsideTargetIsRejected() {
        env.put(ConfigLoader.OUTPUT_DIR, target.resolve("runs").toString());
        assertTrue(anyContains(problems(), "is inside GA_TARGET_PROJECT"));
    }
}
