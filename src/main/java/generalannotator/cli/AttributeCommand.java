package generalannotator.cli;

import generalannotator.checker.Checker;
import generalannotator.config.ConfigurationException;
import generalannotator.edits.AnnotationApplier;
import generalannotator.evaluation.CombinedAttribution;
import generalannotator.evaluation.EvaluationConfig;
import generalannotator.evaluation.EvaluationFiles;
import generalannotator.evaluation.Evaluator;
import generalannotator.evaluation.WarningIntegrity;
import generalannotator.evaluation.WarningRegistry;
import generalannotator.evaluation.matching.WarningMatcher;
import generalannotator.graph.GraphEdge;
import generalannotator.graph.WarningGraphJson;
import generalannotator.propagation.NullabilityPropagation;
import generalannotator.propagation.PropagationStage;
import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code attribute <run> [--saved-only]}: evidence-backed attribution of every combined round's introduced warnings
 * ({@link CombinedAttribution}), then a rebuild of every propagation round's extended graph so that verified
 * contributions become {@code package -> warning} edges, and the warning-ID integrity check. No LLM or inference
 * runs; counterfactual states that no saved evaluation covers are checked locally with the configured checker
 * (settings as for {@code evaluate-annotations}), or with {@code --saved-only} reported as inconclusive.
 *
 * <p>The rounds are the pipeline's round-1 combined result (with the pipeline's evaluation) and every propagation
 * round's combined result (with that round's evaluation). Pipeline stages record their attribution under
 * {@code artifacts.attribution}. Exit codes: 0 done and consistent; 2 invalid directory or configuration; 3 a round
 * cannot be attributed or the integrity check fails; 4 internal error.
 */
public final class AttributeCommand {

    private AttributeCommand() {}

    /** A combined round result and the evaluation of the packages it applied. */
    record Round(Path roundDir, Path evaluationDir, Optional<String> stage) {}

    @SuppressWarnings("try")
    public static ExitCode run(String argument, boolean savedOnly, Map<String, String> env, Path workingDirectory,
            Path defaultJavaHome, EvaluateAnnotationsCommand.CheckerFactory checkers, AnnotationApplier applier,
            WarningMatcher matcher, Clock clock, PrintStream out, PrintStream err) {
        Path dir;
        try {
            dir = workingDirectory.resolve(argument).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            err.println("GeneralAnnotator: '" + argument + "' is not a valid path");
            return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
        }
        if (!Files.isRegularFile(dir.resolve("report.json"))) {
            err.println("GeneralAnnotator: " + dir + " is not a run directory (runs/<run id>)");
            return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
        }
        Optional<EvaluationConfig> config = Optional.empty();
        Optional<Checker> checker = Optional.empty();
        if (!savedOnly) {
            try {
                List<String> roots = new ArrayList<>();
                Map<?, ?> r = (Map<?, ?>) JsonParser.parse(Files.readString(dir.resolve("report.json")));
                if (r.get("configuration") instanceof Map<?, ?> c && c.get("sourceRoots") instanceof List<?> l) {
                    l.forEach(o -> roots.add(String.valueOf(o)));
                }
                EvaluationConfig c = EvaluationConfig.load(env, workingDirectory, defaultJavaHome,
                        dir.resolve("workspace").resolve("target"), roots, dir.resolve(Evaluator.DIR));
                config = Optional.of(c);
                checker = Optional.of(checkers.create(c.analysis(), env));
            } catch (ConfigurationException e) {
                err.println("GeneralAnnotator: invalid configuration (or use --saved-only):");
                e.problems().forEach(p -> err.println("  - " + p));
                return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
            } catch (IOException | RuntimeException e) {
                err.println("GeneralAnnotator: cannot read the run configuration: " + e.getMessage());
                return ExitCode.INTERNAL_ERROR;
            }
        }
        out.println("GeneralAnnotator attribute " + dir + " (" + CombinedAttribution.MODEL + "; "
                + (savedOnly ? "saved evidence only" : "local checks where no saved evaluation is the required state")
                + "; no LLM or inference)");
        try (EvaluationFiles.Lock lock = EvaluationFiles.lock(dir.resolve(".pipeline"), "attribute, pid "
                + ProcessHandle.current().pid())) {
            List<Round> rounds = rounds(dir);
            if (rounds.isEmpty()) {
                out.println("  no completed combined round with a saved evaluation; nothing to attribute");
                return ExitCode.COMPLETED;
            }
            Map<String, String> attributions = new LinkedHashMap<>();
            for (Round r : rounds) {
                Path a = new CombinedAttribution(dir, r.roundDir(), r.evaluationDir(), config, checker, applier, matcher,
                        clock, out).run();
                String rel = dir.relativize(a.resolve(CombinedAttribution.RECORD)).toString();
                r.stage().ifPresent(s -> attributions.put(s, rel));
                Map<?, ?> rec = WarningRegistry.parse(a.resolve(CombinedAttribution.RECORD));
                out.println("  " + rec.get("roundResultId") + ": " + rec.get("counts") + "; candidates "
                        + rec.get("candidateCounts") + " -> " + rel);
                for (Object o : WarningRegistry.list(rec.get("warnings"))) {
                    Map<?, ?> w = (Map<?, ?>) o;
                    out.println("    " + w.get("warningId") + "  " + w.get("status") + "  " + w.get("contributors")
                            + "  " + w.get("site"));
                    for (Object c : WarningRegistry.list(w.get("candidates"))) {
                        Map<?, ?> v = (Map<?, ?>) c;
                        out.println("        " + v.get("package") + " " + v.get("status") + ": " + v.get("evidence"));
                    }
                }
            }
            pipeline(dir, attributions);
            PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
            List<Path> named = WarningRegistry.namedPropagationDirs(dir);
            int edges = 0;
            for (Path stage : WarningRegistry.propagationDirs(dir)) {
                try {
                    PropagationStage.rebuildGraph(stage, matcher, clock, quiet);
                } catch (NullabilityPropagation.InvalidException e) {
                    if (named.contains(stage)) {
                        throw new IOException(dir.relativize(stage) + " cannot be rebuilt: " + e.getMessage());
                    }
                    out.println("  skipped:    " + dir.relativize(stage) + " (superseded: " + e.getMessage() + ")");
                    continue;
                }
                long n = WarningGraphJson.load(stage.resolve(PropagationStage.GRAPH)).edges().stream()
                        .filter(e -> e.kind().equals(GraphEdge.INTRODUCES) && e.provenance().containsKey("attribution"))
                        .count();
                out.println("  graph:      " + dir.relativize(stage.resolve(PropagationStage.GRAPH)) + " (" + n
                        + " attribution edge(s))");
                edges = (int) n;
            }
            WarningIntegrity.Report check = WarningIntegrity.check(dir);
            out.println("  attribution edges in the latest graph: " + edges);
            if (!check.ok()) {
                out.println("  warning-ID integrity: " + check.problems().size() + " problem(s):");
                check.problems().forEach(p -> out.println("    - " + p));
                return ExitCode.EXECUTION_FAILURE;
            }
            out.println("  warning-ID integrity: OK");
            out.println("  Then: GeneralAnnotator visualize <propagation directory>");
            return ExitCode.COMPLETED;
        } catch (EvaluationFiles.BusyException e) {
            err.println("GeneralAnnotator: " + e.getMessage());
            return ExitCode.EXECUTION_FAILURE;
        } catch (IOException | RuntimeException e) {
            err.println("GeneralAnnotator: attribute failed: " + e.getMessage());
            return ExitCode.EXECUTION_FAILURE;
        }
    }

    /** The round-1 combined result (pipeline) and every propagation round's, each with its evaluation. */
    static List<Round> rounds(Path dir) throws IOException {
        List<Round> out = new ArrayList<>();
        Path pipeline = dir.resolve("pipeline.json");
        if (Files.isRegularFile(pipeline)) {
            Map<?, ?> stages = (Map<?, ?>) ((Map<?, ?>) JsonParser.parse(Files.readString(pipeline))).get("stages");
            if (stages != null && stages.get("combined") instanceof Map<?, ?> c && "COMPLETED".equals(c.get("status"))
                    && c.get("artifacts") instanceof Map<?, ?> ca && ca.get("directory") instanceof String cd
                    && stages.get("evaluation") instanceof Map<?, ?> e && e.get("artifacts") instanceof Map<?, ?> ea) {
                Object ed = ea.get("directory") != null ? ea.get("directory")
                        : ea.get("graph") == null ? null : Path.of(String.valueOf(ea.get("graph"))).getParent().toString();
                if (ed != null) {
                    out.add(new Round(dir.resolve(cd).normalize(), dir.resolve(String.valueOf(ed)).normalize(),
                            Optional.of("combined")));
                }
            }
        }
        List<Path> named = WarningRegistry.namedPropagationDirs(dir);
        for (Path stage : WarningRegistry.propagationDirs(dir)) {
            Map<?, ?> r = (Map<?, ?>) JsonParser.parse(Files.readString(stage.resolve(PropagationStage.RESULT)));
            if (!"COMPLETED".equals(r.get("outcome")) || !(r.get("combined") instanceof Map<?, ?> c)
                    || !"COMPLETED".equals(c.get("status")) || !(r.get("evaluation") instanceof Map<?, ?> e)) {
                continue;
            }
            int round = r.get("round") instanceof Number n ? n.intValue() : PropagationStage.DEFAULT_ROUND;
            out.add(new Round(dir.resolve(String.valueOf(c.get("directory"))).normalize(),
                    dir.resolve(String.valueOf(e.get("directory"))).normalize(),
                    named.contains(stage) ? Optional.of(PropagationStage.stageName(round)) : Optional.empty()));
        }
        return out;
    }

    /** Records each stage's attribution in pipeline.json ({@code artifacts.attribution}). */
    @SuppressWarnings("unchecked")
    private static void pipeline(Path dir, Map<String, String> attributions) throws IOException {
        Path file = dir.resolve("pipeline.json");
        if (!Files.isRegularFile(file) || attributions.isEmpty()) {
            return;
        }
        Map<String, Object> state = (Map<String, Object>) mutable(JsonParser.parse(Files.readString(file)));
        Map<String, Object> stages = (Map<String, Object>) state.get("stages");
        attributions.forEach((name, path) -> {
            if (stages.get(name) instanceof Map<?, ?> st) {
                Map<String, Object> s = (Map<String, Object>) st;
                Map<String, Object> a = s.get("artifacts") instanceof Map<?, ?> m ? (Map<String, Object>) m
                        : new LinkedHashMap<>();
                a.put("attribution", path);
                s.put("artifacts", a);
            }
        });
        EvaluationFiles.writeAtomically(file, Json.write(state));
    }

    private static Object mutable(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), mutable(v)));
            return copy;
        }
        if (value instanceof List<?> l) {
            List<Object> copy = new ArrayList<>();
            l.forEach(v -> copy.add(mutable(v)));
            return copy;
        }
        return value;
    }
}
