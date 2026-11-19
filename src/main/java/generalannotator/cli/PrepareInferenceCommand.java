package generalannotator.cli;

import generalannotator.inference.InferenceSettings;
import generalannotator.inference.SlurmScript;
import generalannotator.inference.SubmissionPreparer;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code prepare-inference <run directory> [--warning ID]}: prepare an immutable inference
 * submission ({@code inference/<submission id>/}) from the run's GENERATED prompt units, with a Slurm
 * array batch script and the GPU worker. Runs locally only: nothing is uploaded or submitted, and
 * no model is loaded. Settings come from {@code GA_SLURM_*} and {@code GA_INFERENCE_*} variables.
 *
 * <p>Exit codes: 0 prepared; 2 invalid arguments, settings, prompt manifest or prompt files, or an
 * existing submission directory (nothing written); 4 internal error.
 */
public final class PrepareInferenceCommand {

    private final String runArgument;
    private final Optional<String> warningId;
    private final Map<String, String> env;
    private final Path workingDirectory;
    private final Clock clock;
    private final PrintStream out;
    private final PrintStream err;

    public PrepareInferenceCommand(String runArgument, Optional<String> warningId, Map<String, String> env,
            Path workingDirectory, Clock clock, PrintStream out, PrintStream err) {
        this.runArgument = runArgument;
        this.warningId = warningId;
        this.env = Map.copyOf(env);
        this.workingDirectory = workingDirectory;
        this.clock = clock;
        this.out = out;
        this.err = err;
    }

    public ExitCode run() {
        List<String> problems = new ArrayList<>();
        InferenceSettings settings = InferenceSettings.fromEnvironment(env, problems);
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
        SubmissionPreparer.Prepared prepared;
        try {
            prepared = SubmissionPreparer.prepare(runDir, warningId, settings, clock);
        } catch (SubmissionPreparer.PreparationException e) {
            return usage(e.problems());
        } catch (IOException e) {
            err.println("GeneralAnnotator: cannot prepare the submission: " + e);
            return ExitCode.INTERNAL_ERROR;
        }
        String sizes = sizes(prepared.tasks().stream().map(List::size).toList());
        out.println("GeneralAnnotator prepare-inference");
        out.println("  run:        " + runDir + " (run " + prepared.runId() + ")");
        out.println("  submission: " + prepared.directory() + " (immutable)");
        out.println("  prompts:    " + prepared.warningIds().size() + " GENERATED prompt(s), copied unchanged with "
                + "SHA-256 hashes" + (prepared.excluded().isEmpty() ? ""
                        : "; not prepared (not GENERATED): " + String.join(", ", prepared.excluded())));
        out.println("  tasks:      " + prepared.tasks().size() + " array task(s) with " + sizes + " prompt(s), "
                + "array 0-" + (prepared.tasks().size() - 1) + "%" + settings.arrayConcurrency()
                + "; each prompt is a separate request");
        out.println("  model:      " + settings.model() + " served as " + settings.servedModelName() + ", context "
                + settings.maxModelLen() + ", reasoning parser " + settings.reasoningParser().orElse("none")
                + ", max_tokens " + (settings.maxTokens() == 0 ? "rest of context" : settings.maxTokens()));
        out.println("  slurm:      account " + settings.account() + ", partition " + settings.partition() + ", qos "
                + settings.qos() + ", gres " + settings.gres() + settings.constraint().map(c -> ", constraint " + c)
                        .orElse("") + ", time " + settings.time() + " per task");
        out.println("  batch:      " + SlurmScript.FILE_NAME + " (sbatch it from the submission directory on the "
                + "cluster)");
        out.println("  Nothing was uploaded or submitted, and no model was loaded. Upload, submission and result "
                + "download are not implemented yet.");
        return ExitCode.COMPLETED;
    }

    /** Run-length description of task sizes, for example {@code 4×10, 1×1}. */
    static String sizes(List<Integer> sizes) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < sizes.size();) {
            int j = i;
            while (j < sizes.size() && sizes.get(j).equals(sizes.get(i))) {
                j++;
            }
            parts.add((j - i) + "×" + sizes.get(i));
            i = j;
        }
        return String.join(", ", parts);
    }

    private ExitCode usage(List<String> problems) {
        err.println("GeneralAnnotator: cannot prepare inference:");
        problems.forEach(p -> err.println("  - " + p));
        return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
    }
}
