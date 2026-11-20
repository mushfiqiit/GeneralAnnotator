package generalannotator.edits;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Applies a set of annotation edits to Java sources under a workspace root, all or nothing: every
 * edit is checked first, and files are written only if no edit is rejected. Implementations never
 * touch files outside {@code workspaceRoot}.
 */
public interface AnnotationApplier {

    ApplyResult apply(Path workspaceRoot, List<AnnotationEdit> edits) throws IOException;

    /** What happened to one edit. */
    enum Outcome {
        /** The source was changed. */
        APPLIED,
        /** Nothing to do: the annotation was already present (ADD) or already absent (REMOVE). */
        NO_OP,
        /** The edit could not be applied reliably; see the reason. */
        REJECTED
    }

    /** The outcome of one edit with an explanation. */
    record EditResult(AnnotationEdit edit, Outcome outcome, String detail) {}

    /**
     * One changed file.
     *
     * @param path the workspace-relative path
     * @param before the content before
     * @param after the content after
     */
    record FileChange(String path, String before, String after) {}

    /**
     * The result of {@link #apply}.
     *
     * @param results one result per edit, in input order
     * @param changes the files written (empty unless {@link #committed()})
     * @param committed whether files were written: no edit was rejected and at least one was applied
     */
    record ApplyResult(List<EditResult> results, List<FileChange> changes, boolean committed) {
        public ApplyResult {
            results = List.copyOf(results);
            changes = List.copyOf(changes);
        }

        public boolean rejected() {
            return results.stream().anyMatch(r -> r.outcome() == Outcome.REJECTED);
        }
    }
}
