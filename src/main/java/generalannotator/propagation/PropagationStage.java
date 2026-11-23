package generalannotator.propagation;

import generalannotator.checker.Checker;
import generalannotator.edits.AnnotationApplier;
import generalannotator.edits.AnnotationEdit;
import generalannotator.edits.AnnotationKinds;
import generalannotator.edits.EditOrigin;
import generalannotator.edits.Proposal;
import generalannotator.evaluation.AttemptOutcome;
import generalannotator.evaluation.Candidates;
import generalannotator.evaluation.EvaluationConfig;
import generalannotator.evaluation.EvaluationFiles;
import generalannotator.evaluation.Evaluator;
import generalannotator.evaluation.HistoryGraph;
import generalannotator.evaluation.RoundApplication;
import generalannotator.evaluation.matching.WarningMatcher;
import generalannotator.graph.AnnotationNode;
import generalannotator.graph.GraphEdge;
import generalannotator.graph.GraphNode;
import generalannotator.graph.WarningGraph;
import generalannotator.graph.WarningGraphJson;
import generalannotator.graph.WarningNode;
import generalannotator.report.Json;
import generalannotator.report.JsonParser;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The single-pass, warning-guided nullability propagation stage ({@code propagate <dir>}, {@code pipeline
 * propagate <dir>}). It never calls an LLM and never changes the target checkout, a snapshot or earlier round
 * artifacts.
 *
 * <ol>
 *   <li><b>Input</b> ({@link Input#resolve}): for a pipeline, the <em>completed combined round result</em> named in
 *       {@code pipeline.json} (its annotated snapshot and its complete warnings), verified to belong together;
 *       a missing, incomplete or unverifiable result stops the stage, with no fallback to the original program.
 *       A plain run without a pipeline uses its own baseline (round 0), which is stated in the output.
 *   <li><b>Candidates</b> from that fixed input only ({@link NullabilityPropagation}): one {@code @Nullable} edit
 *       per matched warning; every other warning is recorded with its reason.
 *   <li><b>Packages:</b> equal edit sets are merged ({@link Candidates#fromProposals}), IDs {@code P0001, ...},
 *       origins {@code <warning>@propagation-r<source round>}, generator {@value #GENERATOR}.
 *   <li><b>Independent evaluation</b> ({@link Evaluator}, round = source round + 1): each package on a fresh copy
 *       of the input snapshot (earlier annotations included); baseline warnings keep the input's IDs; resolved
 *       and introduced warnings become edges, introduced ones get the next round's IDs ({@code W2001, ...} after
 *       round 1); failed or incomplete evaluations claim nothing. {@value #GRAPH} in the stage directory extends
 *       the earlier evaluation graph with this layer ({@link HistoryGraph}: only warning and annotation nodes and
 *       resolved-by / introduces edges from independent evaluations; earlier history kept whole; input warnings
 *       linked to earlier nodes only through verified provenance and the matcher, never by ID; unattributable
 *       warnings kept as separate nodes). {@link #rebuildGraph} regenerates it from saved artifacts.
 *   <li><b>Combined result</b> ({@link RoundApplication}): every successfully evaluated package (COMPLETED or
 *       AMBIGUOUS) that conflicts with no other is applied together to another copy of the input and checked once,
 *       on the evaluation's baseline, with IDs after the evaluation's; conflicts are reported. It is a separate
 *       result, never attributed to packages.
 * </ol>
 * Exactly one pass: warnings introduced by the evaluations or the combined check are recorded but never analyzed.
 * Output: {@code <run>/propagation/<id>/} ({@code analysis.json}, {@code propagation.json}, {@code summary.txt},
 * {@value #GRAPH}), {@code <run>/evaluations/<id>-<key>/} and {@code <run>/rounds/round<n>-<key>/}. Every key
 * includes the input's snapshot, report and graph identity; rerunning reuses only results for the same input.
 */
public final class PropagationStage {

    public static final String DIR = "propagation";
    public static final String GENERATOR = "nullability-propagation-4";
    /** Package IDs of this stage ({@code P0001, ...}), distinct from answer packages ({@code A0001, ...}). */
    public static final String ID_PREFIX = "P";
    /** The extended graph: earlier history plus this stage's nodes and edges. */
    public static final String GRAPH = Evaluator.GRAPH;
    public static final String ANALYSIS = "analysis.json";
    public static final String RESULT = "propagation.json";
    public static final String SUMMARY = "summary.txt";

    /** Origins are named {@code <warning>@propagation-r<source round>}. */
    public static String group(int sourceRound) {
        return "propagation-r" + sourceRound;
    }

    /** The round a pipeline propagation runs when none is selected: round 2, on the combined round-1 result. */
    public static final int DEFAULT_ROUND = 2;

    /**
     * The pipeline stage that records propagation round {@code round}: {@code propagation} for round 2 (as before
     * rounds could be selected), {@code propagation-r<round>} for later rounds, so no round overwrites another's.
     */
    public static String stageName(int round) {
        return round == DEFAULT_ROUND ? "propagation" : "propagation-r" + round;
    }

    /**
     * Package IDs of round {@code round}: {@code P0001, ...} in round 2, {@code P<round>-0001, ...} later, so the
     * packages of every round are distinct nodes of one extended graph.
     */
    public static String idPrefix(int round) {
        return round <= DEFAULT_ROUND ? ID_PREFIX : ID_PREFIX + round + "-";
    }

    /**
     * The fixed baseline a propagation pass reads: a directory laid out like a run ({@code report.json},
     * {@code warning-graph.json}, {@code workspace/target}) whose report and snapshot belong together.
     *
     * @param dir the input directory
     * @param output the run directory that receives propagation/, evaluations/ and rounds/
     * @param sourceRound the round whose state this is (0 for a run's own baseline)
     * @param state the state's name ({@code S0}, or a combined result's ID such as {@code round1-09d3783e43a5})
     * @param description how the input was selected, for the record and the output
     * @param historyGraph the earlier evaluation graph to extend, if any
     * @param nodes the state's warning nodes (their IDs are kept)
     * @param originRounds the round each warning ID was introduced in
     * @param identity a digest of the snapshot, report and warning graph, part of every cache key
     * @param inherit whether evaluations keep these IDs (always for a combined result)
     * @param historyEvaluation the evaluation whose packages the input state applied (the evidence for linking
     *     the input's warnings); by default the history graph's own directory
     * @param historyIds how that evaluation's baseline IDs map to nodes of the history graph (the earlier
     *     propagation's {@code reportToGraph}); empty for round 2, whose base warnings are the graph's roots
     */
    public record Input(Path dir, Path output, int sourceRound, String state, String description,
            Optional<Path> historyGraph, List<WarningNode> nodes, Map<String, Integer> originRounds, String identity,
            boolean inherit, Optional<Path> historyEvaluation, Optional<Map<String, String>> historyIds) {

        public Input(Path dir, Path output, int sourceRound, String state, String description,
                Optional<Path> historyGraph, List<WarningNode> nodes, Map<String, Integer> originRounds,
                String identity, boolean inherit) {
            this(dir, output, sourceRound, state, description, historyGraph, nodes, originRounds, identity, inherit,
                    Optional.empty(), Optional.empty());
        }

        public Path snapshot() {
            return dir.resolve("workspace").resolve("target");
        }

        /** The round this input feeds: the source round plus one. */
        public int round() {
            return sourceRound + 1;
        }

        /**
         * Like {@link #resolve(Path)}, with an explicitly selected round: for a pipeline, round {@code n} reads the
         * completed combined result of round {@code n - 1} recorded in {@code pipeline.json} (round 2: the combined
         * round-1 result); a round is accepted only for a pipeline directory.
         */
        public static Input resolve(Path dir, java.util.OptionalInt round)
                throws NullabilityPropagation.InvalidException, IOException {
            if (round.isEmpty()) {
                return resolve(dir);
            }
            if (!Files.isRegularFile(dir.resolve("pipeline.json"))) {
                throw new NullabilityPropagation.InvalidException("--round " + round.getAsInt() + " selects a "
                        + "propagation round of a pipeline, but " + dir + " has no pipeline.json");
            }
            return fromPipeline(dir, round.getAsInt());
        }

        /**
         * Round {@code round} of a pipeline: round 2 reads the combined round-1 result; round {@code n > 2} reads
         * the combined result of the completed propagation round {@code n - 1} recorded in its own pipeline stage
         * ({@link #stageName}), after verifying that round's record, its artifacts, and that its input is still
         * what it was computed on (so a regenerated earlier round makes every later one stale, never silently
         * reused). Directories are never chosen by name or modification time; nothing falls back.
         */
        public static Input fromPipeline(Path runDir, int round)
                throws IOException, NullabilityPropagation.InvalidException {
            if (round < DEFAULT_ROUND) {
                throw new NullabilityPropagation.InvalidException("round " + round + " is not a propagation round: "
                        + "round 1 is the LLM round (pipeline start / finish); propagation rounds start at "
                        + DEFAULT_ROUND);
            }
            if (round == DEFAULT_ROUND) {
                return fromPipeline(runDir);
            }
            int previousRound = round - 1;
            String previousStage = stageName(previousRound);
            String rerunPrevious = "`pipeline propagate " + runDir + (previousRound == DEFAULT_ROUND ? ""
                    : " --round " + previousRound) + "`";
            String fix = " Run " + rerunPrevious + " until it completes, then rerun `pipeline propagate " + runDir
                    + " --round " + round + "`; round " + round + " never falls back to an earlier round.";
            Map<?, ?> state = parse(runDir.resolve("pipeline.json"));
            Map<?, ?> stages = state.get("stages") instanceof Map<?, ?> m ? m : Map.of();
            Map<?, ?> stage = stages.get(previousStage) instanceof Map<?, ?> m ? m : Map.of();
            if (!"COMPLETED".equals(stage.get("status")) || !"COMPLETED".equals(stage.get("outcome"))) {
                throw new NullabilityPropagation.InvalidException("round " + round + " reads the completed combined "
                        + "result of round " + previousRound + ", but pipeline stage " + previousStage + " is "
                        + (stage.isEmpty() ? "absent" : stage.get("status") + " (outcome " + stage.get("outcome")
                                + ")") + "." + fix);
            }
            Map<?, ?> artifacts = stage.get("artifacts") instanceof Map<?, ?> m ? m : Map.of();
            for (String key : List.of("directory", "combined", "graph", "evaluations")) {
                if (!(artifacts.get(key) instanceof String)) {
                    throw new NullabilityPropagation.InvalidException("pipeline stage " + previousStage + " records no "
                            + key + " artifact, so round " + previousRound + "'s result is incomplete." + fix);
                }
            }
            Path stageDir = inside(runDir, DIR, (String) artifacts.get("directory"), previousStage, fix);
            Path combined = inside(runDir, RoundApplication.DIR, (String) artifacts.get("combined"), previousStage, fix);
            Path evaluation = inside(runDir, Evaluator.DIR, (String) artifacts.get("evaluations"), previousStage, fix);
            Path graph = inside(runDir, DIR, (String) artifacts.get("graph"), previousStage, fix);
            if (!Files.isRegularFile(stageDir.resolve(RESULT)) || !Files.isRegularFile(graph)
                    || !Files.isDirectory(evaluation)) {
                throw new NullabilityPropagation.InvalidException("the round-" + previousRound + " artifacts named by "
                        + "pipeline stage " + previousStage + " are missing (" + stageDir.resolve(RESULT) + ", " + graph
                        + ", " + evaluation + ")." + fix);
            }
            Map<?, ?> record = parse(stageDir.resolve(RESULT));
            Map<?, ?> recordCombined = record.get("combined") instanceof Map<?, ?> m ? m : Map.of();
            Map<?, ?> recordEvaluation = record.get("evaluation") instanceof Map<?, ?> m ? m : Map.of();
            Map<?, ?> recordInput = record.get("input") instanceof Map<?, ?> m ? m : Map.of();
            Map<?, ?> history = record.get("history") instanceof Map<?, ?> m ? m : Map.of();
            boolean consistent = record.get("round") instanceof Number r && r.intValue() == previousRound
                    && "COMPLETED".equals(record.get("outcome")) && "COMPLETED".equals(recordCombined.get("status"))
                    && artifacts.get("combined").equals(recordCombined.get("directory"))
                    && artifacts.get("evaluations").equals(recordEvaluation.get("directory"))
                    && artifacts.get("graph").equals(recordEvaluation.get("extendedGraph"))
                    && history.get("reportToGraph") instanceof Map<?, ?>;
            if (!consistent) {
                throw new NullabilityPropagation.InvalidException(stageDir.resolve(RESULT) + " does not describe a "
                        + "completed round " + previousRound + " with the combined result, evaluation and graph that "
                        + "pipeline stage " + previousStage + " names." + fix);
            }
            Input previous = fromPipeline(runDir, previousRound);
            if (!previous.identity().equals(recordInput.get("identity"))) {
                throw new NullabilityPropagation.InvalidException("round " + previousRound + " (" + stageDir + ") was "
                        + "computed on input " + recordInput.get("state") + " (identity " + recordInput.get("identity")
                        + "), but its input now resolves to " + previous.state() + " (identity " + previous.identity()
                        + "); its results are stale and kept only as history." + fix);
            }
            Input in = fromRoundResult(combined, runDir, Optional.of(graph), "the combined round-" + previousRound
                    + " result recorded in " + runDir.resolve("pipeline.json") + " (stage " + previousStage + ")");
            Object baseState = parse(combined.resolve(RoundApplication.RECORD)).get("baseState");
            if (in.sourceRound() != previousRound || !previous.state().equals(baseState)) {
                throw new NullabilityPropagation.InvalidException("the combined result " + combined + " is round "
                        + in.sourceRound() + " on state " + baseState + ", not round " + previousRound + " on "
                        + previous.state() + "." + fix);
            }
            Map<String, String> ids = new TreeMap<>();
            ((Map<?, ?>) history.get("reportToGraph")).forEach((k, v) -> ids.put(String.valueOf(k), String.valueOf(v)));
            return new Input(in.dir(), runDir, in.sourceRound(), in.state(), in.description(), in.historyGraph(),
                    in.nodes(), in.originRounds(), in.identity(), true, Optional.of(evaluation), Optional.of(ids));
        }

        private static Path inside(Path runDir, String area, String relative, String stage, String fix)
                throws NullabilityPropagation.InvalidException {
            Path p = runDir.resolve(relative).normalize();
            if (!p.startsWith(runDir.resolve(area))) {
                throw new NullabilityPropagation.InvalidException("pipeline stage " + stage + " names " + relative
                        + ", which is not under " + runDir.resolve(area) + "." + fix);
            }
            return p;
        }

        /**
         * A pipeline directory: its completed combined round result (from pipeline.json); a combined round result
         * directory: that result; any other run directory: its own baseline (round 0).
         */
        public static Input resolve(Path dir) throws NullabilityPropagation.InvalidException, IOException {
            if (Files.isRegularFile(dir.resolve("pipeline.json"))) {
                return fromPipeline(dir);
            }
            if (Files.isRegularFile(dir.resolve(RoundApplication.RECORD))) {
                Path rounds = dir.getParent();
                if (rounds == null || !rounds.getFileName().toString().equals(RoundApplication.DIR)) {
                    throw new NullabilityPropagation.InvalidException(dir + " is a round result outside "
                            + "runs/<run>/rounds/; there is no run directory to write to");
                }
                return fromRoundResult(dir, rounds.getParent(), Optional.empty(), "the combined round result " + dir);
            }
            return original(dir);
        }

        /** The run's own baseline (round 0). */
        static Input original(Path runDir) throws IOException, NullabilityPropagation.InvalidException {
            Path graph = runDir.resolve(WarningGraphJson.FILE_NAME);
            if (!Files.isRegularFile(graph) || !Files.isRegularFile(runDir.resolve("report.json"))) {
                throw new NullabilityPropagation.InvalidException(runDir + " is not a completed run");
            }
            WarningGraph g = WarningGraphJson.load(graph);
            Map<String, Integer> rounds = new TreeMap<>();
            g.warningNodes().forEach(n -> rounds.put(n.id(), 0));
            return new Input(runDir, runDir, 0, Evaluator.BASELINE_STATE, "the run's own baseline (round 0)",
                    Optional.empty(), g.warningNodes(), rounds, identity(runDir), false);
        }

        /** The completed combined result recorded in a pipeline's {@code pipeline.json}; never a fallback. */
        static Input fromPipeline(Path runDir) throws IOException, NullabilityPropagation.InvalidException {
            Map<?, ?> state = parse(runDir.resolve("pipeline.json"));
            Map<?, ?> stages = state.get("stages") instanceof Map<?, ?> m ? m : Map.of();
            Map<?, ?> combined = stages.get("combined") instanceof Map<?, ?> m ? m : Map.of();
            Map<?, ?> artifacts = combined.get("artifacts") instanceof Map<?, ?> m ? m : Map.of();
            if (!"COMPLETED".equals(combined.get("status")) || !(artifacts.get("directory") instanceof String d)) {
                throw new NullabilityPropagation.InvalidException("this pipeline has no completed combined round "
                        + "result (combined stage: " + combined.get("status") + "). Run `pipeline finish " + runDir
                        + "` until it completes; propagation reads the combined round's annotated code and warnings and "
                        + "never falls back to the original baseline.");
            }
            Path dir = runDir.resolve(d).normalize();
            if (!dir.startsWith(runDir.resolve(RoundApplication.DIR))) {
                throw new NullabilityPropagation.InvalidException("pipeline.json names " + d + " as the combined "
                        + "result, which is not under " + runDir.resolve(RoundApplication.DIR));
            }
            Map<?, ?> evaluation = stages.get("evaluation") instanceof Map<?, ?> m ? m : Map.of();
            Map<?, ?> evaluationArtifacts = evaluation.get("artifacts") instanceof Map<?, ?> m ? m : Map.of();
            Optional<Path> history = evaluationArtifacts.get("graph") instanceof String g
                    && Files.isRegularFile(runDir.resolve(g)) ? Optional.of(runDir.resolve(g)) : Optional.empty();
            return fromRoundResult(dir, runDir, history, "the combined round result recorded in "
                    + runDir.resolve("pipeline.json"));
        }

        /** A combined round result, verified: complete, and its snapshot, report, graph and findings belong together. */
        static Input fromRoundResult(Path dir, Path output, Optional<Path> history, String selectedBy)
                throws IOException, NullabilityPropagation.InvalidException {
            Path record = dir.resolve(RoundApplication.RECORD);
            String fix = " Rerun `pipeline finish` (or apply-round) to produce a complete combined result; propagation "
                    + "does not fall back to other sources or warnings.";
            if (!Files.isRegularFile(record)) {
                throw new NullabilityPropagation.InvalidException(dir + " has no " + RoundApplication.RECORD + "." + fix);
            }
            Map<?, ?> r = parse(record);
            if (!"COMPLETED".equals(r.get("status"))) {
                throw new NullabilityPropagation.InvalidException("the combined round result " + dir + " is "
                        + r.get("status") + ", not COMPLETED, so its warnings are incomplete or not compared." + fix);
            }
            for (String f : List.of("report.json", WarningGraphJson.FILE_NAME, RoundApplication.FINDINGS,
                    "workspace/target")) {
                if (!Files.exists(dir.resolve(f))) {
                    throw new NullabilityPropagation.InvalidException("the combined round result " + dir + " lacks "
                            + f + "." + fix);
                }
            }
            String id = String.valueOf(r.get("roundResultId"));
            Object recordedTree = r.get("workspace") instanceof Map<?, ?> w ? w.get("treeSha256") : null;
            String tree = EvaluationFiles.treeHash(dir.resolve("workspace").resolve("target"));
            if (!id.equals(dir.getFileName().toString()) || !tree.equals(recordedTree)) {
                throw new NullabilityPropagation.InvalidException("the annotated snapshot of " + dir + " no longer "
                        + "matches round.json (recorded tree " + recordedTree + ", now " + tree + "); its warnings would "
                        + "not describe it." + fix);
            }
            Map<?, ?> report = parse(dir.resolve("report.json"));
            WarningGraph graph = WarningGraphJson.load(dir.resolve(WarningGraphJson.FILE_NAME));
            Map<?, ?> findings = parse(dir.resolve(RoundApplication.FINDINGS));
            Object nextRunId = r.get("nextRound") instanceof Map<?, ?> n ? n.get("runId") : null;
            if (nextRunId == null || !nextRunId.equals(report.get("runId")) || !nextRunId.equals(graph.runId())) {
                throw new NullabilityPropagation.InvalidException("the report and warning graph of " + dir + " do not "
                        + "belong to its round.json (run IDs " + report.get("runId") + ", " + graph.runId() + ", expected "
                        + nextRunId + ")." + fix);
            }
            Map<String, Integer> rounds = new TreeMap<>();
            for (Object o : findings.get("findings") instanceof List<?> l ? l : List.of()) {
                Map<?, ?> f = (Map<?, ?>) o;
                rounds.put(String.valueOf(f.get("warningId")), ((Number) f.get("round")).intValue());
            }
            java.util.Set<String> graphIds = new java.util.TreeSet<>();
            graph.warningNodes().forEach(n -> graphIds.add(n.id()));
            Object count = r.get("findingCount");
            if (!Boolean.TRUE.equals(findings.get("complete")) || !(count instanceof Number c)
                    || c.intValue() != graphIds.size() || !graphIds.equals(rounds.keySet())) {
                throw new NullabilityPropagation.InvalidException("the warnings of " + dir + " are incomplete or do not "
                        + "match (round.json " + count + ", warning-graph.json " + graphIds.size() + ", findings.json "
                        + rounds.size() + ")." + fix);
            }
            int round = ((Number) r.get("round")).intValue();
            return new Input(dir, output, round, id, selectedBy, history, graph.warningNodes(), rounds, identity(dir),
                    true);
        }

        private static String identity(Path dir) throws IOException {
            return EvaluationFiles.sha256(EvaluationFiles.treeHash(dir.resolve("workspace").resolve("target")) + "\n"
                    + EvaluationFiles.hashPath(dir.resolve("report.json")) + "\n"
                    + EvaluationFiles.hashPath(dir.resolve(WarningGraphJson.FILE_NAME)));
        }

        static Map<?, ?> parse(Path file) throws IOException {
            try {
                return (Map<?, ?>) JsonParser.parse(Files.readString(file, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw new IOException(file + " is not valid JSON: " + e.getMessage());
            }
        }
    }

    /** How the stage ended. */
    public enum Outcome {
        /** Packages were evaluated and their compatible successes applied together and checked. */
        COMPLETED,
        /** No baseline warning matched; nothing was evaluated. */
        NO_CANDIDATES,
        /** Packages were evaluated, but none succeeded without conflicts; there is no combined state. */
        NOTHING_TO_COMBINE,
        /** The evaluation baseline failed; nothing was evaluated. */
        BASELINE_FAILED,
        /** The combined edit set was rejected (conflicts); nothing was applied together. */
        COMBINED_BLOCKED,
        /** The combined check failed or was not comparable. */
        COMBINED_FAILED,
        /** The evaluation could not run (busy or refused). */
        FAILED
    }

    public record Result(Outcome outcome, Path directory, Map<String, Object> record) {}

    private final Input input;
    private final Path runDir;
    private final EvaluationConfig config;
    private final Checker checker;
    private final AnnotationApplier applier;
    private final WarningMatcher matcher;
    private final Clock clock;
    private final PrintStream out;

    public PropagationStage(Input input, EvaluationConfig config, Checker checker, AnnotationApplier applier,
            WarningMatcher matcher, Clock clock, PrintStream out) {
        this.input = input;
        this.runDir = input.output();
        this.config = config;
        this.checker = checker;
        this.applier = applier;
        this.matcher = matcher;
        this.clock = clock;
        this.out = out;
    }

    public Result run() throws IOException, NullabilityPropagation.InvalidException {
        Path snapshot = input.snapshot();
        int round = input.sourceRound() + 1;
        out.println("  input:      " + input.description());
        out.println("              source round " + input.sourceRound() + ", state " + input.state() + ", "
                + input.nodes().size() + " warning(s) (its complete NullAway findings)");
        out.println("  snapshot:   " + snapshot + " (read only; every evaluation copies it, keeping its annotations)");
        NullabilityPropagation.Analysis analysis = NullabilityPropagation.analyze(input.dir());
        String analysisJson = Json.write(analysisJson(analysis));
        // keyed by the input's identity (snapshot, report, graph): results for another baseline are never reused
        // the round is part of the ID (propagation<round>-), the input identity and the rule version part of its key
        String id = "propagation" + round + "-" + EvaluationFiles.sha256(input.identity() + "\n" + analysisJson)
                .substring(0, 12);
        Path dir = runDir.resolve(DIR).resolve(id);
        Path analysisFile = dir.resolve(ANALYSIS);
        if (Files.isRegularFile(analysisFile)
                && !Files.readString(analysisFile, StandardCharsets.UTF_8).equals(analysisJson)) {
            throw new IOException(analysisFile + " was changed; it no longer matches this baseline");
        }
        EvaluationFiles.writeAtomically(analysisFile, analysisJson);
        out.println("  analysis:   " + analysis.warnings() + " baseline warning(s): " + analysis.matches().size()
                + " matched, " + analysis.skipped().size() + " skipped (" + NullabilityPropagation.RULES + ")");
        for (NullabilityPropagation.Match m : analysis.matches()) {
            out.println("    " + m.warningId() + "  MATCHED  " + m.rule() + ": " + String.join(" + ",
                    m.edits().stream().map(AnnotationEdit::canonical).toList()));
        }
        for (NullabilityPropagation.Skip s : analysis.skipped()) {
            out.println("    " + s.warningId() + "  skipped  " + s.category() + ": " + s.reason());
        }

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("schemaVersion", 1);
        record.put("id", id);
        record.put("generator", GENERATOR);
        record.put("rules", NullabilityPropagation.RULES);
        record.put("round", round);
        record.put("runId", analysis.runId());
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("stage", "propagation");
        in.put("sourceRound", input.sourceRound());
        in.put("state", input.state());
        in.put("selectedBy", input.description());
        in.put("directory", input.dir().toString());
        in.put("snapshot", snapshot.toString());
        in.put("warnings", input.nodes().size());
        in.put("identity", input.identity());
        in.put("introducedRound", round);
        record.put("input", in);
        record.put("onePass", "Candidates come only from the baseline warnings; warnings introduced by the "
                + "evaluations or the combined check are recorded but not analyzed, and added annotations are not "
                + "propagated further.");
        record.put("analysis", ANALYSIS);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("warnings", analysis.warnings());
        counts.put("matched", analysis.matches().size());
        Map<String, Integer> skipped = new TreeMap<>();
        analysis.skipped().forEach(s -> skipped.merge(s.category().name(), 1, Integer::sum));
        counts.put("skipped", skipped);
        record.put("counts", counts);
        if (analysis.matches().isEmpty()) {
            record.put("outcome", Outcome.NO_CANDIDATES.name());
            record.put("detail", "no baseline warning is a matched nullable assignment, return or argument");
            return finish(dir, record, Outcome.NO_CANDIDATES);
        }

        // Packages and independent evaluation.
        List<Proposal> proposals = new ArrayList<>();
        for (NullabilityPropagation.Match m : analysis.matches()) {
            String rationale = m.rule() + ": " + m.evidence();
            proposals.add(new Proposal(new Proposal.ProposalSource(analysis.runId(), id, m.warningId(), round,
                    EvaluationFiles.sha256(NullabilityPropagation.RULES + "\n" + m.rule()), analysisFile,
                    EvaluationFiles.sha256(Json.write(matchJson(m)))),
                    Proposal.deduplicate(m.edits(), m.edits().stream().map(e -> new EditOrigin(m.warningId(),
                            m.edits().indexOf(e), Optional.of("deterministic rule " + m.rule()))).toList()),
                    rationale, "deterministic rule; the checker evaluation decides the effect"));
        }
        Candidates candidates = Candidates.fromProposals(id, analysis.runId(), runDir, snapshot,
                analysis.sourceRoots(), proposals, GENERATOR, group(input.sourceRound()), idPrefix(round));
        counts.put("uniquePackages", candidates.candidates().size());
        out.println("  packages:   " + analysis.matches().size() + " match(es) -> " + candidates.candidates().size()
                + " unique package(s); each is evaluated alone on a fresh baseline copy");
        Evaluator evaluator = new Evaluator(candidates, config, checker, applier, matcher, round, clock, out);
        if (input.inherit()) {
            evaluator.inheriting(new Evaluator.InheritedBaseline(input.state(), input.nodes(), input.originRounds(),
                    input.identity()));
        }
        Evaluator.Result evaluated = evaluator.run(Optional.empty());
        Map<String, Object> evaluation = new LinkedHashMap<>();
        evaluation.put("status", evaluated.status().name());
        evaluation.put("directory", runDir.relativize(evaluated.directory()).toString());
        evaluation.put("graph", runDir.relativize(evaluated.directory().resolve(Evaluator.GRAPH)).toString());
        record.put("evaluation", evaluation);
        if (evaluated.status() != Evaluator.Status.COMPLETED) {
            Outcome o = evaluated.status() == Evaluator.Status.BASELINE_FAILED ? Outcome.BASELINE_FAILED
                    : Outcome.FAILED;
            record.put("outcome", o.name());
            record.put("detail", "the evaluation did not complete: " + evaluated.status() + " " + evaluated.message());
            return finish(dir, record, o);
        }
        Map<String, AttemptOutcome> outcomes = new LinkedHashMap<>();
        evaluated.attempts().forEach(a -> outcomes.put(a.candidateId(), a.outcome()));
        WarningGraph layer = WarningGraphJson.load(evaluated.directory().resolve(Evaluator.GRAPH));
        record.put("history", writeGraph(input, dir, layer, evaluated.directory(), matcher, out));
        record.put("graphModel", HistoryGraph.MODEL);
        evaluation.put("extendedGraph", runDir.relativize(dir.resolve(GRAPH)).toString());

        // Compatible successes.
        List<Candidates.Candidate> successful = candidates.candidates().stream()
                .filter(c -> outcomes.containsKey(c.id()) && outcomes.get(c.id()).compared()).toList();
        Map<String, List<String>> conflicts = conflicts(successful);
        List<Object> packages = new ArrayList<>();
        Set<String> compatible = new LinkedHashSet<>();
        for (Candidates.Candidate c : candidates.candidates()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", c.id());
            p.put("warningIds", c.warningIds());
            p.put("edits", c.edits().stream().map(AnnotationEdit::canonical).toList());
            AttemptOutcome o = outcomes.get(c.id());
            p.put("evaluation", o == null ? null : o.name());
            if (o == null || !o.compared()) {
                p.put("combined", "EXCLUDED");
                p.put("reason", "not successfully evaluated (" + o + "); its effect is unknown");
            } else if (conflicts.containsKey(c.id())) {
                p.put("combined", "EXCLUDED");
                p.put("reason", "conflicts with " + conflicts.get(c.id()));
            } else {
                p.put("combined", "INCLUDED");
                compatible.add(c.id());
            }
            packages.add(p);
        }
        record.put("packages", packages);
        record.put("conflicts", conflicts);
        if (!conflicts.isEmpty()) {
            out.println("  conflicts:  " + conflicts + " (excluded from the combined result; nothing is chosen by "
                    + "order)");
        }
        if (compatible.isEmpty()) {
            record.put("outcome", Outcome.NOTHING_TO_COMBINE.name());
            record.put("detail", "no package was successfully evaluated without conflicts");
            return finish(dir, record, Outcome.NOTHING_TO_COMBINE);
        }

        // Combined application and one check.
        out.println("  combined:   " + compatible.size() + " compatible, successfully evaluated package(s) applied "
                + "together to a separate copy, checked once");
        RoundApplication application = new RoundApplication(candidates.only(compatible), config, checker, applier,
                matcher, round, clock, out, "every compatible, successfully evaluated nullability-propagation package");
        if (input.inherit()) {
            Set<String> reserved = new LinkedHashSet<>();
            layer.nodes().forEach(n -> reserved.add(n.id()));
            application.withBaseline(evaluated.directory(), input.state(), reserved);
        }
        RoundApplication.Result combined = application.run();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("status", combined.status().name());
        c.put("directory", runDir.relativize(combined.directory()).toString());
        c.put("detail", combined.record().get("detail"));
        c.put("comparison", combined.record().get("comparison"));
        c.put("findingCount", combined.record().get("findingCount"));
        c.put("conflicts", combined.record().get("conflicts"));
        c.put("note", "The combined state's effects are its own; they are not attributed to individual packages.");
        record.put("combined", c);
        Outcome o = switch (combined.status()) {
            case COMPLETED -> Outcome.COMPLETED;
            case REJECTED -> Outcome.COMBINED_BLOCKED;
            case BUSY -> Outcome.FAILED;
            default -> Outcome.COMBINED_FAILED;
        };
        record.put("outcome", o.name());
        record.put("detail", String.valueOf(combined.record().get("detail")));
        return finish(dir, record, o);
    }

    /**
     * Writes {@value #GRAPH} into the stage directory ({@link HistoryGraph}): the earlier evaluation graph, kept
     * whole, plus this stage's layer, with the input state's warnings placed on earlier nodes only through verified
     * provenance and the matcher. Stale {@code .dot}/{@code .svg} renderings of an earlier graph are removed.
     */
    static Map<String, Object> writeGraph(Input input, Path dir, WarningGraph layer, Path evaluationDir,
            WarningMatcher matcher, PrintStream out) throws IOException {
        HistoryGraph.Built built = HistoryGraph.build(input.historyGraph(),
                input.historyEvaluation().or(() -> input.historyGraph().map(Path::getParent)),
                input.inherit() ? Optional.of(input.dir()) : Optional.empty(), input.output(), layer, evaluationDir,
                input.state(), input.sourceRound(), matcher);
        // verified correspondences between distinct occurrences are registry metadata, never shared IDs or edges
        List<?> correspondences = built.record().get("correspondences") instanceof List<?> l ? l : List.of();
        if (!correspondences.isEmpty()) {
            generalannotator.evaluation.WarningRegistry.update(input.output(), r -> {
                for (Object o : correspondences) {
                    Map<?, ?> c = (Map<?, ?>) o;
                    r.correspond(String.valueOf(c.get("reportWarning")), String.valueOf(c.get("correspondsTo")),
                            String.valueOf(c.get("evidence")));
                }
            });
        }
        Path file = dir.resolve(GRAPH);
        String json = WarningGraphJson.write(built.graph());
        boolean changed = !Files.isRegularFile(file) || !Files.readString(file, StandardCharsets.UTF_8).equals(json);
        EvaluationFiles.writeAtomically(file, json);
        if (changed) {
            for (String rendering : List.of(".dot", ".svg")) {
                Files.deleteIfExists(dir.resolve(GRAPH.replace(".json", rendering)));
            }
        }
        Map<String, Object> h = new LinkedHashMap<>(built.record());
        h.put("graph", GRAPH);
        out.println("  graph:      " + file + " (" + built.graph().nodes().size() + " node(s), "
                + built.graph().edges().size() + " edge(s), model " + HistoryGraph.MODEL + "; "
                + (input.historyGraph().isPresent() ? "extends " + input.historyGraph().get() : "no earlier graph")
                + ")");
        if (built.record().get("counts") instanceof Map<?, ?> c && !c.isEmpty()) {
            out.println("              input warnings placed: " + c);
        }
        return h;
    }

    /**
     * Rebuilds {@value #GRAPH} of an existing propagation directory from saved artifacts only (its record, the
     * input it was computed on, the earlier evaluation and its own evaluation); no checker, LLM or inference runs.
     * The input must still have the recorded identity; otherwise the result is stale and propagation must be rerun.
     */
    public static Result rebuildGraph(Path stageDir, WarningMatcher matcher, Clock clock, PrintStream out)
            throws IOException, NullabilityPropagation.InvalidException {
        Path file = stageDir.resolve(RESULT);
        if (!Files.isRegularFile(file)) {
            throw new NullabilityPropagation.InvalidException(stageDir + " has no " + RESULT
                    + "; pass a propagation directory (runs/<run>/propagation/<id>)");
        }
        Map<String, Object> record = new LinkedHashMap<>();
        Map<?, ?> saved = Input.parse(file);
        saved.forEach((k, v) -> record.put(String.valueOf(k), v));
        Path runDir = stageDir.toAbsolutePath().normalize().getParent().getParent();
        Map<?, ?> in = record.get("input") instanceof Map<?, ?> m ? m : Map.of();
        if (!(record.get("evaluation") instanceof Map<?, ?> e) || e.get("directory") == null
                || !"COMPLETED".equals(e.get("status"))) {
            throw new NullabilityPropagation.InvalidException(stageDir + " has no completed evaluation (outcome "
                    + record.get("outcome") + "), so there is no graph to rebuild");
        }
        int round = record.get("round") instanceof Number r ? r.intValue() : DEFAULT_ROUND;
        Input input = Files.isRegularFile(runDir.resolve("pipeline.json")) ? Input.fromPipeline(runDir, round)
                : Input.resolve(Path.of(String.valueOf(in.get("directory"))));
        if (!input.identity().equals(in.get("identity"))) {
            throw new NullabilityPropagation.InvalidException("the input of " + stageDir + " was " + in.get("state")
                    + " (identity " + in.get("identity") + "), but it now resolves to " + input.state() + " (identity "
                    + input.identity() + "). The saved results describe another input; rerun propagate instead.");
        }
        Path evaluationDir = runDir.resolve(String.valueOf(((Map<?, ?>) record.get("evaluation")).get("directory")));
        Map<?, ?> evaluation = Input.parse(evaluationDir.resolve("evaluation.json"));
        Object inherited = evaluation.get("inheritedBaseline") instanceof Map<?, ?> ib ? ib.get("identity") : null;
        if (input.inherit() && !input.identity().equals(inherited)) {
            throw new NullabilityPropagation.InvalidException("the evaluation " + evaluationDir + " was not computed on "
                    + "input " + input.state() + " (inherited identity " + inherited + "); rerun propagate instead.");
        }
        WarningGraph layer = WarningGraphJson.load(evaluationDir.resolve(Evaluator.GRAPH));
        out.println("GeneralAnnotator propagate --rebuild-graph " + stageDir + " (saved artifacts only; nothing is "
                + "rechecked)");
        out.println("  input:      source round " + input.sourceRound() + ", state " + input.state() + ", "
                + input.nodes().size() + " warning(s)");
        record.put("history", writeGraph(input, stageDir, layer, evaluationDir, matcher, out));
        record.put("graphModel", HistoryGraph.MODEL);
        record.put("graphRebuiltAt", clock.instant().toString());
        EvaluationFiles.writeAtomically(file, Json.write(record));
        EvaluationFiles.writeAtomically(stageDir.resolve(SUMMARY), summary(input, stageDir, record));
        return new Result(Outcome.valueOf(String.valueOf(record.get("outcome"))), stageDir, record);
    }

    /**
     * Packages whose edits clash with another package's at one position (ADD and REMOVE, or two different
     * nullness annotations), mapped to the packages they clash with. Both sides are excluded.
     */
    static Map<String, List<String>> conflicts(List<Candidates.Candidate> packages) {
        Map<String, List<String>> conflicts = new TreeMap<>();
        for (Candidates.Candidate a : packages) {
            for (Candidates.Candidate b : packages) {
                if (a == b) {
                    continue;
                }
                boolean clash = a.edits().stream().anyMatch(x -> b.edits().stream().anyMatch(y ->
                        x.positionKey().equals(y.positionKey()) && !x.equals(y)
                                && ((x.annotation().equals(y.annotation()) && x.action() != y.action())
                                        || (AnnotationKinds.nullness(AnnotationKinds.simpleName(x.annotation())) != null
                                                && AnnotationKinds.nullness(AnnotationKinds.simpleName(y.annotation()))
                                                        != null))));
                if (clash) {
                    conflicts.computeIfAbsent(a.id(), k -> new ArrayList<>()).add(b.id());
                }
            }
        }
        return conflicts;
    }

    private Result finish(Path dir, Map<String, Object> record, Outcome outcome) throws IOException {
        record.put("finishedAt", clock.instant().toString());
        EvaluationFiles.writeAtomically(dir.resolve(RESULT), Json.write(record));
        EvaluationFiles.writeAtomically(dir.resolve(SUMMARY), summary(input, dir, record));
        return new Result(outcome, dir, record);
    }

    private static String summary(Input input, Path dir, Map<String, ?> r) {
        Path runDir = input.output();
        StringBuilder b = new StringBuilder();
        b.append("Nullability propagation ").append(r.get("id")).append(" (").append(r.get("rules"))
                .append(", one pass, no LLM): ").append(r.get("outcome")).append('\n');
        b.append("  run:        ").append(runDir).append('\n');
        b.append("  input:      source round ").append(input.sourceRound()).append(", state ").append(input.state())
                .append(", ").append(input.nodes().size()).append(" warning(s); ").append(input.description())
                .append('\n');
        b.append("  snapshot:   ").append(input.snapshot()).append('\n');
        b.append("  counts:     ").append(r.get("counts")).append('\n');
        if (r.get("packages") instanceof List<?> ps) {
            for (Object o : ps) {
                Map<?, ?> p = (Map<?, ?>) o;
                b.append("  ").append(p.get("id")).append("  ").append(p.get("evaluation")).append("  combined ")
                        .append(p.get("combined")).append("  ").append(p.get("warningIds")).append("  ")
                        .append(p.get("edits")).append(p.get("reason") == null ? "" : "  (" + p.get("reason") + ")")
                        .append('\n');
            }
        }
        if (r.get("evaluation") instanceof Map<?, ?> e) {
            b.append("  evaluations: ").append(runDir.resolve(String.valueOf(e.get("directory")))).append('\n');
            if (e.get("extendedGraph") != null) {
                b.append("  graph:      ").append(runDir.resolve(String.valueOf(e.get("extendedGraph"))))
                        .append(" (earlier history plus this stage, ").append(r.get("graphModel")).append(")\n");
                if (r.get("history") instanceof Map<?, ?> h && h.get("counts") instanceof Map<?, ?> c) {
                    b.append("              input warnings placed: ").append(c).append('\n');
                    if (h.get("reportToGraph") instanceof Map<?, ?> m && !m.isEmpty()) {
                        b.append("              combined-result ID -> graph node: ").append(m).append('\n');
                    }
                }
            }
        }
        if (r.get("combined") instanceof Map<?, ?> c) {
            b.append("  combined:   ").append(c.get("status")).append(": ").append(c.get("detail")).append('\n');
            b.append("              ").append(runDir.resolve(String.valueOf(c.get("directory")))).append('\n');
        }
        b.append("  analysis:   ").append(dir.resolve(ANALYSIS)).append(" (every warning: matched or skipped with its "
                + "reason)\n");
        return b.toString();
    }

    static Map<String, Object> analysisJson(NullabilityPropagation.Analysis a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", 1);
        m.put("rules", NullabilityPropagation.RULES);
        m.put("runId", a.runId());
        m.put("sourceRoots", a.sourceRoots());
        m.put("warnings", a.warnings());
        m.put("matches", a.matches().stream().map(PropagationStage::matchJson).toList());
        List<Object> skipped = new ArrayList<>();
        for (NullabilityPropagation.Skip s : a.skipped()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("warningId", s.warningId());
            x.put("category", s.category().name());
            x.put("reason", s.reason());
            skipped.add(x);
        }
        m.put("skipped", skipped);
        m.put("compilerErrors", a.compilerErrors());
        m.put("note", "Generated from the unchanged baseline before any edit is applied. A match requires the "
                + "NullAway message kind, the tree at the diagnostic position and the resolved symbols to agree.");
        return m;
    }

    static Map<String, Object> matchJson(NullabilityPropagation.Match m) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("warningId", m.warningId());
        x.put("rule", m.rule().name());
        x.put("edit", Candidates.editFields(m.edit()));
        x.put("canonical", m.edit().canonical());
        if (m.removal().isPresent()) {
            x.put("removes", Candidates.editFields(m.removal().get()));
        }
        if (!m.hierarchy().isEmpty()) {
            x.put("hierarchy", m.hierarchy().stream().map(Candidates::editFields).toList());
        }
        if (!m.removals().isEmpty()) {
            x.put("contractChange", m.removals().stream().map(r -> r.canonical() + "; replaced by @"
                    + m.edit().annotation()).toList());
        }
        x.put("evidence", m.evidence());
        return x;
    }
}
