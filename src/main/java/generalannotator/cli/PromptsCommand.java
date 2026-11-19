package generalannotator.cli;

import generalannotator.graph.WarningNode;
import generalannotator.prompts.PromptAssembler;
import generalannotator.prompts.PromptLimits;
import generalannotator.prompts.PromptManifestJson;
import generalannotator.prompts.PromptSources;
import generalannotator.prompts.PromptTemplate;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code prompts <run directory> [--warning ID]}: assemble one LLM prompt unit per warning from the
 * run's existing artifacts (warning graph, targets, slices, compact usage context), joined by
 * warning ID and checked to belong to the same run. No earlier stage is rerun and no LLM is called.
 *
 * <p>Writes {@code prompts/<ID>.txt} for each GENERATED unit and {@code prompts/manifest.json}. A
 * warning whose inputs are missing, failed or inconsistent is SKIPPED with reasons; a unit over a
 * size limit ({@code GA_PROMPT_MAX_CHARS}, {@code GA_PROMPT_MAX_FILE_CHARS}) is OVERSIZED. In both
 * cases no prompt file is left for that warning.
 *
 * <p>Exit codes: 0 manifest written (SKIPPED and OVERSIZED entries allowed); 2 invalid arguments,
 * limits or run files, artifacts from different runs, or an unsupported checker (nothing written);
 * 4 internal error, including failure to write.
 */
public final class PromptsCommand {

    public static final String MAX_CHARS = "GA_PROMPT_MAX_CHARS";
    public static final String MAX_FILE_CHARS = "GA_PROMPT_MAX_FILE_CHARS";
    public static final String DIRECTORY = "prompts";

    private final String runArgument;
    private final Optional<String> warningId;
    private final Map<String, String> env;
    private final Path workingDirectory;
    private final PrintStream out;
    private final PrintStream err;

    public PromptsCommand(String runArgument, Optional<String> warningId, Map<String, String> env,
            Path workingDirectory, PrintStream out, PrintStream err) {
        this.runArgument = runArgument;
        this.warningId = warningId;
        this.env = Map.copyOf(env);
        this.workingDirectory = workingDirectory;
        this.out = out;
        this.err = err;
    }

    public ExitCode run() {
        List<String> problems = new ArrayList<>();
        int maxChars = positive(MAX_CHARS, PromptLimits.DEFAULT.maxPromptChars(), problems);
        int maxFileChars = positive(MAX_FILE_CHARS, PromptLimits.DEFAULT.maxFileChars(), problems);
        if (!problems.isEmpty()) {
            return usage(problems);
        }
        PromptLimits limits = new PromptLimits(maxChars, maxFileChars);
        Path runDir;
        try {
            runDir = workingDirectory.resolve(runArgument).normalize();
        } catch (InvalidPathException e) {
            return usage(List.of("'" + runArgument + "' is not a valid path."));
        }
        if (!Files.isDirectory(runDir)) {
            return usage(List.of(runDir + " is not a run directory. Pass runs/<run id> (list them with: ls runs)."));
        }
        PromptSources sources;
        try {
            sources = PromptSources.load(runDir);
        } catch (PromptSources.InvalidRunException e) {
            return usage(List.of(e.getMessage()));
        }
        String runId = sources.graph().runId();
        String checker = sources.graph().checkerId();
        if (PromptTemplate.checkerGuidance(checker).isEmpty()) {
            return usage(List.of("No prompt template guidance for checker '" + checker + "'."));
        }
        List<WarningNode> selected = new ArrayList<>();
        for (WarningNode n : sources.graph().warningNodes()) {
            if (warningId.isEmpty() || warningId.get().equals(n.id())) {
                selected.add(n);
            }
        }
        if (warningId.isPresent() && selected.isEmpty()) {
            return usage(List.of("Warning " + warningId.get() + " is not in " + runDir.resolve("warning-graph.json")
                    + "."));
        }
        Path promptsDir = runDir.resolve(DIRECTORY);
        Path manifestFile = promptsDir.resolve(PromptManifestJson.FILE_NAME);
        Map<String, Map<?, ?>> entries;
        try {
            entries = PromptManifestJson.loadEntries(manifestFile, runId);
        } catch (IOException | IllegalArgumentException e) {
            return usage(List.of("Cannot read the existing " + manifestFile + ": " + e.getMessage()));
        }

        out.println("GeneralAnnotator prompts");
        out.println("  run:      " + runDir + " (run " + runId + ", checker " + checker + ", " + selected.size()
                + " warning(s))");
        out.println("  template: " + PromptTemplate.VERSION + "; limits " + limits.maxPromptChars()
                + " characters per unit, " + limits.maxFileChars() + " per Java file");
        out.println("  No LLM is called and no earlier stage is rerun.");
        out.println();
        int generated = 0;
        int skipped = 0;
        int oversized = 0;
        try {
            Files.createDirectories(promptsDir);
            for (WarningNode node : selected) {
                Map<String, Object> entry = unit(sources.join(node), limits, promptsDir, runDir);
                switch (String.valueOf(entry.get("status"))) {
                    case "GENERATED" -> generated++;
                    case "OVERSIZED" -> oversized++;
                    default -> skipped++;
                }
                entries.put(node.id(), entry);
                out.println(line(entry));
            }
            PromptManifestJson.save(manifestFile, PromptManifestJson.write(runId, checker, limits, entries));
        } catch (IOException e) {
            err.println("GeneralAnnotator: cannot write prompts in " + promptsDir + ": " + e);
            return ExitCode.INTERNAL_ERROR;
        }
        out.println();
        out.println("  manifest: " + manifestFile + " (" + generated + " generated, " + skipped + " skipped, "
                + oversized + " oversized)");
        return ExitCode.COMPLETED;
    }

    /** Assembles and writes one unit (or removes a stale one) and returns its manifest entry. */
    private static Map<String, Object> unit(PromptSources.Joined joined, PromptLimits limits, Path promptsDir,
            Path runDir) throws IOException {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("warningId", joined.warningId());
        List<String> reasons = new ArrayList<>(joined.reasons());
        PromptAssembler.Rendered rendered = null;
        if (joined.inputs().isPresent()) {
            try {
                rendered = PromptAssembler.assemble(joined.inputs().get(), limits);
                reasons.addAll(rendered.oversized());
            } catch (IllegalArgumentException e) {
                reasons.add("cannot delimit the input safely: " + e.getMessage());
            }
        }
        String status = rendered != null && rendered.fits() ? "GENERATED"
                : rendered != null && reasons.size() == rendered.oversized().size() ? "OVERSIZED" : "SKIPPED";
        entry.put("status", status);
        entry.put("templateVersion", PromptTemplate.VERSION);
        entry.put("reasons", reasons);
        entry.put("notes", joined.notes());
        entry.put("inputs", joined.inputPaths());
        entry.put("slice", joined.slice().isEmpty() ? null : joined.slice());
        Path promptFile = promptsDir.resolve(joined.warningId() + ".txt");
        Map<String, Object> output = new LinkedHashMap<>();
        if (status.equals("GENERATED")) {
            Files.writeString(promptFile, rendered.text(), StandardCharsets.UTF_8);
            output.put("file", runDir.relativize(promptFile).toString().replace('\\', '/'));
        } else {
            Files.deleteIfExists(promptFile); // never leave a stale prompt for a unit that is not generated
            output.put("file", null);
        }
        if (rendered != null) {
            output.put("chars", rendered.text().length());
            output.put("bytes", rendered.text().getBytes(StandardCharsets.UTF_8).length);
            output.put("sectionChars", rendered.sectionChars());
            output.put("javaFileChars", rendered.fileChars());
        }
        entry.put("output", output);
        return entry;
    }

    static String line(Map<String, Object> entry) {
        String id = String.valueOf(entry.get("warningId"));
        String status = String.valueOf(entry.get("status"));
        if (status.equals("GENERATED")) {
            Map<?, ?> output = (Map<?, ?>) entry.get("output");
            Map<?, ?> files = (Map<?, ?>) output.get("javaFileChars");
            return String.format("  %s  %-9s  %s (%s characters, %d Java file(s))", id, status, output.get("file"),
                    output.get("chars"), files.size());
        }
        List<String> reasons = ((List<?>) entry.get("reasons")).stream().map(String::valueOf).toList();
        return String.format("  %s  %-9s  %s", id, status, String.join("; ", reasons));
    }

    private int positive(String name, int fallback, List<String> problems) {
        String raw = env.get(name);
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            if (value < 1) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            problems.add(name + "='" + raw + "' must be a positive integer");
            return fallback;
        }
    }

    private ExitCode usage(List<String> problems) {
        err.println("GeneralAnnotator: cannot assemble prompts:");
        problems.forEach(p -> err.println("  - " + p));
        return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
    }
}
