package generalannotator.graph;

import java.util.HashSet;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Warning-node IDs by inference round.
 *
 * <ul>
 *   <li>Initial (baseline) warnings: {@code W0001}, {@code W0002}, ... (round 0, {@link
 *       InitialWarningGraph#warningId}).
 *   <li>Warnings introduced in round {@code r} (one iteration of LLM inference and evaluation, not one
 *       annotation attempt): {@code "W" + r + three-digit sequence}, so round 1 gives {@code W1001,
 *       W1002, ...}, round 2 {@code W2001}, round 12 {@code W12001}. Every attempt of a round draws from
 *       the round's one sequence.
 * </ul>
 *
 * The ID alone does not identify the round ({@code W1001} could also be the 1001st initial warning),
 * so the round is always stored as metadata next to the ID and {@link #sequence} needs it. A round
 * holds at most {@value #MAX_PER_ROUND} introduced warnings; the next allocation fails with {@link
 * ExhaustedException} rather than spilling into another round's IDs, and an ID that already exists in
 * the graph is never reissued ({@link CollisionException}).
 */
public final class WarningIds {

    /** Name of this ID scheme, recorded in evaluation metadata. */
    public static final String SCHEME = "round-sequence-1";
    public static final int MAX_PER_ROUND = 999;

    private WarningIds() {}

    /** The ID of the {@code sequence}-th warning introduced in {@code round}. */
    public static String introduced(int round, int sequence) {
        if (round < 1) {
            throw new IllegalArgumentException("introduced warnings belong to a round >= 1, not " + round);
        }
        if (sequence < 1 || sequence > MAX_PER_ROUND) {
            throw new ExhaustedException(round);
        }
        return "W" + round + String.format("%03d", sequence);
    }

    /** The sequence number of {@code id} if it is an introduced-warning ID of {@code round}. */
    public static OptionalInt sequence(String id, int round) {
        String prefix = "W" + round;
        if (round < 1 || !id.startsWith(prefix) || id.length() != prefix.length() + 3) {
            return OptionalInt.empty();
        }
        String digits = id.substring(prefix.length());
        if (!digits.chars().allMatch(Character::isDigit)) {
            return OptionalInt.empty();
        }
        int n = Integer.parseInt(digits);
        return n >= 1 ? OptionalInt.of(n) : OptionalInt.empty();
    }

    /** Problems with allocating an ID. */
    public abstract static class AllocationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AllocationException(String message) {
            super(message);
        }
    }

    /** The round's sequence is used up. */
    public static final class ExhaustedException extends AllocationException {
        private static final long serialVersionUID = 1L;

        ExhaustedException(int round) {
            super("round " + round + " has used all " + MAX_PER_ROUND + " introduced-warning IDs (W" + round
                    + "001 to W" + round + "999); another ID would collide with a different round");
        }
    }

    /** The next ID is already taken in the graph (for example by an initial warning). */
    public static final class CollisionException extends AllocationException {
        private static final long serialVersionUID = 1L;

        CollisionException(String id, int round) {
            super("the next round-" + round + " warning ID " + id + " is already used in the graph (for example by "
                    + "an initial warning when there are more than 999 of them); refusing to reuse it");
        }
    }

    /**
     * Allocates the IDs of one round in order, resuming after {@code lastSequence}, and never returns
     * an ID in {@code taken} or one it returned before.
     */
    public static final class Allocator {
        private final int round;
        private final Set<String> taken;
        private int last;

        public Allocator(int round, Set<String> taken, int lastSequence) {
            if (round < 1) {
                throw new IllegalArgumentException("round must be >= 1: " + round);
            }
            if (lastSequence < 0 || lastSequence > MAX_PER_ROUND) {
                throw new IllegalArgumentException("lastSequence out of range: " + lastSequence);
            }
            this.round = round;
            this.taken = new HashSet<>(Objects.requireNonNull(taken, "taken"));
            this.last = lastSequence;
        }

        public String next() {
            String id = introduced(round, last + 1);
            if (!taken.add(id)) {
                throw new CollisionException(id, round);
            }
            last++;
            return id;
        }

        public int round() {
            return round;
        }

        public int lastSequence() {
            return last;
        }
    }
}
