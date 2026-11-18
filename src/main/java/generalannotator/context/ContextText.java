package generalannotator.context;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Renders a {@link WarningContext} as readable text ({@code context/<warningId>.txt}). */
public final class ContextText {

    private ContextText() {}

    public static String render(WarningContext c) {
        StringBuilder out = new StringBuilder();
        out.append("Context for ").append(c.warningId()).append(" (").append(c.status()).append(")\n");
        c.reason().ifPresent(r -> out.append("Reason: ").append(r).append('\n'));
        out.append('\n');
        out.append("Warning\n");
        c.warning().forEach((k, v) -> {
            if (v != null) {
                out.append("  ").append(k).append(": ").append(oneLine(String.valueOf(v))).append('\n');
            }
        });
        if (!c.target().isEmpty()) {
            out.append("\nTarget\n");
            c.target().forEach((k, v) -> {
                if (v instanceof Collection<?> items && !items.isEmpty() && items.iterator().next() instanceof Map) {
                    out.append("  ").append(k).append(":\n");
                    items.forEach(i -> out.append("    - ").append(inline(i)).append('\n'));
                } else if (v != null && !(v instanceof Collection<?> items && items.isEmpty())) {
                    out.append("  ").append(k).append(": ").append(inline(v)).append('\n');
                }
            });
        }
        if (!c.references().isEmpty()) {
            out.append("\nReferences: ").append(inline(c.references())).append('\n');
        }
        for (EvidenceCategory category : c.categories()) {
            out.append("\n== ").append(category.name()).append(" (").append(category.items().size()).append(" of ")
                    .append(category.total());
            if (category.omitted() > 0) {
                out.append(", ").append(category.omitted()).append(" omitted by the limit");
            }
            out.append(") ==\n");
            out.append("   ").append(category.description()).append('\n');
            int n = 1;
            for (Evidence e : category.items()) {
                item(out, n++, e);
            }
        }
        out.append("\n== limitations ==\n");
        c.limitations().forEach(l -> out.append("- ").append(l).append('\n'));
        return out.toString();
    }

    private static void item(StringBuilder out, int n, Evidence e) {
        out.append("\n[").append(n).append("] ").append(e.kind());
        out.append(" at ").append(e.location() == null ? "(class file)" : e.location().toString());
        out.append("\n    in: ").append(e.enclosingMember()).append('\n');
        if (e.expression() != null && !e.expression().isEmpty()) {
            out.append("    expression: ").append(e.expression()).append('\n');
        }
        if (!e.observations().isEmpty()) {
            out.append("    observations: ").append(String.join(", ",
                    e.observations().stream().map(Enum::name).toList())).append('\n');
        }
        e.details().forEach((k, v) -> {
            if (v instanceof List<?> items && !items.isEmpty() && items.getFirst() instanceof Map) {
                out.append("    ").append(k).append(":\n");
                items.forEach(i -> out.append("      - ").append(inline(i)).append('\n'));
            } else {
                out.append("    ").append(k).append(": ").append(inline(v)).append('\n');
            }
        });
        if (!e.conditions().isEmpty()) {
            out.append("    conditions (nearest first):\n");
            e.conditions().forEach(c -> out.append("      ").append(c.kind()).append(" ").append(c.text())
                    .append("  (line ").append(c.line()).append(")\n"));
        }
        if (e.omittedConditions() > 0) {
            out.append("      ... ").append(e.omittedConditions()).append(" more condition(s) omitted\n");
        }
        if (!e.localDefinitions().isEmpty()) {
            out.append("    local definitions:\n");
            e.localDefinitions().forEach(d -> out.append("      ").append(d.line()).append(": ").append(d.text())
                    .append('\n'));
        }
        if (e.snippet() != null) {
            int width = String.valueOf(e.snippet().endLine()).length();
            for (int i = 0; i < e.snippet().lines().size(); i++) {
                String num = String.valueOf(e.snippet().startLine() + i);
                out.append("    | ").append(" ".repeat(width - num.length())).append(num).append("  ")
                        .append(e.snippet().lines().get(i)).append('\n');
            }
            if (e.snippet().omittedLines() > 0) {
                out.append("    | (").append(e.snippet().omittedLines()).append(" line(s) of this statement omitted)\n");
            }
        }
    }

    /** Compact one-line rendering of JSON-like values. */
    static String inline(Object v) {
        return switch (v) {
            case null -> "null";
            case Map<?, ?> m -> {
                StringBuilder s = new StringBuilder("{");
                m.forEach((k, x) -> {
                    if (x != null && !(x instanceof Collection<?> c && c.isEmpty())) {
                        if (s.length() > 1) {
                            s.append(", ");
                        }
                        s.append(k).append('=').append(inline(x));
                    }
                });
                yield s.append('}').toString();
            }
            case Collection<?> c -> {
                StringBuilder s = new StringBuilder("[");
                for (Object x : c) {
                    if (s.length() > 1) {
                        s.append(", ");
                    }
                    s.append(inline(x));
                }
                yield s.append(']').toString();
            }
            default -> oneLine(String.valueOf(v));
        };
    }

    private static String oneLine(String s) {
        return s.replace("\r", "").replace("\n", " / ");
    }
}
