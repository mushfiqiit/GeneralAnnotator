# Implementation Tasks

Do the tasks in order unless a task says otherwise. Each task should be small
enough to implement and verify by itself. Read `docs/project-spec.md` before
starting any task.

Status values: `done`, `next`, `todo`, `blocked` (waiting on a decision in
project-spec §6).

Every task also has these acceptance criteria:

- `./gradlew build` succeeds.
- New behavior has tests that use deterministic fixtures. No network access
  and no dependence on machine-specific paths.
- Nothing in the code, docs, or CLI output claims behavior that is not
  implemented.
- Any provisional choice on an unresolved decision is recorded in
  project-spec §6.

---

## T0 — Bootstrap the project · `done`

Gradle Wrapper (Kotlin DSL), Java 21 toolchain, JUnit Jupiter, a placeholder
`Main`, README, AGENTS.md, this file, and the project specification.

Acceptance: `./gradlew build` passes, and `./gradlew run` prints the
"not implemented" status message.

## T1 — Parse NullAway diagnostics from captured compiler output · `done`

`diagnostics.Diagnostic` (path as reported, line, optional caret column,
severity, bracketed checker tag, full multi-line message, source snippet) and
`diagnostics.JavacOutputParser`. Non-NullAway diagnostics, location-less
messages, javac count lines, unrecognized lines and problems (missing caret,
missing/mismatched counts, javac truncation notice) are all retained.

Verified by: `JavacOutputParserTest` with fixtures in
`src/test/resources/javac-output/` (empty, single, multiple/mixed,
multi-line javac message, truncated, crash, general error, count mismatch,
CRLF, split streams).

## T2 — Classify a checker run: findings vs. execution failure · `done`

`checker.CheckerOutcome` (sealed: `CompletedAnalysis` / `ExecutionFailure`
with `FailureKind`, reasons and incomplete findings) and
`checker.nullaway.NullAwayResultClassifier`. A non-zero javac exit caused only
by NullAway errors is a completed analysis; unrelated compiler errors,
crashes, timeouts, start failures, inconsistent or unrecognized output, and a
missing probe finding are failures.

Verified by: `NullAwayResultClassifierTest`.

## T3 — External command runner · `done`

`exec.CommandRunner`, `exec.ProcessCommandRunner` (ProcessBuilder argument
lists, working directory, environment overrides, timeout with process-tree
kill, concurrent stdout/stderr draining, start failures as results),
`exec.RecordedCommand` (raw logs). Used for git, the NullAway build and javac.

Verified by: `ProcessCommandRunnerTest` (output capture, exit codes, spaces,
environment, working directory, 2 MiB on both streams, timeout, start failure).

## T4 — NullAway checker on a fixture project · `done`

`checker.Checker`, `checker.Checkers` (rejects unsupported ids),
`checker.nullaway.NullAwayChecker`: builds the local checkout's jar and
processor path via an external init script, runs javac + Error Prone with
derived or configured AnnotatedPackages and a probe file, classifies, and
reports coverage. Invocation decision recorded in project-spec §6.

Verified by: `NullAwayCheckerTest` (fake runner) and
`./gradlew integrationTest` (`NullAwayCheckerIntegrationTest`: the
`simple project` fixture yields exactly its 2 known findings; the
`broken project` fixture is `COMPILATION_FAILED`).

## T4a — `check` command with minimal isolation and report · `done`

Environment configuration (`config.ConfigLoader`), a per-run isolated copy
(`isolation.WorkingCopyCreator`, git file list or walk, symlinks skipped),
`report.json` + raw logs per run (`report.RunReport`, `report.Json`), console
summary and exit codes (`cli.CheckCommand`, `cli.ExitCode`).

Verified by: `ConfigLoaderTest`, `WorkingCopyCreatorTest` (uncommitted and
untracked content copied, ignored and deleted files not, symlink reported,
original unchanged), `CheckCommandTest`, `MainTest`, `JsonTest`,
`CheckCommandIntegrationTest`, and a real run on EventBus
(`GA_TARGET_SOURCE_ROOTS=EventBus/src`): COMPLETED, 42 NullAway errors,
original EventBus files and git metadata byte-identical before and after.

## T4b — Initial warning graph · `done`

After a completed analysis, build one root warning node (distance 0) per
finding, with no edges. IDs are `W0001`, ... in deterministic sorted order.
Save the graph as `warning-graph.json` in the run directory and print the
ID-to-warning mapping. A failed analysis writes no graph. Code:
`graph.GraphNode`, `WarningNode`, `GraphEdge`, `WarningGraph`,
`InitialWarningGraph`, `WarningGraphJson`, and `report.JsonParser`.

Verified by: `InitialWarningGraphTest` (node count, unique sequential IDs,
root and distance 0, empty edges, order independence, duplicates, empty
graph, invariants), `WarningGraphJsonTest` (save/load equality including
paths with spaces, byte-identical rewrite, empty collections, schema
validation), `JsonParserTest`, `WarningsTextTest`, `CheckCommandTest`
(graph written for completed runs including zero findings, absent for
failures), and a real EventBus run (42 nodes, W0001–W0042, 0 edges).

Not done (later tasks): graph expansion, annotation-action nodes, edge kinds,
cross-run matching, and visualization.

## T4c — Graphviz visualization of the warning graph · `done`

`visualize <warning-graph.json | run directory>` loads a saved graph with
`WarningGraphJson`, without rerunning the checker. It writes
`warning-graph.dot` beside the JSON and renders `warning-graph.svg` with
Graphviz `dot`, run through the existing `CommandRunner`. `GA_GRAPHVIZ_DOT`
overrides the executable. Nodes are labeled with their existing IDs, tooltips
show location and message, no edges are invented, edge-free graphs use a
compact osage grid in ID order, and an empty graph shows "No warnings".
Missing or failing Graphviz exits 3 and keeps the JSON and DOT. Code:
`visualization.DotExporter`, `visualization.GraphvizRenderer`, and
`cli.VisualizeCommand`.

Verified by: `DotExporterTest` (ID labels, tooltips, escaping, isolated
nodes, no invented edges, empty graph, determinism), `GraphvizRendererTest`
(success, missing executable, failing dot, no SVG output, timeout; the DOT is
kept and stale SVGs removed), `VisualizeCommandTest` (run directory and JSON
file input, override, missing Graphviz, empty graph, invalid inputs), and
`GraphvizIntegrationTest` with real Graphviz 12.1.0. That test checks SVG
tooltips decode to the exact text including backslashes and entities, the
empty-graph message, compact ID-ordered packing of 42 nodes, and the
end-to-end command. Also rendered the real EventBus graph (42 nodes, 7×6
grid).

## T4d — Specimin target members per warning · `done`

For every warning node, find the enclosing method, constructor or field
using the JDK syntax tree API on the run's snapshot. Produce Specimin's
`--root` (the package root), `--targetFile`, and `--targetMethod` or
`--targetField`. Write `warning-targets.json` (keyed by warning ID) from
`check` and from the new `targets <run dir>` command. Unsupported or
ambiguous locations are explicitly unresolved with a reason. Specimin is not
run. Code: `targets.MemberLocator`, `SpeciminSignatures`, `ParsedSource`,
`TargetExtractor`, `WarningTargetsJson`, and `cli.TargetsCommand`.

Verified by: `TargetExtractorTest`. It covers methods, overloads,
constructors including nested and varargs ones, field initializers, fields
declared together (by caret position and by message), a message naming
another field, nested classes, lambdas, anonymous and local classes, a
secondary top-level class, missing columns, initializer blocks, enum
constants, record components, compact constructors, annotation elements,
class headers, positions outside classes, and missing, unparseable or
misplaced files. Also `SpeciminCompatibilityTest`: every resolved fixture
target is one of the member strings Specimin builds with JavaParser 3.28.2
(test-only dependency), which caught the constructor-varargs rule. Then
`WarningTargetsJsonTest`, `TargetsCommandTest`, `CheckCommandTest`, and the
fixture integration test (`Greeter#lengthOf(String)`, `Greeter#name()`). Real
EventBus run: 41 of 42 resolved; the one unresolved is in a static initializer
block. All 41 match JavaParser/Specimin member strings for the same snapshot.

Not done: running Specimin, and validating that a slice reproduces the
warning (T10).

## T5 — Isolated repository states · `todo`

T4a provides one fresh copy per run. Still needed for the pipeline: multiple
identified states derived from one another (apply edits to a state without
re-copying the original), state identifiers, and cleanup. Choose and document
the mechanism (project-spec §6).

Acceptance: tests show that edits in one state are visible neither in the
original repository nor in other states, that a state can be recreated from
its identifier, and that states can be cleaned up.

## T6 — Annotation edit model and applier · `done`

`apply-proposal <submission directory> --warning <ID>` imports one collected
LLM proposal and applies its edits, all or nothing, to a fresh copy of the run
snapshot under `applications/<UTC timestamp>-<ID>/` (`application.json`,
`changes.diff`, `workspace/`).
- **Model** (`edits`): an immutable `AnnotationEdit` (source root, file,
  declaring class, field or executable with normalized parameter types,
  position FIELD/RETURN/PARAMETER/TYPE_USE with a structured `TypeUsePath`,
  ADD/REMOVE, qualified annotation), independent of checker and parser.
  `Proposal` holds the deduplicated `ProposedEdit`s, each with all its origins.
- **Import** (`proposals.ProposalImporter`): the result `collection.json`
  selects, verified with the collection's checks through
  `inference.remote.CollectedResults`. Only COMPLETED_PROPOSED is imported; the
  other outcomes are reported. The source root comes from
  `warning-targets.json` and `report.json`.
- **Applier** (`edits.AnnotationApplier`, implemented by
  `edits.javaparser.JavaParserAnnotationApplier`): structural location,
  overload and constructor matching, TYPE_USE paths, NO_OP and conflict
  handling, token-anchored text splices, and reparse verification.
- **Application** (`proposals.ProposalApplication`): a fresh snapshot copy per
  proposal (`isolation.SnapshotCopier`), a temporary directory renamed when
  complete, never overwriting, and before/after hashes.

Not done here:
- rerunning the checker and comparing warnings (T8);
- a `Proposer` interface and manual proposal files (T7);
- graph expansion;
- detecting a snapshot edited after the check (no source hashes are recorded).

Verified by:
- `JavaParserAnnotationApplierTest` covers:
  - fields, returns, parameters, constructors, nested classes and overloads;
  - raw versus generic matching, and rejection of different type arguments,
    qualified names and ambiguous method/constructor pairs;
  - qualified, nested, array, array-component, type-argument and wildcard
    positions; primitives;
  - existing, imported and on-demand-imported annotations; removal; repeated
    application;
  - conflicting nullness annotations and declaration-annotation placement;
  - missing classes, fields and methods, invalid indices, constructors, void,
    varargs and C-style arrays;
  - multi-variable fields, duplicate and conflicting edits, and a multi-file
    proposal writing nothing;
  - path traversal and symbolic links (file and directory);
  - comment preservation, and the edited sources compiling in-process against
    local stand-in annotations.
- `ProposalImportAndApplicationTest` covers import with deduplication;
  FAILED, INSUFFICIENT_EVIDENCE, NO_RESULT and unsafe answers; a stale
  collection, a changed prompt and an unconfigured source root; application
  records, unchanged snapshot and inference files, isolation between
  proposals, no overwriting, rejection without a workspace, a fake applier and
  command exit codes.
- `MainTest`.

A manual cross-check on a scratch copy of the EventBus run applied all 39
proposals. Each edited workspace compiled with `javac` and JSpecify, and the
real run directory was not modified.

## T7 — Manual proposal source · `todo`

Add a `Proposer` interface and a manual implementation that reads edits from a
human-written proposal file. Choose and document the file format.

Acceptance: valid and invalid proposal-file fixtures are tested.

## T8 — Annotation evaluation and warning-graph expansion · `done`

`evaluate-annotations <submission dir> [--warning ID]` evaluates every distinct
candidate edit from the collected answers alone against one immutable baseline,
and records the results as an expanded graph under
`runs/<run>/evaluations/<submission id>-<key>/`. The key is a hash of the
snapshot tree, the checker configuration fingerprint, the candidate set and the
matcher.

- **Candidates.** Validated COMPLETED_PROPOSED answers are imported through
  `ProposalImporter`. Identical edits are merged by source root, file, class,
  member, exact position, action and annotation, keeping every association
  (warning, proposal group, answer index, uncertainty). Groups keep rationale,
  uncertainty and run, submission, attempt, prompt and answer provenance.
  Failed, missing, invalid and insufficient-evidence answers are listed as
  excluded.
- **Baseline.** An explicit file copy of the run snapshot (no git, so ignored
  `runs/` directories cannot hide sources) is checked with the `check`
  environment settings and the run's source roots. A failed baseline blocks the
  evaluation and is kept in `baseline-failed/`.
- **Attempts.** For each candidate: a fresh copy of `baseline/source`, exactly
  one edit through the AST applier, and the checker with identical settings.
  Outcomes are COMPLETED, AMBIGUOUS, NO_OP, REJECTED (including suppressing
  edits), COMPILATION_FAILED, TIMED_OUT, CRASHED, EXECUTION_FAILED and
  NOT_COMPARABLE (different checker build or annotated packages).
- **Matching.** Through the `WarningMatcher` interface. The provisional rule
  `anchored-1` uses normalized path, checker, severity, message, structural
  enclosing declaration and annotation-free source line, with offsets inside
  the declaration for repeated findings. Undecidable groups are ambiguous.
- **Graph.** Baseline warning nodes (state `S0`), one annotation node per
  candidate with its outcome and proposal associations, `W -> A` resolved-by
  edges for every resolved baseline warning, and new nodes `A-N<k>` (state
  `S0+A`, distance 1) with `A -> N` introduces edges. The graph is saved as
  `evaluation-graph.json` (`warning-graph.json` schemaVersion 2), and
  `visualize` distinguishes the node kinds. (T8a replaced `A-N<k>` with round
  IDs and schema version 3; T8b replaced single-edit candidates with one
  package per answer and schema version 4.)
- **Persistence.** Atomic writes and an OS file lock. Completed attempts are
  reused; incomplete ones are moved to `attempts/interrupted/` and rerun. The
  graph and `summary.json` are rebuilt from the records. The original
  `warning-graph.json`, run snapshot and inference artifacts are only read.

Code:
- `evaluation`: `Candidates`, `EvaluationConfig`, `Evaluator`, `EvaluationGraph`,
  `EvaluationFiles`, `ToolIdentity`, `AttemptOutcome`, `FindingJson`.
- `evaluation.matching`: `WarningMatcher`, `AnchoredWarningMatcher`,
  `DeclarationAnchors`, `ObservedFinding`.
- `graph`: `AnnotationNode`, `WarningNode.state`, `GraphEdge` kinds and
  `WarningGraphJson` v2.
- Also `DotExporter`, `VisualizeCommand`, `ConfigLoader.loadForSnapshot`,
  `CollectedResults.warningIds` and `cli.EvaluateAnnotationsCommand`.

Not done:
- cumulative annotation sequences;
- inferring fixes for introduced warnings;
- an append-only state-transition log (T9);
- a real-NullAway integration test for this command (the EventBus cross-check
  below was manual).

Verified by:
- `EvaluateAnnotationsTest` (fake checker, real AST applier) covers:
  - one and many resolutions with zero, one and many introduced warnings;
  - no change, unchanged totals with changed findings, and ambiguity;
  - a shifted column on the edited line, and repeated similar diagnostics;
  - deduplication, multi-edit groups, and actual versus proposed associations;
  - no-op, rejected and suppressing edits, execution failures, a failed and
    retried baseline, and a different checker build;
  - interruption and resume, reuse without duplicates, and a held lock;
  - the warning filter keeping the full checker scope, and a parent git
    repository with an ignored `runs/`;
  - unchanged originals, a new evaluation for another classpath, mapping to
    the run's warning IDs, and graph round trip and DOT output.
- `AnchoredWarningMatcherTest` covers shifted lines and columns, offsets,
  multiplicity, ambiguity, unresolved anchors and normalization.
- `DeclarationAnchorsTest` covers fields, initializers, constructors,
  generics and varargs, anonymous, nested and enum-constant classes, stability
  under inserted annotations, and unparsable or outside files.
- `WarningGraphJsonTest` covers v2 round trips, unchanged v1 output and strict
  rejection.
- `MainTest`.

On a scratch copy of the EventBus run (NullAway 0.14.2-SNAPSHOT, JSpecify 1.0.0
on the classpath):
- the baseline reproduced all 42 run findings;
- 30 candidates came from 39 answers (W0008 and W0011 excluded as FAILED);
- all 30 attempts COMPLETED: 15 reduced the count, 12 kept it with one
  resolved and one introduced warning, 2 increased it, and 1 changed nothing;
- a fresh run took about 75 s and was reproducible (same evaluation ID,
  byte-identical graph); a rerun reused everything. One earlier run had an
  unexplained 6-minute idle pause in one attempt, outside the checker
  commands, which did not recur;
- the real run directory was not touched.

## T8a — Warning IDs by inference round · `done`

Introduced warnings are named by the inference round that introduced them,
not by attempt.
- Initial warnings keep `W0001...`. Round 1 introduces `W1001, W1002, ...`,
  round 2 `W2001...`, round 12 `W12001...`.
- **Explicit rounds.** `evaluation.json`, `attempt.json`, `summary.json` and
  every graph node (`round`, 0 for initial warnings) store the round. The
  graph is schema version 3.
- **Allocation.** `WarningIds.Allocator` allocates in recording order,
  resuming after the highest ID in the completed attempt records. It refuses
  overflow past 999 and collisions with existing IDs.
- **Who gets IDs.** Persisting warnings keep their baseline IDs. Only
  introduced occurrences get new IDs, and `comparison.json` maps each to its
  result finding (`introducedWarnings`).
- **Rounds produced today.** `evaluate-annotations` evaluates round 1, and an
  evaluation recording another round is refused.
- **Migration.** Older evaluations are refused until
  `migrate-warning-ids <evaluation dir>` converts them. The migration:
  - backs up every changed file, then renames in candidate order;
  - updates records, comparisons, summary, graph and DOT, and removes the
    stale SVG;
  - establishes round 1 only when every candidate was proposed for an initial
    warning of the run, and otherwise refuses;
  - completes an interrupted run from its marker.

Code:
- `graph`: `WarningIds`, plus the `round` component of `WarningNode` and
  `AnnotationNode`, and `WarningGraphJson` v3 (v2 still read).
- `evaluation`: `Evaluator` (round, `RECORD_SCHEMA_VERSION` 2, allocation,
  refusals), `EvaluationGraph` and `WarningIdMigration`.
- Also `cli.MigrateWarningIdsCommand`, `EvaluateAnnotationsCommand`,
  `DotExporter` tooltips and `Main`.

Verified by:
- `WarningIdsTest` covers formats, multi-digit rounds, reading the sequence
  only together with the round, disjoint rounds, resume, overflow and
  collisions.
- `WarningIdRoundsTest` (fake checker) covers:
  - several attempts sharing one round with consistent edges, records,
    comparisons, summary and rounds;
  - persisting warnings keeping their IDs;
  - filtered-then-full resume without renumbering, determinism of fresh runs,
    and no duplicate after an interrupted attempt;
  - rounds 2 and 12, refusal of a different round, and overflow at the 1000th
    warning;
  - migration: equal to a fresh graph, backup contents, refusal before
    migration, resume without checker runs, idempotence, an unestablishable
    round, a recorded round, a disagreeing graph, and completion of an
    interrupted migration.
- `WarningGraphJsonTest` covers v3 and legacy v2.
- `EvaluateAnnotationsTest` and `MainTest`.

On a scratch copy of the earlier EventBus evaluation:
- `evaluate-annotations` refused the legacy directory;
- `migrate-warning-ids` renamed the 23 introduced warnings to `W1001`–`W1023`,
  with a backup;
- the evaluation then resumed with all 30 attempts reused, no checker run, and
  a byte-identical graph;
- a second migration reported the directory as current.

## T8b — One annotation package per proposal · `done`

The complete `edits` array of one validated answer is now one candidate, an
**annotation package**. T8 had split answers into distinct edits, so W0037's
three `@Nullable` fields became A0024–A0026 and were evaluated separately.
- **Package contents.** A package keeps its warning, answer provenance,
  rationale, uncertainty and every edit. Identical entries within the answer
  are merged, keeping their indices and uncertainties. Answers about different
  warnings stayed separate packages even when they shared edits (replaced by
  T8c: identical edit sets now share one package).
- **Applying and checking.** Each package is applied all or nothing in one
  fresh copy of the baseline, and NullAway runs once:
  - any rejected, conflicting or suppressing edit rejects the package and
    keeps no workspace;
  - a mix of NO_OP and applied edits is applied and checked;
  - a package whose edits are all NO_OP is reported without a check.
- **Graph.** One annotation node per package holds all its edits with their
  applier outcomes. Edges come from the package's own comparison (`W -> A`,
  `A -> N`). The graph is schema version 4; versions 2 and 3 are still read as
  single-edit nodes.
- **Resume identity.** The evaluation key adds the candidate model
  (`answer-package-1`) and the round; each attempt records its package key
  (answer plus complete edit set). Earlier single-edit evaluations are
  therefore never reused. They are listed as "kept, not reused" and left
  untouched, and `migrate-warning-ids` keeps their single-edit meaning.
- **Output.** CLI output, `summary.json`, tooltips and labels list every
  package's edits. `changes.diff` covers every changed file.

Code:
- `evaluation`: `Candidates` (packages), `Evaluator` (package attempts, key
  version 2, record version 3, older evaluations noted), `EvaluationGraph` and
  `WarningIdMigration` (edit-level record version 2).
- `graph`: `AnnotationNode` (`edits`, `PackageEdit`, optional
  `answerEditIndex`) and `WarningGraphJson` v4.
- Also `DotExporter` and `cli.EvaluateAnnotationsCommand`.

Verified by:
- `PackageEvaluationTest` covers:
  - a W0037-like three-field package as one candidate, one workspace, one
    checker call and one node with resolution and introduction edges;
  - resume;
  - a partial package that narrows the message;
  - multi-file packages and atomic rejection;
  - conflicts, duplicates, and mixed or all-NO_OP packages.
- `EvaluateAnnotationsTest` covers shared edits in separate packages, package
  edges and workspace isolation, plus the earlier scenarios.
- `WarningIdMigrationTest` (hand-built single-edit evaluation) covers the
  migration cases, and a package evaluation that leaves the single-edit
  directory untouched and evaluates afresh.
- `WarningGraphJsonTest` covers v4 packages and reading v2/v3.
- `WarningIdRoundsTest` covers a different round getting its own directory.

On a scratch copy of the EventBus run:
- 39 packages (42 edits) were all COMPLETED in about 100 s;
- W0037's package (A0034) resolved W0037 and introduced nothing;
- 26 packages reduced the count, 8 kept it with matched changes, and 5
  increased it;
- the earlier single-edit evaluation directory stayed byte-identical.

## T8c — Equivalent annotation packages share one evaluation · `done`

T8b made one package per answer, so answers about different warnings that
proposed exactly the same edit set were evaluated separately. On EventBus,
A0022, A0026, A0028 and A0029 all add `@Nullable` to
`SubscriberMethodFinder.FindState.clazz`. Each was checked, and each added
its own copy of the same five introduced warnings. They now share one package.
- **Equivalence.** After import, answers are grouped by their complete edit
  set, compared as sets of `AnnotationEdit` values. Equality covers source
  root, source-relative file, declaring class, member signature, exact
  position (parameter index, type-use path), action and annotation name.
  - Edit order and repeated entries are ignored.
  - Rationale, uncertainty, warning and provenance are excluded.
  - Only what the edit model already normalizes is equal; names are not
    resolved.
  - Overlapping and subset packages stay separate; packages are never split.
- **Provenance.** Each package keeps every originating answer as an origin:
  warning, answer, attempt, prompt hash, answer file and hash, rationale,
  uncertainty, and per-edit answer indices and uncertainties.
  - `--warning` selects a package if any origin matches, and keeps all its
    origins.
  - The CLI and `summary.json` report imported proposals and unique packages.
- **Identity.** Edits are kept in canonical order (`Candidates.editKey`). IDs
  follow each package's first warning. The canonical key is the exact edit
  text; the package key is only its hash. Resume requires the recorded edit
  set to be equal field by field, not only the hash.
  - The evaluation key moves to model `equivalent-package-1` (key version 3,
    record version 4), so results are never shared across baselines,
    configurations or rounds.
- **Graph.** One annotation node per unique package lists every answer as a
  proposal, with per-edit answer indices. Proposals are associations, not
  edges.
  - Edges are deduplicated, and introduced warnings exist once per package.
  - Graph schema version 5; versions 2–4 are read (v4 indices move to the
    proposal).
- **Rebuild of earlier evaluations (`evaluate-annotations --reuse-from`).**
  - **Refusal.** Nothing is reused unless the earlier `answer-package-1`
    evaluation has the same run, submission, snapshot hash, configuration
    fingerprint, matcher and round. The new baseline's findings and checker
    identity must also equal the earlier ones.
  - **Mapping.** Earlier package IDs are mapped to merged IDs by exact edit
    set and answer hash (`reuse/<earlier id>.json`).
  - **Import.** A merged package imports a checker result only if every
    complete earlier attempt of it has an equivalent result. Paths inside
    each attempt's own directory are ignored when comparing.
  - **Rewriting.** Workspace, diff and logs are copied. Introduced warnings
    get fresh round IDs, and the old-to-new mapping is recorded in
    `importedFrom`.
  - **Conflicts** are reported and the package is evaluated again; results are
    never chosen or combined.
  - **Rejected and no-op results** are evaluated again (no checker run).
  - **Read only.** The earlier directory is only read.

Code:
- `evaluation`:
  - `Candidates`: `merge`, `Candidate`, `Origin`, `EditAssociation`,
    `canonicalKey`, `sameEditSet`.
  - `Evaluator`: key version 3, record version 4, origins in records,
    edit-set check on resume, `imported`, reuse report.
  - `PackageReuse` (new) and `EvaluationGraph` (proposals per origin, edge
    deduplication).
- `graph`: `AnnotationNode` (`Proposal.answerEditIndices` per edit,
  `PackageEdit` without indices) and `WarningGraphJson` v5.
- Also `DotExporter`, `cli.EvaluateAnnotationsCommand` and `Main`
  (`--reuse-from`, options in any order).

Verified by:
- `CandidatesMergeTest` covers:
  - four answers becoming one package with every origin;
  - reordered and repeated edits;
  - overlap and subset;
  - every identity dimension (source root, file, class, member and
    parameter types, parameter index, return, type-use path, action,
    annotation);
  - model normalization only;
  - deterministic IDs and exact canonical keys.
- `EquivalentPackagesTest` (fake checker, real applier) covers:
  - four `clazz`-like answers giving one package, one checker run, one node
    with four proposals and one set of five introduced warnings, with no
    duplicate edges;
  - provenance in records;
  - warning filter;
  - reordered and repeated edits;
  - overlapping packages;
  - isolation by baseline, configuration and round;
  - resume;
  - a record with another edit set never reused;
  - `--reuse-from` from a synthesized one-package-per-answer evaluation:
    import with a graph byte-identical to a fresh evaluation and the old
    directory unchanged, a conflicting result evaluated again, and refusals
    for round, configuration, single-edit evaluations, baseline findings and
    candidate edit sets.
- Updates in `EvaluateAnnotationsTest`, `PackageEvaluationTest`,
  `WarningGraphJsonTest` (v5 shared packages, reading v4), `WarningIdMigrationTest`
  and `MainTest`.

On a scratch copy of the EventBus run:
- the 39 answers gave 27 unique packages (30 edits); 8 packages are shared by
  20 answers;
- the `clazz` package (A0017) has 4 proposals, resolves W0024, W0028, W0030
  and W0031, and introduces 5 warnings once; there are 19 introduced warning
  nodes instead of 38;
- `--reuse-from` on the T8b evaluation verified the baseline and imported all
  27 results in about 7 s;
- a fresh evaluation (27 checker runs, about 70 s) wrote a byte-identical
  graph, and every `changes.diff` matched;
- the earlier evaluation directory stayed byte-identical.

## T8d — Apply a whole round and recheck once · `done`

`apply-round <submission> --round 1` applies **all** validated packages of
round 1 together to one isolated copy of the run snapshot, runs NullAway once
on that combined state, and saves every finding.
- **Packages and union.** Packages and provenance come from `Candidates` (T8c
  merging). Excluded answers are reported, so complete coverage is never
  claimed. All edits form a union in canonical order: shared edits are
  applied once and list every package and answer, multi-edit packages keep all
  edits, and no package is selected by its individual result.
- **Validation.** The AST applier validates the whole union against the
  snapshot's declarations before writing anything:
  - already-present annotations are NO_OP;
  - ADD/REMOVE pairs, competing nullness annotations at one position,
    missing or ambiguous targets, unsupported placements and suppressing
    edits are rejected;
  - one rejection rejects the round (status REJECTED, conflicts with packages
    and answers, no workspace published, no checker run).
- **Baseline.** An evaluation baseline is reused only if snapshot hash,
  configuration fingerprint and matcher are equal and its source still has
  its recorded hash; otherwise a fresh baseline is checked.
- **Check and comparison.** One checker run on the combined workspace with
  the baseline's settings; the analyzed scope and the unchecked modules are
  reported. All findings are saved:
  - matcher categories: persisting findings keep baseline IDs; introduced and
    ambiguous ones get round-1 IDs scoped to the result;
  - execution failures are kept as INCOMPLETE with logs and no comparison;
  - a different checker build is NOT_COMPARABLE.
- **Artifacts.** `runs/<run>/rounds/round1-<key>/` holds `round.json`,
  `candidates.json`, `workspace/target/`, `changes.diff` (with per-file
  hashes), `check/`, `findings.json`, `warnings.txt` and `comparison.json`.
  - Completed results also get `report.json` and a v1 `warning-graph.json`,
    so `targets`, `slice` and `context` accept the directory as the next
    round's run.
  - Results are built in a temporary directory and published by an atomic
    rename, never overwritten. Completed, rejected and not-comparable results
    are reused by key, a modified workspace is refused, and failed attempts
    are kept as `-failed-<time>` and retried.
- **Not done.** Nothing is attributed to annotation nodes, and evaluation
  graphs are unchanged.

Code:
- `evaluation.RoundApplication` (new);
- `cli.ApplyRoundCommand` (new);
- `Main` (dispatch and usage);
- package-visible helpers in `Evaluator`.

Verified by:
- `RoundApplicationTest` (fake checker, real applier) covers:
  - several packages in one workspace and one post-edit check, with shared
    edits applied once and multi-edit packages kept;
  - interacting edits whose combined result differs from the independent
    evaluation, with the evaluation baseline reused and left unchanged;
  - complete findings with persisting and introduced categories, IDs and
    published paths;
  - ambiguous findings;
  - ADD/REMOVE conflicts and missing members rejecting the round atomically,
    and suppressing edits as unsupported;
  - NO_OPs;
  - an ignored parent git directory, with the snapshot and graph unchanged;
  - a failed combined check (kept, incomplete, retried) and a failed baseline;
  - reuse, another configuration, and a modified workspace refused;
  - a different checker build;
  - `targets` accepting the result as a run;
  - round 2 refused.
- `MainTest` covers dispatch.
- No real-NullAway test was added; the real run below was manual.

On a scratch copy of the EventBus run (NullAway 0.14.2-SNAPSHOT, JSpecify on
the classpath):
- all 27 packages (30 distinct edits from 39 answers) applied without
  conflicts;
- the evaluation baseline was verified and reused, and one NullAway run took
  about 5 s;
- 42 findings became 19: 40 resolved, 17 introduced, 2 persisting (W0008 and
  W0011, which had no answer). The independent evaluations had 19 introduced
  warnings in total;
- a second invocation reused the result;
- `targets` accepted the result directory;
- the snapshot, inference results and evaluations stayed byte-identical.

## T9 — Record attempts and state transitions · `todo`

Persist warning/action/resolution records and state transitions in a
documented, append-only format. (T8 writes per-attempt records and a graph
rebuilt from them; an append-only transition log across states is still open.)

Acceptance: round-trip tests, and an output schema documented in `docs/`.

## T10a — Specimin slice generation · `done`

`slice <run directory> [--warning ID]` runs the local Specimin
(`GA_SPECIMIN_HOME`, built with its own `./gradlew installDist`) once per
warning in `warning-targets.json`. Each run gets a disposable copy of the
target's `isolatedSourceRoot`, the NullAway modularity model, an optional
explicit `--jarPath` (`GA_SPECIMIN_JAR_PATH`), and `--disable-root-validation`
when required. Output goes to a fresh `slices/<ID>/`, and
`slices/slice-results.json` records GENERATED / FAILED / SKIPPED with
reproduction NOT_VALIDATED. Earlier results are never overwritten. Code:
`slicing.Slicer`, `SpeciminDistribution`, `SpeciminSlicer`,
`SliceResultsJson`, `cli.SliceCommand`, and `WarningTargetsJson.load`.

Verified by: `SpeciminSlicerTest` (exact argument lists for methods,
constructors and fields, generics and spaces, jar path, root validation,
disposable copy, non-zero exit, timeout, start failure, empty output, missing
snapshot root), `SpeciminDistributionTest`, `SliceCommandTest` (all
warnings, shared targets run separately, continuing after a failure,
SKIPPED reasons, single warning, refusal to overwrite and merging, invalid
paths and configuration, missing snapshot, setup failure), and
`WarningTargetsJsonTest` (reader round trip). Also `SliceIntegrationTest` with
real Specimin `b1e5d43` (two GENERATED fixture slices and one real FAILED
"could not locate"). Real EventBus run: 41 GENERATED, 0 FAILED, 1 SKIPPED
(W0033) in about 44 s; the original project and the run snapshot were
byte-identical afterwards.

## T10b — Validate that slices reproduce their warnings · `next`

Rerun the checker on each GENERATED slice and decide whether the warning is
reproduced. This needs a rule for matching the warning inside the slice
(project-spec §6.1) and a decision on how to compile slices (classpath,
stubs). Update `reproduction` from NOT_VALIDATED.

## T11 — Symbol usage extraction · `done`

`context <run directory> [--warning ID]` attributes the run snapshot's
configured source roots with javac and, for each warning in
`warning-targets.json` (IDs preserved), writes `context/<ID>.json` and `.txt`.
Each file holds the target identity (declaration, modifiers, annotations,
parameters) and evidence by category: warning site, call sites (arguments,
result consumption, later uses of a local holding the result), method
references, parameter uses, return statements, override relations, field
writes and reads. Unresolved references are listed separately. Each item has
a snippet, enclosing conditions, syntactic null guards, local definitions and
observations. References are matched by compiler symbol. Untrustworthy
same-name references are reported as unresolved and not counted. Evidence is
deduplicated, ordered deterministically, and limited through `GA_CONTEXT_MAX_*`
with omitted counts. No LLM, checker, Specimin, edit or region policy is
involved. Code: `context.SourceModel`, `SiteAnalyzer`, `ContextExtractor`,
`Evidence`, `EvidenceCategory`, `WarningContext`, `ContextJson`,
`ContextText`, `ContextLimits`, `cli.ContextCommand`, and
`MemberLocator.targetableMembers`.

Verified by: `ContextExtractorTest` on a four-file fixture. It covers
cross-file calls, overloads (and calls to them excluded), a shadowing local,
an unrelated same-name field and method in another class, field reads and
writes, argument and return flows, a ternary parameter flow, guarded
dereferences (enclosing condition and early exit), `this(...)`, implicit
`super()`, anonymous-class and diamond creations, override relations, missing
dependencies (unresolved receiver), erroneous arguments with same-arity
overloads in both directions, truncation, and determinism. Also
`ContextCommandTest` (files and IDs, SKIPPED and FAILED, single warning,
regeneration, env limits, usage errors, snapshot unchanged) and `MainTest`.
Real EventBus run: 41 EXTRACTED, 1 SKIPPED (W0033), 0 javac errors.

Not done: usages from unsafe regions (T12), multi-hop or dispatch-aware
context (project-spec §6), prompting.

## T11a — Compact, type-relevant context view · `done`

`context/<ID>.txt` is now a compact LLM-facing view, while `context/<ID>.json`
keeps every detail. The view shows the warning and target declaration once.
Evidence is grouped by enclosing member, overlapping snippets are merged into
lines shown once in source order, and lines carry short notes (read, write,
call, return, alias holds or may hold, annotations the snippet does not show).
Omitted code is an explicit `...` gap. Empty categories, zero counts,
descriptions and checker URLs are left out. Initializers, writes, arguments,
returns, dereferences, annotations, enclosing and early-exit conditions, and
`synchronized`/`volatile` context are kept. The underlying alias flow was
fixed: uses after an unconditional reassignment are no longer attributed, and
uses after a conditional one are `MAY_BE_REASSIGNED`. Evidence is
deduplicated by location and symbol, and distinct evidence is kept first
before the limit. CLI, file names, IDs and limitations are unchanged. Code:
`context.CompactView`, `CompactViewBuilder`, `ContextText`, plus changes to
`SiteAnalyzer` (alias flow, exit branches, member ranges), `ContextExtractor`
(marks, dedup, prioritization) and `Evidence` (marks).

Verified by: `CompactViewTest` on `q/Holder.java`, which mirrors W0001. It
covers both writes, the lazy-initialization chain with null checks and the
return, the target declaration and each line shown once, sorted rows, explicit
gaps, unconditional reassignment, branch, partial assignment and loop
uncertainty, early-exit guards, deduplication by location, annotation notes,
distinct-first limits and determinism, and URL stripping. Also the updated
`ContextCommandTest`. EventBus: W0001.txt went from 6091 to 2163 bytes, and
all 42 .txt files from 230316 to 80250 bytes.

## T12 — Include usages from unsafe regions · `blocked`

Blocked on project-spec §6.2 (safe/unsafe region definition).

## T13a — LLM prompt units · `done`

`prompts <run directory> [--warning ID]` assembles one prompt unit per
warning without calling an LLM. The warning (from `warning-graph.json`), its
target, its Specimin slice files and its compact usage context
(`context/<ID>.txt`, without its duplicated header; the JSON is only checked)
are joined by warning ID and verified to belong to the same run. The
versioned instructions ask for the smallest annotation-only edits and forbid
suppressions and body changes. They treat the context as read-only evidence
and slice stubs as reduction artifacts, and require a JSON reply (`warningId`,
`status` PROPOSED or INSUFFICIENT_EVIDENCE, precisely located `edits`,
`rationale`, `uncertainty`). Slice reproduction status (`NOT_VALIDATED`) is
preserved. Missing, failed or mismatched inputs make a warning SKIPPED, and
units over `GA_PROMPT_MAX_CHARS`/`GA_PROMPT_MAX_FILE_CHARS` are OVERSIZED
(never truncated). Output is `prompts/<ID>.txt` and `prompts/manifest.json`.
Code: `prompts.PromptSources`, `PromptAssembler`, `PromptTemplate`,
`ContextSection`, `PromptLimits`, `PromptManifestJson`, `cli.PromptsCommand`.

Verified by: `PromptsCommandTest` on a run built with real targets and
context. It covers association and single-warning isolation (no other ID
appears), a two-file slice in path order and verbatim with an embedded
"instruction" comment kept as data, the context without its header or JSON,
the target block, SKIPPED for an unresolved target, a failed slice, missing
context, missing slice results and removal of stale prompts, rejection of
artifacts from another run, a slice made for another member and context of
another warning, OVERSIZED per file and per unit with sizes, invalid limits,
byte-identical reruns, and manifest merging. Also `MainTest`. EventBus: 41
GENERATED, 1 SKIPPED (W0033).

## T13b — Wulver inference: submission and GPU worker · `done`

`prepare-inference <run directory> [--warning ID]` turns the GENERATED
entries of `prompts/manifest.json` into an immutable `inference/<id>/`
submission. It contains the prompts copied unchanged, `submission.json` (IDs,
full SHA-256 hashes, slice reproduction copied unchanged, deterministic task
assignments, excluded entries), `settings.json`, `run_inference.sbatch` (an
array job with configurable account, partition, QoS, GRES, constraint, time,
modules and concurrency) and `worker/ga_inference_worker.py`. Batch sizes 1 to
10 are accepted (41 prompts at 10 per task give 10, 10, 10, 10, 1); anything
else is rejected. The worker:
- refuses to load a model outside a Slurm GPU job or on a GPU that is too small;
- checks the conda environment;
- runs its own localhost vLLM server with offline weights, handling startup
  failures, timeouts and port collisions;
- sends prompts one at a time after a length check;
- saves raw responses, reasoning, validated answers and metadata atomically;
- keeps completed results across resubmissions;
- stops its server on exit and on SIGTERM.

Code: `inference.InferenceSettings`, `TaskAssignment`, `SlurmScript`,
`SubmissionPreparer`, `cli.PrepareInferenceCommand`, the resource
`generalannotator/inference/ga_inference_worker.py`, and the Gradle task
`workerTest`.

Verified by: `PrepareInferenceCommandTest` (41 prompts with defaults, 10 per
task, invalid batch sizes, single warning, duplicate IDs, unsafe paths, missing,
edited, mismatched and symlinked prompt files, run and template mismatches, no
overwrite and no partial leftovers, determinism, settings validation and
customization, and the keys the worker reads) and `MainTest`. Also
`workerTest`: 16 Python tests with a fake vLLM server and `nvidia-smi`
covering answer validation, completed and INSUFFICIENT_EVIDENCE outcomes,
malformed, truncated, empty and wrong-ID answers, HTTP errors, too-long
prompts, hash mismatches, the server dying mid-task, startup crash, timeout
and port collision, fixed busy ports, the login-node and GPU guards, SIGTERM
cleanup, and resubmission. A manual cross-check ran the worker with the fakes
on a submission prepared from the EventBus prompts. No GPU job was submitted.

## T13c — Wulver connection, upload, submission, monitoring and collection · `done`

`inference connect|upload|submit|attach|status|collect|retry|run` take a
prepared submission to Wulver and back over one shared OpenSSH connection:
- **Connection:** `connect` reuses a running master or opens one with a single
  interactive login in the user's terminal. The socket is in a private 0700
  directory, or a user-owned socket or SSH-config ControlPath is reused. Every
  other ssh/rsync call uses the same socket with `ControlMaster=no` and
  `BatchMode=yes`, so a lost connection gives exit 5 (reconnect) instead of a
  new prompt.
- **Upload:** only the prepared inputs go to a unique
  `<base>/<subdir>/<run id>/<submission id>`, without `--delete` and without
  writing into a foreign directory. Remote SHA-256 hashes are verified before
  any submission.
- **Submission:** one `sbatch --parsable` array (prepared tasks and
  concurrency). The intent is saved before the call and the job ID right after.
  Duplicates are prevented by the saved state, an exclusive local lock and a
  remote `mkdir` marker. Lost or unreadable outcomes are UNKNOWN and are
  resolved only by `attach <job id>` (verified with sacct/squeue) or `attach
  --not-submitted` (verified absence).
- **Status:** squeue for active tasks and sacct for history. Missing
  information is UNKNOWN. Scheduler states are kept apart from prompt results.
- **Collection:** a mirror, then a non-destructive merge (write-once attempts;
  a completed result is never replaced by a non-completed one) and per-prompt
  verification of submission, run, warning, prompt hash, metadata and answer
  files.
- **Retry:** only after every task has finished, no outcome is unknown and the
  collection is clean. It resubmits the tasks of failed or missing prompts in
  the same directory, where the worker keeps completed results and writes new
  attempts.
- **`run`:** connect, upload, submit once, then poll (default 300 s) and
  collect. Ctrl-C and disconnects leave the jobs running, and rerunning resumes.

`prepare-inference --warning` now also takes a comma-separated list
(`W0001,W0002`), for small test arrays. The worker now writes server logs per Slurm job
(`results/logs/task-NNNN/job-<id>/`), so retries keep earlier logs.

Code: `inference.remote.RemoteSettings`, `SshConnection`, `Cluster`,
`SshCluster`, `SchedulerSnapshot`, `SbatchOutcome`, `RemoteException`,
`RemoteState`, `StateStore`, `LocalSubmission`, `ResultCollector`,
`RemoteInference`, `cli.InferenceCommand`, `exec.TerminalCommandRunner`, and
exit code 5.

Verified by:
- `SshClusterTest`: argv of every ssh/rsync call (same socket, BatchMode, no
  `--delete`, no host-key override; only the managed master command is
  interactive), check, sbatch parsing (ID, `ID;cluster`, rejected, duplicate,
  missing or garbled output), lost connection and timeout during sbatch as
  UNKNOWN, squeue/sacct parsing including pending ranges, "invalid job id", a
  failed sacct and cut-off output, rsync failures as disconnect versus failure,
  hash output, job records, unsafe values, settings validation.
- `RemoteInferenceTest` with `FakeCluster`: upload of only the inputs, upload
  disconnect and resume, hash mismatch blocking submission, foreign remote
  directories, a single submission across reruns, the concurrent-invocation
  lock, the remote marker after lost local state, ambiguous disconnect (no
  resubmission, wrong and unknown jobs refused, verified attach), verified
  not-submitted, interrupted intent read back as UNKNOWN, rejection, scheduler
  silence as unknown (status, run, retry), partial collection while running,
  identity and hash mismatches, retry preservation with conflicts, monitoring
  to completion, disconnect and interrupt while monitoring, a moved remote
  location.
- `InferenceCommandTest`: reuse without login, exactly one interactive login
  in a 0700 directory, an insecure directory refused, no terminal, a failed
  login, user-owned connections only reused, exit codes.

A manual cross-check ran the installed launcher (`inference connect`, `run`,
`retry`, `submit`, `status`) on a 41-prompt, 10-per-task EventBus submission.
It used real rsync, bash and `sha256sum` through a fake `ssh`, and fake
`sbatch`/`squeue`/`sacct` running the real worker with a fake vLLM. The cycle
went from 41 failed (a fixture error) to 40 after a retry, then to all 41
completed after a second retry. That retry re-requested only W0005 of its
10-prompt task. Nothing contacted Wulver.

## T13e — Disconnected operation and stale-connection recovery · `done`

Observed incident (submission `20261119T165512Z-inference`, job 1629432):
1. Monitoring stopped when the VPN was turned off.
2. After reconnecting, `inference connect` reported "Reusing the SSH master"
   because `ssh -O check` answered.
3. `inference status` then failed with `mux_client_request_session: read from
   master failed: Broken pipe` and listed all 41 tasks as UNKNOWN, next to an
   older prompt total. The worker failures W0008 (`STARTUP_TIMEOUT`) and W0011
   (`INVALID_ANSWER`) are unrelated to this.

Changes:
- **The documented workflow** is `connect`, `upload`, `submit`, disconnect
  freely, then `connect`, `status`, `collect`. `submit` returns as soon as
  the job ID is saved and prints these steps. `run` stays optional.
- **Health** means `ssh -O check` plus a bounded remote round trip that must
  print `GA-HEALTH ok` (`SshConnection.health`, used by `Cluster.check`).
- **No fresh login:** non-interactive ssh/rsync use `ProxyCommand=false` as
  well as `ControlMaster=no` and `BatchMode=yes`. A missing or refusing socket
  can then not fall back to a fresh, possibly key-based login.
- **Managed masters** get `ServerAliveInterval` (`GA_SSH_SERVER_ALIVE_SECONDS`,
  default 15).
- **`connect` and stale masters:** a stale managed master gets `ssh -O exit`,
  then one interactive login. A master that will not exit is reported, not
  killed. User-owned sockets are never stopped; `connect` prints commands
  instead.
- **Disconnects versus uncertainty:** `Cluster.query` throws DISCONNECTED for
  a lost transport, and timeouts are followed by a health check, so a dead
  connection is exit 5 and not a list of UNKNOWN tasks. `status.json` keeps
  the last successful snapshot and records `refreshFailure`.
- **Labels:** `status` marks cached scheduler data and cached prompt results
  as such.
- **UNKNOWN calls first:** `submit` and `run` check for an UNKNOWN sbatch call
  (initial or retry) before their "already submitted" shortcut.

Verified by:
- `SshClusterTest`: the health probe argv, timeout and marker; a live master
  whose probe fails; transport loss and a hung command during squeue/sacct
  (DISCONNECTED) versus a slow scheduler (UNKNOWN); the no-fallback options.
- `InferenceCommandTest`: healthy reuse with no login; stale managed recovery
  with exactly one login; a stale master that does not exit; stale user-owned
  sockets left untouched; submit, retry and collect never prompting on a stale
  connection.
- `RemoteInferenceTest`: submit without polling or sleeping; the disconnected
  submit, status and collect cycle keeping the same job ID and results;
  transport loss during status (exit 5, cached labels, `refreshFailure`) and
  while monitoring; an UNKNOWN retry blocking submit and run; collection
  without scheduler history.

The real VPN-off, sleep and reconnect cycle has not been run with this
version.

## T13d — Wulver end-to-end and GPU smoke test · `next`

On Wulver, with one or two prompts (`prepare-inference --warning`), confirm:
- (observed with the previous version: the Duo login, `bash -l` finding the
  Slurm tools, `sha256sum` and rsync, upload, sbatch, monitoring and
  collection.) Still to check: `submit`, then VPN off and sleep, then
  `connect`, `status` and `collect` with this version, including stale-master
  recovery and `ServerAliveInterval`;
- that sacct reports `WorkDir` for `attach`, and the effective account and QoS
  limits (MaxSubmitJobs and similar) for a 41-task array;
- the model loads from the cache offline with vLLM 0.30.0 on the requested GPU
  type and fits in memory;
- the served name, the 32768 context and the `qwen3` reasoning parser work,
  and `/tokenize` and `/version` are available;
- reasoning arrives in `reasoning_content` or `reasoning`;
- the sampling and `max_tokens` defaults give complete JSON answers;
- startup and answer times fit `GA_SLURM_TIME`;
- `--signal=B:TERM@120` reaches the worker under `exec`.

## T13 — LLM proposer · `todo`

Add an LLM implementation of `Proposer` behind the existing interface. Tests
use recorded or faked responses only.

## T14 — Sequence exploration and selection · `blocked`

Blocked on project-spec §6.1, §6.3, and §6.4.

## T15 — Batching independent attempts · `blocked`

Blocked on project-spec §6.5.

## T16 — Resumable round-1 pipeline · `done`

`pipeline start`, `pipeline status <dir> [--connect]` and `pipeline finish
<dir>` (`generalannotator.pipeline.Pipeline`) chain the existing commands
in-process. No stage is reimplemented.
- **start:** validate every setting, then `check`, `slice`, `context`,
  `prompts`, `prepare-inference` (`GA_INFERENCE_PROMPTS_PER_TASK` defaults to
  10 here), `inference connect`, `upload` and `submit`. It returns once the job
  ID is saved.
- **status:** the recorded stages plus `inference status`; scheduler states
  and prompt results are kept apart, and cached data is labeled.
- **finish:** connect and query; once every task is terminal, `collect`,
  `evaluate-annotations` and `apply-round`.
- **Outcomes:** FAILED, NO_WARNINGS, NO_ELIGIBLE_PROMPTS, RECONNECT_REQUIRED,
  SUBMISSION_UNKNOWN, SUBMITTED, WAITING_FOR_JOBS, NO_PACKAGES, BLOCKED,
  PARTIAL and COMPLETED, each with exclusions per stage and warning.
- **State:** the run directory is the pipeline directory. `pipeline.json`
  holds the fingerprinted settings (every `GA_*` except SSH transport), the
  snapshot hash, the checker identity, stage records, artifacts, submission
  and job IDs, and exclusions; `pipeline-summary.txt` is the readable version.
  Writes are atomic and one writer holds the lock.
- **Resume:** only with unchanged settings and snapshot. Slicing resumes per
  missing warning, the submission is prepared once, and sbatch is never
  repeated (a recorded job is reused; UNKNOWN needs `inference attach`).
  Repeated `finish` reuses the collection, evaluations and round result.

Small hooks in existing commands:
- `CheckCommand`: checker factory and run directory;
- `EvaluateAnnotationsCommand.resultDirectory`;
- `ApplyRoundCommand.result`;
- `SliceCommand.configurationProblems`;
- `FakeChecker` and `FakeCluster` made public for tests.

Verified by `PipelineTest` (fake NullAway, Specimin, ssh, terminal, cluster and
worker), which covers:
- stage order without intervention, one login, task sizes [10, 2] for 12
  prompts (the batch default of that time; one prompt per task since T16a),
  one sbatch, no monitoring, and an unchanged target;
- invalid settings creating no directory;
- a failed baseline;
- no warnings;
- excluded prompts, and no eligible prompt;
- an interrupted start: a failed login, then a mid-slicing interruption
  resumed per warning; changed settings refused; one submission;
- an UNKNOWN sbatch outcome never resubmitted and blocking finish;
- status cached after a disconnect, and fresh with `--connect`;
- finish while tasks are active (nothing collected);
- a full finish with stale-master recovery: one new login, PARTIAL for a
  failed prompt, merged packages, a two-edit package, independent
  evaluations before the combined check, and the comparison;
- a repeated finish that changes nothing;
- a conflicting round (BLOCKED, evaluations kept);
- a failed combined check (FAILED).

`MainTest` covers dispatch.

Manual, offline (an unreachable SSH target, no terminal): `pipeline start` with
real NullAway and Specimin on EventBus ran stages 1–5 unattended in about 40 s.
- It found 42 warnings and prepared 41 prompts in 5 tasks; W0033 was excluded
  (initializer block).
- It stopped as RECONNECT_REQUIRED without contacting anything.
- `--resume` skipped every completed stage.
- The EventBus checkout was unchanged.

**Not verified:** a real submission, collection and disconnected run through
`pipeline`, and a real `finish`. Its stages were verified separately on real
data before (T8b–T8d).

## T16a — One prompt per task and a 30-minute startup timeout · `done`

On EventBus, two of the five 10-prompt array tasks failed with exit code 4:
their vLLM servers were not ready within 1200 s, so all 20 of their prompts
failed with them.
- **New defaults.** `GA_INFERENCE_PROMPTS_PER_TASK` defaults to 1 (the
  pipeline no longer forces 10), and `GA_INFERENCE_STARTUP_TIMEOUT_SECONDS` to
  1800. Explicit values (1 to 10 prompts per task; 10 to 86400 seconds) still
  apply and are validated, and array concurrency is unchanged (4).
- **Propagation.** The defaults reach the submission's `settings.json` and
  `submission.json` task assignments, the batch script's `--array` range, and
  the worker's readiness deadline. The pipeline prints the effective batch
  size, startup timeout and concurrency, and records them per preparation.
- **Existing pipelines.** Inference and Slurm settings are no longer part of
  the resume fingerprint, so a pipeline prepared under the old default still
  finishes. A note reports the submission's own settings. Existing
  submissions are immutable and keep theirs.
- **Fresh starts.** A fresh start needs no earlier run artifacts. The docs say
  that `GA_TARGET_PROJECT` must be a source project that survives deleting
  `runs/`, and that `GA_OUTPUT_DIR` should be unset.
- **Unchanged:** startup failures (STARTUP_FAILED, STARTUP_TIMEOUT) stay
  distinct from answer-validation failures, and answer validation is not
  weakened.

Verified by:
- `InferenceDefaultsTest`: the defaults; 41 eligible prompts becoming 41
  single-prompt tasks, each assigned once, with `--array=0-40%4` and
  `startupTimeoutSeconds` 1800; overrides and invalid values.
- `PipelineTest`:
  - a fresh start without `runs/` giving 41 single-prompt tasks and one
    array job;
  - explicit overrides applied and invalid ones refused;
  - one task's startup failure excluding only its prompt (PARTIAL);
  - a pipeline recorded under the old default still finishing;
  - the earlier submission, reconnect, finish and resume scenarios.
- Worker tests:
  - the worker honours an 1800 s readiness deadline (30 polls of 60 virtual
    seconds);
  - with one prompt per task, a startup failure affects only that task's
    prompt, and the other tasks complete.

Not verified: whether 1800 s is enough for the 27B model on Wulver nodes.

## T17 — Single-pass nullability propagation · `done`

`propagate <run dir>` and `pipeline propagate <dir>` form a deterministic stage
that never calls an LLM.
- **Analysis.** `NullabilityPropagation` classifies every baseline warning
  against the javac-attributed snapshot (`context.SourceModel`). It matches
  only nullable field/method-result assignments, field initializers and
  returns into unannotated destinations, and only when the message kind, the
  tree at the caret and the symbols agree. Every other warning is recorded
  with a category and reason (other category such as dereferences, value not
  an explicitly `@Nullable` field or method, unresolved, explicit contract or
  override, not editable).
- **Packages.** One `@Nullable` edit per match, from the unchanged baseline
  only, merged by edit set (`Candidates.fromProposals`, origins
  `<warning>@propagation-1`, generator recorded).
- **Evaluation.** `Evaluator` (round 1, W1001...) evaluates each package
  alone, and `RoundApplication` applies the successful, mutually compatible
  packages together and checks them once. Conflicts are reported and
  excluded.
- **One pass:** introduced warnings are never analyzed.
- **Output:** `propagation/<id>/` (`analysis.json`, `propagation.json`,
  `summary.txt`), `evaluations/<id>-<key>/` and `rounds/round1-<key>/`.

Code:
- `propagation.NullabilityPropagation` and `PropagationStage` (new);
- `cli.PropagateCommand` (new);
- `Pipeline.propagate` (a new `propagation` stage, independent of the LLM
  stages);
- `Main`;
- `Candidates` (`generator`, `fromProposals`, `only`; answer evaluations keep
  their keys);
- the generator recorded by `Evaluator` and `RoundApplication`
  (`RoundApplication` also takes a selection description).

Verified by:
- `PropagationTest` (real javac and applier, fake NullAway):
  - both patterns (assignment, field initializer, method result, return);
  - ignored dereferences and unrelated warnings;
  - an explicit `@NonNull`;
  - a returned local;
  - messages that the tree contradicts;
  - duplicate candidates becoming one package with two origins;
  - graph edges, the W1001 ID and generator marks;
  - one-pass behaviour (B from nullable A annotated; C stays unchanged
    although `c = b` then warns);
  - a failed package evaluation (claims nothing, left out of the combined
    result) and a failed evaluation baseline;
  - conflicting packages excluded and reported;
  - no candidates;
  - reuse.
- `PipelineTest` (`pipeline propagate` leaves the LLM stages alone) and
  `MainTest`.

Manual, with real NullAway:
- **EventBus baseline:** 42 warnings, all skipped with reasons (no
  `@Nullable` in the tree yet).
- **EventBus with four `@Nullable` fields:** 36 warnings, all skipped with
  reasons. The flows are `null`, conditionals or locals, and one override.
- **A small JSpecify project:**
  - 5 of 12 warnings matched, giving 4 packages, all COMPLETED;
  - the combined state went from 12 to 6 findings;
  - `c = b` was introduced as W1001, and `c` was left unannotated;
  - the target and snapshot were unchanged.

## T17a — Propagate from the combined round-1 result · `done`

`pipeline propagate` used to read the original program's baseline (42 EventBus
warnings, so NO_CANDIDATES). It now reads the combined round-1 result recorded
in `pipeline.json`: the annotated snapshot and its complete warnings (23).
- **Input (`PropagationStage.Input`).**
  - Pipeline directory: its completed combined result.
  - Round result directory: that result.
  - Plain run: its own baseline, labelled round 0.
  - A combined result is verified before use: status, snapshot tree hash,
    run IDs of the report and graph, and finding counts and IDs. A missing or
    unverifiable result stops with an actionable message and never falls
    back.
- **Evaluation.** Packages are named `P0001, ...` and evaluated with round =
  source round + 1 on copies of the input snapshot, so earlier annotations
  are kept. Baseline warnings keep the input's IDs
  (`Evaluator.InheritedBaseline`, verified one-to-one), and introduced
  warnings get `W2001, ...`.
- **Extended graph.** `propagation/<id>/evaluation-graph.json` adds this
  layer to the earlier evaluation graph. (T17a left attempt-introduced
  warnings out; T17b replaced that with history-preserving linking.)
- **Combined result.** It goes to `rounds/round2-<key>/`, pinned to the
  evaluation's baseline (`RoundApplication.withBaseline`), with new IDs after
  the evaluation's.
- **Cache keys** include the input's snapshot, report and graph identity
  (`propagation2-*`). Earlier results, including NO_CANDIDATES from the
  original baseline, are kept, never reused, and listed in pipeline.json as
  `previous`.

Verified by:
- `PropagationRoundInputTest`, with round 1 produced by the real evaluator
  and round application:
  - candidates come from round-1 annotations and warnings only;
  - round-1 annotations are kept in evaluation copies and the combined state;
  - round IDs are kept, with W2001 for new warnings;
  - the earlier graph history is preserved and unmodified, with no ID
    collisions;
  - pending, tampered, mismatched and incomplete combined results are
    refused without fallback;
  - single pass;
  - an earlier original-baseline result is kept but not reused.
- `PipelineTest`: refusal without a combined result, and selection after
  `pipeline finish`.
- Manual check with real NullAway on a copy of the EventBus pipeline:
  - input `round1-09d3783e43a5` with 23 warnings;
  - one match, W1001: `mainThreadSupport` assigned from
    `getMainThreadSupport()`, which round 1 made `@Nullable`;
  - the package resolved W1001 and introduced nothing; the combined check
    went from 23 to 22 findings;
  - the real run was unchanged.

## T17b — Preserve graph history in the extended propagation graph · `done`

T17a's extended graph dropped the earlier evaluation's attempt-introduced
warnings (and their `A -> W1...` edges), because the combined round result
reuses their IDs for other warnings. That cut the history between round-1
annotations and the round-1 warnings propagation acts on.

- **Graph model.** Only warning nodes and annotation package nodes, and only
  two solid edge kinds: `W -> A` (`resolved-by`: applying A resolved W) and
  `A -> W` (`introduces`: applying A introduced W). `WarningGraph` rejects
  any other edge kind or direction and duplicate edges, as well as dangling
  ones. Every edge comes from one complete, independent package evaluation
  against its recorded source state. The combined round result is saved
  separately as the authoritative list of current warnings and adds no node
  or edge.
- **`evaluation.HistoryGraph`** (model `warning-annotation-history-2`)
  builds the extended graph from saved artifacts only:
  - The earlier evaluation graph is kept whole: every node and edge,
    unchanged.
  - Input warnings (findings of the combined state) are never linked by ID
    or by diagnostic similarity alone. A persisting warning is linked to the
    earlier root B only if the combined comparison recorded B and the
    matcher pairs B's saved baseline finding with it again. An introduced
    warning is compared by the matcher with what each applied package
    introduced in its own completed evaluation on the same base state (saved
    anchored findings; package verified by its package key; the earlier
    `A -> W` edge must exist). Exactly one equivalent: linked to that
    earlier node, whose ID is reused. None, several, or an ambiguous group:
    a separate node with a fresh unique ID of the input's round and no
    incoming edge, with the limitation recorded.
  - The propagation layer is added with input IDs translated to graph IDs:
    `W -> P` for resolved input warnings (W1... or W0...), `P -> W2...` for
    genuinely new warnings; no node for persisting warnings, and a package
    that introduces nothing ends its branch. Every new edge records its
    evaluation, source and result states, matcher and combined-report
    warning ID (`reportWarning`) as provenance metadata.
  - `propagation.json` records `history.reportToGraph` (combined-report ID
    to graph node) and `history.inputWarnings` (relation, evidence or
    limitation per warning).
  - ID clashes are refused, never renamed. The round-2 combined result
    stays separate (`rounds/round2-*`), not in the graph.
- **Serialization and visualization.** Edges get an optional `provenance`
  object within schema version 5; other graphs are unchanged. The DOT
  export draws only solid edges (`resolved-by` green, `introduces` red) with
  provenance in edge tooltips; introduced warnings get a thicker solid border
  instead of a dashed one.
- **Regeneration.** `propagate --rebuild-graph <propagation dir>` rebuilds
  the graph and the record's `history` from saved artifacts (no checker, LLM
  or inference; no configuration). It refuses if the input's identity or the
  evaluation's inherited identity no longer matches. A changed graph removes
  stale `.dot`/`.svg` renderings. `propagation.json` and pipeline.json record
  `graphModel`. Rerunning `pipeline propagate` also regenerates it, with all
  evaluations and checks reused from cache.

Verified by:
- `PropagationHistoryGraphTest`, with a two-package round 1 whose evaluation
  and combined IDs really collide (one package proposed by two equivalent
  answers):
  - the earlier `A0001 -> W1002` edge is kept;
  - the same W1002 is extended with `W1002 -> P -> W2001`;
  - a persisting W0 warning resolved by a package that introduces nothing
    keeps its ID, with no W2 node;
  - linked, ambiguous and unattributed input warnings; the latter two are
    separate nodes without incoming edges; no linking by ID;
  - equivalent answers stay one package, and annotation IDs are unique
    across rounds;
  - only warning and annotation nodes and the two edge kinds, in the JSON
    and the DOT (no state nodes, no dashed or dotted lines);
  - repeated runs and rebuilds are byte-identical, with no duplicate nodes or
    edges and no new checks;
  - a stale graph is replaced, and a changed input is refused.
- `WarningGraphJsonTest`: other edge kinds, wrong directions, duplicate edges
  and `state` nodes are rejected; provenance round-trips.
- `PropagationRoundInputTest`, `DotExporterTest`, `EvaluateAnnotationsTest`,
  updated.
- A rebuild on a copy of the EventBus run (real saved artifacts):
  - 22 input warnings linked to earlier equivalent nodes, plus 1 persisting;
    no separate nodes;
  - `W0012 -> A0009 -> W1007 -> P0001`: the combined report's W1001 is the
    earlier W1007, recorded as `reportWarning` on the edge;
  - no W2 nodes; 95 nodes and 66 edges, no dashed lines in the SVG.

## T18 — Propagate nullable arguments to nonnull parameters · `done`

T17's rules accepted only assignment and return warnings, and rejected locals
as values.

- **Rule `ARGUMENT`** (`NullabilityPropagation`, rules `nullable-flow-2`):
  - **Recognized message:** "passing @Nullable parameter 'x' where @NonNull is
    required".
  - **Call:** the innermost method or constructor call with an argument
    containing the caret whose text equals the quoted expression. The
    executable is the one javac attributed, so overloads are told apart by
    their types. The formal parameter is the one at the argument's index.
  - **Value:** a field read, a method call, or a local or parameter. The
    diagnostic is the evidence that it may be null.
  - **Annotation:** the origin's own `@Nullable`, traced through locals, or
    else the project's single nullable annotation type.
  - **Edit:** `@Nullable` at `PARAMETER[i]` of the declaring method or
    constructor (inherited methods where they are declared). An explicit
    non-null annotation is removed in the same package and recorded as
    `contractChange`.
  - **Skipped with reasons:** varargs positions, abstract or overridden
    methods, anonymous class creations, executables outside the sources,
    unresolved calls, and `null`, conditionals or casts as arguments.
- **Locals** for assignments and returns: a local initialized (through up to
  four locals) from an explicitly `@Nullable` field read or call is accepted.
- **Applier.** An ADD no longer counts as contradicting a nullness annotation
  that the same edit set removes at that position, which makes `@NonNull` ->
  `@Nullable` one atomic package.
- **Stage.** A match may carry two edits. Generator
  `nullability-propagation-2`; the rule version is in `analysis.json`, hence in
  every cache key, so `nullable-flow-1` results are not reused. Everything
  else is unchanged: one pass from the fixed input, packages merged by edit
  set, independent evaluations, the combined result, and the graph.

Verified by:
- `PropagationArgumentTest`:
  - direct field arguments, two-local flow and nullable method results;
  - `@NonNull` replaced in the applied source;
  - `over(Object)` / `over(String)` resolved by type;
  - two arguments of one call mapped to two parameters;
  - repeated warnings merged into one package;
  - the inherited method annotated in its declaring class;
  - the project-annotation fallback;
  - dereferences ignored;
  - varargs, JDK, abstract, overridden and `null` targets skipped;
  - a diagnostic naming another expression is not matched;
  - one pass: the warning introduced inside the annotated method is recorded,
    not propagated;
  - reruns reuse everything.
- The existing propagation tests are unchanged and pass.
- A run on a copy of the EventBus pipeline after round 1 (real NullAway):
  - 7 of 23 warnings matched: W1005, W1007, W1009 and W1021 (arguments),
    W1012 and W1020 (through locals), and W1001;
  - W1006/W1008 (`Logger#log`, abstract) and W1014
    (`SubscriberInfoIndex#getSubscriberInfo`, abstract) were skipped;
  - W1005 annotated `EventBus#invokeSubscriber(Subscription, Object)`
    parameter 1 and resolved, introducing W2001: `event` passed on to
    `handleSubscriberException` (line 519), left for a later pass;
  - the combined check went from 23 to 20 findings (7 resolved, 4
    introduced).

## T19 — Propagate nullable arguments into interface, abstract and overridden methods · `done`

T18 skipped arguments whose receiving method was abstract or overridden
(`UNSUPPORTED_TARGET`), such as EventBus's `logger.log(Level.SEVERE, msg,
cause)`.

- **Hierarchy** (`NullabilityPropagation.implementations`, rules
  `nullable-flow-3`): when the resolved method can be overridden (not static,
  private, final, a constructor, or in a final class), the package also
  annotates the same parameter of every method in the editable sources that
  overrides or implements it:
  - by javac's `Elements.overrides` relation, checked in each source subtype,
    so generic substitutions are found and overloads are not;
  - including implementations inherited from another class, and overrides of
    those overrides, until nothing new is found;
  - never the method's ancestors, siblings or overloads.
- **Edits:** `@Nullable` on unannotated parameters. Explicit non-null ones are
  replaced (REMOVE + ADD) and listed in `contractChange`. Parameters already
  `@Nullable` are kept. All edits form one `Match` (`hierarchy`), hence one
  package and one annotation node; equal complete packages are merged with
  every originating warning.
- **Skipped as a whole** (`INCOMPLETE_HIERARCHY`, new): a required
  implementation declared outside the editable sources (for example inherited
  from `java.io.PrintStream`) or in an anonymous or local class, or an
  explicit non-null annotation that does not resolve to a qualified type.
  Varargs at an override's position: `UNSUPPORTED_TARGET`.
- **Coverage:** only the configured source roots are searched; the evidence
  says that implementations elsewhere are not discovered (project-spec §6,
  research decision 6).
- **Stage:** `analysis.json` lists `hierarchy` edits; generator
  `nullability-propagation-3`. The rule version is in every cache key, so
  `nullable-flow-2` results are not reused. Everything else is unchanged.

Verified by:
- `PropagationHierarchyTest` (fake NullAway that also reports overrides left
  non-null under a `@Nullable` parameter):
  - an interface with three implementations, one two levels down in another
    file, with its overload untouched, reached directly and through a local,
    merged into one four-edit package and node;
  - an abstract method, with its overload untouched;
  - an implementation inherited from a class outside the interface's
    hierarchy, plus that class's own override;
  - multi-level overrides, and a call resolved at the middle level that
    leaves the ancestor alone;
  - `Sink<String>.put(T)` with `put(String)`, and the unrelated `put(Integer)`
    left alone;
  - an existing `@Nullable` implementation kept, and an explicit `@NonNull`
    implementation replaced;
  - an external implementation (`PrintStream#println`) and an anonymous-class
    override, each skipping the whole package;
  - atomic application: every package evaluates without override
    inconsistencies, and the combined source has every hierarchy edit;
  - one pass: the introduced dereference is recorded, not propagated, and
    reruns reuse everything.
- `PropagationArgumentTest` updated: `I#take` (no implementations) and
  `Base#m` + `Sub#m` are now matched.
- A run on a copy of the EventBus pipeline after round 1 (real NullAway):
  - W1006 and W1008 (`logger.log(..., cause)`) share package P0003. It
    annotates `th` in `Logger#log(Level, String, Throwable)`,
    `JavaLogger#log` and `SystemOutLogger#log`, and leaves `log(Level,
    String)` alone. `AndroidLogger` (module `eventbus-android`) is outside
    the source root and is not changed.
  - P0003's own evaluation resolved both, 23 -> 22 findings. It introduced
    W2002, the dereference `th.printStackTrace(System.out)` in
    `SystemOutLogger` (Logger.java:58), left for a later pass.
  - W1014 annotated `SubscriberInfoIndex#getSubscriberInfo` alone (no
    implementation in the sources; the generated ones aren't discovered) and
    resolved it, introducing nothing.
  - 10 of 23 warnings matched, giving 9 packages, all COMPLETED. The
    combined check went from 23 to 18 findings: 10 resolved, 5 introduced
    (W2006-W2010).

## T20 — Propagate nullable returns through overridden and implemented contracts · `done`

The return rule skipped every overriding method (`EXPLICIT_CONTRACT`: "its
inherited return contract is kept"), for example EventBus's
`ThrowableFailureEvent#getExecutionScope()` implementing
`HasExecutionScope`.

- **Ancestors** (`NullabilityPropagation.ancestors`, rules `nullable-flow-4`):
  - **Found:** every method declaration the returning method overrides or
    implements, in all supertypes (multi-level, several interfaces), by
    javac's `Elements.overrides` with type arguments substituted. Overloads
    are excluded.
  - **Inherited implementations:** for a source subclass that inherits the
    method as an implementation, the interface methods it implements there.
  - **Not touched:** overrides and sibling implementations keep their
    stronger non-null returns.
- **Edits:** `@Nullable` at `RETURN` of the method and of each ancestor, in
  one `Match` (`hierarchy`), so one package and one annotation node.
  - Already-`@Nullable` ancestors are kept.
  - Explicit non-null returns, on the method itself or on an ancestor, are
    replaced (REMOVE + ADD) and listed in `contractChange`. Fields with an
    explicit non-null annotation are still skipped.
- **Skipped as a whole** (`INCOMPLETE_HIERARCHY`): an ancestor outside the
  editable sources (for example `Supplier#get`, `Object#toString`), or an
  unresolvable explicit non-null annotation.
- **Stage:** generator `nullability-propagation-4`; the rule version is in
  every cache key, so `nullable-flow-3` results are not reused. Parameter
  hierarchies (T19) and the other rules are unchanged.

Verified by:
- `PropagationReturnHierarchyTest` (fake NullAway that also reports a
  nullable return under a non-null ancestor):
  - **Matched:**
    - an interface getter, with the sibling implementation untouched;
    - abstract and superclass overrides across three levels, with the
      subclass override and the overload untouched;
    - two interface contracts;
    - `Source<String>.get()` (generic), with `get(int)` untouched;
    - an already-`@Nullable` contract kept;
    - explicit `@NonNull` replaced on the method and its interface;
    - an inherited implementation's interface contract.
  - **Skipped as a whole:** `Supplier#get` and `Object#toString`.
  - **Application:** every package evaluates without contract
    inconsistencies, and the combined source has every edit and no
    `@NonNull`.
  - **One pass:** a caller's new dereference is recorded, not propagated,
    and reruns reuse everything.
- The existing propagation tests pass unchanged, apart from the rule
  version.
- A run on a copy of the EventBus pipeline after round 1 (real NullAway):
  - **The getter warning:** the input diagnostic "returning @Nullable
    expression from method with @NonNull return type" at
    `ThrowableFailureEvent.java:50:9` (`return executionContext;`) matched.
    One package annotates `HasExecutionScope#getExecutionScope()` and
    `ThrowableFailureEvent#getExecutionScope()`.
  - **Its evaluation:** that diagnostic resolved, 23 -> 22 findings, and
    nothing was introduced. `getExecutionScope()` has no callers in
    `EventBus/src`.
  - **Whole run:** 11 of 23 warnings matched, giving 10 packages, all
    COMPLETED. The combined check went from 23 to 17: 11 resolved and 5
    introduced, the same five as in T19.

## T21 — Run propagation round 3 (and later rounds) on demand · `done`

`pipeline propagate` ran only round 2, on the combined round-1 result, and
several pieces assumed that.

- **Command:** `pipeline propagate <dir> [--round n]`. Round 2 is the
  default; `--round 2` is the same execution. Round 1 and non-numbers are
  usage errors. Each command runs exactly one pass.
- **Input** (`PropagationStage.Input.fromPipeline(dir, n)`): round n > 2
  reads the combined result named by round n - 1's own stage.
  - **Checked before any record changes:**
    - the stage is COMPLETED with outcome COMPLETED;
    - its artifacts lie under `propagation/`, `rounds/` and `evaluations/`;
    - `propagation.json` is round n - 1 and names the same combined result,
      evaluation and graph;
    - round n - 1's input still has the identity it was computed on (checked
      recursively, so a regenerated earlier round makes later rounds stale);
    - the combined result verifies like round 1's, and its base state is
      round n - 1's input state.
  - Every failure gives an actionable message, and nothing falls back.
- **Records:** round n is recorded in stage `propagation-r<n>`; round 2 stays
  in `propagation`. An identical rerun is not added to `previous`, while a
  changed result keeps the old one there. The pipeline summary and status
  list every round's stage.
- **IDs and keys:**
  - stage IDs `propagation<n>-<key>`, with the round, input identity and
    rule version in the key;
  - packages `P<n>-0001, ...` (round 2 keeps `P0001`);
  - new warnings `W<n>001, ...`;
  - `rounds/round<n>-<key>`.
- **Graph** (`HistoryGraph.build` overload): the earlier graph may itself be
  an extended graph. The evidence is the earlier round's evaluation, and
  persisting base warnings map to nodes through that round's
  `reportToGraph`, whatever their round, instead of having to be roots.
  Round 2 is unchanged.
- **Rebuild:** `propagate --rebuild-graph` resolves a stage's input by its
  recorded round.

Verified by:
- `PipelineTest`, on a three-round chain fixture:
  - **Round 3:**
    - reads round 2's combined result, keeps round 2's record
      byte-identical, and records `propagation-r3`;
    - keeps every earlier annotation;
    - matches a persisting original W0 warning whose origin round 2 made
      `@Nullable`, round 2's introduced warnings, and a return hierarchy
      (`A#val` with `Src#val`);
    - uses P3-0001..3;
    - leaves the introduced `k = h` as W3001, not propagated, with only one
      W3 node;
    - keeps the round-2 graph whole, with no duplicate nodes.
  - **Repeat and rebuild:** repeating reuses everything, adds no history
    entry and keeps the graph identical; `--rebuild-graph` reproduces it.
  - **Round 4:** takes up W3001 and creates no W4 nodes.
  - **Refusals:** a missing round 2, an inconsistent stage record, a stale
    round-2 input and a modified round-2 snapshot are each refused without
    running anything; round 1 and garbage are usage errors.
  - **Default:** `--round 2` equals the default.
- All earlier pipeline and propagation tests pass unchanged.
- A run on a copy of the EventBus pipeline, reading its saved round-2 result
  `round2-fc06b8b3c12e` (17 warnings), with real NullAway:
  - **Matched:** 4 warnings, all introduced in round 2: W2006
    `handleSubscriberException` parameter `event`, W2008
    `PendingPostQueue#poll(int)`, W2009 `SubscriberExceptionEvent#throwable`
    and W2010 `AsyncExecutor#scope`. Packages P3-0001..4, all COMPLETED.
  - **Introduced by the packages' own evaluations:**
    - P3-0001: W3001, the dereference `event` at EventBus.java:540, and
      W3002, `event` passed on at :544;
    - P3-0004: W3003, `scope` passed on at AsyncExecutor.java:126.
  - **Combined:** 17 -> 16 findings, with 4 resolved, 3 introduced and 13
    persisting.
  - **Graph:** 116 nodes and 88 edges. Input warnings are placed 12
    persisting-from-base and 5 linked-to-package-warning.
  - **Untouched:** round 2's record and artifacts.
  - **Repeat:** about 1 s with no checker run, and an identical graph.
