package generalannotator.prompts;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the compact usage context ({@code context/<ID>.txt}) into a prompt section. The warning
 * paragraph and the {@code Target:} line are dropped because the prompt's WARNING section already
 * states them; the target's declaration lines, the evidence blocks and the notes are kept verbatim,
 * except for the pointer to the internal JSON file.
 */
final class ContextSection {

    private ContextSection() {}

    /**
     * @throws IllegalArgumentException if the text is not the compact context of {@code warningId}
     */
    static String body(String text, String warningId) {
        List<String> lines = new ArrayList<>(List.of(text.replace("\r\n", "\n").split("\n", -1)));
        if (lines.isEmpty() || !lines.getFirst().startsWith(warningId + "  ")) {
            throw new IllegalArgumentException("context/" + warningId + ".txt does not start with warning "
                    + warningId);
        }
        int i = 0;
        while (i < lines.size() && !lines.get(i).isBlank()) {
            i++; // the warning paragraph (ID, location, message, status)
        }
        while (i < lines.size() && lines.get(i).isBlank()) {
            i++;
        }
        List<String> out = new ArrayList<>();
        if (i < lines.size() && lines.get(i).startsWith("Target: ")) {
            String targetLine = lines.get(i);
            i++;
            if (targetLine.contains("block marked [target]")) {
                out.add("Target declaration: the block marked [target] below.");
            } else if (i < lines.size() && lines.get(i).startsWith("  ")) {
                out.add("Target declaration:");
            }
        }
        for (; i < lines.size(); i++) {
            out.add(lines.get(i).replaceAll(" Full details: \\S+\\.json\\.$", ""));
        }
        while (!out.isEmpty() && out.getLast().isBlank()) {
            out.removeLast();
        }
        return String.join("\n", out) + "\n";
    }
}
