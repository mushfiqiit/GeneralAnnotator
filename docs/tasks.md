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

## T13d — Wulver end-to-end and GPU smoke test · `next`

On Wulver, with one or two prompts (`prepare-inference --warning`), confirm:
- the Duo login through `inference connect` (`ssh -M -N -f`), that
  `ControlPersist` keeps the master, and that `bash -l` on the login node finds
  `sbatch`, `squeue`, `sacct`, `sha256sum` and rsync;
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
