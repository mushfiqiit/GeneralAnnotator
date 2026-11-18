package generalannotator.slicing;

import generalannotator.exec.RecordedCommand;
import generalannotator.targets.WarningTarget;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of slicing one warning.
 *
 * @param warningId the warning ID
 * @param target the warning's target (resolved or unresolved) as read from warning-targets.json
 * @param status generated, failed or skipped
 * @param command the slicer command with its logs, if it was run
 * @param outputDirectory the slice output directory, if the slicer was run
 * @param outputFiles Java files in the output, relative to {@code outputDirectory}, sorted
 * @param failureReason why the slice was not generated (failure or skip reason)
 */
public record SliceResult(
        String warningId,
        WarningTarget target,
        SliceStatus status,
        Optional<RecordedCommand> command,
        Optional<Path> outputDirectory,
        List<String> outputFiles,
        Optional<String> failureReason) {

    /** Every slice's warning reproduction is unknown until a later validation step. */
    public static final String REPRODUCTION = "NOT_VALIDATED";

    public SliceResult {
        Objects.requireNonNull(warningId, "warningId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        outputFiles = List.copyOf(outputFiles);
        Objects.requireNonNull(failureReason, "failureReason");
        if (status == SliceStatus.GENERATED && (outputFiles.isEmpty() || failureReason.isPresent())) {
            throw new IllegalArgumentException("a generated slice needs output files and no failure reason");
        }
        if (status != SliceStatus.GENERATED && failureReason.isEmpty()) {
            throw new IllegalArgumentException(status + " needs a reason");
        }
    }

    /** An unresolved target is skipped with its existing reason. */
    public static SliceResult skipped(WarningTarget.Unresolved target) {
        return new SliceResult(target.warningId(), target, SliceStatus.SKIPPED, Optional.empty(), Optional.empty(),
                List.of(), Optional.of(target.reason()));
    }
}
