package generalannotator.checker.nullaway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.checker.AnalysisRequest;
import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.checker.CheckerOutcome.ExecutionFailure;
import generalannotator.checker.CheckerRun;
import generalannotator.checker.FailureKind;
import generalannotator.config.ConfigurationException;
import generalannotator.exec.CommandResult;
import generalannotator.exec.CommandRunner;
import generalannotator.exec.CommandSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NullAwayCheckerTest {

    @TempDir Path temp;

    private Path checkout;
    private Path copy;
    private Path runDir;
    private Path nullawayJar;
    private Path errorProneJar;
    private final List<CommandSpec> specs = new ArrayList<>();

    /** What the fake javac prints, given the probe path. */
    private java.util.function.Function<String, String> javacStderr;
    private int javacExit = 1;
    private boolean setupSucceeds = true;

    @BeforeEach
    void setUp() throws IOException {
        checkout = Files.createDirectories(temp.resolve("Null Away"));
        Path gradlew = Files.writeString(checkout.resolve("gradlew"), "");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.createDirectories(checkout.resolve("nullaway/build/libs"));
        Files.writeString(checkout.resolve("nullaway/build.gradle"), "");
        nullawayJar = Files.writeString(checkout.resolve("nullaway/build/libs/nullaway.jar"), "");
        errorProneJar = Files.writeString(temp.resolve("error_prone_core-2.50.0.jar"), "");

        copy = Files.createDirectories(temp.resolve("run 1/workspace/target"));
        Files.createDirectories(copy.resolve("core/src/org/ex/sub"));
        Files.writeString(copy.resolve("core/src/org/ex/A.java"), "package org.ex;\nclass A {}\n");
        Files.writeString(copy.resolve("core/src/org/ex/sub/B.java"), "package org.ex.sub;\nclass B {}\n");
        Files.createDirectories(copy.resolve("tests/src"));
        Files.writeString(copy.resolve("tests/src/T.java"), "class T {}\n");
        runDir = temp.resolve("run 1");

        javacStderr = probe -> probe + ":7: error: [NullAway] dereferenced expression o is @Nullable\n"
                + "        return o.hashCode();\n               ^\n    (see http://t.uber.com/nullaway )\n"
                + copy.resolve("core/src/org/ex/A.java")
                + ":2: error: [NullAway] returning @Nullable expression from method with @NonNull return type\n"
                + "class A {}\n^\n    (see http://t.uber.com/nullaway )\n2 errors\n";
    }

    private final CommandRunner fake = spec -> {
        specs.add(spec);
        String program = Path.of(spec.command().getFirst()).getFileName().toString();
        if (program.equals("gradlew")) {
            String output = spec.command().stream().filter(a -> a.startsWith("-PgeneralAnnotator.output="))
                    .findFirst().orElseThrow().substring("-PgeneralAnnotator.output=".length());
            if (setupSucceeds) {
                try {
                    Files.writeString(Path.of(output), "nullaway-version=0.0.1-TEST\nnullaway-jar=" + nullawayJar
                            + "\nerrorprone=" + errorProneJar + "\n");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return result(spec, setupSucceeds ? 0 : 1, setupSucceeds ? "" : "FAILURE: Build failed");
        }
        String probe = runDir.resolve("generated/probe/generalannotator_probe/GeneralAnnotatorProbe.java").toString();
        return result(spec, javacExit, javacStderr.apply(probe));
    };

    private static CommandResult result(CommandSpec spec, int exit, String stderr) {
        return new CommandResult(spec, OptionalInt.of(exit), "", stderr, Duration.ofMillis(5), false, Optional.empty());
    }

    private CheckerRun analyze(Map<String, String> env) throws ConfigurationException {
        NullAwayChecker checker = NullAwayChecker.create(checkout, env, fake);
        return checker.analyze(new AnalysisRequest(copy, List.of(Path.of("core/src")), List.of(),
                Path.of("/jdk home"), OptionalInt.empty(), runDir, Duration.ofSeconds(30)));
    }

    @Test
    void buildsLocalNullAwayAndRunsJavacWithDerivedScope() throws Exception {
        CheckerRun run = analyze(Map.of());

        CompletedAnalysis done = assertInstanceOf(CompletedAnalysis.class, run.outcome());
        assertEquals(1, done.findings().size());
        assertEquals(1, run.details().get("probeFindings"));
        assertEquals(List.of("org.ex"), run.details().get("annotatedPackages"));
        assertEquals(nullawayJar.toString(), run.details().get("nullawayJar"));

        CommandSpec setup = specs.get(0);
        assertEquals(checkout, setup.workingDirectory());
        assertEquals("/jdk home", setup.environment().get("JAVA_HOME"));
        assertTrue(setup.command().contains(":nullaway:generalAnnotatorProcessorPath"));

        CommandSpec javac = specs.get(1);
        assertEquals("/jdk home/bin/javac", javac.command().getFirst());
        assertEquals(copy, javac.workingDirectory());
        String argFile = Files.readString(runDir.resolve("generated/javac.args"));
        assertTrue(argFile.contains(
                "\"-Xplugin:ErrorProne -XepDisableAllChecks -Xep:NullAway:ERROR"
                        + " -XepOpt:NullAway:AnnotatedPackages=org.ex,generalannotator_probe\""));
        assertTrue(argFile.contains("\"" + errorProneJar + java.io.File.pathSeparator + nullawayJar + "\""));
        assertTrue(argFile.contains("\"" + copy.resolve("core/src/org/ex/sub/B.java") + "\""));
        assertTrue(Files.exists(runDir.resolve("logs/javac.stderr.txt")));

        assertEquals(2, run.coverage().analyzedSourceFiles());
        assertEquals(Map.of("tests", 1), run.coverage().javaFilesOutsideSourceRoots());
    }

    @Test
    void configuredAnnotatedPackagesOverrideDerivation() throws Exception {
        CheckerRun run = analyze(Map.of(NullAwayChecker.ANNOTATED_PACKAGES_ENV, "org.ex.sub"));
        assertEquals(List.of("org.ex.sub"), run.details().get("annotatedPackages"));
    }

    @Test
    void setupFailureIsReportedAndJavacIsNotRun() throws Exception {
        setupSucceeds = false;
        CheckerRun run = analyze(Map.of());
        ExecutionFailure f = assertInstanceOf(ExecutionFailure.class, run.outcome());
        assertEquals(FailureKind.SETUP_FAILED, f.kind());
        assertEquals(1, specs.size());
    }

    @Test
    void missingProbeFindingMeansCheckerNotActive() throws Exception {
        javacStderr = probe -> "";
        javacExit = 0;
        CheckerRun run = analyze(Map.of());
        ExecutionFailure f = assertInstanceOf(ExecutionFailure.class, run.outcome());
        assertEquals(FailureKind.CHECKER_NOT_ACTIVE, f.kind());
        assertEquals(false, run.details().get("checkerActive"));
    }

    @Test
    void invalidCheckoutAndOptionsAreConfigurationErrors() {
        ConfigurationException e = assertThrows(ConfigurationException.class,
                () -> NullAwayChecker.create(temp, Map.of(NullAwayChecker.ANNOTATED_PACKAGES_ENV, "9x"), fake));
        assertTrue(e.problems().stream().anyMatch(p -> p.contains("no executable gradlew")));
        assertTrue(e.problems().stream().anyMatch(p -> p.contains("invalid package name")));
    }

    @Test
    void processorPathMustComeFromTheCheckout() throws IOException {
        Path elsewhere = Files.writeString(temp.resolve("nullaway-published.jar"), "");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ProcessorPath.parse(
                "nullaway-version=1\nnullaway-jar=" + elsewhere + "\nerrorprone=" + errorProneJar + "\n", checkout));
        assertTrue(e.getMessage().contains("is not inside the configured checkout"));
    }

    @Test
    void argFileQuotesSpacesAndEscapes() {
        assertEquals("\"a b\"\n\"c\\\\d\"\n\"e\\\"f\"\n\"\"\n", JavacArgFile.format(List.of("a b", "c\\d", "e\"f", "")));
        assertThrows(IllegalArgumentException.class, () -> JavacArgFile.format(List.of("-J-Xmx1g")));
    }
}
