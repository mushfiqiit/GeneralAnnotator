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

## T1 — Parse NullAway diagnostics from captured compiler output · `next`

Add an immutable diagnostic model and a parser that turns captured
`javac`/Error Prone output text into diagnostics. This task does not run any
process.

- Model fields: source path, line, optional column, severity (`error` or
  `warning`), checker name (such as `NullAway`), and message text.
- Parse Error Prone's format, for example
  `path/Foo.java:12: warning: [NullAway] dereferenced expression x is @Nullable`,
  including the source-snippet and caret lines and the trailing
  `(see http://t.uber.com/nullaway )` line that follow it.
- Ignore diagnostics that are not NullAway, but expose them separately or count
  them. Do not drop them silently.
- Fixtures: captured output text files under `src/test/resources/` covering no
  diagnostics, one diagnostic, several diagnostics, mixed `error`/`warning`
  severities, a non-NullAway compiler error, and malformed or truncated output.

Acceptance: the parser returns the expected diagnostics for every fixture.
Malformed input produces a clear result or exception, never a partial result
that silently loses data.

## T2 — Classify a checker run: findings vs. execution failure · `todo`

Given a captured run result (exit code, stdout, stderr, and timed-out flag),
produce one of two kinds of result: a successful check with its NullAway
findings, or an execution failure with a reason.

- A non-zero exit caused only by NullAway errors is a successful check with
  findings.
- Non-NullAway compilation errors, a missing tool, a timeout, or output that
  cannot be parsed is an execution failure.

Acceptance: fixture-based tests cover each case, and the two outcome kinds are
distinct types (or a sealed hierarchy), not a flag.

## T3 — External command runner · `todo`

Add a `CommandRunner` interface and a process-based implementation. It takes
the command, working directory, environment, and timeout, and captures exit
code, stdout, stderr, duration, and whether the command timed out.

Acceptance: tests use trivial portable commands to check exit codes, output
capture, and timeout. A fake runner is available for later tests.

## T4 — NullAway checker on a fixture project · `todo`

Add a `Checker` interface and a NullAway implementation that uses T2 and T3.
Add a tiny fixture Java project with known NullAway warnings. Decide and
document how NullAway is invoked (project-spec §6, engineering decisions).

Acceptance: an integration test, kept separate from unit tests (for example a
tagged or separate test task), finds exactly the expected warnings in the
fixture. Unit tests run with a fake runner.

## T5 — Isolated repository states · `todo`

Create isolated working copies of a target repository, each with an
identifier. Choose and document the isolation mechanism.

Acceptance: tests show that edits in one state are visible neither in the
original repository nor in other states, and that states can be cleaned up.

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
