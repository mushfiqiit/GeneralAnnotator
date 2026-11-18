package generalannotator.slicing;

import generalannotator.exec.CommandResult;
import generalannotator.exec.CommandRunner;
import generalannotator.exec.CommandSpec;
import generalannotator.exec.RecordedCommand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * A runnable Specimin built from a local checkout ({@code GA_SPECIMIN_HOME}).
 *
 * <p>Setup runs the checkout's own Gradle wrapper: {@code ./gradlew --console=plain installDist}
 * (Specimin uses the Gradle {@code application} plugin). It then reads the jars in {@code
 * build/install/<name>/lib} and the main class from {@code <name>.jar}'s manifest. Specimin is run
 * with {@code java} directly, because the generated start script lacks the {@code --add-exports}
 * flags that Specimin's build gives its own {@code run} task (needed by google-java-format on JDK
 * 16+). Those flags are in {@link #JVM_EXPORTS}.
 *
 * @param home the Specimin checkout
 * @param classpath the distribution's jars, sorted
 * @param mainClass Specimin's main class
 * @param gitHead the checkout's commit, if git could tell
 * @param commands the setup commands (build, git), with logs
 */
public record SpeciminDistribution(
        Path home, List<Path> classpath, String mainClass, Optional<String> gitHead, List<RecordedCommand> commands) {

    /** The JVM flags of Specimin's {@code tasks.withType(JavaExec)} configuration in its build.gradle. */
    public static final List<String> JVM_EXPORTS = List.of(
            "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED");

    public SpeciminDistribution {
        classpath = List.copyOf(classpath);
        commands = List.copyOf(commands);
    }

    /** Specimin could not be prepared. */
    public static final class SetupException extends Exception {
        private static final long serialVersionUID = 1L;

        @SuppressWarnings("serial") // List.copyOf returns a serializable list; records are not serialized
        private final List<RecordedCommand> commands;

        SetupException(String message, List<RecordedCommand> commands) {
            super(message);
            this.commands = List.copyOf(commands);
        }

        public List<RecordedCommand> commands() {
            return commands;
        }
    }

    /** Checks that {@code home} looks like a Specimin checkout; returns problems (empty if fine). */
    public static List<String> layoutProblems(Path home) {
        List<String> problems = new ArrayList<>();
        if (!Files.isExecutable(home.resolve("gradlew"))) {
            problems.add(home + " has no executable gradlew");
        }
        if (!Files.isRegularFile(home.resolve("build.gradle")) && !Files.isRegularFile(home.resolve("build.gradle.kts"))) {
            problems.add(home + " has no build.gradle");
        }
        return problems;
    }

    /**
     * Builds the distribution and locates its jars and main class.
     *
     * @param javaHome JDK used as JAVA_HOME for the Gradle build
     * @param logsDir where setup logs are saved
     */
    public static SpeciminDistribution prepare(Path home, Path javaHome, CommandRunner runner, Duration timeout,
            Path logsDir) throws SetupException, IOException {
        List<RecordedCommand> commands = new ArrayList<>();
        CommandResult build = runner.run(new CommandSpec(
                List.of(home.resolve("gradlew").toString(), "--console=plain", "installDist"),
                home, Map.of("JAVA_HOME", javaHome.toString()), timeout));
        commands.add(RecordedCommand.save(logsDir, "specimin-setup", build));
        if (!build.succeeded()) {
            throw new SetupException("building Specimin in " + home + " with `./gradlew installDist` failed ("
                    + build.describeStatus() + "); see " + logsDir.resolve("specimin-setup.stderr.txt"), commands);
        }

        Path installRoot = home.resolve("build").resolve("install");
        List<Path> installs;
        try (Stream<Path> dirs = Files.isDirectory(installRoot) ? Files.list(installRoot) : Stream.empty()) {
            installs = dirs.filter(Files::isDirectory).sorted().toList();
        }
        if (installs.size() != 1) {
            throw new SetupException("expected exactly one distribution in " + installRoot + " after installDist, found "
                    + installs.size(), commands);
        }
        Path install = installs.getFirst();
        Path lib = install.resolve("lib");
        List<Path> jars;
        try (Stream<Path> files = Files.isDirectory(lib) ? Files.list(lib) : Stream.empty()) {
            jars = files.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
        }
        Path mainJar = lib.resolve(install.getFileName() + ".jar");
        if (jars.isEmpty() || !Files.isRegularFile(mainJar)) {
            throw new SetupException("the distribution in " + install + " has no " + mainJar.getFileName(), commands);
        }
        String mainClass;
        try (JarFile jar = new JarFile(mainJar.toFile())) {
            mainClass = jar.getManifest() == null ? null : jar.getManifest().getMainAttributes().getValue("Main-Class");
        }
        if (mainClass == null || mainClass.isBlank()) {
            throw new SetupException(mainJar + " has no Main-Class in its manifest", commands);
        }

        CommandResult git = runner.run(new CommandSpec(
                List.of("git", "--no-optional-locks", "rev-parse", "HEAD"), home, Map.of(), Duration.ofSeconds(60)));
        commands.add(RecordedCommand.save(logsDir, "specimin-git-head", git));
        Optional<String> head = git.succeeded() ? Optional.of(git.stdout().strip()) : Optional.empty();
        return new SpeciminDistribution(home, jars, mainClass.strip(), head, commands);
    }
}
