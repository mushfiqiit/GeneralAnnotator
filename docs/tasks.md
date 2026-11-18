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

## T6 — Annotation edit model and applier · `todo`

Model one annotation edit (target element and location, annotation, and
import), and apply it to Java source text in a repository state.

Acceptance: fixture-based before/after source tests. Applying an edit to a
location that does not exist fails explicitly.

## T7 — Manual proposal source · `todo`

Add a `Proposer` interface and a manual implementation that reads edits from a
human-written proposal file. Choose and document the file format.

Acceptance: valid and invalid proposal-file fixtures are tested.

## T8 — Single attempt: apply, recheck, compare · `todo`

For one warning and one edit: create a state, apply the edit, rerun the checker,
and report the before/after findings or an execution failure. Any warning
matching done here must go through a pluggable `WarningMatcher` with a
provisional rule that is recorded in project-spec §6.

Acceptance: tests with fake checker results cover warning resolved, warning
persists, new warning introduced, and execution failure after the edit.

## T9 — Record attempts and state transitions · `todo`

Persist warning/action/resolution records and state transitions in a
documented, append-only format.

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

## T12 — Include usages from unsafe regions · `blocked`

Blocked on project-spec §6.2 (safe/unsafe region definition).

## T13 — LLM proposer · `todo`

Add an LLM implementation of `Proposer` behind the existing interface. Tests
use recorded or faked responses only.

## T14 — Sequence exploration and selection · `blocked`

Blocked on project-spec §6.1, §6.3, and §6.4.

## T15 — Batching independent attempts · `blocked`

Blocked on project-spec §6.5.
