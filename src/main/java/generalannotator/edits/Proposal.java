package generalannotator.edits;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One proposer answer for one warning: a set of edits proposed together (jointly, not as
 * alternatives), with identical edits merged and their origins kept.
 *
 * @param source where the answer came from
 * @param edits the distinct edits, in first-occurrence order
 * @param rationale the answer's rationale
 * @param uncertainty the answer's overall uncertainty
 */
public record Proposal(ProposalSource source, List<ProposedEdit> edits, String rationale, String uncertainty) {

    public Proposal {
        edits = List.copyOf(edits);
        if (edits.isEmpty()) {
            throw new IllegalArgumentException("a proposal has at least one edit");
        }
    }

    /**
     * Provenance of an answer.
     *
     * @param runId the analyzed run
     * @param submissionId the inference submission
     * @param warningId the warning the answer is about
     * @param attempt the inference attempt number
     * @param promptSha256 the SHA-256 of the prompt that produced it
     * @param answerFile the answer file (read-only input)
     * @param answerSha256 the SHA-256 of the answer file
     */
    public record ProposalSource(String runId, String submissionId, String warningId, int attempt,
            String promptSha256, Path answerFile, String answerSha256) {}

    /** Merges identical edits, keeping each origin, in first-occurrence order. */
    public static List<ProposedEdit> deduplicate(List<AnnotationEdit> edits, List<EditOrigin> origins) {
        if (edits.size() != origins.size()) {
            throw new IllegalArgumentException("one origin per edit is required");
        }
        Map<AnnotationEdit, List<EditOrigin>> merged = new LinkedHashMap<>();
        for (int i = 0; i < edits.size(); i++) {
            merged.computeIfAbsent(edits.get(i), e -> new ArrayList<>()).add(origins.get(i));
        }
        List<ProposedEdit> result = new ArrayList<>();
        merged.forEach((e, o) -> result.add(new ProposedEdit(e, o)));
        return result;
    }
}
