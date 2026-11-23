package generalannotator.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.evaluation.WarningRegistry.Occurrence;
import generalannotator.evaluation.WarningRegistry.Relation;
import generalannotator.evaluation.WarningRegistry.Site;
import generalannotator.graph.WarningIds;
import generalannotator.report.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The run's warning registry: one public ID names one warning; occurrences, allocation and persistence. */
class WarningRegistryTest {

    @TempDir Path temp;

    static Site site(int line, String message, String declaration) {
        return new Site("src/p/A.java", line, 9, message, declaration, null);
    }

    static Occurrence occ(String key, String id, String state, Relation relation, Site site) {
        String artifact = key.substring(0, key.indexOf('#'));
        return new Occurrence(key, id, artifact, state, key.substring(key.indexOf('#') + 1), relation, site, "test");
    }

    @Test
    void aPersistingWarningKeepsItsIdAcrossStatesAndShiftedLines() throws Exception {
        WarningRegistry r = WarningRegistry.empty(temp);
        r.register(occ(".#W0001", "W0001", "S0", Relation.FIRST, site(10, "deref 'x'", "p.A#m()")), 0, "baseline");
        // the same warning, two lines lower after an edit above it, paired by the matcher in a comparison
        r.register(occ("rounds/round1-a#R001", "W0001", "round1-a", Relation.PERSISTING, site(12, "deref 'x'",
                "p.A#m()")), 0, "combined");
        r.register(occ("rounds/round2-b#R003", "W0001", "round2-b", Relation.PERSISTING, site(13, "deref 'x'",
                "p.A#m()")), 0, "combined");
        assertEquals(3, r.occurrencesOf("W0001").size());
        assertEquals(".#W0001", r.warning("W0001").orElseThrow().first());
        // persistence needs an earlier occurrence of the ID
        assertThrows(WarningRegistry.Conflict.class, () -> r.register(occ("rounds/round2-b#R004", "W0009", "round2-b",
                Relation.PERSISTING, site(20, "deref 'y'", "p.A#n()")), 0, "combined"));
    }

    @Test
    void identicalMessagesAtDifferentSitesAreDifferentWarnings() throws Exception {
        WarningRegistry r = WarningRegistry.empty(temp);
        r.register(occ("rounds/round1-a#R001", "W1001", "round1-a", Relation.FIRST, site(30, "passing @Nullable "
                + "parameter 'cause'", "p.A#a()")), 1, "combined");
        r.register(occ("rounds/round1-a#R002", "W1002", "round1-a", Relation.FIRST, site(37, "passing @Nullable "
                + "parameter 'cause'", "p.A#a()")), 1, "combined");
        WarningRegistry.Conflict c = assertThrows(WarningRegistry.Conflict.class, () -> r.register(occ(
                "rounds/round1-a#R003", "W1001", "round1-a", Relation.FIRST, site(41, "passing @Nullable parameter "
                        + "'cause'", "p.A#a()")), 1, "combined"));
        assertTrue(c.getMessage().contains("already identifies"), c.getMessage());
    }

    @Test
    void independentAndCombinedOccurrencesKeepDistinctIdsAndOnlyCorrespond() throws Exception {
        WarningRegistry r = WarningRegistry.empty(temp);
        Site cause = site(537, "passing @Nullable parameter 'cause'", "p.Bus#handle()");
        r.register(occ("evaluations/e1/attempts/A0008#R009", "W1028", "S0+A0008", Relation.FIRST, cause), 1,
                "evaluation");
        // the W1004 collision: the combined state may not reuse the evaluation's ID for anything, not even the
        // equivalent warning (a distinct occurrence in another state)
        assertThrows(WarningRegistry.Conflict.class, () -> r.register(occ("rounds/round1-a#R007", "W1028", "round1-a",
                Relation.FIRST, cause), 1, "combined"));
        r.register(occ("rounds/round1-a#R007", "W1007", "round1-a", Relation.FIRST, cause), 1, "combined");
        r.correspond("W1007", "W1028", "the matcher pairs them; only A0008 introduced it");
        assertEquals(2, r.warnings().size(), "two occurrences, two public IDs");
        assertEquals(List.of(new WarningRegistry.Correspondence("W1007", "W1028",
                "the matcher pairs them; only A0008 introduced it")), r.correspondences());
        // one occurrence never gets a second ID
        assertThrows(WarningRegistry.Conflict.class, () -> r.register(occ("rounds/round1-a#R007", "W1029", "round1-a",
                Relation.FIRST, cause), 1, "combined"));
    }

    @Test
    void ambiguousCombinedFindingsStaySeparateWarnings() throws Exception {
        Path round = Files.createDirectories(temp.resolve("rounds/round1-a"));
        Files.writeString(round.resolve(RoundApplication.RECORD), Json.write(Map.of("status", "COMPLETED",
                "roundResultId", "round1-a", "round", 1, "baseState", "S0", "matcher", "anchored-1")));
        Files.writeString(round.resolve(RoundApplication.FINDINGS), Json.write(Map.of("findings", List.of(
                finding("W0001", "PERSISTING", "R001", 11, "deref 'x'"),
                finding("W1001", "AMBIGUOUS", "R002", 20, "deref 'x'"),
                finding("W1002", "INTRODUCED", "R003", 25, "deref 'y'")))));
        WarningRegistry r = WarningRegistry.empty(temp);
        r.register(occ(".#W0001", "W0001", "S0", Relation.FIRST, site(10, "deref 'x'", "p.A#m()")), 0, "baseline");
        r.registerRound(round);
        assertEquals(Relation.FIRST, r.occurrences().get("rounds/round1-a#R002").relation(),
                "an ambiguous match is never merged into W0001");
        assertTrue(r.occurrences().get("rounds/round1-a#R002").evidence().contains("ambiguous"));
        assertEquals(Relation.PERSISTING, r.occurrences().get("rounds/round1-a#R001").relation());
        assertEquals(List.of("W0001", "W1001", "W1002"), List.copyOf(r.warnings().keySet()));
    }

    static Map<String, Object> finding(String id, String status, String rid, int line, String message) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("warningId", id);
        f.put("status", status);
        f.put("baselineWarningId", status.equals("PERSISTING") ? id : null);
        f.put("resultId", rid);
        f.put("path", "src/p/A.java");
        f.put("anchor", Map.of("declaration", "p.A#m()", "line", line));
        f.put("diagnostic", Map.of("sourcePath", "/x/src/p/A.java", "line", line, "column", 9, "message", message));
        return f;
    }

    @Test
    void oneSharedAllocatorPerRoundResumesItsOwnIdsAndDetectsExhaustion() throws Exception {
        WarningRegistry r = WarningRegistry.empty(temp);
        Site s = site(1, "m", null);
        r.register(occ("evaluations/e1/attempts/A0001#R001", "W1001", "S0+A0001", Relation.FIRST, s), 1, "evaluation");
        r.register(occ("evaluations/e1/attempts/A0002#R001", "W1002", "S0+A0002", Relation.FIRST, s), 1, "evaluation");
        // the round's combined application continues after the evaluation's IDs
        assertEquals("W1003", r.allocator(1, temp.resolve("rounds/round1-a"), Set.of()).next());
        // an interrupted evaluation that resumes may reuse its own IDs; nothing else is taken twice
        assertFalse(r.idsOutside(temp.resolve("evaluations/e1")).contains("W1001"));
        assertTrue(r.idsOutside(temp.resolve("evaluations/e2")).contains("W1001"));
        // registering the same occurrences again (resume, repeated command) is a no-op
        r.register(occ("evaluations/e1/attempts/A0001#R001", "W1001", "S0+A0001", Relation.FIRST, s), 1, "evaluation");
        assertEquals(2, r.occurrences().size());
        // the namespace is never overrun
        WarningIds.Allocator full = new WarningIds.Allocator(1, Set.of(), WarningIds.MAX_PER_ROUND);
        assertThrows(WarningIds.ExhaustedException.class, full::next);
        assertThrows(WarningRegistry.Conflict.class, () -> r.register(occ("rounds/round1-a#R009", "W2001", "round1-a",
                Relation.FIRST, s), 1, "combined"), "a round-2 form is not a round-1 ID");
    }

    @Test
    void theRegistryIsPersistedAtomicallyAndReloadsIdentically() throws Exception {
        Files.writeString(temp.resolve("report.json"), "{}");
        WarningRegistry.update(temp, r -> {
            r.register(occ(".#W0001", "W0001", "S0", Relation.FIRST, site(10, "deref 'x'", "p.A#m()")), 0, "baseline");
            r.register(occ("rounds/round1-a#R002", "W1001", "round1-a", Relation.FIRST, site(20, "deref 'y'",
                    null)), 1, "combined");
        });
        String saved = Files.readString(temp.resolve(WarningRegistry.FILE));
        WarningRegistry loaded = WarningRegistry.load(temp);
        loaded.save();
        assertEquals(saved, Files.readString(temp.resolve(WarningRegistry.FILE)), "load + save is the identity");
        assertEquals(Map.of(1, 1), loaded.counters());
        // a failing change saves nothing
        assertThrows(WarningRegistry.Conflict.class, () -> WarningRegistry.update(temp, r -> r.register(occ(
                "rounds/round1-a#R003", "W1001", "round1-a", Relation.FIRST, site(30, "other", null)), 1, "combined")));
        assertEquals(saved, Files.readString(temp.resolve(WarningRegistry.FILE)));
    }
}
