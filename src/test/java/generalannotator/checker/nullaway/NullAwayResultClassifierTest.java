package generalannotator.checker.nullaway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.TestResources;
import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.checker.CheckerOutcome.ExecutionFailure;
import generalannotator.checker.FailureKind;
import generalannotator.checker.nullaway.NullAwayResultClassifier.Classification;
import generalannotator.diagnostics.JavacOutputParser;
import generalannotator.exec.CommandResult;
import generalannotator.exec.CommandSpec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class NullAwayResultClassifierTest {

    private static final String PROBE_PATH = "/runs/r1/generated/probe/GeneralAnnotatorProbe.java";
    private static final String PROBE = PROBE_PATH
            + ":5: error: [NullAway] dereferenced expression o is @Nullable\n"
            + "        return o.hashCode();\n"
            + "               ^\n"
            + "    (see http://t.uber.com/nullaway )\n";

    private static final CommandSpec SPEC =
            new CommandSpec(List.of("javac"), Path.of("/"), Map.of(), Duration.ofSeconds(30));

    private static CommandResult exited(int code, String stderr) {
        return new CommandResult(SPEC, OptionalInt.of(code), "", stderr, Duration.ofSeconds(1), false, Optional.empty());
    }

    /** Fixture text with the probe diagnostic added and the error count bumped by one. */
    private static String withProbe(String fixture) {
        String text = TestResources.text("javac-output/" + fixture);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^(\\d+) errors?$").matcher(text);
        if (m.find()) {
            return PROBE + text.substring(0, m.start()) + (Integer.parseInt(m.group(1)) + 1) + " errors"
                    + text.substring(m.end());
        }
        return PROBE + text + "1 error\n";
    }

    private static Classification classify(CommandResult result) {
        return NullAwayResultClassifier.classify(
                result, JavacOutputParser.parse(result.stderr(), result.stdout()), PROBE_PATH::equals);
    }

    @Test
    void nonZeroExitCausedOnlyByNullAwayFindingsIsCompletedAnalysis() {
        Classification c = classify(exited(1, withProbe("multiple-mixed.txt")));
        CompletedAnalysis done = assertInstanceOf(CompletedAnalysis.class, c.outcome());
        assertEquals(2, done.findings().size());
        assertEquals(1, done.otherDiagnostics().size(), "the deprecation warning is kept separately");
        assertEquals(1, c.probeFindings().size(), "probe finding is not a target finding");
    }

    @Test
    void zeroFindingsWithActiveProbeIsCompletedAnalysis() {
        Classification c = classify(exited(1, withProbe("empty.txt")));
        CompletedAnalysis done = assertInstanceOf(CompletedAnalysis.class, c.outcome());
        assertTrue(done.findings().isEmpty());
    }

    @Test
    void unrelatedCompileErrorIsFailureWithIncompleteFindingsPreserved() {
        Classification c = classify(exited(1, withProbe("unrelated-compile-error.txt")));
        ExecutionFailure f = assertInstanceOf(ExecutionFailure.class, c.outcome());
        assertEquals(FailureKind.COMPILATION_FAILED, f.kind());
        assertEquals(1, f.findings().size(), "observed NullAway finding is kept");
        assertTrue(f.reasons().getFirst().contains("cannot find symbol"));
    }

    @Test
    void emptyOutputWithoutProbeIsNotEvidenceOfZeroFindings() {
        Classification c = classify(exited(0, ""));
        ExecutionFailure f = assertInstanceOf(ExecutionFailure.class, c.outcome());
        assertEquals(FailureKind.CHECKER_NOT_ACTIVE, f.kind());
    }

    @Test
    void crashIsFailure() {
        Classification c = classify(exited(1, TestResources.text("javac-output/crash.txt")));
        ExecutionFailure f = assertInstanceOf(ExecutionFailure.class, c.outcome());
        assertEquals(FailureKind.CRASHED, f.kind());
    }

    @Test
    void truncatedOutputIsUntrustworthyAndKeepsObservedFindings() {
        Classification c = classify(exited(1, PROBE + TestResources.text("javac-output/truncated.txt")));
        ExecutionFailure f = assertInstanceOf(ExecutionFailure.class, c.outcome());
        assertEquals(FailureKind.UNTRUSTWORTHY_OUTPUT, f.kind());
        assertEquals(2, f.findings().size());
    }

    @Test
    void pluginMissingIsCompilationFailureAndCheckerInactive() {
        Classification c = classify(exited(1, TestResources.text("javac-output/general-error.txt")));
        ExecutionFailure f = assertInstanceOf(ExecutionFailure.class, c.outcome());
        assertEquals(FailureKind.COMPILATION_FAILED, f.kind());
        assertTrue(f.reasons().stream().anyMatch(r -> r.contains("probe produced no finding")));
    }

    @Test
    void exitOneWithoutErrorsIsUntrustworthy() {
        Classification c = classify(exited(1, PROBE.replace("error:", "warning:") + "1 warning\n"));
        ExecutionFailure f = assertInstanceOf(ExecutionFailure.class, c.outcome());
        assertEquals(FailureKind.UNTRUSTWORTHY_OUTPUT, f.kind());
    }

    @Test
    void invalidCommandLineIsReported() {
        Classification c = classify(exited(2, "error: invalid flag: -bogus\n1 error\n"));
        ExecutionFailure f = assertInstanceOf(ExecutionFailure.class, c.outcome());
        assertEquals(FailureKind.INVALID_INVOCATION, f.kind());
    }

    @Test
    void timeoutAndStartFailureAreFailures() {
        CommandResult timedOut = new CommandResult(
                SPEC, OptionalInt.empty(), "", PROBE, Duration.ofSeconds(30), true, Optional.empty());
        assertEquals(FailureKind.TIMED_OUT,
                assertInstanceOf(ExecutionFailure.class, classify(timedOut).outcome()).kind());
        CommandResult notStarted = new CommandResult(
                SPEC, OptionalInt.empty(), "", "", Duration.ZERO, false, Optional.of("no such file"));
        assertEquals(FailureKind.START_FAILED,
                assertInstanceOf(ExecutionFailure.class, classify(notStarted).outcome()).kind());
    }
}
