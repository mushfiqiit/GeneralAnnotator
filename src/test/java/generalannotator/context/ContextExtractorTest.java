package generalannotator.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.targets.MemberKind;
import generalannotator.targets.WarningTarget;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextExtractorTest {

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

    static WarningTarget.Resolved target(String id, String file, MemberKind kind, String cls, String target,
            String declarationNeedle) {
        return new WarningTarget.Resolved(id, "src/" + file + ":" + ContextFixture.line(file, declarationNeedle) + ":5",
                "src", temp.resolve("src").toString(), file, kind, cls, target, Optional.of("src"), false, List.of());
    }

    static WarningContext extract(ContextLimits limits, WarningTarget target) {
        return new ContextExtractor(model, limits, List.of()).extract(target, Optional.empty());
    }

    static WarningContext extract(WarningTarget target) {
        return extract(ContextLimits.DEFAULT, target);
    }

    static List<Evidence> items(WarningContext c, String category) {
        return c.category(category).orElseThrow(() -> new AssertionError("no category " + category)).items();
    }

    /** {@code file:line} with the file relative to the fixture's source root. */
    static String where(Evidence e) {
        return e.location().file().replaceFirst("^src/", "") + ":" + e.location().line();
    }

    static List<String> locations(List<Evidence> items) {
        return items.stream().map(ContextExtractorTest::where).toList();
    }

    static Map<?, ?> map(Object o) {
        return (Map<?, ?>) o;
    }

    static WarningTarget.Resolved find() {
        return target("W0001", "p/Lib.java", MemberKind.METHOD, "p.Lib", "p.Lib#find(String)",
                "public String find(String key) {");
    }

    @Test
    void methodCallSitesAreMatchedBySymbolAcrossFilesExcludingOverloadsAndSameNameMethods() {
        WarningContext c = extract(find());
        assertEquals(ContextStatus.EXTRACTED, c.status());
        List<Evidence> calls = items(c, "callSites");
        assertEquals(List.of(
                "p/Lib.java:" + ContextFixture.line("p/Lib.java", "String v = find(key);"),
                "q/Broken.java:" + ContextFixture.line("q/Broken.java", "lib.find(unknownVariable)"),
                "q/Client.java:" + ContextFixture.line("q/Client.java", "String r = lib.find(k);"),
                "q/Client.java:" + ContextFixture.line("q/Client.java", "String local = lib.find(null);")),
                locations(calls), calls.toString());
        // lib.find(k, "d"), a.find("first", ...) (overload) and Client#find(k) are other symbols.
        assertEquals(3, c.references().get("sameNameOtherSymbols"));
        // Broken: d.find("k") has no symbol. lib.find(unknownVariable) has an erroneous argument type, but no
        // other overload takes one argument, so javac's symbol is kept and flagged.
        assertTrue(calls.get(1).observations().contains(Observation.ERRONEOUS_ARGUMENT_TYPES));
        List<Evidence> unresolved = items(c, "unresolvedReferences");
        assertEquals(List.of("q/Broken.java:" + ContextFixture.line("q/Broken.java", "d.find(\"k\")")),
                locations(unresolved), unresolved.toString());
        assertEquals("UNRESOLVED_CALL", unresolved.getFirst().kind());
        // javac reports the missing type Dep, not this call, so the cause shows as an unresolved receiver type.
        assertEquals(true, unresolved.getFirst().details().get("receiverTypeUnresolved"));
    }

    @Test
    void callSitesDescribeArgumentsAndHowTheResultIsConsumed() {
        WarningContext c = extract(find());
        Map<String, Evidence> byLine = new java.util.HashMap<>();
        items(c, "callSites").forEach(e -> byLine.put(where(e), e));

        Evidence guarded = byLine.get("q/Client.java:" + ContextFixture.line("q/Client.java", "String r = lib.find(k);"));
        assertEquals("q.Client#use(Lib, String)", guarded.enclosingMember());
        Map<?, ?> argument = map(((List<?>) guarded.details().get("arguments")).getFirst());
        assertEquals("PARAMETER", argument.get("kind"));
        assertEquals("key", argument.get("parameter"));
        Map<?, ?> use = map(guarded.details().get("resultUse"));
        assertEquals("ASSIGNED_TO_VARIABLE", use.get("kind"));
        assertEquals("LOCAL_VARIABLE", use.get("variableKind"));
        Map<?, ?> later = map(use.get("laterUsesOfLocal"));
        List<?> laterUses = (List<?>) later.get("uses");
        assertEquals("NULL_COMPARISON", map(map(laterUses.get(0)).get("use")).get("kind"));
        Map<?, ?> deref = map(laterUses.get(1));
        assertEquals("DEREFERENCE_METHOD_CALL", map(deref.get("use")).get("kind"));
        assertEquals("ENCLOSING_CONDITION", map(((List<?>) deref.get("nullGuards")).getFirst()).get("kind"));
        assertEquals(List.of("String r = lib.find(k);"), guarded.snippet().lines());

        Evidence nullArg = byLine.get("q/Client.java:"
                + ContextFixture.line("q/Client.java", "String local = lib.find(null);"));
        assertTrue(nullArg.observations().contains(Observation.NULL_ARGUMENT), nullArg.toString());
        Map<?, ?> unguarded = map(((List<?>) map(map(nullArg.details().get("resultUse")).get("laterUsesOfLocal"))
                .get("uses")).getFirst());
        assertEquals("DEREFERENCE_METHOD_CALL", map(unguarded.get("use")).get("kind"));
        assertFalse(unguarded.containsKey("nullGuards"), "local.length() is not null-checked");
        assertEquals(List.of(), nullArg.localDefinitions(), "a null literal argument has no local definition");

        Evidence internal = byLine.get("p/Lib.java:" + ContextFixture.line("p/Lib.java", "String v = find(key);"));
        assertEquals("p.Lib#find(String, String)", internal.enclosingMember());
    }

    @Test
    void methodReferencesParameterUsesAndReturnsAreCollected() {
        WarningContext c = extract(find());
        List<Evidence> refs = items(c, "methodReferences");
        assertEquals(List.of("q/Client.java:" + ContextFixture.line("q/Client.java", "a::find")), locations(refs));
        assertEquals("Function<String,String>", refs.getFirst().details().get("functionalInterface"));

        List<Evidence> params = items(c, "parameterUses");
        assertEquals(1, params.size());
        assertEquals("ARGUMENT", map(params.getFirst().details().get("use")).get("kind"));
        assertEquals("Map#get(Object)", ((String) map(params.getFirst().details().get("use")).get("callee"))
                .replaceFirst("^java\\.util\\.", ""));

        List<Evidence> returns = items(c, "returnStatements");
        assertEquals(2, returns.size());
        Evidence nullReturn = returns.getFirst();
        assertTrue(nullReturn.observations().contains(Observation.NULL_LITERAL));
        assertEquals("IF_THEN", nullReturn.conditions().getFirst().kind());
        assertEquals("(cache == null)", nullReturn.conditions().getFirst().text());
        assertEquals("METHOD_CALL", map(returns.get(1).details().get("returned")).get("kind"));
    }

    @Test
    void overloadIsDistinctTargetWithTernaryParameterFlow() {
        WarningContext c = extract(target("W0002", "p/Lib.java", MemberKind.METHOD, "p.Lib",
                "p.Lib#find(String, String)", "public String find(String key, String fallback) {"));
        assertEquals(List.of(
                "q/Client.java:" + ContextFixture.line("q/Client.java", "lib.find(k, \"d\").trim();"),
                "q/Client.java:" + ContextFixture.line("q/Client.java", "String multi = a.find(")),
                locations(items(c, "callSites")));
        Evidence fallback = items(c, "parameterUses").stream()
                .filter(e -> e.details().get("parameter").equals("fallback")).findFirst().orElseThrow();
        Map<?, ?> use = map(fallback.details().get("use"));
        assertEquals("TERNARY_BRANCH", use.get("kind"));
        assertEquals("RETURNED", map(use.get("ternaryUse")).get("kind"));
        Evidence deref = items(c, "callSites").getFirst();
        assertEquals("DEREFERENCE_METHOD_CALL", map(deref.details().get("resultUse")).get("kind"));
        assertTrue(deref.observations().contains(Observation.DEREFERENCE));
    }

    @Test
    void fieldReadsAndWritesRespectShadowingAndUnrelatedSameNameFields() {
        WarningContext c = extract(target("W0003", "p/Lib.java", MemberKind.FIELD, "p.Lib", "p.Lib#name",
                "private String name;"));
        List<Evidence> writes = items(c, "fieldWrites");
        assertEquals(List.of("p/Lib.java:" + ContextFixture.line("p/Lib.java", "this.name = name;"),
                "p/Lib.java:" + ContextFixture.line("p/Lib.java", "name = n;")), locations(writes));
        assertEquals("PARAMETER", map(writes.getFirst().details().get("assigned")).get("kind"));
        assertTrue(writes.getFirst().observations().contains(Observation.IN_CONSTRUCTOR));
        List<Evidence> reads = items(c, "fieldReads");
        assertEquals(List.of("p/Lib.java:" + ContextFixture.line("p/Lib.java", "return name;"),
                "p/Lib.java:" + ContextFixture.line("p/Lib.java", "return name.trim() + this.name.length();")),
                locations(reads));
        assertEquals("this.name", reads.get(1).expression(), "the shadowing local 'name' is not the field");
        assertEquals("DEREFERENCE_METHOD_CALL", map(reads.get(1).details().get("use")).get("kind"));
        assertEquals("RETURNED", map(reads.getFirst().details().get("use")).get("kind"));
        // Excluded: the constructor parameter, the shadowing local (twice: name.trim()), Client.name (twice).
        assertTrue((Integer) c.references().get("sameNameOtherSymbols") >= 4, c.references().toString());
        assertTrue(reads.stream().noneMatch(e -> where(e).startsWith("q/Client.java")));
        assertTrue(writes.stream().noneMatch(e -> where(e).startsWith("q/Client.java")));
        assertEquals(List.of(), items(c, "unresolvedReferences"));
        assertEquals("private String name;", c.target().get("declaration"));
    }

    @Test
    void fieldReadGuardedByEarlyExitAndFieldInitializer() {
        WarningContext cache = extract(target("W0004", "p/Lib.java", MemberKind.FIELD, "p.Lib", "p.Lib#cache",
                "private Map<String, String> cache;"));
        Evidence deref = items(cache, "fieldReads").stream()
                .filter(e -> map(e.details().get("use")).get("kind").equals("DEREFERENCE_METHOD_CALL"))
                .findFirst().orElseThrow();
        Map<?, ?> guard = map(((List<?>) deref.details().get("nullGuards")).getFirst());
        assertEquals("EARLY_EXIT", guard.get("kind"));
        assertEquals("(cache == null)", guard.get("condition"));
        assertTrue(deref.observations().contains(Observation.GUARDED_BY_NULL_CHECK));

        WarningContext shared = extract(target("W0005", "p/Lib.java", MemberKind.FIELD, "p.Lib", "p.Lib#shared",
                "static String shared"));
        Evidence init = items(shared, "fieldWrites").getFirst();
        assertEquals("INITIALIZER", init.kind());
        assertTrue(init.observations().contains(Observation.CONDITIONAL_WITH_NULL_BRANCH));
        assertTrue(init.observations().contains(Observation.IN_INITIALIZER));
    }

    @Test
    void constructorCallsIncludeThisCallsAndExcludeOtherConstructors() {
        WarningContext c = extract(target("W0006", "p/Lib.java", MemberKind.CONSTRUCTOR, "p.Lib", "p.Lib#Lib(String)",
                "public Lib(String name) {"));
        List<Evidence> calls = items(c, "callSites");
        assertEquals(List.of("THIS_CALL", "OBJECT_CREATION"), calls.stream().map(Evidence::kind).toList());
        assertTrue(calls.getFirst().observations().contains(Observation.NULL_ARGUMENT));
        assertEquals("ASSIGNED_TO_VARIABLE", map(calls.get(1).details().get("resultUse")).get("kind"));
        assertEquals(1, c.references().get("sameNameOtherSymbols"), "new Lib() calls Lib()");
        assertTrue(c.category("returnStatements").isEmpty());
    }

    @Test
    void implicitSuperCallsAnonymousClassesAndDiamondCreations() {
        WarningContext base = extract(target("W0007", "q/Hierarchy.java", MemberKind.CONSTRUCTOR, "q.Base",
                "q.Base#Base()", "Base() {"));
        List<Evidence> calls = items(base, "callSites");
        assertEquals(List.of("IMPLICIT_SUPER_CALL", "IMPLICIT_SUPER_CALL", "ANONYMOUS_CLASS_CREATION"),
                calls.stream().map(Evidence::kind).toList(), calls.toString());
        assertEquals("q.Derived#Derived(int)", calls.get(0).enclosingMember());
        assertTrue(calls.get(0).observations().contains(Observation.IMPLICIT_CALL));
        assertEquals(ContextFixture.line("q/Hierarchy.java", "new Base() {"), calls.get(2).location().line());

        WarningContext box = extract(target("W0008", "q/Hierarchy.java", MemberKind.CONSTRUCTOR, "q.Box",
                "q.Box#Box(T)", "Box(T v) {"));
        assertEquals(List.of("q/Hierarchy.java:" + ContextFixture.line("q/Hierarchy.java", "new Box<>(\"s\")")),
                locations(items(box, "callSites")));
        assertEquals("RETURNED", map(items(box, "callSites").getFirst().details().get("resultUse")).get("kind"));
    }

    @Test
    void overrideRelationsInBothDirections() {
        WarningContext base = extract(target("W0009", "q/Hierarchy.java", MemberKind.METHOD, "q.Base",
                "q.Base#value()", "String value() {"));
        List<Evidence> overrides = items(base, "overrideRelations");
        assertEquals(1, overrides.size());
        assertEquals("OVERRIDDEN_BY", overrides.getFirst().kind());
        assertEquals("q.Derived#value()", overrides.getFirst().details().get("method"));
        assertEquals(List.of("@Override"), overrides.getFirst().details().get("methodAnnotations"));

        WarningContext derived = extract(target("W0010", "q/Hierarchy.java", MemberKind.METHOD, "q.Derived",
                "q.Derived#value()", "return null;"));
        assertEquals(ContextStatus.EXTRACTED, derived.status(), derived.reason().toString());
        assertEquals(List.of("OVERRIDES"), items(derived, "overrideRelations").stream().map(Evidence::kind).toList());
        assertEquals(List.of("@Override"), derived.target().get("annotations"));
        assertTrue(items(derived, "returnStatements").getFirst().observations().contains(Observation.NULL_LITERAL));
    }

    @Test
    void missingDependenciesProduceUnresolvedReferencesNotMatches() {
        WarningContext c = extract(find());
        assertTrue(model.errors().stream().anyMatch(e -> e.file().equals("src/q/Broken.java")), model.errors().toString());
        assertTrue(items(c, "callSites").stream().noneMatch(e -> where(e).startsWith("q/Broken.java")
                && !e.observations().contains(Observation.ERRONEOUS_ARGUMENT_TYPES)));
        Evidence u = items(c, "unresolvedReferences").getFirst();
        assertEquals("javac could not resolve this call to a method", u.details().get("reason"));
    }

    @Test
    void erroneousArgumentsWithSameArityOverloadsAreUnresolvedInBothDirections() {
        for (String target : List.of("q.Overloaded#take(String)", "q.Overloaded#take(Integer)")) {
            String param = target.contains("String") ? "void take(String s) {" : "void take(Integer i) {";
            WarningContext c = extract(target("W0013", "q/Broken.java", MemberKind.METHOD, "q.Overloaded", target,
                    param));
            List<Evidence> unresolved = items(c, "unresolvedReferences");
            assertEquals(List.of("q/Broken.java:" + ContextFixture.line("q/Broken.java", "take(unknownThing);")),
                    locations(unresolved), target + ": " + unresolved);
            assertEquals("RESOLVED_DESPITE_ERROR", unresolved.getFirst().kind());
            List<String> calls = locations(items(c, "callSites"));
            assertEquals(target.contains("String")
                    ? List.of("q/Broken.java:" + ContextFixture.line("q/Broken.java", "take(\"known\");"))
                    : List.of(), calls, target);
        }
    }

    @Test
    void limitsTruncateWithOmittedCounts() {
        WarningContext c = extract(new ContextLimits(1, 2, 0, 0), find());
        EvidenceCategory calls = c.category("callSites").orElseThrow();
        assertEquals(4, calls.total());
        assertEquals(1, calls.items().size());
        assertEquals(3, calls.omitted());
        assertTrue(calls.items().getFirst().conditions().isEmpty());

        WarningContext overload = extract(new ContextLimits(5, 2, 4, 0), target("W0002", "p/Lib.java",
                MemberKind.METHOD, "p.Lib", "p.Lib#find(String, String)", "public String find(String key, String"));
        Evidence multi = items(overload, "callSites").get(1);
        assertEquals(2, multi.snippet().lines().size());
        assertEquals(2, multi.snippet().omittedLines(), multi.snippet().toString());
        Map<?, ?> resultUse = map(multi.details().get("resultUse"));
        assertEquals("ASSIGNED_TO_VARIABLE", resultUse.get("kind"));
        assertEquals(List.of(), map(resultUse.get("laterUsesOfLocal")).get("uses"), "maxLocalUses 0");
    }

    @Test
    void outputIsDeterministic() throws Exception {
        String first = ContextJson.write(extract(find()), "r", Map.of(), ContextLimits.DEFAULT);
        try (SourceModel again = SourceModel.attribute(temp,
                SourceModel.javaFiles(List.of(temp.resolve("src"))).reversed(), List.of(), OptionalInt.empty())) {
            String second = ContextJson.write(new ContextExtractor(again, ContextLimits.DEFAULT, List.of())
                    .extract(find(), Optional.empty()), "r", Map.of(), ContextLimits.DEFAULT);
            assertEquals(first, second);
        }
        assertEquals(ContextText.render(extract(find())), ContextText.render(extract(find())));
    }

    @Test
    void unresolvedTargetsAreSkippedWithWarningSiteAndMissingMembersFail() {
        WarningContext skipped = new ContextExtractor(model, ContextLimits.DEFAULT, List.of()).extract(
                new WarningTarget.Unresolved("W0011", "src/p/Lib.java:" + ContextFixture.line("p/Lib.java",
                        "this.name = name;") + ":9", "inside an initializer block", List.of()), Optional.empty());
        assertEquals(ContextStatus.SKIPPED, skipped.status());
        Evidence site = items(skipped, "warningSite").getFirst();
        assertEquals("this.name = name", site.expression());
        assertTrue(site.observations().contains(Observation.IN_CONSTRUCTOR));

        WarningContext failed = extract(target("W0012", "p/Lib.java", MemberKind.METHOD, "p.Lib", "p.Lib#gone()",
                "public class Lib"));
        assertEquals(ContextStatus.FAILED, failed.status());
        assertTrue(failed.reason().orElseThrow().contains("no METHOD p.Lib#gone()"));
        assertNotNull(failed.category("warningSite").orElseThrow());
    }
}
