package generalannotator.prompts;

import generalannotator.diagnostics.Diagnostic;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.graph.WarningNode;
import generalannotator.report.JsonParser;
import generalannotator.slicing.SliceResultsJson;
import generalannotator.targets.WarningTarget;
import generalannotator.targets.WarningTargetsJson;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Loads a run's artifacts once and joins them per warning ID: the warning from {@code
 * warning-graph.json}, its target from {@code warning-targets.json}, its slice from {@code
 * slices/slice-results.json} and {@code slices/<ID>/output}, and its compact usage context from
 * {@code context/<ID>.txt} (checked against {@code context/<ID>.json}, which is not put into
 * prompts). Every inconsistency or missing input becomes an explicit reason; nothing is rerun.
 */
public final class PromptSources {

    /** A run-level problem (missing graph or targets, artifacts from different runs). */
    public static final class InvalidRunException extends Exception {
        private static final long serialVersionUID = 1L;

        InvalidRunException(String message) {
            super(message);
        }
    }

    /** One slice file: its path relative to the slice root (with {@code /}) and its exact text. */
    public record SliceFile(String path, String content) {}

    /** Everything one prompt unit needs, all for the same run and warning. */
    public record Inputs(String runId, String checkerId, WarningNode warning, WarningTarget.Resolved target,
            String reproduction, List<SliceFile> files, String context) {
        public Inputs {
            files = List.copyOf(files);
        }
    }

    /**
     * The join result for one warning.
     *
     * @param inputs present only when there are no reasons
     * @param reasons why no prompt can be assembled
     * @param notes facts worth recording that do not prevent assembly
     * @param inputPaths the artifacts used, relative to the run directory
     * @param slice the slice entry's status and reproduction, if any
     */
    public record Joined(String warningId, Optional<Inputs> inputs, List<String> reasons, List<String> notes,
            Map<String, Object> inputPaths, Map<String, Object> slice) {}

    private final Path runDir;
    private final WarningGraph graph;
    private final Map<String, WarningTarget> targets = new LinkedHashMap<>();
    private final Optional<Map<String, Map<?, ?>>> slices;

    private PromptSources(Path runDir, WarningGraph graph, List<WarningTarget> targets,
            Optional<Map<String, Map<?, ?>>> slices) {
        this.runDir = runDir;
        this.graph = graph;
        targets.forEach(t -> this.targets.put(t.warningId(), t));
        this.slices = slices;
    }

    /** Loads the run-level artifacts and checks that they come from the same run. */
    public static PromptSources load(Path runDir) throws InvalidRunException {
        Path graphFile = runDir.resolve(WarningGraphJson.FILE_NAME);
        Path targetsFile = runDir.resolve(WarningTargetsJson.FILE_NAME);
        Path slicesFile = runDir.resolve("slices").resolve(SliceResultsJson.FILE_NAME);
        if (!Files.isRegularFile(graphFile)) {
            throw new InvalidRunException("No " + WarningGraphJson.FILE_NAME + " in " + runDir + ".");
        }
        if (!Files.isRegularFile(targetsFile)) {
            throw new InvalidRunException("No " + WarningTargetsJson.FILE_NAME + " in " + runDir
                    + ". Create it with: ./gradlew run --args=\"targets " + runDir + "\"");
        }
        try {
            WarningGraph graph = WarningGraphJson.load(graphFile);
            WarningTargetsJson.TargetsFile targets = WarningTargetsJson.load(targetsFile);
            if (!targets.runId().equals(graph.runId())) {
                throw new InvalidRunException(targetsFile + " belongs to run " + targets.runId() + ", but "
                        + graphFile + " to run " + graph.runId() + ".");
            }
            Optional<Map<String, Map<?, ?>>> slices = Optional.empty();
            if (Files.isRegularFile(slicesFile)) {
                Object root = JsonParser.parse(Files.readString(slicesFile, StandardCharsets.UTF_8));
                Object runId = root instanceof Map<?, ?> m ? m.get("runId") : null;
                if (!graph.runId().equals(runId)) {
                    throw new InvalidRunException(slicesFile + " belongs to run " + runId + ", but " + graphFile
                            + " to run " + graph.runId() + ".");
                }
                slices = Optional.of(SliceResultsJson.loadEntries(slicesFile));
            }
            return new PromptSources(runDir, graph, targets.targets(), slices);
        } catch (IOException | IllegalArgumentException e) {
            throw new InvalidRunException("Cannot read the run files: " + e.getMessage());
        }
    }

    public WarningGraph graph() {
        return graph;
    }

    /** {@code targetPath:line[:column]}, exactly as warning-targets.json writes a warning's location. */
    static String location(WarningNode node) {
        Diagnostic d = node.diagnostic();
        return node.targetPath().orElse(d.sourcePath()) + ":" + d.line()
                + (d.column().isPresent() ? ":" + d.column().getAsInt() : "");
    }

    public Joined join(WarningNode node) {
        String id = node.id();
        List<String> reasons = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, Object> paths = new LinkedHashMap<>();
        paths.put("warningGraph", WarningGraphJson.FILE_NAME);
        paths.put("warningTargets", WarningTargetsJson.FILE_NAME);

        // Target.
        WarningTarget target = targets.get(id);
        WarningTarget.Resolved resolved = null;
        if (target == null) {
            reasons.add("warning-targets.json has no entry for " + id);
        } else if (!target.location().equals(location(node))) {
            reasons.add("warning-targets.json locates " + id + " at " + target.location() + ", but the warning graph "
                    + "at " + location(node) + "; the artifacts do not describe the same warning");
        } else if (target instanceof WarningTarget.Unresolved u) {
            reasons.add("the target member is unresolved: " + u.reason());
        } else {
            resolved = (WarningTarget.Resolved) target;
        }

        // Slice.
        Map<String, Object> slice = new LinkedHashMap<>();
        List<SliceFile> files = new ArrayList<>();
        String reproduction = null;
        Path sliceDir = runDir.resolve("slices").resolve(id);
        if (slices.isEmpty()) {
            reasons.add("no slices/" + SliceResultsJson.FILE_NAME + " (generate slices with the slice command)");
        } else {
            paths.put("sliceResults", "slices/" + SliceResultsJson.FILE_NAME);
            Map<?, ?> entry = slices.get().get(id);
            if (entry == null) {
                reasons.add("slices/" + SliceResultsJson.FILE_NAME + " has no entry for " + id);
            } else {
                slice.put("status", entry.get("status"));
                slice.put("reproduction", entry.get("reproduction"));
                reproduction = entry.get("reproduction") instanceof String r ? r : null;
                if ("SKIPPED".equals(entry.get("status")) && target instanceof WarningTarget.Unresolved) {
                    // Skipped because the target is unresolved; that reason is already recorded.
                } else if (!"GENERATED".equals(entry.get("status"))) {
                    reasons.add("the slice is " + entry.get("status")
                            + (entry.get("failureReason") != null ? ": " + entry.get("failureReason") : ""));
                } else if (reproduction == null) {
                    reasons.add("the slice entry for " + id + " has no reproduction status");
                } else {
                    sliceMatchesTarget(entry, resolved, id, reasons);
                    readSlice(entry, id, sliceDir, files, paths, reasons, notes);
                }
            }
        }

        // Usage context.
        Path contextText = runDir.resolve("context").resolve(id + ".txt");
        Path contextJson = runDir.resolve("context").resolve(id + ".json");
        String context = null;
        if (!Files.isRegularFile(contextText) || !Files.isRegularFile(contextJson)) {
            reasons.add("no usage context context/" + id + ".txt and .json (extract it with the context command)");
        } else {
            paths.put("usageContext", "context/" + id + ".txt");
            paths.put("usageContextCheckedAgainst", "context/" + id + ".json");
            try {
                Object root = JsonParser.parse(Files.readString(contextJson, StandardCharsets.UTF_8));
                Map<?, ?> m = root instanceof Map<?, ?> map ? map : Map.of();
                if (!graph.runId().equals(m.get("runId")) || !id.equals(m.get("warningId"))) {
                    reasons.add("context/" + id + ".json belongs to run " + m.get("runId") + ", warning "
                            + m.get("warningId") + ", not run " + graph.runId() + ", warning " + id);
                } else if ("SKIPPED".equals(m.get("status")) && target instanceof WarningTarget.Unresolved) {
                    // Only the warning site is described because the target is unresolved (already recorded).
                } else if (!"EXTRACTED".equals(m.get("status"))) {
                    reasons.add("the usage context is " + m.get("status")
                            + (m.get("reason") != null ? ": " + m.get("reason") : ""));
                } else {
                    context = ContextSection.body(Files.readString(contextText, StandardCharsets.UTF_8), id);
                }
            } catch (IOException | IllegalArgumentException e) {
                reasons.add("cannot read the usage context: " + e.getMessage());
            }
        }

        Optional<Inputs> inputs = reasons.isEmpty()
                ? Optional.of(new Inputs(graph.runId(), graph.checkerId(), node, Objects.requireNonNull(resolved),
                        reproduction, files, context))
                : Optional.empty();
        return new Joined(id, inputs, reasons, notes, paths, slice);
    }

    private static void sliceMatchesTarget(Map<?, ?> entry, WarningTarget.Resolved target, String id,
            List<String> reasons) {
        if (target == null || !(entry.get("target") instanceof Map<?, ?> t)) {
            return;
        }
        Object member = t.get("targetMethod") != null ? t.get("targetMethod") : t.get("targetField");
        if (!target.location().equals(t.get("location")) || !target.target().equals(member)
                || !target.targetFile().equals(t.get("targetFile"))) {
            reasons.add("the slice of " + id + " was generated for " + member + " at " + t.get("location")
                    + ", but warning-targets.json now names " + target.target() + " at " + target.location());
        }
    }

    private Path outputDirectory(Map<?, ?> entry) {
        try {
            return entry.get("outputDirectory") instanceof String dir ? runDir.resolve(dir).normalize() : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private void readSlice(Map<?, ?> entry, String id, Path sliceDir, List<SliceFile> files,
            Map<String, Object> paths, List<String> reasons, List<String> notes) {
        Path output = outputDirectory(entry);
        if (output == null || !output.startsWith(sliceDir) || !Files.isDirectory(output)) {
            reasons.add("the slice output directory " + entry.get("outputDirectory") + " is missing or not inside "
                    + "slices/" + id);
            return;
        }
        paths.put("sliceOutput", runDir.relativize(output).toString().replace('\\', '/'));
        List<String> onDisk;
        try (Stream<Path> walk = Files.walk(output)) {
            onDisk = walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .map(p -> output.relativize(p).toString().replace('\\', '/')).sorted().toList();
        } catch (IOException e) {
            reasons.add("cannot list the slice files: " + e.getMessage());
            return;
        }
        List<String> recorded = new ArrayList<>();
        if (entry.get("outputFiles") instanceof List<?> list) {
            list.forEach(f -> recorded.add(String.valueOf(f)));
        }
        List<String> missing = recorded.stream().filter(f -> !onDisk.contains(f)).toList();
        if (!missing.isEmpty()) {
            reasons.add("slice files recorded in slice-results.json are missing on disk: " + missing);
        }
        if (onDisk.isEmpty()) {
            reasons.add("the slice output contains no Java files");
        }
        List<String> extra = onDisk.stream().filter(f -> !recorded.contains(f)).toList();
        if (!extra.isEmpty()) {
            notes.add("Java files not listed in slice-results.json were included: " + extra);
        }
        paths.put("sliceFiles", onDisk);
        for (String f : onDisk) {
            try {
                byte[] bytes = Files.readAllBytes(output.resolve(f));
                String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                files.add(new SliceFile(f, text));
            } catch (CharacterCodingException e) {
                reasons.add("slice file " + f + " is not valid UTF-8");
            } catch (IOException e) {
                reasons.add("cannot read slice file " + f + ": " + e.getMessage());
            }
        }
    }
}
