package generalannotator.context;

/**
 * Output limits for context extraction. Items beyond a limit are omitted and the omitted count is
 * reported; nothing is dropped silently.
 *
 * @param maxItemsPerCategory evidence items kept per category
 * @param maxSnippetLines source lines kept per snippet
 * @param maxConditions enclosing conditions kept per item (nearest first)
 * @param maxLocalUses later uses of a local variable kept per item (when a value is stored in a local)
 */
public record ContextLimits(int maxItemsPerCategory, int maxSnippetLines, int maxConditions, int maxLocalUses) {

    public static final ContextLimits DEFAULT = new ContextLimits(20, 6, 4, 5);

    public ContextLimits {
        if (maxItemsPerCategory < 1 || maxSnippetLines < 1 || maxConditions < 0 || maxLocalUses < 0) {
            throw new IllegalArgumentException("invalid context limits: " + maxItemsPerCategory + ", "
                    + maxSnippetLines + ", " + maxConditions + ", " + maxLocalUses);
        }
    }
}
