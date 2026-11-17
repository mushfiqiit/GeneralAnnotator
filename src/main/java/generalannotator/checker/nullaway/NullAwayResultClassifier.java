package generalannotator.checker.nullaway;

import generalannotator.checker.CheckerOutcome;
import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.checker.CheckerOutcome.ExecutionFailure;
import generalannotator.checker.FailureKind;
import generalannotator.diagnostics.CompilerOutput;
import generalannotator.diagnostics.CompilerOutput.GeneralMessage;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.diagnostics.Severity;
import generalannotator.exec.CommandResult;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Classifies one javac + Error Prone + NullAway run (task T2).
 *
 * <p>javac exits with 1 when NullAway reports errors, so a non-zero exit alone does not discard
 * findings. The run is a {@link CompletedAnalysis} only if all of the following hold:
 *
 * <ul>
 *   <li>the process started, finished within the timeout, and exited with 0 or 1;
 *   <li>there is no sign of a compiler or Error Prone crash;
 *   <li>every error diagnostic is a NullAway finding (no unrelated compilation errors);
 *   <li>exit 1 is explained by at least one error, and exit 0 has no errors;
 *   <li>the output was fully recognized and javac's summary counts match what was parsed;
 *   <li>the NullAway probe file produced at least one NullAway finding, proving the checker ran.
 * </ul>
 *
 * Otherwise the result is an {@link ExecutionFailure} whose reasons list every violated condition
 * and whose findings are the (incomplete) NullAway findings observed.
 */
public final class NullAwayResultClassifier {

    static final String CHECKER_NAME = "NullAway";

    private static final List<String> CRASH_MARKERS = List.of(
            "An exception has occurred in the compiler",
            "An unhandled exception was thrown by the Error Prone",
            "Exception in thread",
            "java.lang.OutOfMemoryError",
            "Caused by: ");

    private NullAwayResultClassifier() {}

    /** Classification plus the probe findings that were separated from the target findings. */
    public record Classification(CheckerOutcome outcome, List<Diagnostic> probeFindings) {
        public Classification {
            probeFindings = List.copyOf(probeFindings);
        }
    }

    /**
     * @param result the javac command result
     * @param output the parsed javac output of that command
     * @param isProbePath recognizes diagnostic paths that belong to the probe file
     */
    public static Classification classify(
            CommandResult result, CompilerOutput output, Predicate<String> isProbePath) {
        List<Diagnostic> findings = new ArrayList<>();
        List<Diagnostic> probeFindings = new ArrayList<>();
        List<Diagnostic> others = new ArrayList<>();
        for (Diagnostic d : output.diagnostics()) {
            if (d.isFromChecker(CHECKER_NAME)) {
                (isProbePath.test(d.sourcePath()) ? probeFindings : findings).add(d);
            } else {
                others.add(d);
            }
        }

        Map<FailureKind, List<String>> reasons = new EnumMap<>(FailureKind.class);
        if (result.startFailure().isPresent()) {
            add(reasons, FailureKind.START_FAILED, "javac could not be started: " + result.startFailure().get());
        }
        if (result.timedOut()) {
            add(reasons, FailureKind.TIMED_OUT, "javac exceeded the timeout of "
                    + result.spec().timeout().toSeconds() + "s and was killed");
        }
        checkCrash(output, reasons);
        if (result.exitCode().isPresent()) {
            int exit = result.exitCode().getAsInt();
            if (exit == 2) {
                add(reasons, FailureKind.INVALID_INVOCATION, "javac rejected its command line (exit 2)");
            } else if (exit != 0 && exit != 1) {
                add(reasons, FailureKind.CRASHED, "javac exited abnormally (exit " + exit + ")");
            }
        }

        List<String> unrelatedErrors = new ArrayList<>();
        for (Diagnostic d : others) {
            if (d.severity() == Severity.ERROR) {
                unrelatedErrors.add(d.location() + ": " + firstLine(d.message()));
            }
        }
        for (GeneralMessage m : output.generalMessages()) {
            if (m.severity() == Severity.ERROR) {
                unrelatedErrors.add(m.text());
            }
        }
        for (String error : unrelatedErrors) {
            add(reasons, FailureKind.COMPILATION_FAILED, "non-NullAway compiler error: " + error);
        }

        long nullAwayErrors = findings.stream().filter(d -> d.severity() == Severity.ERROR).count()
                + probeFindings.stream().filter(d -> d.severity() == Severity.ERROR).count();
        if (result.exitCode().isPresent()) {
            int exit = result.exitCode().getAsInt();
            if (exit == 1 && nullAwayErrors == 0 && unrelatedErrors.isEmpty()) {
                add(reasons, FailureKind.UNTRUSTWORTHY_OUTPUT,
                        "javac exited with 1 but no error diagnostics were recognized");
            }
            if (exit == 0 && (nullAwayErrors > 0 || !unrelatedErrors.isEmpty())) {
                add(reasons, FailureKind.UNTRUSTWORTHY_OUTPUT,
                        "javac exited with 0 but error diagnostics were reported");
            }
        }
        for (String problem : output.problems()) {
            add(reasons, FailureKind.UNTRUSTWORTHY_OUTPUT, "output problem: " + problem);
        }
        if (!output.unrecognizedLines().isEmpty()) {
            add(reasons, FailureKind.UNTRUSTWORTHY_OUTPUT, output.unrecognizedLines().size()
                    + " unrecognized output line(s), first: " + output.unrecognizedLines().getFirst());
        }
        if (probeFindings.isEmpty()) {
            add(reasons, FailureKind.CHECKER_NOT_ACTIVE,
                    "the NullAway probe produced no finding, so there is no evidence NullAway ran");
        }

        if (reasons.isEmpty()) {
            return new Classification(new CompletedAnalysis(findings, others), probeFindings);
        }
        FailureKind primary = reasons.keySet().iterator().next();
        List<String> all = new ArrayList<>();
        reasons.values().forEach(all::addAll);
        return new Classification(new ExecutionFailure(primary, all, findings, others), probeFindings);
    }

    private static void checkCrash(CompilerOutput output, Map<FailureKind, List<String>> reasons) {
        List<String> texts = new ArrayList<>(output.unrecognizedLines());
        output.diagnostics().forEach(d -> texts.add(d.message()));
        output.generalMessages().forEach(m -> texts.add(m.text()));
        for (String text : texts) {
            for (String marker : CRASH_MARKERS) {
                if (text.contains(marker)) {
                    add(reasons, FailureKind.CRASHED, "compiler or checker crash: " + firstLine(text));
                    return;
                }
            }
        }
    }

    private static void add(Map<FailureKind, List<String>> reasons, FailureKind kind, String reason) {
        reasons.computeIfAbsent(kind, k -> new ArrayList<>()).add(reason);
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }
}
