package generalannotator.evaluation.matching;

import java.util.List;

/**
 * Decides which findings of a state after an edit correspond to which findings of the baseline state.
 * This is where warning identity (project-spec §6.1) is encoded; implementations are provisional and
 * must record their assumptions.
 */
public interface WarningMatcher {

    /** A stable rule identifier, recorded with every comparison (for example {@code anchored-1}). */
    String id();

    /** The rule's assumptions, recorded with every comparison. */
    List<String> assumptions();

    /**
     * Compares the complete findings of two completed analyses. Every finding appears in exactly one
     * category: persisting (paired), resolved (baseline only), introduced (result only) or ambiguous.
     * Ambiguous findings support no resolution or introduction claim.
     */
    Comparison compare(List<ObservedFinding> baseline, List<ObservedFinding> result);

    /** A baseline finding paired with a finding after the edit. */
    record Pair(String baselineId, String resultId) {}

    /** Findings whose correspondence could not be decided, and why. */
    record Ambiguity(List<String> baselineIds, List<String> resultIds, String reason) {
        public Ambiguity {
            baselineIds = List.copyOf(baselineIds);
            resultIds = List.copyOf(resultIds);
        }
    }

    /**
     * The outcome of a comparison.
     *
     * @param persisting paired findings
     * @param resolved baseline findings with no counterpart after the edit
     * @param introduced findings after the edit with no baseline counterpart
     * @param ambiguous undecided groups
     */
    record Comparison(List<Pair> persisting, List<String> resolved, List<String> introduced,
            List<Ambiguity> ambiguous) {
        public Comparison {
            persisting = List.copyOf(persisting);
            resolved = List.copyOf(resolved);
            introduced = List.copyOf(introduced);
            ambiguous = List.copyOf(ambiguous);
        }

        public int ambiguousBaseline() {
            return ambiguous.stream().mapToInt(a -> a.baselineIds().size()).sum();
        }

        public int ambiguousResult() {
            return ambiguous.stream().mapToInt(a -> a.resultIds().size()).sum();
        }
    }
}
