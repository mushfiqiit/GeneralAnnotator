package generalannotator.config;

import generalannotator.config.AnalysisConfig.ClasspathEntry;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Reads and validates {@link AnalysisConfig} from environment variables.
 *
 * <p>The environment is passed in as a map so that validation is testable without touching the
 * real process environment. All problems are collected and reported together.
 */
public final class ConfigLoader {

    public static final String CHECKER = "GA_CHECKER";
    public static final String CHECKER_HOME = "GA_CHECKER_HOME";
    public static final String TARGET_PROJECT = "GA_TARGET_PROJECT";
    public static final String TARGET_SOURCE_ROOTS = "GA_TARGET_SOURCE_ROOTS";
    public static final String TARGET_CLASSPATH = "GA_TARGET_CLASSPATH";
    public static final String CHECKER_JAVA_HOME = "GA_CHECKER_JAVA_HOME";
    public static final String JAVAC_RELEASE = "GA_JAVAC_RELEASE";
    public static final String TIMEOUT_SECONDS = "GA_TIMEOUT_SECONDS";
    public static final String OUTPUT_DIR = "GA_OUTPUT_DIR";

    /** Checker identifiers this build supports. */
    public static final Set<String> SUPPORTED_CHECKERS = Set.of("nullaway");

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(20);

    private final Map<String, String> env;
    private final Path workingDirectory;
    private final Path defaultJavaHome;
    private final List<String> problems = new ArrayList<>();

    /**
     * @param env environment variables
     * @param workingDirectory base for the default output directory ({@code <dir>/runs})
     * @param defaultJavaHome JDK used when {@value #CHECKER_JAVA_HOME} is unset
     */
    public ConfigLoader(Map<String, String> env, Path workingDirectory, Path defaultJavaHome) {
        this.env = Map.copyOf(env);
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        this.defaultJavaHome = defaultJavaHome;
    }

    /** Validates the environment and returns the configuration, or throws with every problem. */
    public AnalysisConfig load() throws ConfigurationException {
        problems.clear();
        String checker = checkerId();
        Path checkerHome = requiredDirectory(CHECKER_HOME);
        Path target = requiredDirectory(TARGET_PROJECT);
        List<Path> sourceRoots = sourceRoots(target);
        return finish(checker, checkerHome, target, sourceRoots, outputDirectory(target));
    }

    /**
     * The configuration for checking an existing snapshot, such as a run's isolated copy: the target
     * and its source roots are given (for example from the run's {@code report.json}) instead of being
     * read from {@value #TARGET_PROJECT} and {@value #TARGET_SOURCE_ROOTS}, which are ignored. Every
     * other setting is read and validated exactly as by {@link #load()}; relative classpath entries
     * are resolved against {@code snapshot}.
     *
     * @param sourceRoots source roots relative to {@code snapshot}; each must be a directory in it
     * @param outputDirectory where results go (outside {@code snapshot})
     */
    public AnalysisConfig loadForSnapshot(Path snapshot, List<String> sourceRoots, Path outputDirectory)
            throws ConfigurationException {
        problems.clear();
        String checker = checkerId();
        Path checkerHome = requiredDirectory(CHECKER_HOME);
        Path target = snapshot.toAbsolutePath().normalize();
        List<Path> roots = new ArrayList<>();
        for (String raw : sourceRoots) {
            Path relative = toPath("source root", raw);
            if (relative == null) {
                continue;
            }
            relative = relative.normalize();
            if (relative.isAbsolute() || relative.toString().isEmpty() || relative.startsWith("..")
                    || !Files.isDirectory(target.resolve(relative))) {
                problems.add("source root '" + raw + "' is not a directory inside " + target + ".");
                continue;
            }
            roots.add(relative);
        }
        if (roots.isEmpty() && problems.stream().noneMatch(p -> p.startsWith("source root"))) {
            problems.add("no source roots were given for " + target + ".");
        }
        Path output = outputDirectory.toAbsolutePath().normalize();
        if (output.startsWith(target)) {
            problems.add("The output directory " + output + " is inside the checked snapshot " + target + ".");
        }
        return finish(checker, checkerHome, target, roots, output);
    }

    private AnalysisConfig finish(String checker, Path checkerHome, Path target, List<Path> sourceRoots, Path output)
            throws ConfigurationException {
        List<ClasspathEntry> classpath = classpath(target);
        Path javaHome = javaHome();
        OptionalInt release = javacRelease();
        Duration timeout = timeout();

        if (!problems.isEmpty()) {
            throw new ConfigurationException(problems);
        }
        return new AnalysisConfig(
                checker, checkerHome, target, sourceRoots, classpath, javaHome, release, timeout, output);
    }

    private String checkerId() {
        String value = required(CHECKER);
        if (value == null) {
            return null;
        }
        if (!SUPPORTED_CHECKERS.contains(value)) {
            problems.add(CHECKER + "='" + value + "' is not a supported checker. Supported: "
                    + String.join(", ", SUPPORTED_CHECKERS.stream().sorted().toList()) + ".");
        }
        return value;
    }

    private String required(String name) {
        String value = env.get(name);
        if (value == null) {
            problems.add(name + " is not set.");
            return null;
        }
        if (value.isBlank()) {
            problems.add(name + " is blank.");
            return null;
        }
        return value.strip();
    }

    private String optional(String name) {
        String value = env.get(name);
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            problems.add(name + " is set but blank; unset it to use the default.");
            return null;
        }
        return value.strip();
    }

    private Path requiredDirectory(String name) {
        String value = required(name);
        return value == null ? null : existingDirectory(name, value);
    }

    private Path existingDirectory(String name, String value) {
        Path path = toPath(name, value);
        if (path == null) {
            return null;
        }
        if (!path.isAbsolute()) {
            problems.add(name + "='" + value + "' must be an absolute path.");
            return null;
        }
        if (!Files.exists(path)) {
            problems.add(name + "='" + value + "' does not exist.");
            return null;
        }
        if (!Files.isDirectory(path)) {
            problems.add(name + "='" + value + "' is not a directory.");
            return null;
        }
        return path.normalize();
    }

    private Path toPath(String name, String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            problems.add(name + "='" + value + "' is not a valid path: " + e.getMessage());
            return null;
        }
    }

    private List<Path> sourceRoots(Path target) {
        String value = required(TARGET_SOURCE_ROOTS);
        if (value == null) {
            return List.of();
        }
        List<Path> roots = new ArrayList<>();
        for (String raw : splitPathList(value)) {
            Path relative = toPath(TARGET_SOURCE_ROOTS, raw);
            if (relative == null) {
                continue;
            }
            if (relative.isAbsolute()) {
                problems.add(TARGET_SOURCE_ROOTS + " entry '" + raw
                        + "' must be relative to " + TARGET_PROJECT + ".");
                continue;
            }
            relative = relative.normalize();
            if (relative.toString().isEmpty() || relative.startsWith("..")) {
                problems.add(TARGET_SOURCE_ROOTS + " entry '" + raw
                        + "' must name a directory inside " + TARGET_PROJECT + ".");
                continue;
            }
            if (target != null && !Files.isDirectory(target.resolve(relative))) {
                problems.add(TARGET_SOURCE_ROOTS + " entry '" + raw + "' is not a directory under "
                        + target + ".");
                continue;
            }
            roots.add(relative);
        }
        for (int i = 0; i < roots.size(); i++) {
            for (int j = 0; j < roots.size(); j++) {
                if (i != j && roots.get(i).startsWith(roots.get(j))) {
                    problems.add(TARGET_SOURCE_ROOTS + " entries '" + roots.get(j) + "' and '"
                            + roots.get(i) + "' overlap.");
                }
            }
        }
        if (roots.isEmpty() && !problems.stream().anyMatch(p -> p.startsWith(TARGET_SOURCE_ROOTS))) {
            problems.add(TARGET_SOURCE_ROOTS + " contains no entries.");
        }
        return roots;
    }

    private List<ClasspathEntry> classpath(Path target) {
        String value = optional(TARGET_CLASSPATH);
        if (value == null) {
            return List.of();
        }
        List<ClasspathEntry> entries = new ArrayList<>();
        for (String raw : splitPathList(value)) {
            Path path = toPath(TARGET_CLASSPATH, raw);
            if (path == null) {
                continue;
            }
            boolean relative = !path.isAbsolute();
            Path resolved = relative && target != null ? target.resolve(path) : path;
            if (relative && path.normalize().startsWith("..")) {
                problems.add(TARGET_CLASSPATH + " entry '" + raw
                        + "' is relative but points outside " + TARGET_PROJECT
                        + "; use an absolute path.");
                continue;
            }
            if ((target != null || !relative) && !Files.exists(resolved)) {
                problems.add(TARGET_CLASSPATH + " entry '" + raw + "' does not exist.");
                continue;
            }
            entries.add(new ClasspathEntry(path.normalize(), relative));
        }
        return entries;
    }

    private Path javaHome() {
        String value = optional(CHECKER_JAVA_HOME);
        Path home;
        String source;
        if (value == null) {
            home = defaultJavaHome;
            source = "the JDK running GeneralAnnotator (" + defaultJavaHome + ")";
        } else {
            home = existingDirectory(CHECKER_JAVA_HOME, value);
            source = CHECKER_JAVA_HOME + "='" + value + "'";
        }
        if (home == null) {
            return null;
        }
        if (!Files.isExecutable(home.resolve("bin").resolve(executable("javac")))) {
            problems.add(source + " is not a JDK: bin/javac is missing or not executable.");
            return null;
        }
        return home;
    }

    private OptionalInt javacRelease() {
        String value = optional(JAVAC_RELEASE);
        if (value == null) {
            return OptionalInt.empty();
        }
        try {
            int release = Integer.parseInt(value);
            if (release < 8) {
                problems.add(JAVAC_RELEASE + "='" + value + "' must be 8 or greater.");
                return OptionalInt.empty();
            }
            return OptionalInt.of(release);
        } catch (NumberFormatException e) {
            problems.add(JAVAC_RELEASE + "='" + value + "' is not an integer.");
            return OptionalInt.empty();
        }
    }

    private Duration timeout() {
        String value = optional(TIMEOUT_SECONDS);
        if (value == null) {
            return DEFAULT_TIMEOUT;
        }
        try {
            long seconds = Long.parseLong(value);
            if (seconds <= 0) {
                problems.add(TIMEOUT_SECONDS + "='" + value + "' must be a positive number of seconds.");
                return DEFAULT_TIMEOUT;
            }
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            problems.add(TIMEOUT_SECONDS + "='" + value + "' is not an integer number of seconds.");
            return DEFAULT_TIMEOUT;
        }
    }

    private Path outputDirectory(Path target) {
        String value = optional(OUTPUT_DIR);
        Path output;
        if (value == null) {
            output = workingDirectory.resolve("runs");
        } else {
            output = toPath(OUTPUT_DIR, value);
            if (output == null) {
                return null;
            }
            output = workingDirectory.resolve(output).normalize();
            if (Files.exists(output) && !Files.isDirectory(output)) {
                problems.add(OUTPUT_DIR + "='" + value + "' exists and is not a directory.");
                return null;
            }
        }
        if (target != null && output.startsWith(target)) {
            problems.add("The output directory " + output + " is inside " + TARGET_PROJECT
                    + "; choose a location outside the target project (" + OUTPUT_DIR + ").");
            return null;
        }
        return output;
    }

    /** Splits a {@link File#pathSeparator}-separated list, ignoring empty entries. */
    static List<String> splitPathList(String value) {
        List<String> parts = new ArrayList<>();
        for (String part : value.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!part.isBlank()) {
                parts.add(part.strip());
            }
        }
        return parts;
    }

    private static String executable(String name) {
        return System.getProperty("os.name", "").startsWith("Windows") ? name + ".exe" : name;
    }
}
