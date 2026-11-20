package generalannotator.evaluation.matching;

import generalannotator.edits.TypeText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provisional warning matching rule {@value #ID} (project-spec §6.1). It never matches by counts
 * alone, by run-local IDs, or by raw line and column.
 *
 * <ol>
 *   <li><b>Primary key.</b> Findings are grouped by: normalized project-relative path; checker name;
 *       severity; message with whitespace collapsed; the enclosing declaration's structural identity
 *       (type chain and member signature with annotations removed from parameter types); and the echoed
 *       source line with annotations, {@code final} and whitespace removed. Inserting an annotation
 *       therefore changes none of these, even on the warning's own line.
 *   <li><b>Equal multiplicity.</b> If a key has as many findings after the edit as before, all persist,
 *       paired in line and column order.
 *   <li><b>One side empty.</b> All baseline findings of the key are resolved, or all later ones are
 *       introduced.
 *   <li><b>Different non-zero multiplicities.</b> Findings are paired by their line offset within the
 *       enclosing declaration, but only if every anchor is resolved and the offsets are unique on each
 *       side; unpaired ones are then resolved or introduced. Otherwise the whole group is ambiguous and
 *       supports no claim.
 * </ol>
 */
public final class AnchoredWarningMatcher implements WarningMatcher {

    public static final String ID = "anchored-1";

    private static final List<String> ASSUMPTIONS = List.of(
            "Two findings are the same warning when path, checker, severity, normalized message, enclosing "
                    + "declaration and normalized source line are equal; line and column are not part of identity.",
            "Annotation edits insert no line breaks (the AST applier splices text inline), so a finding's line "
                    + "offset within its enclosing declaration is stable; it is used only to tell apart repeated "
                    + "findings of one key.",
            "When a key occurs equally often before and after, its findings persist even if one was resolved "
                    + "and an indistinguishable one introduced.",
            "When multiplicities differ and offsets cannot pair the findings, the group is ambiguous: no "
                    + "resolution or introduction is claimed for it.");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<String> assumptions() {
        return ASSUMPTIONS;
    }

    /** The primary matching key of a finding. */
    public static String key(ObservedFinding f) {
        return String.join("\n", f.path(), f.diagnostic().checkerName().orElse(""), f.diagnostic().severity().name(),
                f.diagnostic().message().strip().replaceAll("\\s+", " "), f.anchor().declaration(),
                TypeText.normalize(f.diagnostic().sourceSnippet().orElse("")));
    }

    @Override
    public Comparison compare(List<ObservedFinding> baseline, List<ObservedFinding> result) {
        Map<String, List<ObservedFinding>> before = group(baseline);
        Map<String, List<ObservedFinding>> after = group(result);
        List<String> keys = new ArrayList<>(before.keySet());
        after.keySet().stream().filter(k -> !before.containsKey(k)).forEach(keys::add);
        List<Pair> persisting = new ArrayList<>();
        Set<String> resolved = new HashSet<>();
        Set<String> introduced = new HashSet<>();
        List<Ambiguity> ambiguous = new ArrayList<>();
        for (String key : keys) {
            List<ObservedFinding> b = before.getOrDefault(key, List.of());
            List<ObservedFinding> a = after.getOrDefault(key, List.of());
            if (a.isEmpty()) {
                b.forEach(f -> resolved.add(f.id()));
            } else if (b.isEmpty()) {
                a.forEach(f -> introduced.add(f.id()));
            } else if (a.size() == b.size()) {
                for (int i = 0; i < b.size(); i++) {
                    persisting.add(new Pair(b.get(i).id(), a.get(i).id()));
                }
            } else if (pairable(b) && pairable(a)) {
                Map<Integer, ObservedFinding> byOffset = new LinkedHashMap<>();
                a.forEach(f -> byOffset.put(offset(f), f));
                for (ObservedFinding f : b) {
                    ObservedFinding match = byOffset.remove(offset(f));
                    if (match == null) {
                        resolved.add(f.id());
                    } else {
                        persisting.add(new Pair(f.id(), match.id()));
                    }
                }
                byOffset.values().forEach(f -> introduced.add(f.id()));
            } else {
                ambiguous.add(new Ambiguity(b.stream().map(ObservedFinding::id).toList(),
                        a.stream().map(ObservedFinding::id).toList(), b.size() + " finding(s) before and " + a.size()
                        + " after share path, checker, message, declaration " + b.get(0).anchor().declaration()
                        + " and source line, and their positions cannot tell which changed"));
            }
        }
        persisting.sort(Comparator.comparingInt(p -> indexOf(baseline, p.baselineId())));
        return new Comparison(persisting,
                baseline.stream().map(ObservedFinding::id).filter(resolved::contains).toList(),
                result.stream().map(ObservedFinding::id).filter(introduced::contains).toList(), ambiguous);
    }

    private static Map<String, List<ObservedFinding>> group(List<ObservedFinding> findings) {
        Map<String, List<ObservedFinding>> groups = new LinkedHashMap<>();
        findings.stream()
                .sorted(Comparator.comparingInt((ObservedFinding f) -> f.diagnostic().line())
                        .thenComparingInt(f -> f.diagnostic().column().orElse(0)))
                .forEach(f -> groups.computeIfAbsent(key(f), k -> new ArrayList<>()).add(f));
        // keep the groups in the order of their first finding in the input
        Map<String, List<ObservedFinding>> ordered = new LinkedHashMap<>();
        for (ObservedFinding f : findings) {
            String k = key(f);
            ordered.putIfAbsent(k, groups.get(k));
        }
        return ordered;
    }

    private static boolean pairable(List<ObservedFinding> findings) {
        Set<Integer> offsets = new HashSet<>();
        for (ObservedFinding f : findings) {
            if (!f.anchor().resolved() || !offsets.add(offset(f))) {
                return false;
            }
        }
        return true;
    }

    private static int offset(ObservedFinding f) {
        return f.diagnostic().line() - f.anchor().line();
    }

    private static int indexOf(List<ObservedFinding> findings, String id) {
        for (int i = 0; i < findings.size(); i++) {
            if (findings.get(i).id().equals(id)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }
}
