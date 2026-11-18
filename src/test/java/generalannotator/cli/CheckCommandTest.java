package generalannotator.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.config.ConfigLoader;
import generalannotator.exec.CommandResult;
import generalannotator.exec.CommandRunner;
import generalannotator.exec.ProcessCommandRunner;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.graph.WarningNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end {@code check} flow with real git isolation and fake NullAway setup and javac
 * commands, so it is deterministic and needs no NullAway checkout.
 */
class CheckCommandTest {

    @TempDir Path temp;

    private Path target;
    private Path checkout;
    private Path jdk;
    private Path output;
    private Map<String, String> env;
    private String javacFindingFile = "core/src/org/ex/A.java";
    private boolean javacCompileError;
    private boolean javacNoTargetFindings;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        target = Files.createDirectories(temp.resolve("target project"));
        Files.createDirectories(target.resolve("core/src/org/ex"));
        Files.writeString(target.resolve("core/src/org/ex/A.java"), "package org.ex;\nclass A {}\n");

        checkout = Files.createDirectories(temp.resolve("null away"));
        Path gradlew = Files.writeString(checkout.resolve("gradlew"), "");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.createDirectories(checkout.resolve("nullaway/build/libs"));
        Files.writeString(checkout.resolve("nullaway/build.gradle"), "");
        Files.writeString(checkout.resolve("nullaway/build/libs/nullaway.jar"), "");
        Files.writeString(temp.resolve("error_prone_core-2.50.0.jar"), "");

        jdk = Files.createDirectories(temp.resolve("jdk/bin")).getParent();
        Path javac = Files.writeString(jdk.resolve("bin/javac"), "");
        Files.setPosixFilePermissions(javac, PosixFilePermissions.fromString("rwxr-xr-x"));

        output = temp.resolve("out dir");
        env = new HashMap<>(Map.of(
                ConfigLoader.CHECKER, "nullaway",
                ConfigLoader.CHECKER_HOME, checkout.toString(),
                ConfigLoader.TARGET_PROJECT, target.toString(),
                ConfigLoader.TARGET_SOURCE_ROOTS, "core/src",
                ConfigLoader.OUTPUT_DIR, output.toString()));
    }

    private final CommandRunner real = new ProcessCommandRunner();

    /** Real runner for git; fakes for gradlew and javac. */
    private final CommandRunner runner = spec -> {
        String program = Path.of(spec.command().getFirst()).getFileName().toString();
        try {
            if (program.equals("gradlew")) {
                String file = spec.command().stream().filter(a -> a.startsWith("-PgeneralAnnotator.output="))
                        .findFirst().orElseThrow().substring("-PgeneralAnnotator.output=".length());
                Files.writeString(Path.of(file), "nullaway-version=0.0.1-TEST\nnullaway-jar="
                        + checkout.resolve("nullaway/build/libs/nullaway.jar") + "\nerrorprone="
                        + temp.resolve("error_prone_core-2.50.0.jar") + "\n");
                return ok(spec, 0, "BUILD SUCCESSFUL");
            }
            if (program.equals("javac")) {
                Path argFile = Path.of(spec.command().getLast().substring(1));
                String probe = Files.readAllLines(argFile).stream()
                        .filter(l -> l.contains("GeneralAnnotatorProbe.java")).findFirst().orElseThrow()
                        .replace("\"", "");
                String finding = spec.workingDirectory().resolve(javacFindingFile)
                        + ":2: error: [NullAway] returning @Nullable expression\nclass A {}\n^\n"
                        + "    (see http://t.uber.com/nullaway )\n";
                String stderr = probe + ":7: error: [NullAway] dereferenced expression 'o' is @Nullable\n"
                        + "        return o.hashCode();\n               ^\n    (see http://t.uber.com/nullaway )\n"
                        + (javacNoTargetFindings ? "" : finding);
                if (javacNoTargetFindings) {
                    stderr += "1 error\n";
                } else if (javacCompileError) {
                    stderr += spec.workingDirectory().resolve(javacFindingFile)
                            + ":1: error: cannot find symbol\nimport x.Y;\n        ^\n  symbol: class Y\n3 errors\n";
                } else {
                    stderr += "2 errors\n";
                }
                return ok(spec, 1, stderr);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return real.run(spec);
    };

    private static CommandResult ok(generalannotator.exec.CommandSpec spec, int exit, String stderr) {
        return new CommandResult(spec, OptionalInt.of(exit), "", stderr, Duration.ofMillis(3), false, Optional.empty());
    }

    private ExitCode run() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC);
        return new CheckCommand(env, temp, temp.resolve("unused default jdk"), runner, clock,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run();
    }

    private Path onlyRunDir() throws IOException {
        try (Stream<Path> dirs = Files.list(output)) {
            List<Path> all = dirs.toList();
            assertEquals(1, all.size());
            return all.getFirst();
        }
    }

    @Test
    void completedAnalysisWritesReportLogsAndSummary() throws IOException {
        env.put(ConfigLoader.CHECKER_JAVA_HOME, jdk.toString());
        String before = Files.readString(target.resolve("core/src/org/ex/A.java"));

        assertEquals(ExitCode.COMPLETED, run(), err.toString(StandardCharsets.UTF_8));

        Path runDir = onlyRunDir();
        assertEquals("20261117T120000Z-nullaway", runDir.getFileName().toString());
        String report = Files.readString(runDir.resolve("report.json"));
        assertTrue(report.contains("\"status\": \"COMPLETED\""));
        assertTrue(report.contains("\"exitCode\": 0"));
        assertTrue(report.contains("\"findingCount\": 1"));
        assertTrue(report.contains("\"targetPath\": \"core/src/org/ex/A.java\""), report);
        assertTrue(report.contains("\"checkerActive\": true"));
        assertTrue(report.contains("\"mechanism\": \"filesystem-walk\"") || report.contains("\"mechanism\": \"git\""));
        assertTrue(Files.exists(runDir.resolve("logs/javac.stderr.txt")));
        List<String> warnings = Files.readAllLines(runDir.resolve("warnings.txt"));
        assertTrue(warnings.contains("# count:   1"), warnings::toString);
        assertEquals("W0001  core/src/org/ex/A.java:2:1: error: [NullAway] returning @Nullable expression",
                warnings.getLast());

        WarningGraph graph = WarningGraphJson.load(runDir.resolve("warning-graph.json"));
        assertEquals("20261117T120000Z-nullaway", graph.runId());
        assertEquals(1, graph.nodes().size());
        assertTrue(graph.edges().isEmpty());
        WarningNode node = graph.warningNodes().getFirst();
        assertEquals("W0001", node.id());
        assertTrue(node.root());
        assertEquals(0, node.distance());
        assertEquals(Optional.of("core/src/org/ex/A.java"), node.targetPath());
        assertTrue(report.contains("\"warningGraph\": {\n    \"created\": true,\n    \"file\": \"warning-graph.json\""),
                report);
        assertTrue(Files.exists(runDir.resolve("logs/nullaway-setup.stdout.txt")));
        assertEquals(before, Files.readString(target.resolve("core/src/org/ex/A.java")), "target unchanged");

        String summary = out.toString(StandardCharsets.UTF_8);
        assertTrue(summary.contains("status:   COMPLETED"), summary);
        assertTrue(summary.contains("findings: 1 {error=1}"), summary);
        assertTrue(summary.contains("exit:     0"), summary);
        assertTrue(summary.contains("(1 warning node(s), 0 edge(s))"), summary);
        assertTrue(summary.contains("Warning IDs (all roots, distance 0):\n"
                + "  W0001  core/src/org/ex/A.java:2:1: error: [NullAway] returning @Nullable expression"), summary);
    }

    @Test
    void completedAnalysisWithoutFindingsGivesEmptyGraph() throws IOException {
        env.put(ConfigLoader.CHECKER_JAVA_HOME, jdk.toString());
        javacNoTargetFindings = true;

        assertEquals(ExitCode.COMPLETED, run(), out.toString(StandardCharsets.UTF_8));

        WarningGraph graph = WarningGraphJson.load(onlyRunDir().resolve("warning-graph.json"));
        assertTrue(graph.nodes().isEmpty());
        assertTrue(graph.edges().isEmpty());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("no findings, so no warning nodes"));
    }

    @Test
    void unrelatedCompileErrorIsExecutionFailureExit3() throws IOException {
        env.put(ConfigLoader.CHECKER_JAVA_HOME, jdk.toString());
        javacCompileError = true;

        assertEquals(ExitCode.EXECUTION_FAILURE, run());

        String report = Files.readString(onlyRunDir().resolve("report.json"));
        assertTrue(report.contains("\"status\": \"EXECUTION_FAILURE\""));
        assertTrue(report.contains("\"kind\": \"COMPILATION_FAILED\""));
        assertTrue(report.contains("\"findingsComplete\": false"));
        assertTrue(Files.readString(onlyRunDir().resolve("warnings.txt")).contains("INCOMPLETE"));
        assertFalse(Files.exists(onlyRunDir().resolve("warning-graph.json")),
                "a failed analysis must not produce a (possibly empty) warning graph");
        assertTrue(report.contains("\"created\": false"), report);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("graph:    not created"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("findings are incomplete"));
    }

    @Test
    void unsupportedCheckerIsConfigurationErrorAndCreatesNoRun() {
        env.put(ConfigLoader.CHECKER, "infer");
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run());
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("'infer' is not a supported checker"));
        assertFalse(Files.exists(output));
    }

    @Test
    void sourceRootMissingFromCopyIsExecutionFailure() throws IOException {
        env.put(ConfigLoader.CHECKER_JAVA_HOME, jdk.toString());
        Files.createDirectories(target.resolve("empty root"));
        env.put(ConfigLoader.TARGET_SOURCE_ROOTS, "empty root");
        assertEquals(ExitCode.EXECUTION_FAILURE, run());
        String report = Files.readString(onlyRunDir().resolve("report.json"));
        assertTrue(report.contains("\"kind\": \"SETUP_FAILED\""), report);
    }
}
