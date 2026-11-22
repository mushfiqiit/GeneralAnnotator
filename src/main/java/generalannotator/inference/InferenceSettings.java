package generalannotator.inference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Settings of an inference submission: Slurm resources, the cluster environment, the served model,
 * sampling and the worker's server limits. Read from {@code GA_SLURM_*} and {@code GA_INFERENCE_*}
 * environment variables; defaults are the observed NJIT Wulver setup and the historical vLLM
 * settings, which still need a GPU smoke test. Values that end up in the batch script are restricted
 * to safe characters.
 */
public record InferenceSettings(
        String account,
        String partition,
        String qos,
        String gres,
        Optional<String> constraint,
        int cpusPerTask,
        Optional<String> memory,
        String time,
        int signalSeconds,
        String jobName,
        int promptsPerTask,
        int arrayConcurrency,
        List<String> modules,
        String condaEnv,
        String hfHome,
        String model,
        String servedModelName,
        int maxModelLen,
        Optional<String> reasoningParser,
        List<String> extraServerArgs,
        double temperature,
        double topP,
        int topK,
        long seed,
        int maxTokens,
        int minCompletionTokens,
        int port,
        int portAttempts,
        int startupTimeoutSeconds,
        int healthPollSeconds,
        int requestTimeoutSeconds,
        int stopTimeoutSeconds,
        int minGpuMemoryMiB) {

    public static final int MIN_PROMPTS_PER_TASK = 1;
    public static final int MAX_PROMPTS_PER_TASK = 10;

    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9._:/+=,@%-]+");
    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9._/+@-]*");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)?");
    private static final Pattern TIME = Pattern.compile("([0-9]+-)?[0-9]{1,2}:[0-9]{2}:[0-9]{2}");
    private static final Pattern GRES = Pattern.compile("gpu(:[A-Za-z0-9_.-]+)?:[1-9][0-9]*");
    /** Server options the worker sets itself. */
    static final Set<String> RESERVED_SERVER_ARGS = Set.of("--host", "--port", "--served-model-name",
            "--max-model-len", "--reasoning-parser", "--download-dir", "--api-key");

    public InferenceSettings {
        modules = List.copyOf(modules);
        extraServerArgs = List.copyOf(extraServerArgs);
    }

    /** Variable name, default value, description (for documentation and errors). */
    public record Variable(String name, String defaultValue, String meaning) {}

    public static final List<Variable> VARIABLES = List.of(
            new Variable("GA_SLURM_ACCOUNT", "mjk76", "Slurm account"),
            new Variable("GA_SLURM_PARTITION", "gpu", "Slurm partition"),
            new Variable("GA_SLURM_QOS", "standard", "Slurm QoS"),
            new Variable("GA_SLURM_GRES", "gpu:1", "GPU request, gpu[:type]:count"),
            new Variable("GA_SLURM_CONSTRAINT", "", "optional node constraint (for example a GPU feature)"),
            new Variable("GA_SLURM_CPUS_PER_TASK", "8", "CPUs per array task"),
            new Variable("GA_SLURM_MEM", "", "optional memory per array task, for example 64G"),
            new Variable("GA_SLURM_TIME", "02:00:00", "time limit per array task"),
            new Variable("GA_SLURM_SIGNAL_SECONDS", "120", "SIGTERM this many seconds before the time limit"),
            new Variable("GA_SLURM_JOB_NAME", "ga-inference", "job name"),
            new Variable("GA_SLURM_MODULES", "wulver Miniforge3", "modules loaded before activation"),
            new Variable("GA_INFERENCE_PROMPTS_PER_TASK", "1", "prompts per array task, 1 to 10"),
            new Variable("GA_INFERENCE_ARRAY_CONCURRENCY", "4", "array tasks running at once (%N)"),
            new Variable("GA_INFERENCE_CONDA_ENV", "/project/mjk76/mc2399/envs/llm", "conda environment"),
            new Variable("GA_INFERENCE_HF_HOME", "/project/mjk76/mc2399/hf_cache", "Hugging Face cache (offline)"),
            new Variable("GA_INFERENCE_MODEL", "Qwen/Qwen3.8-27B", "cached model to serve"),
            new Variable("GA_INFERENCE_SERVED_MODEL_NAME", "qwen3.8-27b", "served model name"),
            new Variable("GA_INFERENCE_MAX_MODEL_LEN", "32768", "context length (prompt + answer tokens)"),
            new Variable("GA_INFERENCE_REASONING_PARSER", "qwen3", "vLLM reasoning parser, or none"),
            new Variable("GA_INFERENCE_VLLM_ARGS", "", "extra vllm serve arguments, space separated"),
            new Variable("GA_INFERENCE_TEMPERATURE", "0.6", "sampling temperature"),
            new Variable("GA_INFERENCE_TOP_P", "0.95", "top_p"),
            new Variable("GA_INFERENCE_TOP_K", "20", "top_k, or 0 to not send it"),
            new Variable("GA_INFERENCE_SEED", "0", "per-request seed"),
            new Variable("GA_INFERENCE_MAX_TOKENS", "0", "max_tokens per answer, or 0 for the rest of the context"),
            new Variable("GA_INFERENCE_MIN_COMPLETION_TOKENS", "4096",
                    "answer tokens a prompt must leave free (checked before sending)"),
            new Variable("GA_INFERENCE_PORT", "0", "server port, or 0 for a free port"),
            new Variable("GA_INFERENCE_PORT_ATTEMPTS", "3", "server start attempts on port collisions"),
            new Variable("GA_INFERENCE_STARTUP_TIMEOUT_SECONDS", "1800", "deadline for vLLM readiness (/health): "
                    + "model loading, compilation and CUDA graph capture; not the request timeout"),
            new Variable("GA_INFERENCE_HEALTH_POLL_SECONDS", "5", "/health polling interval"),
            new Variable("GA_INFERENCE_REQUEST_TIMEOUT_SECONDS", "2700", "timeout of one request"),
            new Variable("GA_INFERENCE_STOP_TIMEOUT_SECONDS", "30", "grace period when stopping the server"),
            new Variable("GA_INFERENCE_MIN_GPU_MEMORY_MIB", "70000",
                    "minimum memory of each allocated GPU; the worker refuses smaller GPUs"));

    /** Reads and validates the settings; problems are added to {@code problems}. */
    public static InferenceSettings fromEnvironment(Map<String, String> env, List<String> problems) {
        Reader r = new Reader(env, problems);
        String time = r.matching("GA_SLURM_TIME", TIME, "a Slurm time such as 02:00:00 or 1-00:00:00");
        String reasoning = r.matching("GA_INFERENCE_REASONING_PARSER", NAME, "a parser name or none");
        List<String> extra = r.words("GA_INFERENCE_VLLM_ARGS");
        for (String arg : extra) {
            String flag = arg.contains("=") ? arg.substring(0, arg.indexOf('=')) : arg;
            if (RESERVED_SERVER_ARGS.contains(flag)) {
                problems.add("GA_INFERENCE_VLLM_ARGS must not set " + flag + "; the worker sets it");
            }
        }
        int maxModelLen = r.integer("GA_INFERENCE_MAX_MODEL_LEN", 1024, 10_000_000);
        int maxTokens = r.integer("GA_INFERENCE_MAX_TOKENS", 0, 10_000_000);
        int minCompletion = r.integer("GA_INFERENCE_MIN_COMPLETION_TOKENS", 1, 10_000_000);
        if (maxTokens >= maxModelLen) {
            problems.add("GA_INFERENCE_MAX_TOKENS=" + maxTokens + " must be smaller than GA_INFERENCE_MAX_MODEL_LEN="
                    + maxModelLen);
        }
        if (minCompletion >= maxModelLen) {
            problems.add("GA_INFERENCE_MIN_COMPLETION_TOKENS=" + minCompletion
                    + " must be smaller than GA_INFERENCE_MAX_MODEL_LEN=" + maxModelLen);
        }
        int port = r.integer("GA_INFERENCE_PORT", 0, 65535);
        if (port != 0 && port < 1024) {
            problems.add("GA_INFERENCE_PORT=" + port + " must be 0 (any free port) or 1024..65535");
        }
        int topK = r.integer("GA_INFERENCE_TOP_K", 0, 100_000);
        return new InferenceSettings(
                r.matching("GA_SLURM_ACCOUNT", WORD, "a Slurm account"),
                r.matching("GA_SLURM_PARTITION", WORD, "a partition"),
                r.matching("GA_SLURM_QOS", WORD, "a QoS"),
                r.matching("GA_SLURM_GRES", GRES, "gpu:<count> or gpu:<type>:<count>"),
                r.optional("GA_SLURM_CONSTRAINT", WORD),
                r.integer("GA_SLURM_CPUS_PER_TASK", 1, 1024),
                r.optional("GA_SLURM_MEM", WORD),
                time,
                r.integer("GA_SLURM_SIGNAL_SECONDS", 0, 3600),
                r.matching("GA_SLURM_JOB_NAME", NAME, "letters, digits, '.', '_' or '-'"),
                r.integer("GA_INFERENCE_PROMPTS_PER_TASK", MIN_PROMPTS_PER_TASK, MAX_PROMPTS_PER_TASK),
                r.integer("GA_INFERENCE_ARRAY_CONCURRENCY", 1, 10_000),
                r.words("GA_SLURM_MODULES"),
                r.matching("GA_INFERENCE_CONDA_ENV", PATH, "an absolute path without spaces"),
                r.matching("GA_INFERENCE_HF_HOME", PATH, "an absolute path without spaces"),
                r.matching("GA_INFERENCE_MODEL", MODEL, "a model id such as Qwen/Qwen3.8-27B"),
                r.matching("GA_INFERENCE_SERVED_MODEL_NAME", NAME, "letters, digits, '.', '_' or '-'"),
                maxModelLen,
                reasoning == null || reasoning.equals("none") ? Optional.empty() : Optional.of(reasoning),
                extra,
                r.decimal("GA_INFERENCE_TEMPERATURE", 0, 2),
                r.decimal("GA_INFERENCE_TOP_P", 0.000001, 1),
                topK,
                r.integer("GA_INFERENCE_SEED", 0, Integer.MAX_VALUE),
                maxTokens,
                minCompletion,
                port,
                r.integer("GA_INFERENCE_PORT_ATTEMPTS", 1, 20),
                r.integer("GA_INFERENCE_STARTUP_TIMEOUT_SECONDS", 10, 86_400),
                r.integer("GA_INFERENCE_HEALTH_POLL_SECONDS", 1, 600),
                r.integer("GA_INFERENCE_REQUEST_TIMEOUT_SECONDS", 10, 86_400),
                r.integer("GA_INFERENCE_STOP_TIMEOUT_SECONDS", 1, 600),
                r.integer("GA_INFERENCE_MIN_GPU_MEMORY_MIB", 0, 10_000_000));
    }

    /** The {@code settings.json} content read by the worker. */
    public Map<String, Object> toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", model);
        m.put("servedName", servedModelName);
        m.put("maxModelLen", maxModelLen);
        m.put("reasoningParser", reasoningParser.orElse(null));
        m.put("extraServerArgs", extraServerArgs);
        root.put("model", m);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("temperature", temperature);
        s.put("topP", topP);
        s.put("topK", topK);
        s.put("seed", seed);
        s.put("maxTokens", maxTokens);
        s.put("minCompletionTokens", minCompletionTokens);
        root.put("sampling", s);
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("port", port);
        server.put("portAttempts", portAttempts);
        server.put("startupTimeoutSeconds", startupTimeoutSeconds);
        server.put("healthPollSeconds", healthPollSeconds);
        server.put("requestTimeoutSeconds", requestTimeoutSeconds);
        server.put("stopTimeoutSeconds", stopTimeoutSeconds);
        server.put("minGpuMemoryMiB", minGpuMemoryMiB);
        root.put("server", server);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("modules", modules);
        e.put("condaEnv", condaEnv);
        e.put("hfHome", hfHome);
        e.put("offline", true);
        root.put("environment", e);
        Map<String, Object> slurm = new LinkedHashMap<>();
        slurm.put("account", account);
        slurm.put("partition", partition);
        slurm.put("qos", qos);
        slurm.put("gres", gres);
        slurm.put("constraint", constraint.orElse(null));
        slurm.put("cpusPerTask", cpusPerTask);
        slurm.put("mem", memory.orElse(null));
        slurm.put("time", time);
        slurm.put("signalSeconds", signalSeconds);
        slurm.put("jobName", jobName);
        slurm.put("promptsPerTask", promptsPerTask);
        slurm.put("arrayConcurrency", arrayConcurrency);
        root.put("slurm", slurm);
        return root;
    }

    /** Reads variables with defaults from {@link #VARIABLES}. */
    private static final class Reader {
        private final Map<String, String> env;
        private final List<String> problems;

        Reader(Map<String, String> env, List<String> problems) {
            this.env = env;
            this.problems = problems;
        }

        private String raw(String name) {
            String value = env.get(name);
            if (value == null) {
                value = VARIABLES.stream().filter(v -> v.name().equals(name)).findFirst().orElseThrow().defaultValue();
            }
            return value.strip();
        }

        String matching(String name, Pattern pattern, String expected) {
            String value = raw(name);
            if (!pattern.matcher(value).matches()) {
                problems.add(name + "='" + value + "' must be " + expected);
            }
            return value;
        }

        Optional<String> optional(String name, Pattern pattern) {
            String value = raw(name);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            if (!pattern.matcher(value).matches()) {
                problems.add(name + "='" + value + "' contains characters that are not allowed");
            }
            return Optional.of(value);
        }

        List<String> words(String name) {
            String value = raw(name);
            List<String> words = new ArrayList<>();
            for (String w : value.isEmpty() ? new String[0] : value.split("\\s+")) {
                if (!WORD.matcher(w).matches()) {
                    problems.add(name + " contains '" + w + "', which has characters that are not allowed");
                }
                words.add(w);
            }
            return words;
        }

        int integer(String name, int min, int max) {
            String value = raw(name);
            try {
                int n = Integer.parseInt(value);
                if (n < min || n > max) {
                    throw new NumberFormatException();
                }
                return n;
            } catch (NumberFormatException e) {
                problems.add(name + "='" + value + "' must be an integer from " + min + " to " + max);
                return min;
            }
        }

        double decimal(String name, double min, double max) {
            String value = raw(name);
            try {
                double d = Double.parseDouble(value);
                if (!(d >= min && d <= max)) {
                    throw new NumberFormatException();
                }
                return d;
            } catch (NumberFormatException e) {
                problems.add(name + "='" + value + "' must be a number from " + min + " to " + max);
                return min;
            }
        }
    }
}
