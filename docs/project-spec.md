# GeneralAnnotator — Project Specification

Status: draft. Stage 1 (*Check*) is implemented for NullAway (tasks T1–T4a in
`docs/tasks.md`), with the initial warning graph (T4b), visualization (T4c),
Specimin target members (T4d), Specimin slice generation without reproduction
validation (T10a), one-hop usage-context extraction (T11, T11a), and LLM
prompt-unit assembly without LLM calls (T13a), and the first half of Wulver
inference (T13b): an immutable Slurm submission and the GPU worker. Upload,
submission, monitoring and collection (T13c, T13e) have been used on Wulver.
Collected proposals can be applied to a fresh copy of the run snapshot (T6,
`apply-proposal`). Each distinct complete edit set proposed by the collected
answers can be evaluated as one annotation package against one baseline with
the checker; answers proposing exactly the same set share the package. The
results are recorded as an expanded graph (T8, T8b, T8c,
`evaluate-annotations`). Annotation sequences, an append-only transition log
and search are not implemented.

## 1. Purpose

GeneralAnnotator is a Java research tool for studying how pluggable type checkers'
warnings can be resolved by adding type annotations. It runs a checker on a
Java repository, asks a proposer (first a human, later an LLM) for annotation
edits, applies each proposal (its edit set, as one package) in an isolated
repository state, reruns the checker,
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
- Applying one proposal's annotation edits at a time (one package), each in an
  isolated repository state.

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
| **Warning identity** | The rule that decides whether a diagnostic in one state is "the same warning" as one in another state. *Unresolved* (§6); T8 uses the provisional rule `anchored-1` behind `WarningMatcher`. |
| **Slice** | A reduced, compilable program Specimin produces that keeps the code needed to reproduce a target warning. |
| **Symbol usage** | A source location that references a symbol (field, method, parameter, local, or type) relevant to a warning. |
| **Enclosing region** | A syntactic region (for example a method or class body) that contains a diagnostic or a usage. |
| **Safe / unsafe region** | A classification of enclosing regions based on checker diagnostics. *Definition unresolved* (§6). |
| **Annotation edit (action)** | One concrete source change that adds, removes, or changes a type annotation at one program element, such as adding `@Nullable` to a parameter. |
| **Proposal** | A set of candidate annotation edits from a proposer (manual or LLM) for a given warning and context. |
| **Proposer** | The component that makes proposals: manual (file-based) first, LLM later. |
| **Repository state** | An identified, immutable snapshot of the target's source after a specific sequence of annotation edits. |
| **Attempt** | Applying one annotation package (one complete edit set, together; later perhaps a batch of packages) to a repository state in isolation, then rerunning the checker once. |
| **Annotation package** | A complete edit set proposed together by one or more validated answers, possibly for different warnings, with every answer kept as an origin (provenance). Two answers share a package exactly when their edit sets are equal (T8c). It is applied all or nothing and evaluated as a unit; its edits are never evaluated separately (T8b), and packages that only overlap are never merged or split. |
| **State transition** | The link from a source state to a result state through an attempt, together with the attempt's outcome. |
| **Resolution** | The effect of an attempt on a warning: for example resolved, persisting, or newly introduced. Depends on warning identity. |
| **Annotation sequence** | An ordered series of edits leading from the initial state to a later state. |
| **Warning graph** | Per-run graph of warning nodes and (later) other nodes and edges. The initial graph is built from a completed analysis only: one root warning node per finding, no edges. Saved as `warning-graph.json`. An evaluation's expanded graph (`evaluation-graph.json`, schema version 5) adds annotation nodes (one per unique package), result-state warning nodes and `resolved-by` / `introduces` edges. |
| **Round** | One iteration of LLM inference and evaluation (not one annotation attempt, not a graph distance). Round 1 evaluates answers about the initial warnings. Introduced warnings carry their round's IDs (`W1001, ...` for round 1, `W2001, ...` for round 2), and the round is stored explicitly on nodes and records. |
| **Baseline state** | `S0`: an evaluation's immutable copy of the run snapshot, checked once; every attempt starts from it. The state after edit `A` is `S0+A`. |
| **Annotation node** | One annotation package evaluated (or still to be evaluated) against one baseline state, holding all its edits, an explicit outcome and every originating answer (as associations, not edges). |
| **Warning node** | A graph node for one checker finding observed in one run. Its ID (`W0001`, ...) identifies that *occurrence within the run*, not a warning identity across runs. It holds the complete diagnostic. |
| **Root / distance** | Initial and baseline warning nodes are roots with distance 0. Provisionally (T8), a warning node's distance is the number of edits applied to the baseline in the state where it was observed, so warnings introduced by one evaluated edit have distance 1. Distance across longer sequences is not defined yet (§6). |
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
   **Provisional (T8), isolated in `evaluation.matching.AnchoredWarningMatcher`
   (`anchored-1`) behind the `WarningMatcher` interface:**
   - The primary key is the normalized project-relative path, checker,
     severity, whitespace-collapsed message, enclosing declaration and source
     line with annotations, `final` and whitespace removed.
   - The declaration is the innermost member, initializer, enum constant or
     type whose JavaParser range contains the finding. It is identified by its
     type chain and member signature, with annotations removed from parameter
     types (`DeclarationAnchors`).
   - Equal multiplicity per key means persisting. One empty side means
     resolved or introduced.
   - Different non-zero multiplicities are paired by line offset within the
     declaration, but only if every anchor is resolved and the offsets are
     unique; otherwise the group is ambiguous and supports no claim.
   - Counts, run-local IDs and raw line/column are never identity.
   - Assumption: edits insert no line breaks (the AST applier splices inline).
   - Known consequence: a message that lists several fields changes when one
     field is annotated. On EventBus W0037 this was recorded as resolved plus
     introduced, not as a narrowed warning.
   - A key that occurs equally often before and after persists, even if one
     occurrence was resolved and an indistinguishable one introduced.
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
  The only runtime dependency is JavaParser 3.28.2 (T6, the annotation
  applier); JSON stays in-house. Revisit this if proposal files need richer
  JSON handling.
- **Initial warning graph.** It is built only from a `CompletedAnalysis`; a
  failed run writes no graph, and `report.json` says why. IDs `W0001`, ... are
  assigned after a deterministic sort (target path, line, column, severity,
  checker, message, snippet). They are run-local occurrence IDs; this is *not*
  a decision on §6.1. Node kinds are a sealed type with only `warning` for now.
  The edge record (`source`, `target`, `kind`) exists so the edges collection
  is typed, but no edge kinds are defined.

- **Visualization.** It lives in a separate `visualization` package; the
  graph model has no visualization code or dependency. The command reads a
  saved `warning-graph.json` (it never reruns the checker), writes DOT, and
  renders SVG through the `CommandRunner` with Graphviz `dot` (overridable
  with `GA_GRAPHVIZ_DOT`). Edge-free graphs use the `osage` layout through the
  `layout` graph attribute, because `dot`'s own layout lines disconnected
  nodes up in one row, and with Graphviz 12.1 its packing ignored `sortv` and
  swapped the first and last component. Escaping was verified against
  Graphviz 12.1: a backslash becomes `\\` in labels and `\\\\` in
  tooltips, because tooltips are expanded twice; `&` becomes `&amp;`. The
  real-Graphviz integration test guards against version changes.

- **Specimin targets.** Members are located with the JDK's syntax tree
  API (`com.sun.source`, parse only, no new runtime dependency) on the run's
  isolated snapshot. The caret position decides; messages only tell apart
  fields declared together, and a conflict is unresolved. Format and rules
  follow Specimin's source (mushfiqiit/specimin `b1e5d43`): nested classes
  joined with `.`, constructors as `Class#SimpleName(...)`, parameter types as
  written without annotations, method varargs `T...` but constructor varargs
  without `...` (as JavaParser 3.28.2 prints them). Lambdas, anonymous and
  local classes map to the enclosing member of a named class. Initializer
  blocks, enum constants, record components, compact constructors and
  annotation elements are unresolved. Specimin's `--root` is derived from the
  `package` declaration. A test cross-checks signatures against JavaParser
  3.28.2 (test-only dependency). A target does *not* establish that a slice
  reproduces the warning.

- **Specimin invocation.** The local checkout (`GA_SPECIMIN_HOME`) is
  built with its own `./gradlew installDist`, and Specimin runs as
  `java <Specimin's JavaExec --add-exports flags> -cp <install/lib jars>
  <manifest Main-Class> ...`. The start script was not used because it lacks
  those flags and fails in google-java-format. The modularity model comes
  from the graph's checker (`nullaway`). Dependency jars are passed only when
  `GA_SPECIMIN_JAR_PATH` is set. Every warning runs separately against a
  disposable copy of its source root, because Specimin may write into
  `--root`. GENERATED means exit 0 and at least one `.java` file;
  reproduction stays NOT_VALIDATED until T10b.

- **Usage context (T11).** `context` attributes all Java files under the
  configured source roots of the run snapshot in one javac task (JDK
  compiler API, `-proc:none`, no output files, attribution continues after
  errors). Context is extracted from the original snapshot, not from the
  Specimin slice. References are matched by the javac symbol of the target
  member. The target is found in the attributed tree by its Specimin
  signature from `warning-targets.json`. Two method symbols with the same
  owner, name and erased parameter types count as the same method, because
  diamond inference creates per-call copies. Never trusted, and listed as
  unresolved: references without a symbol, references where javac reported an
  error inside the reference itself (outside its arguments), and calls with an
  erroneous argument type when another overload accepts that number of
  arguments. Evidence is one hop: call sites (arguments, result consumption,
  later uses of a local that stores the result), parameter uses, returns,
  override links in both directions, and field initializers, writes and reads.
  Null guards are recognized syntactically. Limits are configurable, and
  omissions are counted. All of this is a heuristic. It does not establish
  which annotation is correct, and it applies no safe/unsafe policy (§6.2).
- **Local alias flow and the compact view (T11a).** Later uses of a local
  that stores the evidence value are attributed conservatively and textually.
  A reassignment that is an unconditional statement earlier in a block
  containing the use ends attribution. A reassignment on some paths (a branch,
  or later in an enclosing loop), or a storing assignment that runs on some
  paths only, makes a use `MAY_BE_REASSIGNED`. Exceptions and labeled jumps
  are ignored. Evidence is deduplicated by kind, location and symbol. Before
  the item limit, distinct evidence (kind, consumption, observations) is kept
  first, round robin. `context/<ID>.txt` is the compact LLM-facing view built
  from the kept evidence: member blocks, merged lines in source order, notes,
  explicit gaps. `context/<ID>.json` keeps the full details. For class-file
  declarations only nullness-named and type-use annotations are reported.

- **Prompt units (T13a).** `prompts` joins the warning graph, targets, slice
  results, slice files and `context/<ID>.txt` by warning ID. It requires one
  `runId` across graph, targets and slice results, and checks per warning
  that locations and target members agree and that `context/<ID>.json` names
  the same run and warning. The JSON is never put into prompts. Each unit
  holds exactly one warning. Slice files are verbatim, sorted by path, in
  blocks whose end markers carry a SHA-256 prefix of the content. Line numbers
  appear only in the usage context, labeled as original. Units over the
  configured limits are OVERSIZED, never truncated. The template is
  versioned (`prompt-template-1`), and the manifest has no timestamps.

- **Wulver inference, first half (T13b).** `prepare-inference` copies the
  GENERATED prompts byte for byte into an immutable `inference/<id>/`
  directory, built under a temporary name, renamed when complete and made
  read-only. It records full SHA-256 hashes and assigns prompts
  deterministically: numeric warning order, 1 to 10 prompts per array task,
  default 1. Every prompt is a separate request. The worker is standard-library
  Python, so nothing is installed on the cluster. It runs only inside a Slurm
  GPU allocation with enough GPU memory, owns a localhost vLLM server with
  offline cached weights, checks prompt length before sending (never
  truncating), and validates answers against `prompt-template-1`. Every attempt
  is saved atomically (raw response, reasoning, answer, metadata), and
  completed results are kept across resubmissions. **Provisional defaults,
  isolated in `inference.InferenceSettings` and needing a GPU smoke test:** the
  historical served name, 32768 context and `qwen3` parser; sampling 0.6 /
  0.95 / top-k 20 / seed 0 from the Qwen3 model card; `max_tokens` 0 (rest of
  the context, because 16384 truncated answers historically); a minimum of
  70000 MiB of GPU memory; 2 hours per task; array concurrency 4.

- **Wulver transfer and submission (T13c).** OpenSSH connection sharing: one
  interactive login (password and Duo) in the user's terminal opens a
  ControlMaster. All other ssh/rsync calls reuse it with `BatchMode=yes` and
  never prompt, so a lost connection asks for reconnection (exit 5). Each
  submission gets its own remote directory, and the upload is verified with
  remote SHA-256 before sbatch. One array job is submitted per explicit
  request. The intent is persisted before sbatch and the job ID after it.
  Duplicates are blocked by state, a local lock and a remote `mkdir` marker.
  Ambiguous outcomes are UNKNOWN until resolved by a scheduler-verified
  `attach`. Retries are new array jobs in the same directory, allowed only
  when every task has finished; the worker keeps completed results.
  **Provisional defaults, isolated in `inference.remote.RemoteSettings`:**
  - target `mc2399@wulver.njit.edu`, remote base `/project/mjk76/mc2399` with
    `generalannotator/inference/<run id>/<submission id>`;
  - managed socket directory `~/.ga-ssh`, ControlPersist 4h, ServerAliveInterval
    15 s (T13e);
  - a connection is healthy only after a remote round trip (`printf` marker,
    ConnectTimeout + 15 s), and non-interactive calls add `ProxyCommand=false`
    so they can never fall back to a fresh login (T13e);
  - remote commands run with `bash -l -c`;
  - polling every 300 s, and monitoring stops after 3 polls with unknown state;
  - retries skip `PROMPT_TOO_LONG`, `PROMPT_HASH_MISMATCH`, `PROMPT_MISSING` and
    `UNSUPPORTED_TEMPLATE` (`RemoteInference.NOT_RETRYABLE`).

### Engineering decisions (open, to be settled by the task that needs them)

- Warning-graph expansion. **Provisional (T8), in `evaluation.EvaluationGraph`:**
  - Baseline warning nodes (state `S0`) are kept.
  - **Annotation packages (T8b, T8c):**
    - Each validated answer's complete edit set, deduplicated within the answer
      (provenance kept), is a package. A package is applied all or nothing in
      one workspace and checked once.
    - **Equivalent packages are merged (T8c).** Provisional, in
      `evaluation.Candidates.merge`. Answers, possibly about different
      warnings, whose complete edit sets are equal share one package. Every
      answer is kept as an origin: warning, answer, attempt, prompt hash,
      answer file and hash, rationale, uncertainty, and per-edit answer
      indices and uncertainties.
      - Equality is exact `AnnotationEdit` equality on sets (source root,
        source-relative file, declaring class, member signature, position
        with parameter index and type-use path, action, annotation name).
        Order and repeated entries are ignored.
      - The only normalization is what the edit model already does: type text
        and a type-use path without steps. Names are not resolved, so
        `String` and `java.lang.String` differ.
      - Overlapping and subset packages stay separate, and packages are never
        split.
      - This replaces T8b's rule that packages from different answers stay
        separate even when equal.
    - Each package is one annotation node holding every edit with its applier
      outcome and every origin as a proposal association (not an edge).
      - Its identity is the complete canonical edit set plus the baseline,
        configuration and round. That is the evaluation key
        `equivalent-package-1` and the per-attempt edit set, compared field by
        field; the package key, a hash, only indexes it.
      - Edits are kept in canonical order (`Candidates.editKey`). The ID
        `A0001, ...` follows the numeric order of each package's first
        warning.
      - `--warning` selects a package if any origin matches.
    - **Rebuild from one-package-per-answer evaluations (T8c).**
      `evaluate-annotations --reuse-from`, in `evaluation.PackageReuse`, reuses
      a T8b checker result only if all of the following hold:
      - run, submission, snapshot tree hash, configuration fingerprint,
        matcher and round are equal;
      - the new baseline's findings and checker identity equal the earlier
        ones;
      - the earlier attempt recorded exactly the merged package's edit set,
        and its answer is one of the package's origins with the same answer
        hash;
      - every complete earlier attempt of the package has an equivalent
        result (paths inside each attempt directory ignored).
      Conflicts are reported and the package is evaluated again. Rejected
      and no-op results are re-derived. Introduced warnings get the round IDs
      a fresh evaluation would give, and the mapping is recorded. Earlier
      directories are only read.
    - Every node has an explicit outcome: UNEVALUATED, INTERRUPTED, COMPLETED,
      AMBIGUOUS, NO_OP (all edits already satisfied), REJECTED (any edit
      rejected or conflicting, or a suppressing edit), a failure kind, or
      NOT_COMPARABLE.
    - Earlier single-edit evaluations (T8) treated each distinct edit as its own
      candidate. That split multi-edit answers (W0037's three fields) into
      edits whose separate results do not establish the answer's effect. They
      keep a different evaluation key, so they are never reused or combined
      into package results (`--reuse-from` refuses them).
  - `W -> A` (`resolved-by`) is added for every baseline warning a trustworthy
    comparison of the whole package found resolved, proposed or not. Never from
    a count reduction alone.
  - Each introduced finding gets a new warning node (state `S0+A`, distance 1,
    the evaluation's round) and `A -> N` (`introduces`). Introduced findings of
    different attempts are never merged. A shared package is one attempt, so its
    introduced warnings exist once. Edges are deduplicated.
  - **Warning IDs by round (T8a), in `graph.WarningIds`:**
    - IDs: initial `W0001...`; round `r` uses `"W" + r + 3-digit sequence`.
    - Every attempt of a round shares the round's sequence. IDs are allocated
      when an attempt is recorded, after the highest ID in the completed
      records, so they are never renumbered and an interrupted attempt leaves
      none behind.
    - The order follows evaluation order: candidate order for an unfiltered
      run, and filtered candidates first otherwise.
    - The round is metadata on nodes and records, never parsed from the ID
      alone, because `W1001` is also a valid initial ID.
    - Overflow past 999 and collisions with existing IDs (more than 999 initial
      warnings) are refused.
    - `evaluate-annotations` always evaluates round 1. Where round 2 comes from
      (inference on introduced warnings, and which state it starts from) is
      open, together with annotation sequences (§6.3, §6.4).
    - Legacy evaluations (`A0003-N1`, records schema 1, graph schema 2) are
      converted only by `migrate-warning-ids`, with a backup. It establishes
      round 1 only when every candidate was proposed for an initial warning of
      the run; otherwise it refuses.
  - The originating answer is node data, not an edge.
  - The graph is rebuilt from per-attempt records after every invocation.

  Open:
  - how nodes of states after several packages relate, and what distance means
    there (§6.3, §6.4);
  - whether packages that only share some edits should share work (they are
    evaluated separately, so a common edit is checked again and warnings it
    introduces can appear once per package);
  - whether package equivalence should go beyond the edit model's syntactic
    normalization, for example by resolving type names in member signatures
    (`String` and `java.lang.String`) or treating annotations from different
    libraries as the same; this is not done, because it could merge ambiguous
    targets;
  - how the number and kind of answers proposing a shared package should
    count, for example as evidence when selecting sequences (§6.4); origins
    are recorded but not weighed;
  - what equivalent attempts with different recorded results (a
    `--reuse-from` conflict) mean, for example checker nondeterminism; they
    are reported and evaluated again, not investigated;
  - whether per-edit attribution inside a package is wanted (it is not
    attempted);
  - whether introduced warnings of different attempts should ever be
    identified with each other.
- Isolation mechanism for *multiple* repository states (copy per state, `git
  worktree`, overlay, etc.) and how states are identified. **Provisional (T8):**
  - Each attempt is an explicit file copy of the baseline copy
    (`SnapshotCopier`; symlinks skipped and reported; no git).
  - States are named `S0` and `S0+<candidate>`; edits never accumulate.
  - An evaluation directory is keyed by a hash of the snapshot tree, the
    checker configuration fingerprint (including classpath content hashes and
    the JDK `release` file), the candidate set and the matcher. Results are
    reused only under the same key.
  - The built checker is identified after each run (`ToolIdentity`: version,
    processor-path jar hashes, annotated packages). An attempt with a
    different identity is NOT_COMPARABLE.
- Evaluation policy (T8, provisional, in `Evaluator.suppression`): adding
  `@SuppressWarnings` or `@NullUnmarked`, or removing `@NullMarked`, is not
  evaluated (REJECTED), because it switches checking off instead of annotating
  types. Open questions:
  - Whether other annotations (for example `@Generated` with NullAway's
    TreatGeneratedAsUnannotated option, or `@Initializer`) need the same
    treatment.
  - NO_OP edits are not checked, because their state equals the baseline.
  - The baseline is always rerun; the run's original check is never reused,
    because the run records no source hashes and its classpath may differ. The
    baseline is compared with the run's `warning-graph.json` only to map
    warning IDs.
  - Every attempt reruns NullAway's Gradle setup (about 1 s on EventBus, plus
    about 1.5 s of javac). Caching the processor path is an optimization that
    has not been done.
- Supporting targets whose sources need dependency resolution or generated
  code, for example by extracting the classpath from the target's build.
- Which nullability annotation library edits use (JSpecify, or the target's
  existing annotations). **Provisional (T13a):** the prompt template's
  NullAway guidance (`prompts.PromptTemplate.CHECKERS`, the only place it is
  encoded) asks for the annotations the project already uses and otherwise
  `org.jspecify.annotations.Nullable`. `apply-proposal` writes whatever
  annotation a proposal names, fully qualified, without adding imports or
  dependencies, so compiling the edited copy needs the library on the
  classpath (`GA_TARGET_CLASSPATH` for `evaluate-annotations`; otherwise every
  applied edit is COMPILATION_FAILED).
- **Annotation edits and their application (T6), provisional choices:**
  - Edits are applied to a fresh copy of the run snapshot, never to slices.
  - The source root comes from `warning-targets.json` (the package root the
    prompt named).
  - Rewriting uses token-anchored text splices from JavaParser's tokens, not
    `LexicalPreservingPrinter`. In 3.28.2 that printer ignored in-place
    field-type changes, and after a replacement it produced invalid
    `@Anullableint`.
  - Placement follows the `edits.AnnotationKinds` table (type-use versus
    declaration annotations; unknown annotations only on simple types),
    together with its nullness-family conflict rules.
  - Parameter types match after normalization; raw versus parameterized forms
    match only when one side has no type arguments.
  - Multi-variable fields, C-style arrays and varargs parameters are rejected.
  - Existing annotations count only when their name is written qualified or
    single-type imported.

  Open questions:
  - whether to split multi-variable fields;
  - whether to resolve annotation `@Target`s from the classpath instead of the
    table;
  - how to detect a snapshot changed after the check (the run records no
    source hashes);
  - how applied proposals feed repository states (T5); `evaluate-annotations`
    evaluates each distinct complete edit set as one package against one
    baseline (T8b, T8c), not sequences of packages.
- Whether the worker should retry transient HTTP errors itself (it doesn't;
  `inference retry` resubmits failed prompts as new attempts).
- Whether a managed master that ignores `ssh -O exit` should ever be stopped
  automatically (by pid). Currently `connect` only reports it (T13e).
- Effective Wulver limits (account/QoS MaxSubmitJobs, GPU availability) for
  large arrays are unverified; only MaxArraySize=100001 is known. Whether
  `GA_INFERENCE_ARRAY_CONCURRENCY=4` is a good default depends on them (T13d).
- File format for manual proposals and which serialization library to use for
  reading them.
- What to do with warnings whose target is unresolved (for example
  initializer blocks). This might mean targeting all fields assigned in the
  block, but that would be a guess and is not done; they are SKIPPED.
- How slices are compiled and checked to validate reproduction (T10b), and
  how a warning in a slice is matched to the original warning (§6.1).
- How much context a proposer needs, and in what form. The current
  extraction is one hop, and the compact view is one fixed layout; whether
  it helps proposals is unmeasured.
  Possible extensions are following values through further calls, treating
  calls through an overridden supertype method as usages (dynamic dispatch),
  and including callers of callers. These should be decided by evaluating
  proposals (T13), not guessed now. Whether usages in unsafe regions are added
  or prioritized waits on §6.2 (T12).

## 7. Current status

| Stage | Status |
|-------|--------|
| Build skeleton (Gradle Wrapper, JUnit) | Implemented |
| 1. Check (NullAway) | Implemented: `check` command on configured source roots of one target, verified on fixtures and on EventBus core (`EventBus/src`) |
| Initial warning graph | Implemented: root warning nodes (distance 0), no edges, `warning-graph.json` save/load. |
| Graph expansion | Implemented provisionally (T8): baseline, annotation and introduced-warning nodes, `resolved-by` and `introduces` edges, `evaluation-graph.json` (schema version 5), introduced warnings with round IDs (`W1001, ...`; T8a), one annotation node per package (T8b), equivalent answers sharing one package and node (T8c), rebuild from earlier package evaluations (`--reuse-from`). Single packages only; no sequences. |
| Graph visualization | Implemented: `visualize` command (Graphviz DOT/SVG, hover tooltips). |
| 2. Slice: target identification | Implemented: Specimin target member per warning (`warning-targets.json`, `targets` command). |
| 2. Slice: generation | Implemented: `slice` command runs Specimin per warning into `slices/<ID>/` (`slice-results.json`). Reproduction is NOT_VALIDATED. |
| 2. Slice: reproduction validation | Not implemented (T10b). |
| 3. Extract context | Implemented for symbol usages: `context` command writes `context/<ID>.json`/`.txt` (one hop, symbol-based, no safe/unsafe policy). Unsafe-region usages (T12) blocked on §6.2. |
| 4. Propose (manual / LLM) | Prompt units (`prompts`), Wulver submissions with a GPU worker (`prepare-inference`), and upload, submission, monitoring, collection and retry over one shared SSH connection (`inference`), with a submit, disconnect, reconnect and collect workflow (T13e). Used on Wulver (jobs 1628816 and 1629432); the T13e health check and stale-connection recovery are tested with fakes only. Proposals are applied by `apply-proposal` and evaluated by `evaluate-annotations`. |
| 5. Apply and recheck | `apply-proposal` applies a proposal's edits, all or nothing, to a snapshot copy (`applications/`). `evaluate-annotations` applies each distinct complete edit set (equivalent answers merged) as one package to a copy of one baseline, reruns NullAway once on all source roots and compares findings with the provisional `anchored-1` rule (T8, T8b, T8c). Verified with fakes and manually on EventBus (39 answers, 27 unique packages, all completed). |
| 6. Record | Partly: per-attempt records, summary and the expanded graph (T8). No append-only state-transition log (T9). |
| 7. Explore and select | Not implemented; blocked on §6 decisions |
| 8. Batch | Not implemented; blocked on §6 decisions |
