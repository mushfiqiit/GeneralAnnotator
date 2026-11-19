package generalannotator.inference;

import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.prompts.PromptManifestJson;
import generalannotator.prompts.PromptTemplate;
import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Prepares an immutable inference submission directory from a run's GENERATED prompt units: the
 * prompt files copied byte for byte, {@code submission.json} (submission and run IDs, warning IDs,
 * full SHA-256 hashes, deterministic task assignments, excluded entries), {@code settings.json},
 * the Slurm batch script and the worker. Nothing is uploaded or submitted, and no model is run.
 *
 * <p>The directory is built under a temporary name and renamed into place only when complete; its
 * files are then read-only. The worker later writes {@code results/} and the batch script {@code
 * logs/} next to them on the cluster.
 */
public final class SubmissionPreparer {

    public static final String SUBMISSION_FILE = "submission.json";
    public static final String SETTINGS_FILE = "settings.json";
    public static final String WORKER_FILE = "worker/ga_inference_worker.py";
    static final String WORKER_RESOURCE = "/generalannotator/inference/ga_inference_worker.py";
    public static final Set<String> SUPPORTED_TEMPLATES = Set.of(PromptTemplate.VERSION);
    public static final String NOTE = "Prompts prepared for LLM inference. Results are proposals: they are not "
            + "checker-validated and do not establish that a warning is resolved. Slice reproduction is copied "
            + "unchanged from the prompt manifest.";
    private static final Pattern WARNING_ID = Pattern.compile("W[0-9]{4,}");
    private static final DateTimeFormatter ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** Thrown when nothing can be prepared; {@code problems} says why. */
    public static final class PreparationException extends Exception {
        private static final long serialVersionUID = 1L;
        @SuppressWarnings("serial")
        private final List<String> problems;

        PreparationException(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    /** One prompt selected for inference. */
    record Prompt(String warningId, Path source, byte[] bytes, String sha256, String templateVersion,
            Object slice) {}

    /** The prepared submission. */
    public record Prepared(Path directory, String submissionId, String runId, List<String> warningIds,
            List<List<String>> tasks, List<String> excluded) {}

    private SubmissionPreparer() {}

    public static Prepared prepare(Path runDir, Optional<String> warningId, InferenceSettings settings, Clock clock)
            throws PreparationException, IOException {
        List<String> problems = new ArrayList<>();
        Path graphFile = runDir.resolve(WarningGraphJson.FILE_NAME);
        Path promptsDir = runDir.resolve("prompts");
        Path manifestFile = promptsDir.resolve(PromptManifestJson.FILE_NAME);
        if (!Files.isRegularFile(graphFile)) {
            throw new PreparationException(List.of("No " + WarningGraphJson.FILE_NAME + " in " + runDir + "."));
        }
        if (!Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new PreparationException(List.of("No prompts/" + PromptManifestJson.FILE_NAME + " in " + runDir
                    + ". Create it with: ./gradlew run --args=\"prompts " + runDir + "\""));
        }
        WarningGraph graph;
        Map<?, ?> manifest;
        byte[] manifestBytes;
        try {
            graph = WarningGraphJson.load(graphFile);
            manifestBytes = Files.readAllBytes(manifestFile);
            Object root = JsonParser.parse(new String(manifestBytes, StandardCharsets.UTF_8));
            if (!(root instanceof Map<?, ?> m)) {
                throw new PreparationException(List.of(manifestFile + " is not a JSON object."));
            }
            manifest = m;
        } catch (IllegalArgumentException e) {
            throw new PreparationException(List.of("Cannot read the run files: " + e.getMessage()));
        }
        String runId = graph.runId();
        if (!Long.valueOf(PromptManifestJson.SCHEMA_VERSION).equals(manifest.get("schemaVersion"))) {
            problems.add(manifestFile + ": unsupported schemaVersion " + manifest.get("schemaVersion"));
        }
        if (!runId.equals(manifest.get("runId"))) {
            problems.add(manifestFile + " belongs to run " + manifest.get("runId") + ", but warning-graph.json to "
                    + runId);
        }
        if (!(manifest.get("prompts") instanceof List<?> entries)) {
            problems.add(manifestFile + " has no \"prompts\" array");
            throw new PreparationException(problems);
        }

        List<Prompt> selected = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        Map<String, Map<String, Object>> excludedEntries = new LinkedHashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        Path realPrompts = promptsDir.toRealPath();
        for (Object item : entries) {
            if (!(item instanceof Map<?, ?> e) || !(e.get("warningId") instanceof String id)) {
                problems.add("a manifest entry has no warningId");
                continue;
            }
            if (!WARNING_ID.matcher(id).matches()) {
                problems.add("manifest warningId '" + id + "' is not a warning ID like W0001");
                continue;
            }
            if (!ids.add(id)) {
                problems.add("manifest lists " + id + " more than once");
                continue;
            }
            if (warningId.isPresent() && !warningId.get().equals(id)) {
                continue;
            }
            if (!"GENERATED".equals(e.get("status"))) {
                excluded.add(id);
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("status", e.get("status"));
                x.put("reasons", e.get("reasons"));
                excludedEntries.put(id, x);
                continue;
            }
            prompt(e, id, runId, promptsDir, realPrompts, problems).ifPresent(selected::add);
        }
        if (warningId.isPresent()) {
            if (!ids.contains(warningId.get())) {
                problems.add("Warning " + warningId.get() + " is not in " + manifestFile + ".");
            } else if (!excluded.isEmpty()) {
                problems.add("Warning " + warningId.get() + " has no GENERATED prompt (status "
                        + excludedEntries.get(warningId.get()).get("status") + ").");
            }
        }
        if (problems.isEmpty() && selected.isEmpty()) {
            problems.add("No GENERATED prompt in " + manifestFile + ".");
        }
        if (!problems.isEmpty()) {
            throw new PreparationException(problems);
        }

        String submissionId = ID_FORMAT.format(clock.instant()) + "-inference";
        Path inferenceDir = runDir.resolve("inference");
        Path target = inferenceDir.resolve(submissionId);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new PreparationException(List.of(target + " already exists; submissions are never overwritten."));
        }
        List<List<String>> tasks = TaskAssignment.assign(selected.stream().map(Prompt::warningId).toList(),
                settings.promptsPerTask());
        Path partial = inferenceDir.resolve("." + submissionId + ".partial");
        if (Files.exists(partial, LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(partial);
        }
        Files.createDirectories(partial.resolve("prompts"));
        Files.createDirectories(partial.resolve("worker"));
        Files.createDirectories(partial.resolve("logs"));
        try {
            Map<String, Integer> taskOf = new LinkedHashMap<>();
            for (int t = 0; t < tasks.size(); t++) {
                for (String id : tasks.get(t)) {
                    taskOf.put(id, t);
                }
            }
            List<Object> prompts = new ArrayList<>();
            List<Prompt> ordered = new ArrayList<>(selected);
            ordered.sort(Comparator.comparing(Prompt::warningId, TaskAssignment.WARNING_ORDER));
            for (Prompt p : ordered) {
                Path copy = partial.resolve("prompts").resolve(p.warningId() + ".txt");
                Files.write(copy, p.bytes());
                if (!sha256(Files.readAllBytes(copy)).equals(p.sha256())) {
                    throw new IOException("the copy of " + p.source() + " differs from the original");
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("warningId", p.warningId());
                m.put("file", "prompts/" + p.warningId() + ".txt");
                m.put("sha256", p.sha256());
                m.put("bytes", p.bytes().length);
                m.put("templateVersion", p.templateVersion());
                m.put("slice", p.slice());
                m.put("taskIndex", taskOf.get(p.warningId()));
                prompts.add(m);
            }
            byte[] worker = workerBytes();
            Files.write(partial.resolve(WORKER_FILE), worker);
            byte[] settingsJson = Json.write(settings.toJson()).getBytes(StandardCharsets.UTF_8);
            Files.write(partial.resolve(SETTINGS_FILE), settingsJson);
            byte[] script = SlurmScript.render(settings, submissionId, runId, tasks.size())
                    .getBytes(StandardCharsets.UTF_8);
            Files.write(partial.resolve(SlurmScript.FILE_NAME), script);

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("schemaVersion", 1);
            root.put("submissionId", submissionId);
            root.put("runId", runId);
            root.put("checker", graph.checkerId());
            root.put("createdAt", clock.instant().toString());
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("promptManifest", "prompts/" + PromptManifestJson.FILE_NAME);
            source.put("promptManifestSha256", sha256(manifestBytes));
            source.put("templateVersion", manifest.get("templateVersion"));
            source.put("selection", warningId.orElse("all GENERATED"));
            root.put("source", source);
            root.put("promptsPerTask", settings.promptsPerTask());
            root.put("arrayConcurrency", settings.arrayConcurrency());
            root.put("taskCount", tasks.size());
            root.put("prompts", prompts);
            List<Object> taskList = new ArrayList<>();
            for (int t = 0; t < tasks.size(); t++) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("taskIndex", t);
                m.put("warningIds", tasks.get(t));
                taskList.add(m);
            }
            root.put("tasks", taskList);
            List<Object> excludedList = new ArrayList<>();
            excludedEntries.forEach((id, x) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("warningId", id);
                m.putAll(x);
                excludedList.add(m);
            });
            root.put("excluded", excludedList);
            Map<String, Object> files = new LinkedHashMap<>();
            files.put("settings", file(SETTINGS_FILE, settingsJson));
            files.put("worker", file(WORKER_FILE, worker));
            files.put("batchScript", file(SlurmScript.FILE_NAME, script));
            root.put("files", files);
            root.put("note", NOTE);
            Files.writeString(partial.resolve(SUBMISSION_FILE), Json.write(root), StandardCharsets.UTF_8);

            makeReadOnly(partial);
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            deleteTree(partial);
            throw e;
        }
        return new Prepared(target, submissionId, runId, selected.stream().map(Prompt::warningId)
                .sorted(TaskAssignment.WARNING_ORDER).toList(), tasks, excluded);
    }

    /** Validates one GENERATED manifest entry and its prompt file. */
    private static Optional<Prompt> prompt(Map<?, ?> e, String id, String runId, Path promptsDir, Path realPrompts,
            List<String> problems) throws IOException {
        String expected = "prompts/" + id + ".txt";
        Object template = e.get("templateVersion");
        if (!(e.get("output") instanceof Map<?, ?> output) || !expected.equals(output.get("file"))) {
            problems.add(id + ": output.file must be \"" + expected + "\"");
            return Optional.empty();
        }
        if (!(template instanceof String t) || !SUPPORTED_TEMPLATES.contains(t)) {
            problems.add(id + ": template " + template + " is not supported (" + SUPPORTED_TEMPLATES + ")");
            return Optional.empty();
        }
        if (!(e.get("slice") instanceof Map<?, ?> slice) || !(slice.get("reproduction") instanceof String)) {
            problems.add(id + ": the manifest entry has no slice reproduction status");
            return Optional.empty();
        }
        Path file = promptsDir.resolve(id + ".txt");
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !file.toRealPath().startsWith(realPrompts)) {
            problems.add(id + ": " + expected + " is missing or not a regular file inside prompts/");
            return Optional.empty();
        }
        byte[] bytes = Files.readAllBytes(file);
        if (!(output.get("bytes") instanceof Long size) || size != bytes.length) {
            problems.add(id + ": " + expected + " has " + bytes.length + " bytes, the manifest records "
                    + output.get("bytes") + " (regenerate prompts)");
            return Optional.empty();
        }
        String header = "GeneralAnnotator prompt unit | template " + template + " | run " + runId + " | warning "
                + id + "\n";
        if (!new String(bytes, StandardCharsets.UTF_8).startsWith(header)) {
            problems.add(id + ": " + expected + " does not start with the header of run " + runId + ", warning " + id);
            return Optional.empty();
        }
        return Optional.of(new Prompt(id, file, bytes, sha256(bytes), (String) template, slice));
    }

    private static Map<String, Object> file(String path, byte[] bytes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("sha256", sha256(bytes));
        return m;
    }

    static byte[] workerBytes() throws IOException {
        try (InputStream in = SubmissionPreparer.class.getResourceAsStream(WORKER_RESOURCE)) {
            if (in == null) {
                throw new IOException("the worker resource " + WORKER_RESOURCE + " is missing from the build");
            }
            return in.readAllBytes();
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Files read-only; the batch script and worker also executable; prompts/ and worker/ read-only. */
    private static void makeReadOnly(Path dir) throws IOException {
        if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                String perms;
                if (Files.isDirectory(p)) {
                    perms = rel.equals("prompts") || rel.equals("worker") ? "r-xr-xr-x" : "rwxr-xr-x";
                } else if (rel.equals(SlurmScript.FILE_NAME) || rel.equals(WORKER_FILE)) {
                    perms = "r-xr-xr-x";
                } else {
                    perms = "r--r--r--";
                }
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms));
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.getFileStore(p).supportsFileAttributeView("posix")) {
                    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
                }
                Files.delete(p);
            }
        }
    }
}
