package generalannotator.context;

import generalannotator.context.CompactView.Block;
import generalannotator.context.CompactView.Row;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the compact, LLM-facing view of a {@link WarningContext} ({@code context/<warningId>.txt}):
 * the warning and target once, then the relevant source lines grouped by enclosing member, each
 * line once in source order with merged notes and explicit {@code ...} gaps. Empty categories,
 * zero counts, category descriptions and checker documentation URLs are left out; the JSON file
 * keeps every extracted detail.
 */
public final class ContextText {

    static final Map<String, String> NOUNS = new LinkedHashMap<>();

    static {
        NOUNS.put("callSites", "call sites");
        NOUNS.put("methodReferences", "method references");
        NOUNS.put("parameterUses", "parameter uses");
        NOUNS.put("returnStatements", "returns");
        NOUNS.put("overrideRelations", "override relations");
        NOUNS.put("fieldWrites", "writes");
        NOUNS.put("fieldReads", "reads");
    }

    static final String STANDARD_NOTE = "Heuristic one-hop evidence matched by javac symbols; null guards and local "
            + "alias flow are syntactic and conservative; not a minimal or sound slice; no safe/unsafe-region "
            + "filtering. Observations are not proofs of the correct annotation.";

    private ContextText() {}

    public static String render(WarningContext c) {
        StringBuilder out = new StringBuilder();
        Map<String, Object> w = c.warning();
        out.append(c.warningId());
        if (w.get("checker") != null) {
            out.append("  ").append(w.get("checker"));
        }
        if (w.get("severity") != null) {
            out.append(' ').append(w.get("severity"));
        }
        out.append("  ").append(w.get("location")).append('\n');
        if (w.get("message") != null) {
            out.append(stripUrls(String.valueOf(w.get("message")))).append('\n');
        }
        if (c.status() != ContextStatus.EXTRACTED) {
            out.append("Status: ").append(c.status()).append(c.reason().map(r -> " (" + r + ")").orElse(""))
                    .append('\n');
        }

        CompactView view = c.compact();
        if (!c.target().isEmpty()) {
            out.append('\n').append("Target: ").append(c.target().get("memberKind")).append(' ')
                    .append(c.target().get("target"));
            if (view.targetInBlock()) {
                out.append("  (declared at ").append(c.target().get("declaredAt")).append("; block marked [target])");
            } else if (c.target().get("file") != null) {
                out.append("  (").append(c.target().get("file")).append(')');
            }
            out.append('\n');
            rows(out, view.target());
        }
        summary(out, c);

        for (Block block : view.blocks()) {
            out.append('\n').append(block.file()).append("  ").append(block.member());
            if (block.targetMember()) {
                out.append("  [target]");
            }
            out.append('\n');
            rows(out, block.rows());
        }
        binaryOverrides(out, c);
        unresolved(out, c);

        out.append("\nNotes:\n");
        for (String l : c.limitations()) {
            if (!ContextExtractor.STANDARD_LIMITATIONS.contains(l)) {
                out.append("- ").append(l).append('\n');
            }
        }
        out.append("- ").append(STANDARD_NOTE).append(" Full details: ").append(c.warningId()).append(".json.\n");
        return out.toString();
    }

    /** {@code Found: 2 writes, 4 reads} plus truncation and exclusion counts, all only when non-zero. */
    private static void summary(StringBuilder out, WarningContext c) {
        List<String> found = new ArrayList<>();
        List<String> truncated = new ArrayList<>();
        for (EvidenceCategory category : c.categories()) {
            String noun = NOUNS.get(category.name());
            if (noun == null || category.total() == 0) {
                continue;
            }
            found.add(category.total() + " " + (category.total() == 1 ? noun.substring(0, noun.length() - 1) : noun));
            if (category.omitted() > 0) {
                truncated.add(category.items().size() + " of " + category.total() + " " + noun);
            }
        }
        if (!found.isEmpty()) {
            out.append("Found: ").append(String.join(", ", found)).append('\n');
        } else if (c.status() == ContextStatus.EXTRACTED) {
            out.append("Found: no uses of the target in the analyzed sources\n");
        }
        if (!truncated.isEmpty()) {
            out.append("Shown: ").append(String.join(", ", truncated))
                    .append(" (limit GA_CONTEXT_MAX_ITEMS; distinct kinds of use are kept first)\n");
        }
        if (c.references().get("sameNameOtherSymbols") instanceof Integer n && n > 0) {
            out.append("Excluded: ").append(n).append(" same-name reference(s) to other symbols\n");
        }
    }

    private static void rows(StringBuilder out, List<Row> rows) {
        int width = 1;
        for (Row r : rows) {
            width = Math.max(width, String.valueOf(r.line()).length());
        }
        for (Row r : rows) {
            if (r.gap()) {
                out.append("  ").append(" ".repeat(width)).append("  ").append(r.text()).append("  // ")
                        .append(r.omitted()).append(r.omitted() == 1 ? " line" : " lines").append(" omitted\n");
                continue;
            }
            String number = String.valueOf(r.line());
            out.append("  ").append(" ".repeat(width - number.length())).append(number).append("  ").append(r.text());
            if (!r.notes().isEmpty()) {
                out.append("  // ").append(String.join("; ", r.notes()));
            }
            out.append('\n');
        }
    }

    private static void binaryOverrides(StringBuilder out, WarningContext c) {
        c.category("overrideRelations").ifPresent(category -> {
            List<String> lines = new ArrayList<>();
            for (Evidence e : category.items()) {
                if (e.location() == null) {
                    String annotations = e.details().get("methodAnnotations") instanceof List<?> a && !a.isEmpty()
                            ? "  " + String.join(" ", a.stream().map(String::valueOf).toList()) : "";
                    lines.add("  " + e.enclosingMember() + annotations
                            + (e.kind().equals("OVERRIDES") ? "  (overridden by the target; class file)"
                                    : "  (class file)"));
                }
            }
            if (!lines.isEmpty()) {
                out.append("\nOverride relations without source:\n");
                lines.forEach(l -> out.append(l).append('\n'));
            }
        });
    }

    private static void unresolved(StringBuilder out, WarningContext c) {
        c.category("unresolvedReferences").filter(u -> u.total() > 0).ifPresent(u -> {
            out.append("\nUnresolved same-name references (not counted as uses): ").append(u.total());
            if (u.omitted() > 0) {
                out.append(", ").append(u.items().size()).append(" shown");
            }
            out.append('\n');
            for (Evidence e : u.items()) {
                out.append("  ").append(e.location()).append("  ").append(e.expression()).append("  // ")
                        .append(e.details().get("reason")).append('\n');
            }
        });
    }

    /** Removes checker documentation links such as {@code (see http://t.uber.com/nullaway )}. */
    public static String stripUrls(String message) {
        String s = message.replaceAll("\\s*\\(see\\s+https?://[^)]*\\)", "");
        s = s.replaceAll("\\s*https?://\\S+", "");
        return s.replaceAll("\\s*\\R\\s*", " ").strip();
    }
}
