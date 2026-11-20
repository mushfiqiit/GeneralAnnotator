package generalannotator.cli;

import generalannotator.edits.AnnotationApplier;
import generalannotator.edits.ProposedEdit;
import generalannotator.proposals.ProposalApplication;
import generalannotator.proposals.ProposalImporter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;

/**
 * {@code apply-proposal <submission directory> --warning <ID>}: import the collected LLM proposal of
 * one warning and apply its edits, all or nothing, to a fresh copy of the run's snapshot under {@code
 * applications/<id>/}. The original project, the snapshot and the inference artifacts are only read.
 * Neither the checker nor the LLM is run, and the warning graph is not changed.
 *
 * <p>Exit codes: 0 applied (or every edit was already satisfied); 2 invalid arguments or
 * inconsistent artifacts (nothing written); 3 no applicable proposal (failed, missing, invalid or
 * insufficient-evidence result; nothing written) or the proposal was rejected (the record is written,
 * without a workspace); 4 internal error.
 */
public final class ApplyProposalCommand {

    private final String submissionArgument;
    private final String warningId;
    private final Path workingDirectory;
    private final AnnotationApplier applier;
    private final Clock clock;
    private final PrintStream out;
    private final PrintStream err;

    public ApplyProposalCommand(String submissionArgument, String warningId, Path workingDirectory,
            AnnotationApplier applier, Clock clock, PrintStream out, PrintStream err) {
        this.submissionArgument = submissionArgument;
        this.warningId = warningId;
        this.workingDirectory = workingDirectory;
        this.applier = applier;
        this.clock = clock;
        this.out = out;
        this.err = err;
    }

    public ExitCode run() {
        Path dir;
        try {
            dir = workingDirectory.resolve(submissionArgument).normalize();
        } catch (InvalidPathException e) {
            return usage("'" + submissionArgument + "' is not a valid path");
        }
        if (!Files.isDirectory(dir)) {
            return usage(dir + " is not a submission directory (runs/<run id>/inference/<submission id>)");
        }
        ProposalImporter.Result imported;
        try {
            imported = ProposalImporter.load(dir, warningId);
        } catch (ProposalImporter.InvalidException e) {
            return usage(e.getMessage());
        } catch (IOException e) {
            err.println("GeneralAnnotator: cannot read the inference artifacts: " + e);
            return ExitCode.INTERNAL_ERROR;
        }
        out.println("GeneralAnnotator apply-proposal " + warningId + " (submission " + imported.entry().submissionId()
                + ", run " + imported.entry().runId() + ")");
        if (imported instanceof ProposalImporter.NotApplicable n) {
            out.println("  No proposal applied: result " + n.status() + ": " + n.reason());
            return ExitCode.EXECUTION_FAILURE;
        }
        ProposalImporter.Ready ready = (ProposalImporter.Ready) imported;
        out.println("  answer:   " + ready.proposal().source().answerFile() + " (attempt "
                + ready.proposal().source().attempt() + ")");
        out.println("  snapshot: " + ready.run().snapshot() + ", source root " + ready.run().sourceRoot());
        ProposalApplication.Applied applied;
        try {
            applied = ProposalApplication.apply(ready, applier, clock);
        } catch (IOException e) {
            err.println("GeneralAnnotator: cannot apply the proposal: " + e);
            return ExitCode.INTERNAL_ERROR;
        }
        for (int i = 0; i < ready.proposal().edits().size(); i++) {
            ProposedEdit pe = ready.proposal().edits().get(i);
            AnnotationApplier.EditResult r = applied.result().results().get(i);
            out.println("  edit " + i + ": " + r.outcome() + "  " + pe.edit().canonical());
            out.println("          " + r.detail());
        }
        out.println("  outcome:  " + applied.outcome() + "; record " + applied.directory().resolve(
                ProposalApplication.RECORD));
        if (applied.outcome() == ProposalApplication.Outcome.APPLIED) {
            out.println("  workspace: " + applied.directory().resolve("workspace") + "; diff "
                    + applied.directory().resolve(ProposalApplication.DIFF));
        }
        out.println("  Source edits only: the checker was not rerun, so whether the warning is resolved is unknown.");
        return applied.outcome() == ProposalApplication.Outcome.REJECTED ? ExitCode.EXECUTION_FAILURE
                : ExitCode.COMPLETED;
    }

    private ExitCode usage(String problem) {
        err.println("GeneralAnnotator: cannot apply the proposal: " + problem);
        return ExitCode.USAGE_OR_CONFIGURATION_ERROR;
    }
}
