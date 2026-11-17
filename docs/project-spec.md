# GeneralAnnotator — Project Specification

Status: draft, bootstrap stage. Only the build skeleton exists. No pipeline stage
described here is implemented yet; see `docs/tasks.md` for what is planned next.

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
| **Batch** | A set of edits attempted together because they are believed to be independent. *Criteria unresolved* (§6). |

## 6. Unresolved design decisions

These questions are open. Code must not quietly encode an answer to them. When
a task needs a provisional choice, it must be isolated (for example behind an
interface or in one named class), labeled provisional, and noted here.

### Research decisions (explicitly unresolved)

1. **Exact warning identity and matching rules.** What makes two diagnostics
   in different repository states the same warning? Candidates include file plus
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

### Engineering decisions (open, to be settled by the task that needs them)

- Isolation mechanism for repository states: directory copy, `git worktree`,
  or something else.
- How NullAway is invoked on target repositories: through the target's own
  build, or through a controlled `javac` + Error Prone command line. This also
  covers how NullAway options such as `AnnotatedPackages` are chosen.
- Which nullability annotation library edits use (JSpecify, or the target's
  existing annotations).
- File format for manual proposals and for recorded results, and which
  serialization library to use.
- How Specimin is invoked and pinned (version, JDK requirements).

## 7. Current status

| Stage | Status |
|-------|--------|
| Build skeleton (Gradle Wrapper, JUnit) | Implemented |
| 1. Check (NullAway) | Not implemented |
| 2. Slice (Specimin) | Not implemented |
| 3. Extract context | Not implemented |
| 4. Propose (manual / LLM) | Not implemented |
| 5. Apply and recheck | Not implemented |
| 6. Record | Not implemented |
| 7. Explore and select | Not implemented; blocked on §6 decisions |
| 8. Batch | Not implemented; blocked on §6 decisions |
