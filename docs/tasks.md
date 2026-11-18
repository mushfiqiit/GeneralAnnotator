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

## T5 — Isolated repository states · `next`

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

## T10 — Specimin slicer integration · `todo`

Add a `Slicer` interface and a Specimin implementation that produces a slice
for a target warning, then confirms that the warning reproduces in the slice.

## T11 — Symbol usage extraction · `todo`

Extract usages of the symbols relevant to a warning, using fixtures.

## T12 — Include usages from unsafe regions · `blocked`

Blocked on project-spec §6.2 (safe/unsafe region definition).

## T13 — LLM proposer · `todo`

Add an LLM implementation of `Proposer` behind the existing interface. Tests
use recorded or faked responses only.

## T14 — Sequence exploration and selection · `blocked`

Blocked on project-spec §6.1, §6.3, and §6.4.

## T15 — Batching independent attempts · `blocked`

Blocked on project-spec §6.5.
