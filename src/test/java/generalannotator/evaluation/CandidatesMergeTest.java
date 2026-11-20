package generalannotator.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import generalannotator.edits.AnnotationEdit;
import generalannotator.edits.AnnotationPosition;
import generalannotator.edits.EditAction;
import generalannotator.edits.EditOrigin;
import generalannotator.edits.MemberRef;
import generalannotator.edits.Proposal;
import generalannotator.edits.TypeUsePath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Which answers share a package: exact equality of complete edit sets, nothing else. */
class CandidatesMergeTest {

    private static final Path RUN = Path.of("/runs/run1").toAbsolutePath();
    private static final String NULLABLE = "org.jspecify.annotations.Nullable";

    private static AnnotationEdit field(String name) {
        return new AnnotationEdit("src", "p/A.java", "p.A", MemberRef.parse(name), AnnotationPosition.field(),
                EditAction.ADD, NULLABLE);
    }

    private static AnnotationEdit with(String sourceRoot, String file, String declaringClass, String member,
            AnnotationPosition position, EditAction action, String annotation) {
        return new AnnotationEdit(sourceRoot, file, declaringClass, MemberRef.parse(member), position, action,
                annotation);
    }

    /** An answer for {@code warning} listing {@code edits} in this order (duplicates allowed). */
    private static Proposal answer(String warning, AnnotationEdit... edits) {
        List<EditOrigin> origins = new ArrayList<>();
        for (int i = 0; i < edits.length; i++) {
            origins.add(new EditOrigin(warning, i, Optional.of("u" + i)));
        }
        return new Proposal(new Proposal.ProposalSource("run 1", "sub", warning, 1, "prompt-" + warning,
                RUN.resolve("inference/sub/" + warning + ".json"), "answer-" + warning),
                Proposal.deduplicate(List.of(edits), origins), "rationale " + warning, "uncertainty " + warning);
    }

    private static List<Candidates.Candidate> merge(Proposal... answers) {
        return Candidates.merge(RUN, List.of(answers));
    }

    private static List<List<String>> origins(List<Candidates.Candidate> cs) {
        return cs.stream().map(Candidates.Candidate::warningIds).toList();
    }

    @Test
    void identicalEditSetsFromDifferentWarningsShareOnePackageWithEveryOrigin() {
        List<Candidates.Candidate> cs = merge(answer("W0024", field("clazz")), answer("W0025", field("other")),
                answer("W0028", field("clazz")), answer("W0030", field("clazz")), answer("W0031", field("clazz")));

        assertEquals(List.of(List.of("W0024", "W0028", "W0030", "W0031"), List.of("W0025")), origins(cs));
        Candidates.Candidate shared = cs.get(0);
        assertEquals("A0001", shared.id());
        assertEquals(List.of(field("clazz")), shared.edits());
        assertEquals("package of 1 edit(s) proposed by 4 answers (W0024, W0028, W0030, W0031)", shared.canonical());
        Candidates.Origin o = shared.origins().get(2);
        assertEquals("W0030@1", o.group());
        assertEquals("prompt-W0030", o.promptSha256());
        assertEquals("answer-W0030", o.answerSha256());
        assertEquals("rationale W0030", o.rationale());
        assertEquals("uncertainty W0030", o.uncertainty());
        assertEquals("inference/sub/W0030.json", o.answerFile());
    }

    @Test
    void editOrderAndDuplicateEntriesDoNotMatterButAreKeptAsAssociations() {
        List<Candidates.Candidate> cs = merge(answer("W0001", field("x"), field("y")),
                answer("W0002", field("y"), field("x"), field("y")));

        assertEquals(1, cs.size());
        Candidates.Candidate c = cs.get(0);
        assertEquals(List.of(field("x"), field("y")), c.edits(), "canonical order, not answer order");
        assertEquals(List.of(List.of(0), List.of(1)), c.origins().get(0).edits().stream()
                .map(Candidates.EditAssociation::answerEditIndices).toList());
        assertEquals(List.of(List.of(1), List.of(0, 2)), c.origins().get(1).edits().stream()
                .map(Candidates.EditAssociation::answerEditIndices).toList());
        assertEquals(List.of(Optional.of("u0"), Optional.of("u2")), c.origins().get(1).edits().get(1).uncertainties());
        assertEquals(3, c.origins().get(1).answerEdits());
    }

    @Test
    void overlappingAndSubsetPackagesStaySeparateAndAreNeverSplit() {
        List<Candidates.Candidate> cs = merge(answer("W0001", field("x")), answer("W0002", field("x"), field("y")),
                answer("W0003", field("y"), field("z")), answer("W0004", field("x"), field("y"), field("z")));

        assertEquals(4, cs.size());
        assertEquals(List.of(1, 2, 2, 3), cs.stream().map(c -> c.edits().size()).toList());
    }

    @Test
    void anyDifferenceInTargetPositionActionOrAnnotationKeepsPackagesSeparate() {
        AnnotationPosition field = AnnotationPosition.field();
        AnnotationEdit base = with("src", "p/A.java", "p.A", "m(String, int)", AnnotationPosition.parameter(0),
                EditAction.ADD, NULLABLE);
        List<AnnotationEdit> variants = List.of(
                base,
                with("lib/src", "p/A.java", "p.A", "m(String, int)", AnnotationPosition.parameter(0), EditAction.ADD,
                        NULLABLE),
                with("src", "q/A.java", "p.A", "m(String, int)", AnnotationPosition.parameter(0), EditAction.ADD,
                        NULLABLE),
                with("src", "p/A.java", "p.A.Inner", "m(String, int)", AnnotationPosition.parameter(0), EditAction.ADD,
                        NULLABLE),
                with("src", "p/A.java", "p.A", "m(String, long)", AnnotationPosition.parameter(0), EditAction.ADD,
                        NULLABLE),
                with("src", "p/A.java", "p.A", "m(java.lang.String, int)", AnnotationPosition.parameter(0),
                        EditAction.ADD, NULLABLE),
                with("src", "p/A.java", "p.A", "m(String, int)", AnnotationPosition.parameter(1), EditAction.ADD,
                        NULLABLE),
                with("src", "p/A.java", "p.A", "m(String, int)", AnnotationPosition.returnType(), EditAction.ADD,
                        NULLABLE),
                with("src", "p/A.java", "p.A", "m(String, int)",
                        AnnotationPosition.of(TypeUsePath.parse("parameter[0]/typeArgument[0]")), EditAction.ADD,
                        NULLABLE),
                with("src", "p/A.java", "p.A", "m(String, int)", AnnotationPosition.parameter(0), EditAction.REMOVE,
                        NULLABLE),
                with("src", "p/A.java", "p.A", "m(String, int)", AnnotationPosition.parameter(0), EditAction.ADD,
                        "javax.annotation.Nullable"),
                with("src", "p/A.java", "p.A", "f", field, EditAction.ADD, NULLABLE));
        List<Proposal> answers = new ArrayList<>();
        for (int i = 0; i < variants.size(); i++) {
            answers.add(answer(String.format("W%04d", i + 1), variants.get(i)));
        }

        List<Candidates.Candidate> cs = Candidates.merge(RUN, answers);

        assertEquals(variants.size(), cs.size(), "unresolved names (String vs java.lang.String) are not merged either");
    }

    @Test
    void onlyDifferencesTheEditModelNormalizesAreEqual() {
        // Parameter type text is normalized (whitespace, annotations, final, varargs), and a type-use path
        // without steps is its root position.
        AnnotationEdit written = with("src", "p/A.java", "p.A", "m(final java.util.List< String >, int...)",
                AnnotationPosition.of(TypeUsePath.parse("parameter[1]")), EditAction.ADD, NULLABLE);
        AnnotationEdit canonical = with("src", "p/A.java", "p.A", "m(java.util.List<String>, int[])",
                AnnotationPosition.parameter(1), EditAction.ADD, NULLABLE);

        List<Candidates.Candidate> cs = merge(answer("W0001", written), answer("W0002", canonical));

        assertEquals(1, cs.size());
        assertEquals(List.of("W0001", "W0002"), cs.get(0).warningIds());
    }

    @Test
    void idsAndKeysAreDeterministicAndKeysAreExactCanonicalText() {
        List<Candidates.Candidate> first = merge(answer("W0001", field("a")), answer("W0002", field("x"), field("y")),
                answer("W0003", field("a")), answer("W0004", field("y"), field("x")));
        List<Candidates.Candidate> again = merge(answer("W0001", field("a")), answer("W0002", field("x"), field("y")),
                answer("W0003", field("a")), answer("W0004", field("y"), field("x")));

        assertEquals(first, again);
        assertEquals(List.of("A0001", "A0002"), first.stream().map(Candidates.Candidate::id).toList());
        assertEquals(List.of(List.of("W0001", "W0003"), List.of("W0002", "W0004")), origins(first));
        Candidates.Candidate xy = first.get(1);
        assertEquals(Candidates.editKey(field("x")) + "\n--\n" + Candidates.editKey(field("y")) + "\n--\n",
                xy.canonicalKey());
        assertEquals(EvaluationFiles.sha256(xy.canonicalKey()), xy.packageKey());
        assertNotEquals(first.get(0).packageKey(), xy.packageKey());
        // A position change inside a type is part of the key.
        assertNotEquals(Candidates.editKey(with("src", "p/A.java", "p.A", "m(String)", AnnotationPosition.of(
                        TypeUsePath.parse("return/typeArgument[0]")), EditAction.ADD, NULLABLE)),
                Candidates.editKey(with("src", "p/A.java", "p.A", "m(String)", AnnotationPosition.of(
                        TypeUsePath.parse("return/typeArgument[1]")), EditAction.ADD, NULLABLE)));
    }
}
