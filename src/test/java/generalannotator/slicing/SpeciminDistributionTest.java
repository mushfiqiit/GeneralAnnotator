package generalannotator.slicing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.exec.CommandRunner;
import generalannotator.exec.CommandSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpeciminDistributionTest {

    @TempDir Path temp;

    private Path home;
    private final List<CommandSpec> specs = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        home = FakeSpecimin.checkout(temp.resolve("specimin checkout"));
    }

    private CommandRunner runner(int buildExit, String mainClass) {
        return spec -> {
            specs.add(spec);
            if (FakeSpecimin.isInstallDist(spec)) {
                if (buildExit == 0) {
                    try {
                        FakeSpecimin.install(home, mainClass);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return FakeSpecimin.result(spec, buildExit, "", buildExit == 0 ? "" : "BUILD FAILED");
            }
            return FakeSpecimin.result(spec, 0, "b1e5d4347a9d\n", "");
        };
    }

    private SpeciminDistribution prepare(CommandRunner runner) throws Exception {
        return SpeciminDistribution.prepare(home, Path.of("/jdk"), runner, Duration.ofMinutes(5), temp.resolve("logs"));
    }

    @Test
    void buildsWithInstallDistAndReadsJarsAndMainClass() throws Exception {
        SpeciminDistribution d = prepare(runner(0, FakeSpecimin.MAIN_CLASS));

        CommandSpec build = specs.getFirst();
        assertEquals(List.of(home.resolve("gradlew").toString(), "--console=plain", "installDist"), build.command());
        assertEquals(home, build.workingDirectory());
        assertEquals("/jdk", build.environment().get("JAVA_HOME"));
        assertEquals(FakeSpecimin.MAIN_CLASS, d.mainClass());
        assertEquals(List.of("javaparser-core-3.28.2.jar", "specimin.jar"),
                d.classpath().stream().map(p -> p.getFileName().toString()).toList());
        assertEquals(Optional.of("b1e5d4347a9d"), d.gitHead());
        assertEquals(2, d.commands().size());
    }

    @Test
    void buildFailureIsReported() {
        var e = assertThrows(SpeciminDistribution.SetupException.class, () -> prepare(runner(1, FakeSpecimin.MAIN_CLASS)));
        assertTrue(e.getMessage().contains("./gradlew installDist` failed (exit 1)"), e.getMessage());
        assertEquals(1, e.commands().size());
    }

    @Test
    void missingMainClassIsReported() {
        var e = assertThrows(SpeciminDistribution.SetupException.class, () -> prepare(runner(0, null)));
        assertTrue(e.getMessage().contains("has no Main-Class"), e.getMessage());
    }

    @Test
    void missingDistributionIsReported() {
        CommandRunner noInstall = spec -> FakeSpecimin.result(spec, 0, "", "");
        var e = assertThrows(SpeciminDistribution.SetupException.class, () -> prepare(noInstall));
        assertTrue(e.getMessage().contains("expected exactly one distribution"), e.getMessage());
    }

    @Test
    void layoutProblemsNameMissingFiles() {
        List<String> problems = SpeciminDistribution.layoutProblems(temp);
        assertTrue(problems.stream().anyMatch(p -> p.contains("no executable gradlew")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("no build.gradle")));
        assertTrue(SpeciminDistribution.layoutProblems(home).isEmpty());
    }
}
