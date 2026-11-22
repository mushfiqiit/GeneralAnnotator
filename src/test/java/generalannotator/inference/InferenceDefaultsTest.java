package generalannotator.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.cli.PrepareInferenceCommandTest;
import generalannotator.report.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The default batch size (one prompt per array task) and startup timeout (1800 s), and their overrides. */
class InferenceDefaultsTest {

    @TempDir Path temp;

    private static InferenceSettings settings(Map<String, String> env) {
        List<String> problems = new ArrayList<>();
        InferenceSettings s = InferenceSettings.fromEnvironment(env, problems);
        assertEquals(List.of(), problems);
        return s;
    }

    @Test
    void theDefaultsAreOnePromptPerTaskAnd1800SecondsToStartWithTheUsualConcurrency() {
        InferenceSettings s = settings(Map.of());

        assertEquals(1, s.promptsPerTask());
        assertEquals(1800, s.startupTimeoutSeconds());
        assertEquals(4, s.arrayConcurrency());
    }

    @Test
    void fortyOneEligiblePromptsBecomeFortyOneTasksOfExactlyOnePromptEachAssignedOnce() throws Exception {
        Path run = PrepareInferenceCommandTest.makeRun(temp.resolve("runs/run 1"), 42, "W0033");

        SubmissionPreparer.Prepared prepared = SubmissionPreparer.prepare(run, Optional.empty(), settings(Map.of()),
                PrepareInferenceCommandTest.CLOCK);

        assertEquals(41, prepared.warningIds().size());
        assertEquals(41, prepared.tasks().size());
        assertTrue(prepared.tasks().stream().allMatch(t -> t.size() == 1), prepared.tasks().toString());
        Map<?, ?> submission = (Map<?, ?>) JsonParser.parse(Files.readString(prepared.directory()
                .resolve("submission.json")));
        Set<String> assigned = new HashSet<>();
        List<?> tasks = (List<?>) submission.get("tasks");
        assertEquals(41, tasks.size());
        for (Object o : tasks) {
            List<?> ids = (List<?>) ((Map<?, ?>) o).get("warningIds");
            assertEquals(1, ids.size());
            assertTrue(assigned.add((String) ids.get(0)), "assigned twice: " + ids);
        }
        assertEquals(new HashSet<>(prepared.warningIds()), assigned, "every prompt is assigned exactly once");
        for (Object o : (List<?>) submission.get("prompts")) {
            Map<?, ?> p = (Map<?, ?>) o;
            int task = ((Number) p.get("taskIndex")).intValue();
            assertEquals(List.of(p.get("warningId")), ((Map<?, ?>) tasks.get(task)).get("warningIds"));
        }
        Map<?, ?> json = (Map<?, ?>) JsonParser.parse(Files.readString(prepared.directory().resolve("settings.json")));
        assertEquals(1800L, ((Map<?, ?>) json.get("server")).get("startupTimeoutSeconds"),
                "the worker reads its startup deadline from settings.json");
        assertEquals(1L, ((Map<?, ?>) json.get("slurm")).get("promptsPerTask"));
        assertEquals(4L, ((Map<?, ?>) json.get("slurm")).get("arrayConcurrency"));
        assertTrue(Files.readString(prepared.directory().resolve(SlurmScript.FILE_NAME)).contains("#SBATCH --array=0-40%4"));
    }

    @Test
    void explicitOverridesStillWorkAndAreValidated() throws Exception {
        InferenceSettings s = settings(Map.of("GA_INFERENCE_PROMPTS_PER_TASK", "10",
                "GA_INFERENCE_STARTUP_TIMEOUT_SECONDS", "2400", "GA_INFERENCE_ARRAY_CONCURRENCY", "2"));
        assertEquals(10, s.promptsPerTask());
        assertEquals(2400, s.startupTimeoutSeconds());
        assertEquals(2, s.arrayConcurrency());
        Path run = PrepareInferenceCommandTest.makeRun(temp.resolve("runs/run 2"), 12);
        SubmissionPreparer.Prepared prepared = SubmissionPreparer.prepare(run, Optional.empty(), s,
                PrepareInferenceCommandTest.CLOCK);
        assertEquals(List.of(10, 2), prepared.tasks().stream().map(List::size).toList());
        Map<?, ?> json = (Map<?, ?>) JsonParser.parse(Files.readString(prepared.directory().resolve("settings.json")));
        assertEquals(2400L, ((Map<?, ?>) json.get("server")).get("startupTimeoutSeconds"));

        for (Map.Entry<String, String> bad : List.of(Map.entry("GA_INFERENCE_STARTUP_TIMEOUT_SECONDS", "5"),
                Map.entry("GA_INFERENCE_STARTUP_TIMEOUT_SECONDS", "86401"),
                Map.entry("GA_INFERENCE_STARTUP_TIMEOUT_SECONDS", "half an hour"),
                Map.entry("GA_INFERENCE_PROMPTS_PER_TASK", "0"), Map.entry("GA_INFERENCE_PROMPTS_PER_TASK", "11"))) {
            List<String> problems = new ArrayList<>();
            InferenceSettings.fromEnvironment(Map.of(bad.getKey(), bad.getValue()), problems);
            assertEquals(1, problems.size(), bad.toString());
            assertTrue(problems.get(0).contains(bad.getKey()), problems.toString());
        }
    }
}
