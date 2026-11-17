package generalannotator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.cli.CheckCommand;
import generalannotator.cli.ExitCode;
import generalannotator.config.ConfigLoader;
import generalannotator.exec.ProcessCommandRunner;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The full {@code check} command against the real local NullAway and a fixture target. */
class CheckCommandIntegrationTest {

    @TempDir Path temp;

    @Test
    void checkCommandReportsFixtureFindings() throws Exception {
        Path output = temp.resolve("runs dir");
        Map<String, String> env = Map.of(
                ConfigLoader.CHECKER, "nullaway",
                ConfigLoader.CHECKER_HOME, IntegrationEnvironment.nullAwayCheckout().toString(),
                ConfigLoader.TARGET_PROJECT, IntegrationEnvironment.fixture("simple project").toString(),
                ConfigLoader.TARGET_SOURCE_ROOTS, "src/main/java",
                ConfigLoader.CHECKER_JAVA_HOME, IntegrationEnvironment.checkerJavaHome().toString(),
                ConfigLoader.OUTPUT_DIR, output.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        ExitCode exit = new CheckCommand(env, temp, IntegrationEnvironment.checkerJavaHome(),
                new ProcessCommandRunner(), Clock.systemUTC(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run();

        String summary = out.toString(StandardCharsets.UTF_8);
        System.out.println(summary);
        assertEquals(ExitCode.COMPLETED, exit, summary + err.toString(StandardCharsets.UTF_8));
        assertTrue(summary.contains("findings: 2 {error=2}"), summary);
        Path runDir;
        try (Stream<Path> dirs = Files.list(output)) {
            List<Path> all = dirs.toList();
            assertEquals(1, all.size());
            runDir = all.getFirst();
        }
        String report = Files.readString(runDir.resolve("report.json"));
        assertTrue(report.contains("\"targetPath\": \"src/main/java/com/example/fixture/Greeter.java\""), report);
        assertTrue(report.contains("\"checkerActive\": true"));
        assertTrue(report.contains("\"mechanism\": \"git\""));
    }
}
