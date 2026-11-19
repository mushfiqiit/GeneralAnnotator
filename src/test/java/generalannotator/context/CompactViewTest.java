package generalannotator.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.context.CompactView.Block;
import generalannotator.context.CompactView.Row;
import generalannotator.targets.MemberKind;
import generalannotator.targets.WarningTarget;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The compact view and local alias flow, on {@code q/Holder.java} (which mirrors EventBus W0001). */
class CompactViewTest {

    static final String FILE = "q/Holder.java";

    @TempDir static Path temp;

    static SourceModel model;

    @BeforeAll
    static void attribute() throws Exception {
        Path root = ContextFixture.write(temp);
        model = SourceModel.attribute(root, SourceModel.javaFiles(List.of(root.resolve("src"))), List.of(),
                OptionalInt.empty());
    }

    @AfterAll
    static void close() throws Exception {
        model.close();
    }

    static int line(String needle) {
        return ContextFixture.line(FILE, needle);
    }

    static int line(String method, String needle) {
        return ContextFixture.line(FILE, method, needle);
    }

    static WarningContext extract(ContextLimits limits, String cls, String field, String declaration) {
        WarningTarget.Resolved target = new WarningTarget.Resolved("W0001",
                "src/" + FILE + ":" + line(declaration) + ":5", "src", temp.resolve("src").toString(), FILE,
                MemberKind.FIELD, cls, cls + "#" + field, Optional.of("src"), false, List.of());
        return new ContextExtractor(model, limits, List.of()).extract(target, Optional.empty());
    }

    static WarningContext instance(ContextLimits limits) {
        return extract(limits, "q.Holder", "instance", "static volatile Holder instance;");
    }

    static WarningContext source() {
        return extract(ContextLimits.DEFAULT, "q.Aliases", "source", "String source;");
    }

    /** laterUsesOfLocal of the read at {@code line}. */
    static Map<?, ?> local(WarningContext c, int line) {
        Evidence read = c.category("fieldReads").orElseThrow().items().stream()
                .filter(e -> e.location().line() == line).findFirst().orElseThrow();
        return (Map<?, ?>) ((Map<?, ?>) read.details().get("use")).get("laterUsesOfLocal");
    }

    static Map<?, ?> use(Map<?, ?> local, int line) {
        return ((List<?>) local.get("uses")).stream().map(u -> (Map<?, ?>) u)
                .filter(u -> u.get("line").equals(line)).findFirst()
                .orElseThrow(() -> new AssertionError("no use at line " + line + " in " + local));
    }

    static Block block(WarningContext c, String member) {
        return c.compact().blocks().stream().filter(b -> b.member().equals(member)).findFirst().orElseThrow();
    }

    static Row row(Block b, int line) {
        return b.rows().stream().filter(r -> !r.gap() && r.line() == line).findFirst()
                .orElseThrow(() -> new AssertionError("line " + line + " not shown: " + b.rows()));
    }

    @Test
    void lazyInitializationKeepsBothWritesTheNullChecksAndTheReturnInShortOutput() {
        WarningContext c = instance(ContextLimits.DEFAULT);
        String text = ContextText.render(c);

        assertTrue(text.contains("Found: 2 writes, 4 reads"), text);
        assertEquals(1, count(text, "static volatile Holder instance;"), "target declaration once:\n" + text);
        assertTrue(text.contains("static volatile Holder instance;  // warning"), text);
        Block get = block(c, "q.Holder#get()");
        assertEquals(List.of("read"), row(get, line("Holder h = instance;")).notes());
        assertEquals(List.of("h holds the value from line " + line("Holder h = instance;")),
                row(get, line("if (h == null) {")).notes());
        assertEquals(List.of("read", "reassigns h"), row(get, line("h = Holder.instance;")).notes());
        assertEquals(List.of("write", "reassigns h"), row(get, line("h = Holder.instance = new Holder();")).notes());
        assertEquals(List.of("h may hold the value from line " + line("Holder h = instance;") + " or "
                + line("h = Holder.instance;") + " (reassigned at " + line("h = Holder.instance;") + ", "
                + line("h = Holder.instance = new Holder();") + " on some paths; line " + line("h = Holder.instance;")
                + " runs on some paths only)"), row(get, line("return h;")).notes());
        row(get, line("synchronized (Holder.class) {"));

        Block install = block(c, "q.Installer#install()");
        assertEquals(List.of("read"), row(install, line("if (Holder.instance != null) {")).notes());
        row(install, line("throw new IllegalStateException")); // the early-exit branch of the null check
        assertEquals(List.of("write"), row(install, line("Holder install()", "Holder.instance = new Holder();")).notes());
        assertEquals(List.of("read"), row(install, line("return Holder.instance;")).notes());

        assertFalse(text.contains("http"), text);
        assertFalse(text.contains("\n== ") || text.contains("The statement at"), "no category headings or descriptions:\n" + text);
        assertFalse(text.contains("Unresolved"), text);
        assertFalse(text.contains("Excluded"), text);
        assertFalse(text.contains("expression:") || text.contains("observations:"), text);
        String fullJson = ContextJson.write(c, "r", Map.of(), ContextLimits.DEFAULT);
        assertTrue(text.length() * 4 < fullJson.length(), text.length() + " vs " + fullJson.length());
        assertTrue(text.length() < 2200, "compact view is " + text.length() + " characters:\n" + text);
    }

    @Test
    void everyLineAppearsOnceInSourceOrderAndOverlapsAreMerged() {
        WarningContext c = instance(ContextLimits.DEFAULT);
        for (Block b : c.compact().blocks()) {
            List<Integer> lines = new ArrayList<>();
            for (Row r : b.rows()) {
                lines.add(r.line());
            }
            List<Integer> sorted = new ArrayList<>(lines);
            sorted.sort(null);
            assertEquals(sorted, lines, b.member());
            assertEquals(new HashSet<>(lines).size(), lines.size(), "duplicate rows in " + b.member());
        }
        // "if (h == null)" is a condition of three evidence items and an alias use: shown once.
        String text = ContextText.render(c);
        assertEquals(1, count(text, "        if (h == null) {"), text);
    }

    @Test
    void omittedCodeIsAnExplicitGapAndStructureLinesAreNotInvented() {
        WarningContext c = source();
        Block branchy = block(c, "q.Aliases#branchy(boolean)");
        // Lines between the read and the return are code (the reassignment), shown or elided explicitly.
        Set<Integer> shown = new HashSet<>();
        int omitted = 0;
        for (Row r : branchy.rows()) {
            if (r.gap()) {
                omitted += r.omitted();
                assertEquals("...", r.text().strip());
            } else {
                shown.add(r.line());
                assertEquals(ContextFixture.FILES.get(FILE).split("\n")[r.line() - 1].strip(), r.text().strip());
            }
        }
        int first = line("int branchy(boolean flag) {");
        int last = line("branchy", "return s.length();") + 1;
        assertEquals(last - first + 1, shown.size() + omitted, "every line is either shown or counted as omitted");
    }

    @Test
    void reassignmentOnEveryPathEndsAttribution() {
        Map<?, ?> local = local(source(), line("straight", "String s = source;"));
        assertEquals(1, local.get("total"));
        assertEquals(1, local.get("afterReassignment"));
        assertEquals("DEFINITE", use(local, line("int n = s.length();")).get("flow"));
        assertTrue(((List<?>) local.get("uses")).stream()
                .noneMatch(u -> ((Map<?, ?>) u).get("line").equals(line("return n + s.length();"))));
    }

    @Test
    void branchesAndLoopsMakeLaterUsesUncertain() {
        WarningContext c = source();
        Map<?, ?> branchy = use(local(c, line("branchy", "String s = source;")), line("branchy", "return s.length();"));
        assertEquals("MAY_BE_REASSIGNED", branchy.get("flow"));
        assertEquals(List.of(line("branchy", "s = \"other\";")), branchy.get("reassignedAt"));

        Map<?, ?> partial = use(local(c, line("assignedInBranch", "s = source;")),
                line("assignedInBranch", "return s.length();"));
        assertEquals("MAY_BE_REASSIGNED", partial.get("flow"));
        assertEquals(true, partial.get("assignedOnSomePathsOnly"));
        assertEquals(line("assignedInBranch", "String s = \"init\";"),
                local(c, line("assignedInBranch", "s = source;")).get("declaredAt"));

        Map<?, ?> loop = local(c, line("void loop()", "String s = source;"));
        for (String needle : List.of("while (s != null) {", "use(s);")) {
            Map<?, ?> u = use(loop, line("void loop()", needle));
            assertEquals("MAY_BE_REASSIGNED", u.get("flow"), needle);
            assertEquals(List.of(line("void loop()", "s = next();")), u.get("reassignedAt"), needle);
        }
        String text = ContextText.render(c);
        assertTrue(text.contains("s may hold the value from line " + line("branchy", "String s = source;")
                + " (reassigned at " + line("branchy", "s = \"other\";") + " on some paths)"), text);
    }

    @Test
    void earlyExitGuardsArePreserved() {
        WarningContext c = source();
        Map<?, ?> u = use(local(c, line("guarded", "String s = source;")), line("guarded", "return s.length();"));
        assertEquals("DEFINITE", u.get("flow"));
        Map<?, ?> guard = (Map<?, ?>) ((List<?>) u.get("nullGuards")).getFirst();
        assertEquals("EARLY_EXIT", guard.get("kind"));
        Block guarded = block(c, "q.Aliases#guarded()");
        row(guarded, line("guarded", "if (s == null) {"));
        row(guarded, line("guarded", "return 0;"));
        assertEquals(List.of("s holds the value from line " + line("guarded", "String s = source;")),
                row(guarded, line("guarded", "return s.length();")).notes());
    }

    @Test
    void evidenceIsDeduplicatedByLocationNotText() {
        WarningContext c = source();
        EvidenceCategory reads = c.category("fieldReads").orElseThrow();
        assertEquals(6, reads.total(), "six reads with identical text at different locations");
        assertEquals(6, reads.items().stream().map(Evidence::location).distinct().count());
    }

    @Test
    void annotationsTheSnippetDoesNotShowAreKeptAsNotes() {
        Block direct = block(source(), "q.Aliases#direct()");
        assertEquals(List.of("read", "param x of q.Aliases#use(String): @Nullable"),
                row(direct, line("use(source);")).notes());
        Block loop = block(source(), "q.Aliases#loop()");
        assertTrue(row(loop, line("void loop()", "String s = source;")).notes().stream()
                .noneMatch(n -> n.contains("@Nullable")), "an alias use's callee is not a note on the read line");
    }

    @Test
    void limitsKeepDistinctEvidenceFirstAndAreDeterministic() {
        ContextLimits two = new ContextLimits(2, 6, 4, 5);
        WarningContext c = instance(two);
        EvidenceCategory reads = c.category("fieldReads").orElseThrow();
        assertEquals(4, reads.total());
        List<Integer> kept = reads.items().stream().map(e -> e.location().line()).toList();
        // Source order would keep both reads in get(); distinct uses keep the stored read and the null check.
        assertEquals(List.of(line("Holder h = instance;"), line("if (Holder.instance != null) {")), kept);
        String text = ContextText.render(c);
        assertTrue(text.contains("Shown: 2 of 4 reads"), text);
        assertEquals(text, ContextText.render(instance(two)));
        assertNotEquals(text, ContextText.render(instance(ContextLimits.DEFAULT)));
    }

    @Test
    void urlsAreStrippedFromMessages() {
        assertEquals("@NonNull field 'f' not initialized",
                ContextText.stripUrls("@NonNull field 'f' not initialized\n    (see http://t.uber.com/nullaway )"));
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
