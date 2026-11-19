package generalannotator.prompts;

/**
 * Size limits for one prompt unit. Java files are never truncated: a unit that exceeds a limit is
 * reported as OVERSIZED and no prompt file is written for it.
 *
 * @param maxPromptChars characters allowed for the whole prompt unit
 * @param maxFileChars characters allowed for any single slice file
 */
public record PromptLimits(int maxPromptChars, int maxFileChars) {

    public static final PromptLimits DEFAULT = new PromptLimits(100_000, 40_000);

    public PromptLimits {
        if (maxPromptChars < 1 || maxFileChars < 1) {
            throw new IllegalArgumentException("prompt limits must be positive: " + maxPromptChars + ", " + maxFileChars);
        }
    }
}
