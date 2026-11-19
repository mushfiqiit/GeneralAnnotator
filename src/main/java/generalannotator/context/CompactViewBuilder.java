package generalannotator.context;

import generalannotator.context.CompactView.Block;
import generalannotator.context.CompactView.Row;
import generalannotator.context.Evidence.Alias;
import generalannotator.context.Evidence.Label;
import generalannotator.context.Evidence.Mark;
import generalannotator.context.Evidence.Member;
import generalannotator.context.Evidence.Tag;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Builds the {@link CompactView} from the marks of the kept evidence: groups them by member,
 * merges overlapping ranges so that every source line appears once and in source order, merges
 * the notes attached to a line, and replaces omitted code with explicit gap rows. Lines made only
 * of closing brackets are shown instead of being elided, so the structure stays visible without
 * suggesting that anything is missing.
 */
final class CompactViewBuilder {

    private static final Pattern STRUCTURAL = Pattern.compile("[\\s{}()\\[\\];,]*");

    private final SourceModel model;
    private final ContextLimits limits;

    CompactViewBuilder(SourceModel model, ContextLimits limits) {
        this.model = model;
        this.limits = limits;
    }

    /**
     * @param categories evidence after truncation (unresolved references are not shown as code)
     * @param target the target member, or null when the target is unresolved
     */
    CompactView build(List<EvidenceCategory> categories, Member target) {
        Map<Member, TreeMap<Integer, List<Tag>>> members = new LinkedHashMap<>();
        for (EvidenceCategory category : categories) {
            if (category.name().equals("unresolvedReferences")) {
                continue;
            }
            for (Evidence e : category.items()) {
                for (Mark mark : e.marks()) {
                    TreeMap<Integer, List<Tag>> lines = members.computeIfAbsent(mark.member(), m -> new TreeMap<>());
                    int from = Math.max(mark.fromLine(), mark.member().startLine());
                    int to = Math.min(mark.toLine(), mark.member().endLine());
                    for (int l = from; l <= to; l++) {
                        lines.computeIfAbsent(l, x -> new ArrayList<>());
                    }
                    if (mark.tag() != null && from <= to) {
                        lines.get(from).add(mark.tag());
                    }
                }
            }
        }
        List<Row> targetRows = List.of();
        boolean targetInBlock = false;
        if (target != null) {
            int declarationEnd = declarationEnd(target);
            TreeMap<Integer, List<Tag>> own = members.get(target);
            if (own != null && own.lastKey() <= declarationEnd) {
                members.remove(target);
                for (int l = target.startLine(); l <= declarationEnd; l++) {
                    own.computeIfAbsent(l, x -> new ArrayList<>());
                }
                targetRows = rows(target, own, declarationEnd);
            } else if (own != null) {
                targetInBlock = true;
            } else {
                TreeMap<Integer, List<Tag>> lines = new TreeMap<>();
                for (int l = target.startLine(); l <= declarationEnd; l++) {
                    lines.put(l, new ArrayList<>());
                }
                targetRows = rows(target, lines, declarationEnd);
            }
        }
        List<Block> blocks = new ArrayList<>();
        List<Member> order = new ArrayList<>(members.keySet());
        order.sort(Comparator.comparing(Member::file).thenComparingInt(Member::startLine)
                .thenComparing(Member::label));
        for (Member m : order) {
            TreeMap<Integer, List<Tag>> lines = members.get(m);
            int headerEnd = Math.min(m.headerEndLine(), m.startLine() + limits.maxSnippetLines() - 1);
            for (int l = m.startLine(); l <= headerEnd; l++) {
                lines.computeIfAbsent(l, x -> new ArrayList<>());
            }
            blocks.add(new Block(m.file(), m.label(), m.equals(target), rows(m, lines, m.endLine())));
        }
        return new CompactView(targetRows, targetInBlock, blocks);
    }

    /** Last line of the target's declaration: the method header, or the whole field declaration (limited). */
    private int declarationEnd(Member target) {
        return Math.min(target.headerEndLine(), target.startLine() + limits.maxSnippetLines() - 1);
    }

    private List<Row> rows(Member member, TreeMap<Integer, List<Tag>> lines, int lastLine) {
        Optional<SourceModel.Unit> unit = model.unit(member.file());
        if (unit.isEmpty() || lines.isEmpty()) {
            return List.of();
        }
        SourceModel.Unit u = unit.get();
        List<Row> raw = new ArrayList<>();
        int previous = -1;
        for (Map.Entry<Integer, List<Tag>> entry : lines.entrySet()) {
            int line = entry.getKey();
            if (line < 1 || line > u.lineCount()) {
                continue;
            }
            if (previous > 0 && line > previous + 1) {
                gap(u, previous + 1, line - 1, raw);
            }
            raw.add(new Row(line, u.lineText(line), notes(entry.getValue()), 0));
            previous = line;
        }
        if (previous > 0 && previous < Math.min(lastLine, u.lineCount())) {
            gap(u, previous + 1, Math.min(lastLine, u.lineCount()), raw);
        }
        return dedent(raw);
    }

    /** Lines {@code from..to} that are not selected: shown when purely structural, else one gap row. */
    private static void gap(SourceModel.Unit u, int from, int to, List<Row> out) {
        boolean structural = true;
        for (int l = from; l <= to && structural; l++) {
            structural = STRUCTURAL.matcher(u.lineText(l)).matches();
        }
        if (structural) {
            for (int l = from; l <= to; l++) {
                if (!u.lineText(l).isBlank()) {
                    out.add(new Row(l, u.lineText(l), List.of(), 0));
                }
            }
        } else {
            out.add(new Row(from, "...", List.of(), to - from + 1));
        }
    }

    private static List<Row> dedent(List<Row> rows) {
        int indent = Integer.MAX_VALUE;
        for (Row r : rows) {
            if (!r.gap() && !r.text().isBlank()) {
                indent = Math.min(indent, leading(r.text()));
            }
        }
        if (indent == Integer.MAX_VALUE) {
            indent = 0;
        }
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.gap()) {
                // Indent the ellipsis like the next shown line (or the previous one at the end).
                Row neighbour = i + 1 < rows.size() ? rows.get(i + 1) : i > 0 ? rows.get(i - 1) : r;
                int at = neighbour.gap() ? indent : leading(neighbour.text());
                out.add(new Row(r.line(), " ".repeat(Math.max(0, at - indent)) + "...", List.of(), r.omitted()));
            } else {
                String text = r.text().length() >= indent ? r.text().substring(indent) : r.text().strip();
                if (text.length() > SiteAnalyzer.MAX_LINE_CHARS) {
                    text = text.substring(0, SiteAnalyzer.MAX_LINE_CHARS - 1) + "…";
                }
                out.add(new Row(r.line(), text.stripTrailing(), r.notes(), 0));
            }
        }
        return out;
    }

    private static int leading(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Merged notes of one line: labels first (deduplicated), then alias notes grouped by variable. */
    static List<String> notes(List<Tag> tags) {
        Set<String> labels = new LinkedHashSet<>();
        Map<String, TreeSet<Integer>> definite = new TreeMap<>();
        Map<String, TreeSet<Integer>> uncertain = new TreeMap<>();
        Map<String, TreeSet<Integer>> reassigned = new TreeMap<>();
        Map<String, TreeSet<Integer>> partial = new TreeMap<>();
        for (Tag t : tags) {
            switch (t) {
                case Label l -> labels.add(l.text());
                case Alias a -> {
                    if (a.definite()) {
                        definite.computeIfAbsent(a.variable(), v -> new TreeSet<>()).add(a.fromLine());
                    } else {
                        uncertain.computeIfAbsent(a.variable(), v -> new TreeSet<>()).add(a.fromLine());
                        reassigned.computeIfAbsent(a.variable(), v -> new TreeSet<>()).addAll(a.reassignedAt());
                        if (a.partial()) {
                            partial.computeIfAbsent(a.variable(), v -> new TreeSet<>()).add(a.fromLine());
                        }
                    }
                }
            }
        }
        List<String> notes = new ArrayList<>();
        labels.stream().filter(l -> !l.startsWith("reassigns ")).forEach(notes::add);
        labels.stream().filter(l -> l.startsWith("reassigns ")).forEach(notes::add);
        definite.forEach((v, from) -> notes.add(v + " holds the value from line " + join(from, " or ")));
        uncertain.forEach((v, from) -> {
            StringBuilder s = new StringBuilder(v + " may hold the value from line " + join(from, " or "));
            List<String> why = new ArrayList<>();
            if (!reassigned.get(v).isEmpty()) {
                why.add("reassigned at " + join(reassigned.get(v), ", ") + " on some paths");
            }
            if (partial.containsKey(v)) {
                why.add("line " + join(partial.get(v), ", ") + " runs on some paths only");
            }
            if (!why.isEmpty()) {
                s.append(" (").append(String.join("; ", why)).append(')');
            }
            notes.add(s.toString());
        });
        return notes;
    }

    private static String join(Set<Integer> lines, String separator) {
        return String.join(separator, lines.stream().map(String::valueOf).toList());
    }
}
