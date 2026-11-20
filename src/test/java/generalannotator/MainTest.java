package generalannotator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.cli.ExitCode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MainTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private ExitCode run(Map<String, String> env, String... args) {
        return Main.run(args, env,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void noArgumentsIsUsageError() {
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Usage: GeneralAnnotator"));
    }

    @Test
    void unknownCommandIsUsageError() {
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "annotate"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("unknown arguments: annotate"));
    }

    @Test
    void helpPrintsUsage() {
        assertEquals(ExitCode.COMPLETED, run(Map.of(), "help"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("check"));
    }

    @Test
    void contextCommandIsDispatchedAndValidatesItsArguments() {
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "context", "no-such-run-directory"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("cannot extract context"));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "context", "x", "--bogus", "W0001"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("unknown arguments: context x --bogus W0001"));
        assertEquals(ExitCode.COMPLETED, run(Map.of(), "help"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("context <run directory> [--warning <ID>]"));
    }

    @Test
    void promptsCommandIsDispatchedAndValidatesItsArguments() {
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "prompts", "no-such-run-directory"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("cannot assemble prompts"));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "prompts", "x", "--only", "W0001"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("unknown arguments: prompts x --only W0001"));
        assertEquals(ExitCode.COMPLETED, run(Map.of(), "help"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("prompts <run directory> [--warning <ID>]"));
    }

    @Test
    void prepareInferenceCommandIsDispatchedAndValidatesItsArguments() {
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "prepare-inference", "no-such-run"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("cannot prepare inference"));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of("GA_INFERENCE_PROMPTS_PER_TASK", "11"),
                "prepare-inference", "no-such-run"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("GA_INFERENCE_PROMPTS_PER_TASK='11'"));
        assertEquals(ExitCode.COMPLETED, run(Map.of(), "help"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains(
                "prepare-inference <run directory> [--warning <ID>[,<ID>...]]"));
    }

    @Test
    void inferenceCommandIsDispatchedAndValidatesItsArguments() {
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "inference"));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "inference", "status"));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of("GA_REMOTE_BASE", "relative"), "inference",
                "status", "x"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("GA_REMOTE_BASE='relative'"));
        assertEquals(ExitCode.COMPLETED, run(Map.of(), "help"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("inference upload|submit|status|collect|retry|run"));
    }

    @Test
    void applyProposalCommandIsDispatchedAndValidatesItsArguments() {
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "apply-proposal", "no-such-dir", "--warning",
                "W0001"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("cannot apply the proposal"));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "apply-proposal", "dir"));
        assertEquals(ExitCode.COMPLETED, run(Map.of(), "help"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("apply-proposal <submission dir> --warning <ID>"));
    }

    @Test
    void evaluateAnnotationsCommandIsDispatchedAndValidatesItsArguments() {
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "evaluate-annotations", "no-such-dir"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("cannot evaluate annotations"));
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "evaluate-annotations", "dir", "--warning"));
        assertEquals(ExitCode.COMPLETED, run(Map.of(), "help"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("evaluate-annotations <submission dir> [--warning <ID>]"));
    }

    @Test
    void checkWithoutConfigurationIsConfigurationError() {
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "check"));
        String text = err.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("GA_CHECKER is not set"), text);
        assertTrue(text.contains("GA_TARGET_PROJECT is not set"), text);
    }
}
