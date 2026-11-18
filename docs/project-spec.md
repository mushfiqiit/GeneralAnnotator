# GeneralAnnotator — Project Specification

Status: draft. Stage 1 (*Check*) is implemented for NullAway (tasks T1–T4a in
`docs/tasks.md`), followed by construction of the initial warning graph (T4b).
No other pipeline stage is implemented yet.

## 1. Purpose

GeneralAnnotator is a Java research tool for studying how pluggable type checkers'
warnings can be resolved by adding type annotations. It runs a checker on a
Java repository, asks a proposer (first a human, later an LLM) for annotation
edits, applies each edit in an isolated repository state, reruns the checker,
and records how warnings change. That record supports exploring and choosing
sequences of annotations.

## 2. Intended pipeline

The stages are numbered by data flow, not by implementation order.

1. **Check.** Run a type checker on a target Java repository and collect its
   diagnostics.
2. **Slice.** For a chosen warning, use [Specimin](https://github.com/njit-jerse/specimin)
   to make a smaller, compilable program slice that still reproduces the warning.
3. **Extract context.** Gather usages of the symbols involved in the warning.
   Also include usages that sit in regions the checker's diagnostics classify as
   unsafe (see §5, *unsafe region*).
4. **Propose.** Ask a proposer for type annotation edits. The first proposer is
   a manual, file-based one. LLM proposers come later.
5. **Apply and recheck.** Apply each proposed edit in its own isolated
   repository state and rerun the checker there.
6. **Record.** Store warning/action/resolution relationships and the state
   transitions between repository states.
7. **Explore and select.** Explore annotation sequences and select among them
   using a defined objective.
8. **Batch.** Eventually, run several annotation attempts together when they
   are independent.

## 3. Initial scope

In scope for the first milestones:

- Java implementation, built with Gradle (Kotlin DSL) via the Gradle Wrapper.
- **NullAway** (an Error Prone plugin) as the first and only checker.
- Small, deterministic fixture projects and captured checker output for testing
  pipeline logic.
- Manual annotation proposals (human-authored edit files) before any real LLM
  integration.
- Applying one annotation edit at a time, each in an isolated repository state.

Out of scope for now:

- Checkers other than NullAway.
- Real LLM calls.
- Sequence exploration/selection algorithms and batching (stages 7 and 8).
  These wait on the unresolved decisions in §6.
- Performance work, distributed execution, and UI.

## 4. Architectural constraints

- Checker, slicer, and LLM/proposer integrations sit behind Java interfaces.
  Pipeline logic must be testable with fake implementations and fixtures.
- **Repository-state isolation.** The original target repository is never
  modified in place. Each attempt runs in its own state (for example a copy or a
  git worktree; the mechanism is still open, see §6). Attempts must not see each
  other's edits.
- **Findings vs. failures.** A *checker finding* (the checker ran and reported a
  diagnostic) is a different kind of result from an *execution failure* (the
  tool or build could not run, crashed, timed out, or failed for reasons unrelated
  to the checker). These must never be merged or confused. For example, a
  compilation error caused by a malformed edit is not a "new warning".
- Pipeline logic is tested with deterministic fixtures. Tests that call real
  external tools are kept apart from unit tests.

## 5. Terminology

| Term | Meaning |
|------|---------|
| **Target repository** | The Java project being analyzed and annotated. |
| **Checker** | A pluggable type checker run on the target. Initially NullAway. |
| **Diagnostic** | One message the checker emits: location, checker name, message text, and severity. |
| **Warning** | A diagnostic that reports a potential type error (for NullAway, a nullability error). Used loosely as a synonym for a checker finding. |
| **Checker finding** | A diagnostic produced by a checker run that completed. |
| **Execution failure** | A run that did not produce a trustworthy set of findings, such as a build-tool error, an unrelated compilation error, a crash, a timeout, or a missing tool. |
| **Warning identity** | The rule that decides whether a diagnostic in one state is "the same warning" as one in another state. *Unresolved* (§6). |
| **Slice** | A reduced, compilable program Specimin produces that keeps the code needed to reproduce a target warning. |
| **Symbol usage** | A source location that references a symbol (field, method, parameter, local, or type) relevant to a warning. |
| **Enclosing region** | A syntactic region (for example a method or class body) that contains a diagnostic or a usage. |
| **Safe / unsafe region** | A classification of enclosing regions based on checker diagnostics. *Definition unresolved* (§6). |
| **Annotation edit (action)** | One concrete source change that adds, removes, or changes a type annotation at one program element, such as adding `@Nullable` to a parameter. |
| **Proposal** | A set of candidate annotation edits from a proposer (manual or LLM) for a given warning and context. |
| **Proposer** | The component that makes proposals: manual (file-based) first, LLM later. |
| **Repository state** | An identified, immutable snapshot of the target's source after a specific sequence of annotation edits. |
| **Attempt** | Applying one edit (or later, a batch) to a repository state in isolation, then rerunning the checker. |
| **State transition** | The link from a source state to a result state through an attempt, together with the attempt's outcome. |
| **Resolution** | The effect of an attempt on a warning: for example resolved, persisting, or newly introduced. Depends on warning identity. |
| **Annotation sequence** | An ordered series of edits leading from the initial state to a later state. |
| **Warning graph** | Per-run graph of warning nodes and (later) other nodes and edges. The initial graph is built from a completed analysis only: one root warning node per finding, no edges. Saved as `warning-graph.json`. |
| **Warning node** | A graph node for one checker finding observed in one run. Its ID (`W0001`, ...) identifies that *occurrence within the run*, not a warning identity across runs. It holds the complete diagnostic. |
| **Root / distance** | Initial warning nodes are roots with distance 0. How distance grows for nodes added later (for example per annotation step) is not defined yet (§6). |
| **Batch** | A set of edits attempted together because they are believed to be independent. *Criteria unresolved* (§6). |

## 6. Unresolved design decisions

These questions are open. Code must not quietly encode an answer to them. When
a task needs a provisional choice, it must be isolated (for example behind an
interface or in one named class), labeled provisional, and noted here.

### Research decisions (explicitly unresolved)

1. **Exact warning identity and matching rules.** What makes two diagnostics
   in different repository states the same warning? (Stage 1 records
   per-run observations only: path, line, caret column, tag, and message.
   It deliberately defines no matching.) Candidates include file plus
   line, enclosing element plus message key, or AST-anchored positions that survive
   line shifts caused by edits. This decision determines what counts as
   "resolved" and "introduced".
2. **Definition of safe/unsafe enclosing regions.** Which region granularity
   is used (statement, method, class), and which diagnostics make a region
   unsafe?
3. **Annotation reversal and cycle handling.** Can an edit be undone or
   reversed later in a sequence? How are cycles (returning to an earlier state)
   detected and treated during exploration?
4. **Sequence-selection objective.** What is a "good" annotation sequence?
   Possible measures are fewest remaining warnings, fewest edits, no newly
   introduced warnings, or some weighted combination.
5. **Criteria for independent annotation batches.** When can several edits be
   attempted together without their effects interfering?

### Engineering decisions settled for stage 1 (revisit if they stop fitting)

- **NullAway invocation: a controlled `javac` + Error Prone command line, not
  the target's build.** This was decided after inspecting EventBus. Its
  wrapper is Gradle 6.8.3, which cannot run on the JDK 21 that Error Prone 2.50
  needs. Its build also includes Android modules that need an Android SDK, which
  is not installed. The NullAway processor path comes from the local checkout's
  own build (an external init script that adds one task and reuses the
  configurations of NullAway's `buildWithNullAway` task). The built jar must lie
  inside `GA_CHECKER_HOME`. Consequences: source roots (and any classpath) must
  be configured explicitly, and annotation processors and generated sources are
  not supported.
- **AnnotatedPackages.** By default it is derived from the `package`
  declarations of the analyzed sources and reduced to minimal prefixes. It can be
  overridden with `GA_NULLAWAY_ANNOTATED_PACKAGES`.
- **Evidence that the checker ran.** Each javac invocation also compiles a
  generated probe file outside the target, with one known NullAway violation in
  its own annotated package. A missing probe finding is an execution failure.
- **Findings vs. failures.** A sealed `CheckerOutcome` has two types:
  `CompletedAnalysis` and `ExecutionFailure` (with a kind, reasons, and the
  incomplete findings). javac exiting 1 only because of NullAway errors counts as
  completed. The classification rules are in `NullAwayResultClassifier`.
- **Isolation for this milestone: a full directory copy per run.** For a git
  target, the copy holds the files from `git ls-files --cached --others
  --exclude-standard`, with current working-tree contents. Otherwise a tree walk
  is used, with fixed exclusions. Symlinks are skipped and reported. This is not
  the long-term mechanism for repository *states* (see below).
- **JSON.** `report.json` and `warning-graph.json` are written by a small
  in-house writer (`report.Json`). They are read back by a small, strict
  in-house RFC 8259 parser (`report.JsonParser`), added for graph save/load.
  The project still has no runtime dependencies. Revisit this if proposal files
  need richer JSON handling.
- **Initial warning graph.** It is built only from a `CompletedAnalysis`; a
  failed run writes no graph, and `report.json` says why. IDs `W0001`, ... are
  assigned after a deterministic sort (target path, line, column, severity,
  checker, message, snippet). They are run-local occurrence IDs; this is *not*
  a decision on §6.1. Node kinds are a sealed type with only `warning` for now.
  The edge record (`source`, `target`, `kind`) exists so the edges collection
  is typed, but no edge kinds are defined.

### Engineering decisions (open, to be settled by the task that needs them)

- Warning-graph expansion: which node kinds (for example annotation actions,
  warnings observed in later states) and edge kinds are added, what "distance"
  measures for non-root nodes, and how nodes from different runs or states
  relate. This depends on §6.1 and §6.3.
- Isolation mechanism for *multiple* repository states (copy per state, `git
  worktree`, overlay, etc.) and how states are identified.
- Supporting targets whose sources need dependency resolution or generated
  code, for example by extracting the classpath from the target's build.
- Which nullability annotation library edits use (JSpecify, or the target's
  existing annotations).
- File format for manual proposals and which serialization library to use for
  reading them.
- How Specimin is invoked and pinned (version, JDK requirements).

## 7. Current status

| Stage | Status |
|-------|--------|
| Build skeleton (Gradle Wrapper, JUnit) | Implemented |
| 1. Check (NullAway) | Implemented: `check` command on configured source roots of one target, verified on fixtures and on EventBus core (`EventBus/src`) |
| Initial warning graph | Implemented: root warning nodes (distance 0), no edges, `warning-graph.json` save/load. No expansion. |
| 2. Slice (Specimin) | Not implemented |
| 3. Extract context | Not implemented |
| 4. Propose (manual / LLM) | Not implemented |
| 5. Apply and recheck | Not implemented |
| 6. Record | Not implemented |
| 7. Explore and select | Not implemented; blocked on §6 decisions |
| 8. Batch | Not implemented; blocked on §6 decisions |
