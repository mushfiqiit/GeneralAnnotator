package generalannotator.edits;

import java.util.ArrayList;
import java.util.List;

/**
 * A minimal unified diff (as {@code diff -u}, 3 lines of context) for reviewing rewritten files. The
 * common prefix and suffix are trimmed first; the remaining middle is compared with a longest common
 * subsequence, which is fine for the small changes annotation edits make.
 */
public final class UnifiedDiff {

    private static final int CONTEXT = 3;

    private UnifiedDiff() {}

    public static String of(String path, String before, String after) {
        List<String> a = lines(before);
        List<String> b = lines(after);
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }
        // ops over the whole files: ' ' same, '-' only in a, '+' only in b
        StringBuilder ops = new StringBuilder();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < prefix; i++) {
            ops.append(' ');
            texts.add(a.get(i));
        }
        List<String> ma = a.subList(prefix, a.size() - suffix);
        List<String> mb = b.subList(prefix, b.size() - suffix);
        int[][] lcs = new int[ma.size() + 1][mb.size() + 1];
        for (int i = ma.size() - 1; i >= 0; i--) {
            for (int j = mb.size() - 1; j >= 0; j--) {
                lcs[i][j] = ma.get(i).equals(mb.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < ma.size() || j < mb.size()) {
            if (i < ma.size() && j < mb.size() && ma.get(i).equals(mb.get(j))) {
                ops.append(' ');
                texts.add(ma.get(i));
                i++;
                j++;
            } else if (i < ma.size() && (j >= mb.size() || lcs[i + 1][j] >= lcs[i][j + 1])) {
                ops.append('-'); // deletions before insertions, as diff -u prints them
                texts.add(ma.get(i++));
            } else {
                ops.append('+');
                texts.add(mb.get(j++));
            }
        }
        for (int k = a.size() - suffix; k < a.size(); k++) {
            ops.append(' ');
            texts.add(a.get(k));
        }
        StringBuilder out = new StringBuilder();
        out.append("--- a/").append(path).append('\n').append("+++ b/").append(path).append('\n');
        int n = ops.length();
        int k = 0;
        while (k < n) {
            if (ops.charAt(k) == ' ') {
                k++;
                continue;
            }
            int start = Math.max(0, k - CONTEXT);
            int end = k;
            int lastChange = k;
            while (end < n) {
                if (ops.charAt(end) != ' ') {
                    lastChange = end;
                } else if (end - lastChange > 2 * CONTEXT) {
                    break;
                }
                end++;
            }
            end = Math.min(n, lastChange + CONTEXT + 1);
            int aStart = 1;
            int bStart = 1;
            for (int x = 0; x < start; x++) {
                if (ops.charAt(x) != '+') {
                    aStart++;
                }
                if (ops.charAt(x) != '-') {
                    bStart++;
                }
            }
            int aLen = 0;
            int bLen = 0;
            StringBuilder hunk = new StringBuilder();
            for (int x = start; x < end; x++) {
                char op = ops.charAt(x);
                if (op != '+') {
                    aLen++;
                }
                if (op != '-') {
                    bLen++;
                }
                hunk.append(op).append(texts.get(x)).append('\n');
            }
            out.append("@@ -").append(aLen == 0 ? aStart - 1 : aStart).append(',').append(aLen)
                    .append(" +").append(bLen == 0 ? bStart - 1 : bStart).append(',').append(bLen).append(" @@\n")
                    .append(hunk);
            k = end;
        }
        return out.toString();
    }

    private static List<String> lines(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n|\r", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }
}
