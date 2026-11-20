package generalannotator.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Warning-node IDs by inference round. */
class WarningIdsTest {

    @Test
    void initialWarningsKeepTheirFourDigitIds() {
        assertEquals("W0001", InitialWarningGraph.warningId(1));
        assertEquals("W0042", InitialWarningGraph.warningId(42));
    }

    @Test
    void introducedIdsCarryTheRoundAndAThreeDigitSequence() {
        assertEquals("W1001", WarningIds.introduced(1, 1));
        assertEquals("W1002", WarningIds.introduced(1, 2));
        assertEquals("W1999", WarningIds.introduced(1, 999));
        assertEquals("W2001", WarningIds.introduced(2, 1));
        assertEquals("W3001", WarningIds.introduced(3, 1));
        assertEquals("W12001", WarningIds.introduced(12, 1));
        assertEquals("W123045", WarningIds.introduced(123, 45));
        assertThrows(IllegalArgumentException.class, () -> WarningIds.introduced(0, 1));
        assertThrows(WarningIds.ExhaustedException.class, () -> WarningIds.introduced(1, 1000));
    }

    @Test
    void theSequenceIsReadOnlyTogetherWithTheRecordedRound() {
        assertEquals(OptionalInt.of(1), WarningIds.sequence("W1001", 1));
        assertEquals(OptionalInt.of(1), WarningIds.sequence("W10001", 10));
        assertEquals(OptionalInt.empty(), WarningIds.sequence("W10001", 1), "round 10, not round 1");
        assertEquals(OptionalInt.empty(), WarningIds.sequence("W1001", 10));
        assertEquals(OptionalInt.empty(), WarningIds.sequence("W1000", 1), "sequence 000 is never issued");
        assertEquals(OptionalInt.empty(), WarningIds.sequence("W0001", 1));
        assertEquals(OptionalInt.empty(), WarningIds.sequence("A0001-N1", 1));
    }

    @Test
    void roundsNeverShareIdsAndNeverMeetTheFirst999InitialIds() {
        Set<String> all = new HashSet<>();
        for (int i = 1; i <= 999; i++) {
            all.add(InitialWarningGraph.warningId(i));
        }
        for (int round = 1; round <= 12; round++) {
            for (int seq = 1; seq <= WarningIds.MAX_PER_ROUND; seq++) {
                assertTrue(all.add(WarningIds.introduced(round, seq)), round + "/" + seq);
            }
        }
        assertEquals(999 + 12 * 999, all.size());
    }

    @Test
    void theAllocatorResumesAfterTheSavedSequenceDeterministically() {
        WarningIds.Allocator a = new WarningIds.Allocator(1, Set.of("W0001"), 5);
        assertEquals(List.of("W1006", "W1007"), List.of(a.next(), a.next()));
        assertEquals(7, a.lastSequence());
        WarningIds.Allocator b = new WarningIds.Allocator(1, Set.of("W0001"), 5);
        assertEquals("W1006", b.next(), "same saved state, same next ID");
    }

    @Test
    void exhaustionAndCollisionsAreExplicitAndAllocateNothing() {
        WarningIds.Allocator a = new WarningIds.Allocator(7, Set.of(), 998);
        assertEquals("W7999", a.next());
        WarningIds.ExhaustedException e = assertThrows(WarningIds.ExhaustedException.class, a::next);
        assertTrue(e.getMessage().contains("round 7 has used all 999"), e.getMessage());
        assertEquals(999, a.lastSequence());

        // more than 999 initial warnings: W1001 is already an initial ID
        WarningIds.Allocator b = new WarningIds.Allocator(1, Set.of("W1001"), 0);
        assertThrows(WarningIds.CollisionException.class, b::next);
        assertEquals(0, b.lastSequence());
    }
}
