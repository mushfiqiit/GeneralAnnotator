package generalannotator.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One annotation package evaluated (or still to be evaluated) against one baseline state: every edit
 * one validated answer proposed together for one warning, applied together and checked once.
 *
 * <p>Identity: the complete package (the originating answer and its edit set) together with the
 * baseline state and round. Packages from answers about different warnings are different nodes even
 * when they share edits. Every package has a node, so a node without edges is never silent: its
 * {@code outcome} says whether it was not evaluated, changed nothing, was a no-op, was rejected, failed,
 * or could only be compared ambiguously.
 *
 * <p>The originating proposal is kept here, apart from the graph's edges: an edge {@code W -> A}
 * means that the comparison found baseline warning W resolved in the state after the whole package A,
 * whether or not A was proposed for W.
 *
 * <p>Graphs written by earlier versions held one edit per node ({@code edits} has one entry and the
 * proposals carry the index of that edit in each answer).
 *
 * @param id unique ID within the graph, for example {@code A0001}
 * @param canonical a one-line description of the package
 * @param edits the package's distinct edits, in answer order
 * @param baselineState the state the package was applied to, for example {@code S0}
 * @param resultState the state after the package ({@code S0+A0001}), present only if it was applied
 *     and checked
 * @param outcome the evaluation outcome, for example {@code COMPLETED} or {@code UNEVALUATED}
 * @param detail a human-readable explanation of the outcome
 * @param proposals the answer(s) that proposed it: one for a package
 * @param counts the comparison counts, present only for a trustworthy comparison
 * @param round the inference round whose answer proposed the package; empty in graphs written before
 *     rounds were recorded
 */
public record AnnotationNode(String id, String canonical, List<PackageEdit> edits, String baselineState,
        Optional<String> resultState, String outcome, String detail, List<Proposal> proposals,
        Optional<Counts> counts, OptionalInt round) implements GraphNode {

    public static final String KIND = "annotation";

    /**
     * One edit of a package.
     *
     * @param canonical a one-line description of the edit
     * @param fields the edit's identity fields (source root, file, class, member, position, action,
     *     annotation), in a fixed order
     * @param answerEditIndices the indices in the answer's {@code edits} of every identical entry merged
     *     into this edit (empty in graphs of earlier versions)
     * @param applierOutcome APPLIED, NO_OP or REJECTED once the package was applied
     * @param applierDetail the applier's explanation
     */
    public record PackageEdit(String canonical, Map<String, String> fields, List<Integer> answerEditIndices,
            Optional<String> applierOutcome, Optional<String> applierDetail) {
        public PackageEdit {
            Objects.requireNonNull(canonical, "canonical");
            fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            answerEditIndices = List.copyOf(answerEditIndices);
            Objects.requireNonNull(applierOutcome, "applierOutcome");
            Objects.requireNonNull(applierDetail, "applierDetail");
        }
    }

    /**
     * An answer that proposed the package (or, in earlier single-edit graphs, the edit).
     *
     * @param warningId the warning (of the analyzed run) whose answer proposed it
     * @param group the answer ({@code <warning>@<attempt>})
     * @param answerEditIndex the edit's index in the answer, for earlier single-edit nodes only
     * @param baselineWarningId the baseline warning node that warning corresponds to, if it was identified
     */
    public record Proposal(String warningId, String group, OptionalInt answerEditIndex,
            Optional<String> baselineWarningId) {
        public Proposal {
            Objects.requireNonNull(warningId, "warningId");
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(answerEditIndex, "answerEditIndex");
            Objects.requireNonNull(baselineWarningId, "baselineWarningId");
        }
    }

    /**
     * Comparison counts of a completed analysis after the package.
     *
     * @param baselineFindings findings in the baseline state
     * @param resultFindings findings in the state after the package
     * @param resolved baseline findings matched to nothing after the package
     * @param persisting baseline findings matched to a finding after the package
     * @param introduced findings after the package matched to no baseline finding
     * @param ambiguousBaseline baseline findings whose fate could not be decided
     * @param ambiguousResult findings after the package whose origin could not be decided
     */
    public record Counts(int baselineFindings, int resultFindings, int resolved, int persisting, int introduced,
            int ambiguousBaseline, int ambiguousResult) {}

    public AnnotationNode {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("node id must not be blank");
        }
        Objects.requireNonNull(canonical, "canonical");
        edits = List.copyOf(edits);
        if (edits.isEmpty()) {
            throw new IllegalArgumentException("an annotation node holds at least one edit");
        }
        Objects.requireNonNull(baselineState, "baselineState");
        Objects.requireNonNull(resultState, "resultState");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(detail, "detail");
        proposals = List.copyOf(proposals);
        Objects.requireNonNull(counts, "counts");
        Objects.requireNonNull(round, "round");
        if (round.isPresent() && round.getAsInt() < 1) {
            throw new IllegalArgumentException("an annotation node belongs to a round >= 1, not " + round.getAsInt());
        }
    }

    @Override
    public String kind() {
        return KIND;
    }
}
