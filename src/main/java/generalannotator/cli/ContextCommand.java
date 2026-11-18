package generalannotator.cli;

import generalannotator.context.ContextExtractor;
import generalannotator.context.ContextJson;
import generalannotator.context.ContextLimits;
import generalannotator.context.ContextStatus;
import generalannotator.context.ContextText;
import generalannotator.context.EvidenceCategory;
import generalannotator.context.SourceModel;
import generalannotator.context.WarningContext;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.graph.WarningNode;
import generalannotator.report.JsonParser;
import generalannotator.targets.WarningTarget;
import generalannotator.targets.WarningTargetsJson;
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
import java.util.OptionalInt;

/**
 * {@code context <run directory> [--warning ID]}: extract usage context for every warning (or one)
 * of a run from its isolated snapshot, keyed by the IDs in {@code warning-targets.json}. Neither the
 * checker nor Specimin nor any LLM is run, and no source is changed.
 *
 * <p>Reads {@code warning-targets.json}, {@code warning-graph.json} (for the warning messages) and
 * {@code report.json} (configured source roots, classpath, {@code --release}), attributes all Java
 * files under the configured source roots of {@code workspace/target} with javac, and writes {@code
 * context/<ID>.json} and {@code context/<ID>.txt} (regenerated on every run). Limits come from {@code
 * GA_CONTEXT_MAX_ITEMS}, {@code GA_CONTEXT_MAX_SNIPPET_LINES}, {@code GA_CONTEXT_MAX_CONDITIONS} and
 * {@code GA_CONTEXT_MAX_LOCAL_USES}.
 *
 * <p>Exit codes: 0 context written for every selected warning (EXTRACTED, or SKIPPED for warnings
 * without a resolved target); 2 invalid arguments, configuration or run files; 3 attribution was
 * impossible or a target could not be located (FAILED); 4 internal error.
 */
public final class ContextCommand {

    public static final String MAX_ITEMS = "GA_CONTEXT_MAX_ITEMS";
    public static final String MAX_SNIPPET_LINES = "GA_CONTEXT_MAX_SNIPPET_LINES";
    public static final String MAX_CONDITIONS = "GA_CONTEXT_MAX_CONDITIONS";
    public static final String MAX_LOCAL_USES = "GA_CONTEXT_MAX_LOCAL_USES";
    public static final String DIRECTORY = "context";

    private final String runArgument;
    private final Optional<String> warningId;
    private final Map<String, String> env;
    private final Path workingDirectory;
    private final PrintStream out;
    private final PrintStream err;

    public ContextCommand(String runArgument, Optional<String> warningId, Map<String, String> env,
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
        ContextLimits limits = limits(problems);
        if (!problems.isEmpty()) {
            return usage(problems);
        }
        Path runDir;
        try {
            runDir = workingDirectory.resolve(runArgument).normalize();
        } catch (InvalidPathException e) {
            return usage(List.of("'" + runArgument + "' is not a valid path."));
        }
        if (!Files.isDirectory(runDir)) {
            return usage(List.of(runDir + " is not a run directory. Pass runs/<run id> (list them with: ls runs)."));
        }
        Path targetsFile = runDir.resolve(WarningTargetsJson.FILE_NAME);
        Path graphFile = runDir.resolve(WarningGraphJson.FILE_NAME);
        Path reportFile = runDir.resolve("report.json");
        Path snapshot = runDir.resolve("workspace").resolve("target");
        if (!Files.isRegularFile(targetsFile)) {
            return usage(List.of("No " + WarningTargetsJson.FILE_NAME + " in " + runDir
                    + ". Create it with: ./gradlew run --args=\"targets " + runDir + "\""));
        }
        if (!Files.isRegularFile(graphFile)) {
            return usage(List.of("No " + WarningGraphJson.FILE_NAME + " in " + runDir + "."));
        }
        if (!Files.isRegularFile(reportFile)) {
            return usage(List.of("No report.json in " + runDir + "; it is needed for the configured source roots."));
        }
        if (!Files.isDirectory(snapshot)) {
            return usage(List.of("The run's isolated snapshot " + snapshot + " is missing; context must be extracted "
                    + "from the snapshot that produced the warnings."));
        }

        WarningTargetsJson.TargetsFile targets;
        WarningGraph graph;
        List<Path> sourceRoots = new ArrayList<>();
        List<Path> classpath = new ArrayList<>();
        OptionalInt release = OptionalInt.empty();
        try {
            targets = WarningTargetsJson.load(targetsFile);
            graph = WarningGraphJson.load(graphFile);
            Object report = JsonParser.parse(Files.readString(reportFile, StandardCharsets.UTF_8));
            if (!(report instanceof Map<?, ?> r) || !(r.get("configuration") instanceof Map<?, ?> config)
                    || !(config.get("sourceRoots") instanceof List<?> roots)) {
                return usage(List.of(reportFile + " has no configuration.sourceRoots."));
            }
            for (Object root : roots) {
                Path p = Path.of(String.valueOf(root));
                if (p.isAbsolute() || p.normalize().startsWith("..")) {
                    return usage(List.of("Source root '" + root + "' in " + reportFile + " is not inside the snapshot."));
                }
                sourceRoots.add(p);
            }
            if (config.get("classpath") instanceof List<?> entries) {
                entries.forEach(c -> classpath.add(Path.of(String.valueOf(c))));
            }
            if (config.get("javacRelease") instanceof Long value) {
                release = OptionalInt.of(Math.toIntExact(value));
            }
        } catch (IOException | IllegalArgumentException e) {
            return usage(List.of("Cannot read the run files: " + e.getMessage()));
        }

        List<WarningTarget> selected = new ArrayList<>();
        if (warningId.isPresent()) {
            targets.targets().stream().filter(t -> t.warningId().equals(warningId.get())).findFirst()
                    .ifPresent(selected::add);
            if (selected.isEmpty()) {
                return usage(List.of("Warning " + warningId.get() + " is not in " + targetsFile + "."));
            }
        } else {
            selected.addAll(targets.targets());
        }
        Map<String, WarningNode> nodes = new LinkedHashMap<>();
        graph.warningNodes().forEach(n -> nodes.put(n.id(), n));

        // Attribute the snapshot once: every file under the configured roots, plus the target files.
        List<Path> rootDirs = sourceRoots.stream().map(r -> snapshot.resolve(r).normalize()).toList();
        List<Path> missingRoots = rootDirs.stream().filter(d -> !Files.isDirectory(d)).toList();
        List<Path> files = new ArrayList<>();
        try {
            files.addAll(SourceModel.javaFiles(rootDirs));
        } catch (IOException e) {
            err.println("GeneralAnnotator: cannot list the snapshot's sources: " + e);
            return ExitCode.EXECUTION_FAILURE;
        }
        for (WarningTarget t : selected) {
            if (t instanceof WarningTarget.Resolved r) {
                Path f = snapshot.resolve(r.sourceRoot()).resolve(r.targetFile()).normalize();
                if (f.startsWith(snapshot) && Files.isRegularFile(f) && !files.contains(f)) {
                    files.add(f);
                }
            }
        }
        List<String> runLimitations = new ArrayList<>();
        missingRoots.forEach(d -> runLimitations.add("Configured source root " + d + " does not exist in the snapshot."));
        List<Path> missingClasspath = classpath.stream().filter(p -> !Files.exists(p)).toList();
        missingClasspath.forEach(p -> runLimitations.add("Classpath entry " + p + " does not exist; declarations from "
                + "it are unavailable."));
        if (classpath.isEmpty()) {
            runLimitations.add("No classpath was configured (GA_TARGET_CLASSPATH): only the JDK and the analyzed "
                    + "sources are known to javac.");
        }

        out.println("GeneralAnnotator context");
        out.println("  run:      " + runDir + " (run " + targets.runId() + ", " + selected.size() + " warning(s))");
        out.println("  sources:  " + files.size() + " Java file(s) under " + sourceRoots + " in " + snapshot);
        Path contextDir = runDir.resolve(DIRECTORY);
        try (SourceModel model = SourceModel.attribute(snapshot, files, classpath, release)) {
            if (!model.errors().isEmpty()) {
                SourceModel.CompilerError first = model.errors().getFirst();
                runLimitations.add("javac reported " + model.errors().size() + " error(s) while attributing the "
                        + "snapshot (first: " + first.file() + ":" + first.line() + ": " + first.message() + "). "
                        + "References in erroneous code may lack symbols; such references with the target's name "
                        + "are listed as unresolved.");
            }
            out.println("  javac:    " + model.errors().size() + " attribution error(s)"
                    + (model.errors().isEmpty() ? "" : " (missing dependencies or broken code; see analysis in the JSON)"));
            out.println("  output:   " + contextDir);
            out.println("  Neither the checker, Specimin nor an LLM is run; observations are not proofs.");
            out.println();
            Map<String, Object> analysis = analysis(snapshot, sourceRoots, files, classpath, missingClasspath, release,
                    model);
            Files.createDirectories(contextDir);
            ContextExtractor extractor = new ContextExtractor(model, limits, runLimitations);
            int failed = 0;
            for (WarningTarget t : selected) {
                WarningContext context = extractor.extract(t, Optional.ofNullable(nodes.get(t.warningId())));
                if (context.status() == ContextStatus.FAILED) {
                    failed++;
                }
                Files.writeString(contextDir.resolve(t.warningId() + ".json"),
                        ContextJson.write(context, targets.runId(), analysis, limits), StandardCharsets.UTF_8);
                Files.writeString(contextDir.resolve(t.warningId() + ".txt"), ContextText.render(context),
                        StandardCharsets.UTF_8);
                out.println(line(context));
            }
            out.println();
            out.println("  wrote " + selected.size() + " context file pair(s) (.json, .txt) to " + contextDir
                    + (failed == 0 ? "" : "; " + failed + " FAILED"));
            return failed == 0 ? ExitCode.COMPLETED : ExitCode.EXECUTION_FAILURE;
        } catch (SourceModel.AttributionException e) {
            err.println("GeneralAnnotator: cannot attribute the snapshot: " + e.getMessage());
            return ExitCode.EXECUTION_FAILURE;
        } catch (IOException e) {
            err.println("GeneralAnnotator: cannot write context files in " + contextDir + ": " + e);
            return ExitCode.INTERNAL_ERROR;
        }
    }

    /** One summary line, for example {@code W0001  EXTRACTED  METHOD p.A#m(int)  callSites=3 ...}. */
    static String line(WarningContext c) {
        if (c.status() != ContextStatus.EXTRACTED) {
            return String.format("  %s  %-9s  %s", c.warningId(), c.status(), c.reason().orElse(""));
        }
        StringBuilder counts = new StringBuilder();
        for (EvidenceCategory category : c.categories()) {
            if (category.name().equals("warningSite")) {
                continue;
            }
            counts.append(' ').append(category.name()).append('=').append(category.total());
            if (category.omitted() > 0) {
                counts.append("(").append(category.omitted()).append(" omitted)");
            }
        }
        return String.format("  %s  %-9s  %s %s %s", c.warningId(), c.status(), c.target().get("memberKind"),
                c.target().get("target"), counts.toString().strip());
    }

    private static Map<String, Object> analysis(Path snapshot, List<Path> sourceRoots, List<Path> files,
            List<Path> classpath, List<Path> missingClasspath, OptionalInt release, SourceModel model) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("snapshot", snapshot.toString());
        m.put("sourceRoots", sourceRoots.stream().map(Path::toString).toList());
        m.put("filesAnalyzed", files.size());
        m.put("classpath", classpath.stream().map(Path::toString).toList());
        m.put("missingClasspathEntries", missingClasspath.stream().map(Path::toString).toList());
        m.put("javacRelease", release.isPresent() ? release.getAsInt() : null);
        m.put("javacOptions", model.options());
        m.put("attributedWith", "javac of Java " + System.getProperty("java.version"));
        m.put("compilerErrorCount", model.errors().size());
        m.put("compilerErrors", model.errors().stream().limit(20)
                .map(e -> e.file() + ":" + e.line() + ": " + e.message()).toList());
        return m;
    }

    private ContextLimits limits(List<String> problems) {
        ContextLimits d = ContextLimits.DEFAULT;
        int items = positive(MAX_ITEMS, d.maxItemsPerCategory(), 1, problems);
        int lines = positive(MAX_SNIPPET_LINES, d.maxSnippetLines(), 1, problems);
        int conditions = positive(MAX_CONDITIONS, d.maxConditions(), 0, problems);
        int localUses = positive(MAX_LOCAL_USES, d.maxLocalUses(), 0, problems);
        return problems.isEmpty() ? new ContextLimits(items, lines, conditions, localUses) : d;
    }

    private int positive(String name, int fallback, int min, List<String> problems) {
        String raw = env.get(name);
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            if (value < min) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            problems.add(name + "='" + raw + "' must be an integer >= " + min);
            return fallback;
        }
    }

    private ExitCode usage(List<String> problems) {
        err.println("GeneralAnnotator: cannot extract context:");
        problems.forEach(p -> err.println("  - " + p));
        return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
    }
}
