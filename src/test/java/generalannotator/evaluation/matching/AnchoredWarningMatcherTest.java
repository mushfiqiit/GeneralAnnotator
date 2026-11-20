package generalannotator.evaluation.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.diagnostics.Diagnostic;
import generalannotator.diagnostics.Severity;
import generalannotator.evaluation.matching.WarningMatcher.Comparison;
import generalannotator.evaluation.matching.WarningMatcher.Pair;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** The provisional {@code anchored-1} rule on synthetic findings. */
class AnchoredWarningMatcherTest {

    private final AnchoredWarningMatcher matcher = new AnchoredWarningMatcher();

    private static ObservedFinding f(String id, int line, int column, String message, String declaration,
            int declarationLine, String snippet) {
        return new ObservedFinding(id, new Diagnostic("/w/src/p/A.java", line, OptionalInt.of(column), Severity.ERROR,
                Optional.of("NullAway"), message, Optional.of(snippet)), "src/p/A.java",
                declarationLine == 0 ? ObservedFinding.Anchor.unresolved("x")
                        : new ObservedFinding.Anchor(declaration, declarationLine, ""));
    }

    private static ObservedFinding f(String id, int line, String message, String declaration, int declarationLine) {
        return f(id, line, 5, message, declaration, declarationLine, "use(x);");
    }

    @Test
    void resolvesIntroducesAndPersistsByKey() {
        Comparison c = matcher.compare(
                List.of(f("W1", 5, "field x", "p.A#field:x", 5), f("W2", 6, "field y", "p.A#field:y", 6)),
                List.of(f("R1", 6, "field y", "p.A#field:y", 6), f("R2", 8, "returning x", "p.A#get()", 8)));

        assertEquals(List.of(new Pair("W2", "R1")), c.persisting());
        assertEquals(List.of("W1"), c.resolved());
        assertEquals(List.of("R2"), c.introduced());
        assertEquals(List.of(), c.ambiguous());
    }

    @Test
    void unchangedTotalsCanHideChangedFindings() {
        Comparison c = matcher.compare(List.of(f("W1", 5, "a", "p.A#m()", 4)), List.of(f("R1", 5, "b", "p.A#m()", 4)));

        assertEquals(List.of("W1"), c.resolved());
        assertEquals(List.of("R1"), c.introduced());
    }

    @Test
    void shiftedLinesAndColumnsDoNotBreakIdentity() {
        // The declaration moved down two lines and the column moved right: still the same warning.
        Comparison c = matcher.compare(List.of(f("W1", 10, 5, "m", "p.A#m()", 8, "use(x);")),
                List.of(f("R1", 12, 40, "m", "p.A#m()", 10, "@org.jspecify.annotations.Nullable  use(x);")));

        assertEquals(List.of(new Pair("W1", "R1")), c.persisting());
        assertEquals(List.of(), c.resolved());
    }

    @Test
    void repeatedFindingsArePairedByOffsetEvenWhenLinesShift() {
        Comparison c = matcher.compare(
                List.of(f("W1", 9, "m", "p.A#m()", 8), f("W2", 11, "m", "p.A#m()", 8)),
                List.of(f("R1", 13, "m", "p.A#m()", 10)));

        assertEquals(List.of(new Pair("W2", "R1")), c.persisting());
        assertEquals(List.of("W1"), c.resolved());
        assertEquals(List.of(), c.ambiguous());
    }

    @Test
    void multiplicityIsPreserved() {
        Comparison c = matcher.compare(
                List.of(f("W1", 9, "m", "p.A#m()", 8), f("W2", 10, "m", "p.A#m()", 8), f("W3", 11, "m", "p.A#m()", 8)),
                List.of(f("R1", 9, "m", "p.A#m()", 8), f("R2", 10, "m", "p.A#m()", 8), f("R3", 11, "m", "p.A#m()", 8),
                        f("R4", 12, "m", "p.A#m()", 8)));

        assertEquals(3, c.persisting().size());
        assertEquals(List.of("R4"), c.introduced());
    }

    @Test
    void indistinguishableRepeatsAreAmbiguous() {
        Comparison c = matcher.compare(
                List.of(f("W1", 9, "m", "p.A#m()", 8), f("W2", 9, "m", "p.A#m()", 8)),
                List.of(f("R1", 9, "m", "p.A#m()", 8)));

        assertEquals(List.of(), c.resolved());
        assertEquals(List.of(), c.persisting());
        assertEquals(1, c.ambiguous().size());
        assertEquals(List.of("W1", "W2"), c.ambiguous().get(0).baselineIds());
        assertEquals(List.of("R1"), c.ambiguous().get(0).resultIds());
        assertEquals(2, c.ambiguousBaseline());
        assertEquals(1, c.ambiguousResult());
    }

    @Test
    void unresolvedAnchorsCannotBePairedByOffset() {
        Comparison c = matcher.compare(
                List.of(f("W1", 9, "m", "?", 0), f("W2", 11, "m", "?", 0)),
                List.of(f("R1", 9, "m", "?", 0)));

        assertEquals(1, c.ambiguous().size());
    }

    @Test
    void theSameMessageInAnotherDeclarationIsAnotherWarning() {
        Comparison c = matcher.compare(List.of(f("W1", 9, "m", "p.A#m()", 8)), List.of(f("R1", 9, "m", "p.A#n()", 8)));

        assertEquals(List.of("W1"), c.resolved());
        assertEquals(List.of("R1"), c.introduced());
    }

    @Test
    void messageWhitespaceAndAnnotationsInTheSourceLineAreNormalized() {
        String key1 = AnchoredWarningMatcher.key(f("W1", 5, 1, "a  b\n c", "p.A#field:x", 5, "Object x;"));
        String key2 = AnchoredWarningMatcher.key(f("R1", 5, 9, "a b c", "p.A#field:x", 5,
                "@org.jspecify.annotations.Nullable Object   x;"));

        assertEquals(key1, key2);
        assertTrue(matcher.assumptions().size() >= 3);
        assertEquals("anchored-1", matcher.id());
    }
}
