package generalannotator.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.checker.CheckerOutcome.CompletedAnalysis;
import generalannotator.diagnostics.Diagnostic;
import generalannotator.diagnostics.Severity;
import generalannotator.graph.InitialWarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.inference.SubmissionPreparer;
import generalannotator.inference.TaskAssignment;
import generalannotator.prompts.PromptLimits;
import generalannotator.prompts.PromptManifestJson;
import generalannotator.report.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code prepare-inference} on hand-built prompt manifests. Nothing is uploaded, submitted or run. */
class PrepareInferenceCommandTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC);
    static final String SUBMISSION = "20261119T120000Z-inference";

    @TempDir Path temp;

    private Path runDir;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        runDir = makeRun(temp.resolve("runs/run 1"), 42, "W0042");
    }

    /** A run with {@code count} warnings, all GENERATED except {@code skipped}. */
    static Path makeRun(Path runDir, int count, String... skipped) throws IOException {
        Files.createDirectories(runDir.resolve("prompts"));
        List<Diagnostic> findings = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            findings.add(new Diagnostic(runDir.resolve("workspace/target/src/A.java").toString(), i,
                    OptionalInt.of(1), Severity.ERROR, Optional.of("NullAway"), "message " + i, Optional.empty()));
        }
        WarningGraphJson.save(InitialWarningGraph.build("run 1", "nullaway", new CompletedAnalysis(findings, List.of()),
                runDir.resolve("workspace/target")), runDir.resolve("warning-graph.json"));
        Map<String, Map<?, ?>> entries = new LinkedHashMap<>();
        for (int i = 1; i <= count; i++) {
            String id = String.format("W%04d", i);
            boolean generated = !List.of(skipped).contains(id);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("warningId", id);
            e.put("status", generated ? "GENERATED" : "SKIPPED");
            e.put("templateVersion", "prompt-template-1");
            e.put("reasons", generated ? List.of() : List.of("the target member is unresolved: initializer block"));
            e.put("slice", Map.of("status", "GENERATED", "reproduction", "NOT_VALIDATED"));
            Map<String, Object> output = new LinkedHashMap<>();
            if (generated) {
                String text = prompt(id);
                Files.writeString(runDir.resolve("prompts/" + id + ".txt"), text);
                output.put("file", "prompts/" + id + ".txt");
                output.put("chars", text.length());
                output.put("bytes", text.getBytes(StandardCharsets.UTF_8).length);
            } else {
                output.put("file", null);
            }
            e.put("output", output);
            entries.put(id, e);
        }
        Files.writeString(runDir.resolve("prompts/manifest.json"),
                PromptManifestJson.write("run 1", "nullaway", PromptLimits.DEFAULT, entries));
        return runDir;
    }

    static String prompt(String id) {
        return "GeneralAnnotator prompt unit | template prompt-template-1 | run run 1 | warning " + id + "\n\n"
                + "===== BEGIN WARNING =====\nWarning ID: " + id + " — naïve ✓\n===== END WARNING =====\n";
    }

    private ExitCode run(Map<String, String> env, String... args) {
        Optional<String> warning = args.length == 2 ? Optional.of(args[1]) : Optional.empty();
        return new PrepareInferenceCommand(args[0], warning, env, temp, CLOCK,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
                .run();
    }

    private Path submission() {
        return runDir.resolve("inference/" + SUBMISSION);
    }

    private Map<?, ?> json(String file) throws IOException {
        return (Map<?, ?>) JsonParser.parse(Files.readString(submission().resolve(file)));
    }

    private String errors() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void preparesEveryGeneratedPromptUnchangedWithHashesAndDefaults() throws IOException {
        assertEquals(ExitCode.COMPLETED, run(Map.of(), "runs/run 1"), errors());

        Map<?, ?> s = json("submission.json");
        assertEquals(SUBMISSION, s.get("submissionId"));
        assertEquals("run 1", s.get("runId"));
        assertEquals("2026-11-19T12:00:00Z", s.get("createdAt"));
        List<?> prompts = (List<?>) s.get("prompts");
        assertEquals(41, prompts.size());
        for (Object o : prompts) {
            Map<?, ?> p = (Map<?, ?>) o;
            String id = (String) p.get("warningId");
            byte[] original = Files.readAllBytes(runDir.resolve("prompts/" + id + ".txt"));
            byte[] copy = Files.readAllBytes(submission().resolve((String) p.get("file")));
            assertTrue(java.util.Arrays.equals(original, copy), id + " copied unchanged");
            assertEquals(SubmissionPreparer.sha256(original), p.get("sha256"));
            assertEquals(64, ((String) p.get("sha256")).length(), "full SHA-256");
            assertEquals(Map.of("status", "GENERATED", "reproduction", "NOT_VALIDATED"), p.get("slice"));
        }
        assertEquals(41L, s.get("taskCount"));
        assertEquals(Map.of("taskIndex", 0L, "warningIds", List.of("W0001")), ((List<?>) s.get("tasks")).getFirst());
        Map<?, ?> excluded = (Map<?, ?>) ((List<?>) s.get("excluded")).getFirst();
        assertEquals("W0042", excluded.get("warningId"));
        assertEquals("SKIPPED", excluded.get("status"));
        assertFalse(Files.exists(submission().resolve("prompts/W0042.txt")));
        assertEquals(SubmissionPreparer.sha256(Files.readAllBytes(runDir.resolve("prompts/manifest.json"))),
                ((Map<?, ?>) s.get("source")).get("promptManifestSha256"));

        Map<?, ?> settings = json("settings.json");
        Map<?, ?> model = (Map<?, ?>) settings.get("model");
        assertEquals("Qwen/Qwen3.8-27B", model.get("name"));
        assertEquals("qwen3.8-27b", model.get("servedName"));
        assertEquals(32768L, model.get("maxModelLen"));
        assertEquals("qwen3", model.get("reasoningParser"));
        Map<?, ?> environment = (Map<?, ?>) settings.get("environment");
        assertEquals("/project/mjk76/mc2399/envs/llm", environment.get("condaEnv"));
        assertEquals("/project/mjk76/mc2399/hf_cache", environment.get("hfHome"));
        assertEquals(List.of("wulver", "Miniforge3"), environment.get("modules"));
        assertEquals(1L, ((Map<?, ?>) settings.get("slurm")).get("promptsPerTask"));

        String script = Files.readString(submission().resolve("run_inference.sbatch"));
        for (String line : List.of("#SBATCH --account=mjk76", "#SBATCH --partition=gpu", "#SBATCH --qos=standard",
                "#SBATCH --gres=gpu:1", "#SBATCH --array=0-40%4", "module load wulver || exit 2",
                "module load Miniforge3 || exit 2", "conda activate '/project/mjk76/mc2399/envs/llm' || exit 2",
                "HF_HUB_OFFLINE=1", "SUBMISSION_ID='" + SUBMISSION + "'",
                "exec python3 worker/ga_inference_worker.py --submission \"$PWD\" --task \"$SLURM_ARRAY_TASK_ID\"")) {
            assertTrue(script.contains(line), line + "\n" + script);
        }
        byte[] worker = Files.readAllBytes(submission().resolve("worker/ga_inference_worker.py"));
        try (InputStream in = getClass().getResourceAsStream("/generalannotator/inference/ga_inference_worker.py")) {
            assertTrue(java.util.Arrays.equals(in.readAllBytes(), worker));
        }
        Map<?, ?> files = (Map<?, ?>) s.get("files");
        assertEquals(SubmissionPreparer.sha256(worker), ((Map<?, ?>) files.get("worker")).get("sha256"));
        assertEquals(SubmissionPreparer.sha256(script.getBytes(StandardCharsets.UTF_8)),
                ((Map<?, ?>) files.get("batchScript")).get("sha256"));
        assertTrue(Files.isDirectory(submission().resolve("logs")), "Slurm needs logs/ before the job starts");
        if (Files.getFileStore(submission()).supportsFileAttributeView("posix")) {
            assertEquals("r--r--r--", PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(submission().resolve("prompts/W0001.txt"))));
            assertEquals("r-xr-xr-x", PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(submission().resolve("run_inference.sbatch"))));
            assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(submission())),
                    "results/ is created next to the inputs on the cluster");
        }
        String console = out.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("41 array task(s) with 41×1 prompt(s), array 0-40%4"), console);
        assertTrue(console.contains("Nothing was uploaded or submitted"), console);
    }

    @Test
    void tenPromptsPerTaskGivesTenTenTenTenOne() throws IOException {
        assertEquals(ExitCode.COMPLETED, run(Map.of("GA_INFERENCE_PROMPTS_PER_TASK", "10",
                "GA_INFERENCE_ARRAY_CONCURRENCY", "3"), "runs/run 1"), errors());
        List<?> tasks = (List<?>) json("submission.json").get("tasks");
        assertEquals(List.of(10, 10, 10, 10, 1), tasks.stream().map(t -> ((List<?>) ((Map<?, ?>) t).get("warningIds"))
                .size()).toList());
        assertEquals("W0041", ((List<?>) ((Map<?, ?>) tasks.get(4)).get("warningIds")).getFirst());
        Map<?, ?> w0011 = ((List<?>) json("submission.json").get("prompts")).stream().map(p -> (Map<?, ?>) p)
                .filter(p -> p.get("warningId").equals("W0011")).findFirst().orElseThrow();
        assertEquals(1L, w0011.get("taskIndex"));
        assertTrue(Files.readString(submission().resolve("run_inference.sbatch")).contains("#SBATCH --array=0-4%3\n"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("5 array task(s) with 4×10, 1×1 prompt(s)"));
    }

    @Test
    void assignmentIsNumericAndRejectsOtherBatchSizes() {
        assertEquals(List.of(List.of("W0002", "W9999"), List.of("W10000")),
                TaskAssignment.assign(List.of("W10000", "W9999", "W0002"), 2));
        for (String size : List.of("0", "11", "-1", "two")) {
            out.reset();
            err.reset();
            assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR,
                    run(Map.of("GA_INFERENCE_PROMPTS_PER_TASK", size), "runs/run 1"), size);
            assertTrue(errors().contains("GA_INFERENCE_PROMPTS_PER_TASK='" + size + "' must be an integer from 1 to 10"),
                    errors());
        }
        assertFalse(Files.exists(runDir.resolve("inference")));
    }

    @Test
    void aSingleWarningCanBeSelected() throws IOException {
        assertEquals(ExitCode.COMPLETED, run(Map.of(), "runs/run 1", "W0005"), errors());
        Map<?, ?> s = json("submission.json");
        assertEquals(List.of("W0005"), ((List<?>) s.get("prompts")).stream()
                .map(p -> ((Map<?, ?>) p).get("warningId")).toList());
        assertEquals(List.of("W0005.txt"), list(submission().resolve("prompts")));
        assertEquals(List.of(), s.get("excluded"));
        assertEquals("W0005", ((Map<?, ?>) s.get("source")).get("selection"));

        Path other = makeRun(temp.resolve("runs/run 2"), 42, "W0042");
        runDir = other;
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "runs/run 2", "W0042"));
        assertTrue(errors().contains("Warning W0042 has no GENERATED prompt (status SKIPPED)"), errors());
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "runs/run 2", "W0099"));
        assertTrue(errors().contains("Warning W0099 is not in"), errors());
        assertFalse(Files.exists(other.resolve("inference")));
    }

    @Test
    void invalidManifestsAndPromptFilesAreRejected() throws IOException {
        Path manifest = runDir.resolve("prompts/manifest.json");
        String good = Files.readString(manifest);
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put(good.replaceFirst("\"warningId\": \"W0002\"", "\"warningId\": \"W0001\""),
                "manifest lists W0001 more than once");
        cases.put(good.replace("\"file\": \"prompts/W0003.txt\"", "\"file\": \"../W0003.txt\""),
                "W0003: output.file must be \"prompts/W0003.txt\"");
        cases.put(good.replace("\"runId\": \"run 1\"", "\"runId\": \"run 9\""), "belongs to run run 9");
        cases.put(good.replace("\"prompts\": [", "\"entries\": ["), "has no \"prompts\" array");
        cases.put(good.replaceFirst("\"templateVersion\": \"prompt-template-1\",\n      \"reasons\"",
                "\"templateVersion\": \"prompt-template-0\",\n      \"reasons\""), "template prompt-template-0 is not supported");
        cases.put(good.replaceFirst("\"warningId\": \"W0004\"", "\"warningId\": \"../x\""),
                "manifest warningId '../x' is not a warning ID");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            Files.writeString(manifest, c.getKey());
            err.reset();
            assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "runs/run 1"), c.getValue());
            assertTrue(errors().contains(c.getValue()), c.getValue() + " in:\n" + errors());
        }
        Files.writeString(manifest, good);

        Files.delete(runDir.resolve("prompts/W0007.txt"));
        Files.writeString(runDir.resolve("prompts/W0008.txt"), prompt("W0008") + "edited later\n");
        Files.writeString(runDir.resolve("prompts/W0009.txt"), prompt("W0019").replace("W0019", "W0010"));
        Path outside = Files.writeString(temp.resolve("outside.txt"), prompt("W0011"));
        Files.delete(runDir.resolve("prompts/W0011.txt"));
        Files.createSymbolicLink(runDir.resolve("prompts/W0011.txt"), outside);
        err.reset();
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "runs/run 1"));
        for (String expected : List.of("W0007: prompts/W0007.txt is missing or not a regular file",
                "W0008: prompts/W0008.txt has", "the manifest records",
                "W0009: prompts/W0009.txt does not start with the header of run run 1, warning W0009",
                "W0011: prompts/W0011.txt is missing or not a regular file inside prompts/")) {
            assertTrue(errors().contains(expected), expected + " in:\n" + errors());
        }
        assertFalse(Files.exists(runDir.resolve("inference")), "nothing is written when validation fails");
    }

    @Test
    void submissionsAreNeverOverwrittenAndOutputIsDeterministic() throws IOException {
        assertEquals(ExitCode.COMPLETED, run(Map.of(), "runs/run 1"));
        byte[] before = Files.readAllBytes(submission().resolve("submission.json"));
        err.reset();
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(), "runs/run 1"));
        assertTrue(errors().contains("already exists; submissions are never overwritten"), errors());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(submission().resolve("submission.json"))));
        assertEquals(List.of(SUBMISSION), list(runDir.resolve("inference")), "no partial directory is left");

        Path other = makeRun(temp.resolve("runs/copy"), 42, "W0042");
        assertEquals(ExitCode.COMPLETED, run(Map.of(), "runs/copy"));
        for (String file : List.of("submission.json", "settings.json", "run_inference.sbatch", "prompts/W0001.txt")) {
            assertTrue(java.util.Arrays.equals(Files.readAllBytes(submission().resolve(file)),
                    Files.readAllBytes(other.resolve("inference/" + SUBMISSION + "/" + file))), file);
        }
    }

    @Test
    void settingsAreValidatedAndConfigurable() throws IOException {
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("GA_SLURM_ACCOUNT", "mjk76;reboot");
        bad.put("GA_INFERENCE_VLLM_ARGS", "--port 9000");
        bad.put("GA_INFERENCE_MAX_TOKENS", "32768");
        bad.put("GA_SLURM_TIME", "two hours");
        bad.put("GA_SLURM_GRES", "gpu:0");
        bad.put("GA_INFERENCE_CONDA_ENV", "relative/env");
        bad.put("GA_INFERENCE_TEMPERATURE", "3");
        for (Map.Entry<String, String> e : bad.entrySet()) {
            err.reset();
            assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, run(Map.of(e.getKey(), e.getValue()), "runs/run 1"),
                    e.getKey());
            assertTrue(errors().contains(e.getKey()), errors());
        }
        assertFalse(Files.exists(runDir.resolve("inference")));

        Map<String, String> custom = new HashMap<>();
        custom.put("GA_SLURM_GRES", "gpu:a100_80g:1");
        custom.put("GA_SLURM_CONSTRAINT", "A100");
        custom.put("GA_SLURM_MEM", "96G");
        custom.put("GA_SLURM_TIME", "1-00:00:00");
        custom.put("GA_INFERENCE_REASONING_PARSER", "none");
        custom.put("GA_INFERENCE_MAX_TOKENS", "16384");
        custom.put("GA_INFERENCE_VLLM_ARGS", "--gpu-memory-utilization 0.92 --enforce-eager");
        custom.put("GA_INFERENCE_MIN_GPU_MEMORY_MIB", "80000");
        assertEquals(ExitCode.COMPLETED, run(custom, "runs/run 1"), errors());
        String script = Files.readString(submission().resolve("run_inference.sbatch"));
        assertTrue(script.contains("#SBATCH --gres=gpu:a100_80g:1\n#SBATCH --constraint=A100\n#SBATCH --mem=96G\n"),
                script);
        assertTrue(script.contains("#SBATCH --time=1-00:00:00\n"), script);
        Map<?, ?> settings = json("settings.json");
        Map<?, ?> model = (Map<?, ?>) settings.get("model");
        assertEquals(null, model.get("reasoningParser"));
        assertEquals(List.of("--gpu-memory-utilization", "0.92", "--enforce-eager"), model.get("extraServerArgs"));
        assertEquals(16384L, ((Map<?, ?>) settings.get("sampling")).get("maxTokens"));
        assertEquals(80000L, ((Map<?, ?>) settings.get("server")).get("minGpuMemoryMiB"));
    }

    @Test
    void theFilesHaveEveryKeyTheWorkerReads() throws IOException {
        assertEquals(ExitCode.COMPLETED, run(Map.of(), "runs/run 1"));
        Map<?, ?> settings = json("settings.json");
        assertEquals(1L, settings.get("schemaVersion"));
        Map<String, List<String>> keys = Map.of(
                "model", List.of("name", "servedName", "maxModelLen", "reasoningParser", "extraServerArgs"),
                "sampling", List.of("temperature", "topP", "topK", "seed", "maxTokens", "minCompletionTokens"),
                "server", List.of("port", "portAttempts", "startupTimeoutSeconds", "healthPollSeconds",
                        "requestTimeoutSeconds", "stopTimeoutSeconds", "minGpuMemoryMiB"),
                "environment", List.of("modules", "condaEnv", "hfHome", "offline"));
        keys.forEach((section, names) -> assertTrue(((Map<?, ?>) settings.get(section)).keySet().containsAll(names),
                section));
        Map<?, ?> s = json("submission.json");
        assertEquals(1L, s.get("schemaVersion"));
        assertTrue(((Map<?, ?>) ((List<?>) s.get("prompts")).getFirst()).keySet().containsAll(
                List.of("warningId", "file", "sha256", "bytes", "templateVersion", "slice", "taskIndex")));
        assertTrue(((Map<?, ?>) ((List<?>) s.get("tasks")).getFirst()).keySet().containsAll(
                List.of("taskIndex", "warningIds")));
    }

    private static List<String> list(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
