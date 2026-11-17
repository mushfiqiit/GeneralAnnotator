package generalannotator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.checker.AnalysisRequest;
import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.checker.CheckerOutcome.ExecutionFailure;
import generalannotator.checker.CheckerRun;
import generalannotator.checker.FailureKind;
import generalannotator.checker.nullaway.NullAwayChecker;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.exec.ProcessCommandRunner;
import generalannotator.isolation.WorkingCopy;
import generalannotator.isolation.WorkingCopyCreator;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the real local NullAway on tiny fixtures with known findings. */
class NullAwayCheckerIntegrationTest {

    @TempDir Path temp;

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    private CheckerRun analyze(String fixture, String sourceRoot) throws Exception {
        Path runDir = temp.resolve("run dir");
        WorkingCopy copy = new WorkingCopyCreator(runner, Duration.ofMinutes(2))
                .create(IntegrationEnvironment.fixture(fixture), runDir.resolve("workspace/target"),
                        runDir.resolve("logs"));
        NullAwayChecker checker =
                NullAwayChecker.create(IntegrationEnvironment.nullAwayCheckout(), Map.of(), runner);
        return checker.analyze(new AnalysisRequest(copy.root(), List.of(Path.of(sourceRoot)), List.of(),
                IntegrationEnvironment.checkerJavaHome(), OptionalInt.empty(), runDir, Duration.ofMinutes(20)));
    }

    @Test
    void reportsExactlyTheKnownFindingsAndProvesCheckerIsActive() throws Exception {
        CheckerRun run = analyze("simple project", "src/main/java");

        CompletedAnalysis done = assertInstanceOf(CompletedAnalysis.class, run.outcome(),
                () -> "unexpected outcome: " + run.outcome());
        assertEquals(true, run.details().get("checkerActive"));
        assertEquals(List.of("com.example.fixture"), run.details().get("annotatedPackages"));
        List<Diagnostic> findings = done.findings();
        assertEquals(2, findings.size(), findings::toString);
        assertTrue(findings.stream().allMatch(d -> d.sourcePath().endsWith("Greeter.java")));
        assertEquals(List.of(12, 16), findings.stream().map(Diagnostic::line).toList());
        assertTrue(findings.get(0).message().startsWith("dereferenced expression 'find(key)' is @Nullable"),
                findings.get(0)::message);
        assertTrue(findings.get(1).message().startsWith("returning @Nullable expression"),
                findings.get(1)::message);
        assertEquals(2, run.coverage().analyzedSourceFiles());
    }

    @Test
    void unrelatedCompilationErrorIsAnExecutionFailureWithIncompleteFindings() throws Exception {
        CheckerRun run = analyze("broken project", "src");

        ExecutionFailure failure = assertInstanceOf(ExecutionFailure.class, run.outcome(),
                () -> "unexpected outcome: " + run.outcome());
        assertEquals(FailureKind.COMPILATION_FAILED, failure.kind());
        assertTrue(failure.reasons().stream().anyMatch(r -> r.contains("incompatible types")),
                failure.reasons()::toString);
    }
}
