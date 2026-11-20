package generalannotator.proposals;

import static generalannotator.proposals.ProposalFixture.answer;
import static generalannotator.proposals.ProposalFixture.editJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import generalannotator.cli.ApplyProposalCommand;
import generalannotator.cli.ExitCode;
import generalannotator.cli.PrepareInferenceCommandTest;
import generalannotator.edits.AnnotationApplier;
import generalannotator.edits.AnnotationPosition;
import generalannotator.edits.EditAction;
import generalannotator.edits.javaparser.JavaParserAnnotationApplier;
import generalannotator.inference.SubmissionPreparer;
import generalannotator.report.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Importing collected proposals and applying them to copies of a fixture run's snapshot. */
class ProposalImportAndApplicationTest {

    @TempDir Path temp;
    private ProposalFixture f;
    private final Clock clock = PrepareInferenceCommandTest.CLOCK;

    static final Map<String, Object> FIELD = Map.of("kind", "FIELD");
    static final Map<String, Object> RETURN = Map.of("kind", "RETURN");

    @BeforeEach
    void setUp() throws Exception {
        f = new ProposalFixture(temp, 6);
        f.completed("W0001", answer("W0001", "PROPOSED", List.of(
                editJson("x", FIELD, "ADD"),
                editJson("x", Map.of("kind", "TYPE_USE", "typeUsePath", "field"), "ADD"), // same edit, canonicalized
                editJson("get()", RETURN, "ADD"))));
        f.failed("W0002", "STARTUP_TIMEOUT");
        f.completed("W0003", answer("W0003", "INSUFFICIENT_EVIDENCE", List.of()));
        f.completed("W0004", answer("W0004", "PROPOSED", List.of(editJson("missing", FIELD, "ADD"))));
        f.completed("W0005", answer("W0005", "PROPOSED", List.of(editJson("y", FIELD, "ADD"))));
        // W0006: no result
        f.collection();
    }

    private ProposalImporter.Result load(String id) throws Exception {
        return ProposalImporter.load(f.submission, id);
    }

    /** SHA-256 of every file under a directory, to show it is not modified. */
    private static Map<String, String> tree(Path dir) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                hashes.put(dir.relativize(p).toString(), SubmissionPreparer.sha256(Files.readAllBytes(p)));
            }
        }
        return hashes;
    }

    @Test
    void importsOnlyTheSelectedCompletedProposalAndMergesIdenticalEdits() throws Exception {
        ProposalImporter.Ready ready = assertInstanceOf(ProposalImporter.Ready.class, load("W0001"));
        assertEquals("src", ready.run().sourceRoot(), "from warning-targets.json, not the proposal");
        assertEquals(f.runDir.resolve("workspace/target"), ready.run().snapshot());
        assertEquals(2, ready.proposal().edits().size());
        var first = ready.proposal().edits().get(0);
        assertEquals(AnnotationPosition.field(), first.edit().position());
        assertEquals(EditAction.ADD, first.edit().action());
        assertEquals(List.of(0, 1), first.origins().stream().map(o -> o.editIndex()).toList(), "both origins kept");
        assertEquals("u-x", first.origins().get(0).uncertainty().orElseThrow());
        assertEquals(1, ready.proposal().source().attempt());
        assertEquals("because", ready.proposal().rationale());
        assertEquals(SubmissionPreparer.sha256(Files.readAllBytes(ready.proposal().source().answerFile())),
                ready.proposal().source().answerSha256());
    }

    @Test
    void failedMissingInsufficientAndInvalidResultsAreNeverEmptyProposals() throws Exception {
        ProposalImporter.NotApplicable failed = assertInstanceOf(ProposalImporter.NotApplicable.class, load("W0002"));
        assertEquals("FAILED", failed.status());
        assertTrue(failed.reason().contains("STARTUP_TIMEOUT"), failed.reason());
        assertEquals("COMPLETED_INSUFFICIENT_EVIDENCE",
                assertInstanceOf(ProposalImporter.NotApplicable.class, load("W0003")).status());
        assertEquals("NO_RESULT", assertInstanceOf(ProposalImporter.NotApplicable.class, load("W0006")).status());

        // An answer whose edit escapes the source root is not importable.
        Path answer = f.submission.resolve("remote/collected/results/W0005/attempt-001/answer.json");
        Map<String, Object> bad = editJson("y", FIELD, "ADD");
        bad.put("file", "../../etc/A.java");
        Files.writeString(answer, generalannotator.report.Json.write(answer("W0005", "PROPOSED", List.of(bad))));
        ProposalImporter.NotApplicable invalid = assertInstanceOf(ProposalImporter.NotApplicable.class, load("W0005"));
        assertEquals("INVALID_ANSWER", invalid.status());
        assertTrue(invalid.reason().contains("unsafe segment"), invalid.reason());
    }

    @Test
    void inconsistentArtifactsAreRejected() throws Exception {
        assertThrows(ProposalImporter.InvalidException.class, () -> load("W0099"), "not in the submission");
        assertThrows(ProposalImporter.InvalidException.class, () -> load("bad"));

        f.recordStatus("W0001", "FAILED"); // collection.json disagrees with the collected files
        f.collection();
        ProposalImporter.InvalidException stale = assertThrows(ProposalImporter.InvalidException.class,
                () -> load("W0001"));
        assertTrue(stale.getMessage().contains("collect again"), stale.getMessage());
        f.recordStatus("W0001", "COMPLETED_PROPOSED");
        f.collection();

        // A changed prompt: the submission's own hashes no longer match.
        Path prompt = f.submission.resolve("prompts/W0001.txt");
        prompt.toFile().setWritable(true);
        String original = Files.readString(prompt);
        Files.writeString(prompt, original + " ");
        assertThrows(ProposalImporter.InvalidException.class, () -> load("W0001"));
        Files.writeString(prompt, original);

        f.report(List.of("other"));
        ProposalImporter.InvalidException root = assertThrows(ProposalImporter.InvalidException.class,
                () -> load("W0001"));
        assertTrue(root.getMessage().contains("source roots"), root.getMessage());
        f.report(List.of("src"));
        assertInstanceOf(ProposalImporter.Ready.class, load("W0001"));
    }

    @Test
    void appliesToAFreshCopyAndRecordsEverythingWithoutTouchingTheInputs() throws Exception {
        Map<String, String> snapshotBefore = tree(f.runDir.resolve("workspace"));
        Map<String, String> inferenceBefore = tree(f.submission);
        ProposalImporter.Ready ready = (ProposalImporter.Ready) load("W0001");
        ProposalApplication.Applied applied = ProposalApplication.apply(ready, new JavaParserAnnotationApplier(), clock);
        assertEquals(ProposalApplication.Outcome.APPLIED, applied.outcome());
        assertEquals(f.runDir.resolve("applications/20261119T120000Z-W0001"), applied.directory());
        String edited = Files.readString(applied.directory().resolve("workspace/src/p/A.java"));
        assertTrue(edited.contains("    @org.jspecify.annotations.Nullable Object x; // x"), edited);
        assertTrue(edited.contains("@org.jspecify.annotations.Nullable Object get()"), edited);
        assertTrue(Files.readString(applied.directory().resolve("changes.diff")).contains(
                "+    @org.jspecify.annotations.Nullable Object x; // x"));

        Map<?, ?> record = (Map<?, ?>) JsonParser.parse(Files.readString(applied.directory().resolve(
                "application.json")));
        assertEquals("APPLIED", record.get("outcome"));
        assertEquals("W0001", ((Map<?, ?>) record.get("proposal")).get("warningId"));
        assertEquals(1L, ((Map<?, ?>) record.get("proposal")).get("attempt"));
        List<?> edits = (List<?>) record.get("edits");
        assertEquals(2, edits.size());
        assertEquals("APPLIED", ((Map<?, ?>) edits.get(0)).get("outcome"));
        assertEquals(2, ((List<?>) ((Map<?, ?>) edits.get(0)).get("origins")).size());
        Map<?, ?> changed = (Map<?, ?>) ((List<?>) record.get("changedFiles")).get(0);
        assertEquals("src/p/A.java", changed.get("path"));
        assertEquals(SubmissionPreparer.sha256(ProposalFixture.SOURCE.getBytes(StandardCharsets.UTF_8)),
                changed.get("beforeSha256"));
        assertEquals(SubmissionPreparer.sha256(edited.getBytes(StandardCharsets.UTF_8)), changed.get("afterSha256"));
        assertTrue(record.get("note").toString().contains("not that the warning is resolved"));

        assertEquals(snapshotBefore, tree(f.runDir.resolve("workspace")), "snapshot unchanged byte for byte");
        assertEquals(inferenceBefore, tree(f.submission), "inference artifacts unchanged");

        // Another proposal starts from the original snapshot: W0001's edits do not leak into it.
        ProposalApplication.Applied other = ProposalApplication.apply((ProposalImporter.Ready) load("W0005"),
                new JavaParserAnnotationApplier(), clock);
        String otherText = Files.readString(other.directory().resolve("workspace/src/p/A.java"));
        assertTrue(otherText.contains("@org.jspecify.annotations.Nullable Object y;"));
        assertFalse(otherText.contains("Nullable Object x"), otherText);

        // Applying W0001 again never overwrites the first application.
        ProposalApplication.Applied again = ProposalApplication.apply(ready, new JavaParserAnnotationApplier(), clock);
        assertEquals(f.runDir.resolve("applications/20261119T120000Z-W0001-2"), again.directory());
        assertEquals(edited, Files.readString(applied.directory().resolve("workspace/src/p/A.java")));
        try (Stream<Path> list = Files.list(f.runDir.resolve("applications"))) {
            assertTrue(list.noneMatch(p -> p.getFileName().toString().endsWith(".partial")));
        }
    }

    @Test
    void aRejectedProposalLeavesARecordButNoWorkspace() throws Exception {
        ProposalApplication.Applied applied = ProposalApplication.apply((ProposalImporter.Ready) load("W0004"),
                new JavaParserAnnotationApplier(), clock);
        assertEquals(ProposalApplication.Outcome.REJECTED, applied.outcome());
        assertFalse(Files.exists(applied.directory().resolve("workspace")));
        assertFalse(Files.exists(applied.directory().resolve("changes.diff")));
        Map<?, ?> record = (Map<?, ?>) JsonParser.parse(Files.readString(applied.directory().resolve(
                "application.json")));
        Map<?, ?> edit = (Map<?, ?>) ((List<?>) record.get("edits")).get(0);
        assertEquals("REJECTED", edit.get("outcome"));
        assertTrue(edit.get("detail").toString().contains("declares no field missing"));
        assertEquals(null, record.get("workspace"));
    }

    @Test
    void pipelineCodeWorksWithAFakeApplier() throws Exception {
        AnnotationApplier fake = (root, edits) -> new AnnotationApplier.ApplyResult(edits.stream()
                .map(e -> new AnnotationApplier.EditResult(e, AnnotationApplier.Outcome.NO_OP, "fake")).toList(),
                List.of(), false);
        ProposalApplication.Applied applied = ProposalApplication.apply((ProposalImporter.Ready) load("W0001"), fake,
                clock);
        assertEquals(ProposalApplication.Outcome.NO_CHANGE, applied.outcome());
        assertFalse(Files.exists(applied.directory().resolve("workspace")));
    }

    @Test
    void commandReportsOutcomesWithExitCodes() throws Exception {
        assertEquals(ExitCode.COMPLETED, command("W0001"));
        assertEquals(ExitCode.EXECUTION_FAILURE, command("W0002"), "failed inference: nothing to apply");
        assertEquals(ExitCode.EXECUTION_FAILURE, command("W0004"), "rejected");
        assertEquals(ExitCode.USAGE_OR_CONFIGURATION_ERROR, command("W0099"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("the checker was not rerun"));
    }

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private ExitCode command(String warning) {
        return new ApplyProposalCommand(f.submission.toString(), warning, temp, new JavaParserAnnotationApplier(), clock,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(out, true, StandardCharsets.UTF_8))
                .run();
    }
}
