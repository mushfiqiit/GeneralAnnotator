package generalannotator.context;

import java.util.List;

/**
 * The compact, LLM-facing view of a warning's context: relevant source lines grouped by enclosing
 * member, each line shown once in source order with merged notes, and explicit gaps where code is
 * omitted. Built from the kept evidence only; it is not a minimal or sound slice.
 *
 * @param target the target declaration lines, when shown in the header rather than in a block
 * @param targetInBlock whether the target declaration appears as the header of one of the blocks
 * @param blocks one block per member, ordered by file and line
 */
public record CompactView(List<Row> target, boolean targetInBlock, List<Block> blocks) {

    public static final CompactView EMPTY = new CompactView(List.of(), false, List.of());

    public CompactView {
        target = List.copyOf(target);
        blocks = List.copyOf(blocks);
    }

    /** Rows of one member in {@code file}. */
    public record Block(String file, String member, boolean targetMember, List<Row> rows) {
        public Block {
            rows = List.copyOf(rows);
        }
    }

    /**
     * A source line ({@code omitted == 0}) or a gap of {@code omitted} lines starting at {@code line}.
     * Text is dedented within its block.
     */
    public record Row(int line, String text, List<String> notes, int omitted) {
        public Row {
            notes = List.copyOf(notes);
        }

        public boolean gap() {
            return omitted > 0;
        }
    }
}
