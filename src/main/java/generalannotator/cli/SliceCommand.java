package generalannotator.cli;

import generalannotator.exec.CommandRunner;
import generalannotator.exec.RecordedCommand;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.slicing.SliceRequest;
import generalannotator.slicing.SliceResult;
import generalannotator.slicing.SliceResultsJson;
import generalannotator.slicing.SliceStatus;
import generalannotator.slicing.Slicer;
import generalannotator.slicing.SpeciminDistribution;
import generalannotator.slicing.SpeciminSlicer;
import generalannotator.targets.WarningTarget;
import generalannotator.targets.WarningTargetsJson;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code slice <run directory> [--warning ID]}: generate one Specimin slice per warning from the
 * run's {@code warning-targets.json}, sequentially, without rerunning the checker.
 *
 * <p>Configuration: {@code GA_SPECIMIN_HOME} (required, a Specimin checkout), {@code
 * GA_SPECIMIN_JAVA_HOME} (optional JDK; default: the JDK running GeneralAnnotator), {@code
 * GA_SPECIMIN_JAR_PATH} (optional directory of dependency jars for {@code --jarPath}; default: none)
 * and {@code GA_TIMEOUT_SECONDS} (optional per-command timeout; default 1200).
 *
 * <p>Exit codes: 0 every selected resolved target was GENERATED (skipped targets allowed); 2 invalid
 * arguments, configuration, run files, paths, or a conflict with earlier slice results (nothing is
 * run); 3 Specimin setup failed or at least one slice FAILED; 4 internal error.
 */
public final class SliceCommand {

    public static final String SPECIMIN_HOME = "GA_SPECIMIN_HOME";
    public static final String SPECIMIN_JAVA_HOME = "GA_SPECIMIN_JAVA_HOME";
    public static final String SPECIMIN_JAR_PATH = "GA_SPECIMIN_JAR_PATH";
    public static final String TIMEOUT_SECONDS = "GA_TIMEOUT_SECONDS";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(20);

    /** Checker id (from the warning graph) to Specimin modularity model. */
    static final Map<String, String> MODULARITY_MODELS = Map.of("nullaway", "nullaway");

    private final String runArgument;
    private final Optional<String> warningId;
    private final Map<String, String> env;
    private final Path workingDirectory;
    private final Path defaultJavaHome;
    private final CommandRunner runner;
    private final PrintStream out;
    private final PrintStream err;

    public SliceCommand(String runArgument, Optional<String> warningId, Map<String, String> env, Path workingDirectory,
            Path defaultJavaHome, CommandRunner runner, PrintStream out, PrintStream err) {
        this.runArgument = runArgument;
        this.warningId = warningId;
        this.env = Map.copyOf(env);
        this.workingDirectory = workingDirectory;
        this.defaultJavaHome = defaultJavaHome;
        this.runner = runner;
        this.out = out;
        this.err = err;
    }

    /** Validated configuration. */
    record Config(Path speciminHome, Path javaHome, Optional<Path> jarPath, Duration timeout) {}

    public ExitCode run() {
        // 1. Configuration.
        List<String> problems = new ArrayList<>();
        Config config = config(problems);
        if (!problems.isEmpty()) {
            return usage(problems);
        }

        // 2. Run files and snapshot.
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
        Path snapshot = runDir.resolve("workspace").resolve("target");
        if (!Files.isRegularFile(targetsFile)) {
            return usage(List.of("No " + WarningTargetsJson.FILE_NAME + " in " + runDir
                    + ". Create it with: ./gradlew run --args=\"targets " + runDir + "\""));
        }
        if (!Files.isRegularFile(graphFile)) {
            return usage(List.of("No " + WarningGraphJson.FILE_NAME + " in " + runDir + "."));
        }
        if (!Files.isDirectory(snapshot)) {
            return usage(List.of("The run's isolated snapshot " + snapshot + " is missing; slices must be generated "
                    + "from the snapshot that produced the warnings."));
        }
        WarningTargetsJson.TargetsFile targets;
        WarningGraph graph;
        try {
            targets = WarningTargetsJson.load(targetsFile);
            graph = WarningGraphJson.load(graphFile);
        } catch (IOException | IllegalArgumentException e) {
            return usage(List.of("Cannot read the run files: " + e.getMessage()));
        }
        String model = MODULARITY_MODELS.get(graph.checkerId());
        if (model == null) {
            return usage(List.of("No Specimin modularity model for checker '" + graph.checkerId() + "'. Supported: "
                    + MODULARITY_MODELS.keySet() + "."));
        }

        // 3. Selection and path validation (before anything runs).
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
        Map<String, Path> sourceRoots = new LinkedHashMap<>();
        for (WarningTarget t : selected) {
            if (t instanceof WarningTarget.Resolved r) {
                Optional<Path> root = validateTarget(r, snapshot, problems);
                root.ifPresent(p -> sourceRoots.put(r.warningId(), p));
            }
        }
        if (!problems.isEmpty()) {
            return usage(problems);
        }

        // 4. Never overwrite earlier slices or results.
        Path slicesDir = runDir.resolve("slices");
        Path resultsFile = slicesDir.resolve(SliceResultsJson.FILE_NAME);
        Map<String, Map<?, ?>> entries;
        try {
            entries = SliceResultsJson.loadEntries(resultsFile);
        } catch (IOException | IllegalArgumentException e) {
            return usage(List.of("Cannot read existing " + resultsFile + ": " + e.getMessage()));
        }
        for (WarningTarget t : selected) {
            if (entries.containsKey(t.warningId()) || Files.exists(slicesDir.resolve(t.warningId()))) {
                problems.add(t.warningId() + " already has slice results (" + slicesDir.resolve(t.warningId())
                        + " or its entry in " + resultsFile + ")");
            }
        }
        if (!problems.isEmpty()) {
            problems.add("Previous slice results are never overwritten. Move or delete them first, or choose "
                    + "another warning with --warning.");
            return usage(problems);
        }

        // 5. Specimin setup.
        out.println("GeneralAnnotator slice");
        out.println("  run:      " + runDir + " (run " + targets.runId() + ", checker " + graph.checkerId()
                + ", Specimin modularity model " + model + ")");
        out.println("  specimin: " + config.speciminHome() + " (building with ./gradlew installDist ...)");
        SpeciminDistribution distribution;
        try {
            Files.createDirectories(slicesDir);
            distribution = SpeciminDistribution.prepare(config.speciminHome(), config.javaHome(), runner,
                    config.timeout(), slicesDir.resolve("logs"));
        } catch (SpeciminDistribution.SetupException e) {
            err.println("GeneralAnnotator: Specimin setup failed: " + e.getMessage());
            return ExitCode.EXECUTION_FAILURE;
        } catch (IOException e) {
            err.println("GeneralAnnotator: Specimin setup failed: " + e);
            return ExitCode.EXECUTION_FAILURE;
        }
        out.println("  specimin: " + distribution.mainClass() + distribution.gitHead().map(h -> " at " + h).orElse("")
                + ", " + distribution.classpath().size() + " jar(s)");
        out.println("  jars:     " + config.jarPath().map(p -> p + " (--jarPath)")
                .orElse("none (" + SPECIMIN_JAR_PATH + " not set; no --jarPath)"));
        out.println();

        // 6. Slice each warning separately.
        Slicer slicer = new SpeciminSlicer(distribution, config.javaHome(), config.jarPath(), runner);
        Map<String, Object> header = header(runDir, targets, model, config, distribution);
        Map<String, Map<?, ?>> all = new LinkedHashMap<>(entries);
        int failed = 0;
        int generated = 0;
        int skipped = 0;
        for (WarningTarget t : selected) {
            SliceResult result;
            if (t instanceof WarningTarget.Unresolved u) {
                result = SliceResult.skipped(u);
            } else {
                WarningTarget.Resolved r = (WarningTarget.Resolved) t;
                try {
                    result = slicer.slice(new SliceRequest(r, sourceRoots.get(r.warningId()),
                            slicesDir.resolve(r.warningId()), model, config.timeout()));
                } catch (RuntimeException e) {
                    result = new SliceResult(r.warningId(), r, SliceStatus.FAILED, Optional.empty(), Optional.empty(),
                            List.of(), Optional.of("internal error: " + e));
                }
            }
            switch (result.status()) {
                case GENERATED -> generated++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
            }
            out.println(line(result, runDir));
            all.put(result.warningId(), SliceResultsJson.entry(result, runDir));
            try {
                SliceResultsJson.save(resultsFile, header, all);
            } catch (IOException e) {
                err.println("GeneralAnnotator: cannot write " + resultsFile + ": " + e);
                return ExitCode.INTERNAL_ERROR;
            }
        }
        out.println();
        out.println("  results:  " + resultsFile + " (" + generated + " generated, " + failed + " failed, "
                + skipped + " skipped)");
        out.println("  Slices are NOT_VALIDATED: the checker has not been rerun on them.");
        return failed == 0 ? ExitCode.COMPLETED : ExitCode.EXECUTION_FAILURE;
    }

    static String line(SliceResult r, Path runDir) {
        return switch (r.status()) {
            case GENERATED -> String.format("  %s  GENERATED  %d Java file(s) in %s", r.warningId(),
                    r.outputFiles().size(), runDir.relativize(r.outputDirectory().orElseThrow()));
            case FAILED -> String.format("  %s  FAILED     %s", r.warningId(), r.failureReason().orElseThrow());
            case SKIPPED -> String.format("  %s  SKIPPED    %s", r.warningId(), r.failureReason().orElseThrow());
        };
    }

    private Optional<Path> validateTarget(WarningTarget.Resolved r, Path snapshot, List<String> problems) {
        Path root;
        try {
            root = Path.of(r.isolatedSourceRoot()).normalize();
        } catch (InvalidPathException e) {
            problems.add(r.warningId() + ": isolatedSourceRoot '" + r.isolatedSourceRoot() + "' is not a valid path");
            return Optional.empty();
        }
        try {
            if (!root.isAbsolute() || !Files.isDirectory(root)
                    || !root.toRealPath().startsWith(snapshot.toRealPath())) {
                problems.add(r.warningId() + ": isolatedSourceRoot " + root + " is not an existing directory inside "
                        + "the run's snapshot " + snapshot);
                return Optional.empty();
            }
            Path file = Path.of(r.targetFile());
            if (file.isAbsolute() || file.normalize().startsWith("..") || !Files.isRegularFile(root.resolve(file))) {
                problems.add(r.warningId() + ": targetFile '" + r.targetFile() + "' is not a file under " + root);
                return Optional.empty();
            }
        } catch (IOException | InvalidPathException e) {
            problems.add(r.warningId() + ": cannot check paths: " + e.getMessage());
            return Optional.empty();
        }
        return Optional.of(root);
    }

    private Config config(List<String> problems) {
        Path home = directory(SPECIMIN_HOME, env.get(SPECIMIN_HOME), true, problems);
        if (home != null) {
            SpeciminDistribution.layoutProblems(home)
                    .forEach(p -> problems.add(SPECIMIN_HOME + ": " + p + "; expected a Specimin checkout"));
        }
        Path javaHome = env.containsKey(SPECIMIN_JAVA_HOME)
                ? directory(SPECIMIN_JAVA_HOME, env.get(SPECIMIN_JAVA_HOME), false, problems)
                : defaultJavaHome;
        if (javaHome != null && !Files.isExecutable(javaHome.resolve("bin").resolve("java"))) {
            problems.add((env.containsKey(SPECIMIN_JAVA_HOME) ? SPECIMIN_JAVA_HOME + "=" : "default JDK ") + javaHome
                    + " has no executable bin/java");
        }
        Optional<Path> jarPath = env.containsKey(SPECIMIN_JAR_PATH)
                ? Optional.ofNullable(directory(SPECIMIN_JAR_PATH, env.get(SPECIMIN_JAR_PATH), false, problems))
                : Optional.empty();
        Duration timeout = DEFAULT_TIMEOUT;
        String raw = env.get(TIMEOUT_SECONDS);
        if (raw != null) {
            try {
                long seconds = Long.parseLong(raw.strip());
                if (seconds <= 0) {
                    throw new NumberFormatException();
                }
                timeout = Duration.ofSeconds(seconds);
            } catch (NumberFormatException e) {
                problems.add(TIMEOUT_SECONDS + "='" + raw + "' must be a positive integer number of seconds");
            }
        }
        return new Config(home, javaHome, jarPath, timeout);
    }

    private static Path directory(String name, String value, boolean required, List<String> problems) {
        if (value == null) {
            if (required) {
                problems.add(name + " is not set; it must name a local Specimin checkout "
                        + "(https://github.com/mushfiqiit/specimin).");
            }
            return null;
        }
        if (value.isBlank()) {
            problems.add(name + " is blank.");
            return null;
        }
        try {
            Path path = Path.of(value.strip());
            if (!path.isAbsolute()) {
                problems.add(name + "='" + value + "' must be an absolute path.");
                return null;
            }
            if (!Files.isDirectory(path)) {
                problems.add(name + "='" + value + "' is not an existing directory.");
                return null;
            }
            return path.normalize();
        } catch (InvalidPathException e) {
            problems.add(name + "='" + value + "' is not a valid path.");
            return null;
        }
    }

    private static Map<String, Object> header(Path runDir, WarningTargetsJson.TargetsFile targets, String model,
            Config config, SpeciminDistribution distribution) {
        Map<String, Object> specimin = new LinkedHashMap<>();
        specimin.put("home", distribution.home().toString());
        specimin.put("gitHead", distribution.gitHead().orElse(null));
        specimin.put("mainClass", distribution.mainClass());
        specimin.put("classpath", distribution.classpath().stream().map(Path::toString).toList());
        specimin.put("jvmFlags", SpeciminDistribution.JVM_EXPORTS);
        specimin.put("javaHome", config.javaHome().toString());
        specimin.put("modularityModel", model);
        specimin.put("jarPath", config.jarPath().map(Path::toString).orElse(null));
        specimin.put("timeoutSeconds", config.timeout().toSeconds());
        List<Object> setup = new ArrayList<>();
        for (RecordedCommand c : distribution.commands()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("purpose", c.purpose());
            m.put("command", c.result().spec().command());
            m.put("exitCode", c.result().exitCode().isPresent() ? c.result().exitCode().getAsInt() : null);
            m.put("stdoutFile", runDir.relativize(c.stdoutFile()).toString());
            m.put("stderrFile", runDir.relativize(c.stderrFile()).toString());
            setup.add(m);
        }
        specimin.put("setupCommands", setup);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("runId", targets.runId());
        header.put("targetsFile", WarningTargetsJson.FILE_NAME);
        header.put("latestInvocation", specimin);
        return header;
    }

    private ExitCode usage(List<String> problems) {
        err.println("GeneralAnnotator: cannot slice:");
        problems.forEach(p -> err.println("  - " + p));
        return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
    }
}
