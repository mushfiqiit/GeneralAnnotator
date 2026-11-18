package generalannotator.slicing;

import generalannotator.targets.WarningTarget;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * One slicing job.
 *
 * @param target the resolved target of the warning (its ID is the warning ID)
 * @param snapshotSourceRoot the source root in the run's isolated snapshot; it is only read (a
 *     disposable copy is given to the slicer tool)
 * @param sliceDirectory a fresh, not yet existing directory for this warning's slice, copy and logs
 * @param modularityModel Specimin's modularity model, for example {@code nullaway}
 * @param timeout timeout for the slicer process
 */
public record SliceRequest(
        WarningTarget.Resolved target, Path snapshotSourceRoot, Path sliceDirectory, String modularityModel,
        Duration timeout) {

    public SliceRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(snapshotSourceRoot, "snapshotSourceRoot");
        Objects.requireNonNull(sliceDirectory, "sliceDirectory");
        Objects.requireNonNull(modularityModel, "modularityModel");
        Objects.requireNonNull(timeout, "timeout");
    }

    public String warningId() {
        return target.warningId();
    }
}
