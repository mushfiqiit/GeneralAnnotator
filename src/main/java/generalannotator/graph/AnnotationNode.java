package generalannotator.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One concrete annotation edit evaluated (or still to be evaluated) against one baseline state.
 *
 * <p>Identity: the edit (source root, file, declaring class, member, exact position, action and
 * annotation) together with the baseline state. The same edit evaluated against another baseline is
 * a different node. Every candidate edit has a node, so a node without edges is never silent: its
 * {@code outcome} says whether it was not evaluated, changed nothing, was a no-op, was rejected,
 * failed, or could only be compared ambiguously.
 *
 * <p>Proposal associations (which warnings' answers proposed the edit) are kept here, apart from
 * the graph's edges: an edge {@code W -> A} means that the comparison found baseline warning W
 * resolved in the state after A, whether or not A was proposed for W.
 *
 * @param id unique ID within the graph, for example {@code A0001}
 * @param canonical a one-line description of the edit
 * @param edit the edit's identity fields, in a fixed order
 * @param baselineState the state the edit was applied to, for example {@code S0}
 * @param resultState the state after the edit ({@code S0+A0001}), present only if the edit was applied
 *     and checked
 * @param outcome the evaluation outcome, for example {@code COMPLETED} or {@code UNEVALUATED}
 * @param detail a human-readable explanation of the outcome
 * @param proposals the answers that proposed this edit
 * @param counts the comparison counts, present only for a trustworthy comparison
 */
public record AnnotationNode(String id, String canonical, Map<String, String> edit, String baselineState,
        Optional<String> resultState, String outcome, String detail, List<Proposal> proposals,
        Optional<Counts> counts) implements GraphNode {

    public static final String KIND = "annotation";

    /**
     * One answer entry that proposed the edit.
     *
     * @param warningId the warning (of the analyzed run) whose answer proposed it
     * @param group the proposal group (one answer: all edits proposed together)
     * @param answerEditIndex the index of the edit in the answer
     * @param baselineWarningId the baseline warning node that warning corresponds to, if it was identified
     */
    public record Proposal(String warningId, String group, int answerEditIndex, Optional<String> baselineWarningId) {
        public Proposal {
            Objects.requireNonNull(warningId, "warningId");
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(baselineWarningId, "baselineWarningId");
        }
    }

    /**
     * Comparison counts of a completed analysis after the edit.
     *
     * @param baselineFindings findings in the baseline state
     * @param resultFindings findings in the state after the edit
     * @param resolved baseline findings matched to nothing after the edit
     * @param persisting baseline findings matched to a finding after the edit
     * @param introduced findings after the edit matched to no baseline finding
     * @param ambiguousBaseline baseline findings whose fate could not be decided
     * @param ambiguousResult findings after the edit whose origin could not be decided
     */
    public record Counts(int baselineFindings, int resultFindings, int resolved, int persisting, int introduced,
            int ambiguousBaseline, int ambiguousResult) {}

    public AnnotationNode {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("node id must not be blank");
        }
        Objects.requireNonNull(canonical, "canonical");
        edit = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(edit));
        Objects.requireNonNull(baselineState, "baselineState");
        Objects.requireNonNull(resultState, "resultState");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(detail, "detail");
        proposals = List.copyOf(proposals);
        Objects.requireNonNull(counts, "counts");
    }

    @Override
    public String kind() {
        return KIND;
    }
}
