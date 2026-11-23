# GeneralAnnotator

GeneralAnnotator is a Java research tool for resolving pluggable type checker
warnings (starting with NullAway) through proposed type annotations, applied
and re-checked in isolated repository states.

**Status:** pipeline stage 1 (*Check*) is implemented for NullAway. The `check`
command copies a target project into an isolated working copy, runs NullAway
from a local checkout on it, and writes a report. After a completed analysis it
also writes the **initial warning graph**: one root node per finding, no edges.
For each warning it also identifies the **Specimin target member** (the
enclosing method, constructor or field). The `slice` command then generates one
Specimin slice per warning, but whether a slice still reproduces its warning is
**not validated** yet. The `context` command extracts **usage context** for each
warning's target (call sites, arguments, parameter uses, returns, field reads and
writes, null checks, annotations) from the run snapshot. The `visualize` command
renders a saved warning graph with Graphviz. The `prompts` command assembles
**one LLM prompt unit per warning** (instructions, the warning, its slice, its
compact usage context) without calling an LLM. `prepare-inference` turns the
generated prompts into an immutable **Slurm submission** for NJIT Wulver, with a
GPU worker that serves the model with vLLM and validates its answers. The
`inference` commands upload it over one shared SSH connection (a single
password-and-Duo login) and submit one Slurm array job. You can then disconnect
and later check status and collect the results. They have been used on Wulver;
the reconnection safeguards added since are tested with fakes only, and the
GPU settings are not yet tuned. Inference results are proposals;
they are not checker-validated. `apply-proposal` turns one collected proposal
into validated annotation edits and applies them, all or nothing, to a fresh copy
of the run snapshot; it does not rerun the checker, so it says nothing about
whether a warning is resolved. `evaluate-annotations` does rerun it: it applies
each answer's complete edit set as **one annotation package** to a fresh copy of
one baseline, runs NullAway once on all source roots, compares the findings with
a provisional matching rule, and records an **expanded graph** (baseline
warnings, one annotation node per package, `resolved-by` and `introduces`
edges). Different packages are never combined, and no fixes are inferred for
introduced warnings. Slice validation, cumulative annotation
sequences, search and batching are not implemented. See [docs/project-spec.md](docs/project-spec.md) and
[docs/tasks.md](docs/tasks.md).

## Requirements

- **JDK 21** (21.0.8 or newer) installed locally. GeneralAnnotator builds and
  runs on it, and by default the same JDK builds NullAway and runs `javac` +
  Error Prone. The default `java` on your `PATH` can be older (verified with
  Java 11); the Gradle wrapper selects JDK 21 via
  `gradle/gradle-daemon-jvm.properties`.
- **git** on `PATH` (used read-only to list the target's files; see Isolation).
- A local **NullAway source checkout** (https://github.com/uber/NullAway).
  Building it uses the checkout's own Gradle wrapper. The current NullAway build
  compiles with a JDK 27 toolchain, which Gradle's foojay resolver downloads into
  `~/.gradle/jdks` on first use, so the first run needs network access.
- No system Gradle installation.
- **Graphviz** (only for `visualize`), installed as described below.

### Installing Graphviz

```sh
brew install graphviz              # macOS (Homebrew)
sudo apt-get install graphviz      # Debian/Ubuntu
dot -V                             # check: prints "dot - graphviz version ..."
```

For other systems see https://graphviz.org/download/. If `dot` is not on
`PATH`, set `GA_GRAPHVIZ_DOT` to its full path, for example
`export GA_GRAPHVIZ_DOT=/opt/homebrew/bin/dot`. Verified with Graphviz 12.1.0.

## Build and verify

```sh
./gradlew build              # compile, run unit tests (deterministic, no external tools except git and the JDK)
./gradlew test               # unit tests only
GA_CHECKER_HOME=/path/to/NullAway ./gradlew integrationTest
                             # real-tool tests: local NullAway on tiny fixtures, real Graphviz
                             # rendering and real Specimin slicing (not part of build/check;
                             # needs GA_CHECKER_HOME, dot, and GA_SPECIMIN_HOME)
./gradlew workerTest         # the Python inference worker's tests (fake vllm/nvidia-smi;
                             # needs Python 3.10+, GA_PYTHON selects it; not part of build)
./gradlew run --args="help"  # usage
```

## Running a check

```sh
export GA_CHECKER=nullaway
export GA_CHECKER_HOME=/Users/mushfiqurrahmanchowdhury/Documents/NullAway
export GA_TARGET_PROJECT=/Users/mushfiqurrahmanchowdhury/Documents/EventBus
export GA_TARGET_SOURCE_ROOTS=EventBus/src
./gradlew run --args="check"
```

Gradle's `run` task fails (`BUILD FAILED`) when the CLI exits non-zero. To see
the raw exit code, install the distribution and run it directly. The working
directory then determines the default output location:

```sh
./gradlew installDist
build/install/GeneralAnnotator/bin/GeneralAnnotator check; echo "exit=$?"
```

### Configuration

All configuration comes from environment variables. Paths may contain spaces.
Every invalid value is reported together, and nothing runs (exit 2).

| Variable | Required | Meaning |
|---|---|---|
| `GA_CHECKER` | yes | Checker id. Only `nullaway` is supported; anything else is rejected. |
| `GA_CHECKER_HOME` | yes | Absolute path of a NullAway **source checkout** (must contain `gradlew` and `nullaway/build.gradle`). |
| `GA_TARGET_PROJECT` | yes | Absolute path of the target project root. |
| `GA_TARGET_SOURCE_ROOTS` | yes | Source roots to analyze, relative to the target root, separated by `:` (the platform path separator). They must exist, stay inside the target, and not overlap. |
| `GA_TARGET_CLASSPATH` | no | Extra compile classpath entries, `:`-separated. Absolute paths are used as-is; relative paths are resolved inside the isolated copy. Default: empty. |
| `GA_NULLAWAY_ANNOTATED_PACKAGES` | no | Comma-separated NullAway `AnnotatedPackages`. Default: derived from the `package` declarations of the analyzed files (see below). |
| `GA_CHECKER_JAVA_HOME` | no | JDK used to build NullAway (`JAVA_HOME` for its wrapper) and to run `javac`. Default: the JDK running GeneralAnnotator. |
| `GA_JAVAC_RELEASE` | no | Value for `javac --release` (for example `8`). Default: not set. |
| `GA_TIMEOUT_SECONDS` | no | Timeout per external command (git, NullAway build, javac). Default: 1200. |
| `GA_GRAPHVIZ_DOT` | no | `visualize` only: the Graphviz `dot` executable, as a command name or full path. Default: `dot` on `PATH`. |
| `GA_OUTPUT_DIR` | no | Directory for run outputs. Default: `./runs` (relative to the working directory). Must be outside the target. |

**Why `GA_TARGET_SOURCE_ROOTS` is required.** GeneralAnnotator does not run the
target's own build (see constraints below), so it cannot discover source sets.
For EventBus the library sources live in `EventBus/src`. Its other modules are
tests, performance code, the annotation processor (which needs
`de.greenrobot:java-common`), and Android code.

**AnnotatedPackages derivation.** The scope is the set of declared packages,
reduced to minimal prefixes. NullAway already covers sub-packages, so for example
`org.greenrobot.eventbus.meta` is covered by `org.greenrobot.eventbus`. Sibling
packages are never merged into a common parent. Files in the unnamed package
cannot be covered and are reported as a coverage limitation.

## Running the whole pipeline (`pipeline start | status | finish`)

Two commands do a whole round: `pipeline start` analyzes, prepares inference
and submits it; `pipeline finish` collects the results and runs the whole
local evaluation. They call the existing commands in-process, in order, and
record everything in `pipeline.json` inside the run directory, which is the
pipeline directory.

**Setup.** One shell holds every setting. These are the same variables the
individual commands use:

```sh
cd ~/Documents/GeneralAnnotator
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew installDist                                  # the installed launcher can prompt for the login
GA=build/install/GeneralAnnotator/bin/GeneralAnnotator

# NullAway, target, source roots, classpath
export GA_CHECKER=nullaway
export GA_CHECKER_HOME=$HOME/Documents/NullAway
export GA_TARGET_PROJECT=$HOME/Documents/EventBus
export GA_TARGET_SOURCE_ROOTS=EventBus/src
export GA_TARGET_CLASSPATH=$HOME/.gradle/caches/modules-2/files-2.1/org.jspecify/jspecify/1.0.0/7425a601c1c7ec76645a78d22b8c6a627edee507/jspecify-1.0.0.jar
# export GA_CHECKER_JAVA_HOME=...  GA_JAVAC_RELEASE=...  GA_TIMEOUT_SECONDS=...  GA_OUTPUT_DIR=runs
# Specimin
export GA_SPECIMIN_HOME=$HOME/Documents/specimin
# Cluster connection (defaults shown) and Slurm/LLM settings (see "Preparing Wulver inference")
export GA_SSH_TARGET=mc2399@wulver.njit.edu
export GA_REMOTE_BASE=/project/mjk76/mc2399
# export GA_SLURM_ACCOUNT=mjk76 GA_SLURM_PARTITION=gpu GA_SLURM_TIME=02:00:00 GA_INFERENCE_MODEL=...
# Defaults: one prompt per array task, 1800 s vLLM startup timeout, at most 4 tasks at once.
# export GA_INFERENCE_PROMPTS_PER_TASK=1 GA_INFERENCE_STARTUP_TIMEOUT_SECONDS=1800 GA_INFERENCE_ARRAY_CONCURRENCY=4
unset GA_OUTPUT_DIR                                    # runs go to ./runs (created if missing)
```

**Starting from scratch.** The pipeline needs no earlier run, submission,
answer, graph or application directory. `runs/` may be empty or absent; every
run artifact is created from these inputs:
- the target project (`GA_TARGET_PROJECT`);
- the NullAway checkout (`GA_CHECKER_HOME`) and the Specimin checkout
  (`GA_SPECIMIN_HOME`);
- the classpath jar;
- the model weights in the cluster's cache (`GA_INFERENCE_HF_HOME`).

Two settings matter:
- **`GA_TARGET_PROJECT`** must be a real source project that survives
  deleting `runs/`, for example `~/Documents/EventBus`. It must not be a
  generated workspace under `runs/` (`applications/<id>/workspace`,
  `rounds/<id>/workspace/target`, or a snapshot).
- **`GA_OUTPUT_DIR`** should be unset (the default is `runs`) or point outside
  any earlier run. A leftover value from an earlier `apply-proposal` recheck
  would nest new pipelines inside an old run.

**Commands:**

```sh
$GA pipeline start                       # analyze, slice, context, prompts, prepare, log in once, submit
$GA pipeline status runs/<run id>        # recorded stages + scheduler states (cached if offline)
$GA pipeline status runs/<run id> --connect   # reconnect first (may prompt once)
$GA pipeline finish runs/<run id>        # once all tasks ended: collect, evaluate, apply the round
$GA pipeline start --resume runs/<run id>     # continue an interrupted start; never submits twice
$GA pipeline propagate runs/<run id>     # optional, deterministic: single-pass nullability propagation
```

**`pipeline start`**
1. **Validation.** Every setting (checker, classpath, Specimin, Slurm/LLM,
   SSH) is checked before anything runs. Problems are listed, and no
   directory is created.
2. **Analysis (`check`).** A new run directory, a full snapshot copy of the
   target, NullAway findings, `warning-graph.json` and `warning-targets.json`.
   A failed analysis stops the pipeline (FAILED); a clean one ends it
   (NO_WARNINGS).
3. **Per warning.**
   - `slice`: one Specimin run per warning. Unresolved targets are SKIPPED,
     failed slices are FAILED, and slices are never reproduction-validated.
   - `context`: usage context for each warning.
   - `prompts`: one warning per prompt; OVERSIZED and SKIPPED prompts are
     kept with their reasons.
   Every exclusion is recorded in `pipeline.json`. If no prompt is
   GENERATED, the pipeline ends as NO_ELIGIBLE_PROMPTS without a job.
4. **Preparation (`prepare-inference`).** One immutable submission. By default
   each array task gets exactly one prompt (`GA_INFERENCE_PROMPTS_PER_TASK`,
   1 to 10), and its vLLM server has 1800 s to become ready
   (`GA_INFERENCE_STARTUP_TIMEOUT_SECONDS`). At most
   `GA_INFERENCE_ARRAY_CONCURRENCY` (default 4) tasks run at once, and each
   prompt is a separate LLM request. The command prints the effective batch
   size, startup timeout and concurrency, and records them in `pipeline.json`.
   - **Why one prompt per task:** a server that fails to start (STARTUP_FAILED,
     STARTUP_TIMEOUT) then costs only its own prompt.
   - **What the longer timeout does not do:** it guarantees nothing. Startup
     failures stay separate from answer-validation failures (INVALID_ANSWER
     and others), and answer validation is unchanged.
5. **Submission.** `inference connect` reuses a healthy SSH master, or stops
   a stale managed one and opens one with a single login in this terminal.
   Then `upload` (verified by hash) and `submit`. The job ID is saved and
   the command returns; nothing is monitored.
   - Without a terminal (for example under `./gradlew run`), or without a
     connection, it stops as RECONNECT_REQUIRED with the exact login command.
     Everything prepared is kept, and `--resume` continues.
   - An ambiguous sbatch result is SUBMISSION_UNKNOWN. It is never
     resubmitted; resolve it with `inference attach` (the command prints
     how).

After a confirmed submission, the job runs on the cluster without this
terminal, the SSH master, the VPN or the laptop.

**`pipeline status`** shows the recorded stages, then runs `inference status`.
- **Scheduler states** (array tasks) and **prompt results** (from the last
  collection) are shown separately.
- Without a usable connection, the last scheduler snapshot is shown, labeled
  CACHED, with the transport failure.
- It never prompts unless `--connect` is given, and never resubmits.

**`pipeline finish`**
1. **Connect and check the job.** It reconnects (one login if needed; stale
   masters are recovered) and queries the job. If any task is still active,
   or the scheduler gives no information, it reports WAITING_FOR_JOBS and
   collects nothing.
2. **Collect.** Once every task has ended, `inference collect` downloads and
   verifies the results. Hash or identity problems keep the affected results
   out. Failed or missing prompts are recorded as exclusions and make the
   pipeline PARTIAL; they are not retried automatically.
3. **Evaluate (`evaluate-annotations`).** Each answer becomes one atomic
   package, and answers with identical edit sets share one. Each unique
   package is evaluated alone against the pipeline baseline, and the
   expanded graph is written (`W -> A` resolved-by, `A -> N` introduces, round
   IDs).
4. **Apply the whole round (`apply-round`).** The union of all packages is
   applied to one fresh full copy, and NullAway runs once on the combined
   state. Its complete warning set and comparison are saved. A conflicting
   edit set blocks this stage (BLOCKED) and keeps the independent
   evaluations. A failed combined check is FAILED, never zero warnings.
5. **Outcome.** COMPLETED, or PARTIAL when some prompts or answers were
   excluded (answers with insufficient evidence don't count as failures).

**State and resume.**
- **`pipeline.json`** records:
  - pipeline ID, round and status;
  - the fingerprinted settings (every `GA_*` except SSH transport settings);
  - snapshot hash and checker identity;
  - each stage's status, details and artifacts;
  - submission and job IDs;
  - exclusions.
  `pipeline-summary.txt` is the short readable version.
- **Resuming.** Stages are resumed only if the settings and the snapshot
  hash are unchanged; otherwise the command refuses and names what changed.
  Inference and Slurm settings (`GA_INFERENCE_*`, `GA_SLURM_*`) are not
  compared: they only shape the immutable submission, which keeps its own
  `settings.json`. If they differ, a note says so.
  A stage interrupted while RUNNING is redone, and slicing continues with the
  warnings that have no result yet.
- **Repeating `finish`** is safe: the collection is not repeated once complete,
  and evaluations and round results are reused by key, so nothing is
  duplicated.
- **Locking and writes.** One writer at a time holds the lock, and every
  record is written atomically.
- **Isolation.** All work stays in the pipeline directory: snapshot, reports,
  graph, slices, context, prompts, submission, collected answers,
  `evaluations/`, `rounds/`, `pipeline.json` and the summary. The target
  checkout is only read. Baseline, independent and combined checks analyze
  the same configured source roots, and modules outside them are reported as
  not analyzed. The combined result can be the next round's run, but no
  next round is started.

| Exit | `pipeline` meaning |
|---|---|
| 0 | `start`: submitted or no warnings; `status`: fresh scheduler states (or nothing submitted); `finish`: COMPLETED or PARTIAL |
| 2 | Invalid settings or arguments, or settings changed since the pipeline started |
| 3 | FAILED, BLOCKED, NO_ELIGIBLE_PROMPTS, NO_PACKAGES, SUBMISSION_UNKNOWN or WAITING_FOR_JOBS, or another process holds the pipeline |
| 4 | Internal error (completed stages are kept) |
| 5 | The SSH connection is not available: reconnect (VPN), then rerun; progress is kept |

**Verification so far.**
- **Fakes:** the whole flow is covered by deterministic tests with fake
  NullAway, Specimin, ssh, terminal login, cluster and worker.
- **Real tools, offline:** `pipeline start` with real NullAway and Specimin
  on EventBus used an unreachable SSH target, no terminal and an empty socket
  directory. Stages 1–5 ran unattended in about 40 seconds: 42 warnings, and
  41 prompts in 5 array tasks of at most 10 (the pipeline's default batch size at
  the time; it is now one prompt per task). W0033 (an initializer block) was
  excluded and reported. It stopped as RECONNECT_REQUIRED without contacting
  anything, `--resume` skipped every completed stage, and EventBus stayed
  unchanged.
- **Not verified:** a real submission and a real disconnected run have not
  been done through `pipeline`.

## Visualizing a warning graph

`visualize` renders an existing `warning-graph.json` with Graphviz. It does
**not** rerun the checker and does not change the JSON. You can pass either
a run directory or the JSON file itself:

```sh
./gradlew run --args="visualize runs/20261118T000930Z-nullaway"
./gradlew run --args="visualize runs/20261118T000930Z-nullaway/warning-graph.json"
open runs/20261118T000930Z-nullaway/warning-graph.svg     # macOS; or open it in any browser
```

An evaluation directory (`runs/<run>/evaluations/<id>`) or its
`evaluation-graph.json` can be passed too. Its annotation nodes and edges are
drawn as described in "Evaluating annotations".

Paths containing spaces need single quotes inside `--args`:
`--args="visualize '/path/with spaces/runs/<run id>'"`. Relative paths are
resolved against the GeneralAnnotator directory. After a completed `check`, the
summary prints the exact `visualize` command for that run.

Output is written **beside the graph JSON**, in the same run folder:

```
runs/<run id>/warning-graph.json   input (unchanged)
runs/<run id>/warning-graph.dot    Graphviz source (always written)
runs/<run id>/warning-graph.svg    rendered picture (only if Graphviz succeeded)
```

For a JSON file with another name, such as `foo.json`, the outputs are
`foo.dot` and `foo.svg`.

What the picture shows:
- **One box per warning node, labeled with its ID** (`W0001`, ...), read
  directly from the JSON; IDs are never regenerated. All nodes appear, including
  isolated ones. Red fill means error severity, yellow warning, grey note.
- **Hover tooltip** (SVG, in a browser): the ID, `path:line:column`, severity,
  checker, and the full message.
- **Edges:** only those present in the JSON are drawn; none are invented. All
  current graphs have none.
- **Layout:** graphs without edges use Graphviz's `osage` engine, run by the
  `dot` executable through the graph's `layout` attribute. It packs the
  disconnected nodes into a compact grid in ID order, reading left to right and
  top to bottom. (Plain `dot` layout puts them all in one very wide row.) Graphs
  with edges use the hierarchical `dot` layout.
- **Empty graph:** a "No warnings" message.

Rendering uses `dot` on `PATH`, or `GA_GRAPHVIZ_DOT` if it is set. That can be a
command name or an executable's full path; a blank value or a path that isn't an
executable file is rejected with exit 2. Each rendering has a 5-minute timeout.

If Graphviz is missing or fails, `visualize` exits with **3**. The JSON and DOT
files are kept and no partial SVG remains; a stale SVG from an earlier
rendering is removed. The error message says how to install Graphviz or set
`GA_GRAPHVIZ_DOT`, and prints the exact `dot` command for rendering by hand.

| Exit | `visualize` meaning |
|---|---|
| 0 | SVG rendered |
| 2 | Bad argument: the path doesn't exist, a run folder has no graph (failed runs have none), the JSON is invalid, or `GA_GRAPHVIZ_DOT` is invalid |
| 3 | Graphviz missing, failed, or timed out (DOT and JSON kept) |
| 4 | Internal error, such as failing to write the DOT file |

## Specimin target members (`warning-targets.json`)

After a completed analysis, `check` determines, for every warning node, the
member Specimin should preserve. It writes `warning-targets.json` beside
`warning-graph.json`. **Specimin is not run**, and a target only names the
enclosing member: whether a slice of it still reproduces the warning is
validated by a later task.

To compute or refresh the mapping for an existing run without rerunning the
checker:

```sh
./gradlew run --args="targets runs/<run id>"
```

`targets` reads `warning-graph.json`, takes the configured source roots (and
`--release`) from `report.json`, and resolves positions against the run's own
snapshot in `workspace/target`. It rewrites `warning-targets.json` and prints
one line per warning ID. It exits 0 when the file is written, even if some
warnings are unresolved; 2 when the run directory, graph, report or snapshot is
missing or invalid; and 4 on an internal error.

How a target is found:
- The file is the warning's `targetPath` inside the run's isolated copy. It is
  parsed with the JDK's own Java syntax tree API (`com.sun.source`), and the
  warning's line and column, the javac caret, select the innermost syntax
  node. Warning messages are only used to tell apart fields declared together
  (see below).
- The target is the method, constructor or field declared directly in a
  *named* class: a top-level class or a member class. Code in **lambdas**,
  **anonymous classes** and **local classes** maps to the enclosing member of
  the named class, and a note says so.
- Several fields declared together (`int a, b;`) are distinguished by which
  name the caret is on, then by the field named in the message (for example
  `@NonNull field 'Outer$Inner.b' not initialized`). If the position points at
  one field but the message names another, the target is unresolved.
- Without a column, the line resolves only if its first and last non-blank
  characters belong to the same member.

Format, following Specimin's own matching code (mushfiqiit/specimin `b1e5d43`,
`TargetMemberFinderVisitor` and `JavaParserUtil`):

| Field | Meaning (Specimin option) |
|---|---|
| `sourceRoot` | Java package root relative to the target (`--root`), derived from the `package` declaration; the file's directory must match it |
| `isolatedSourceRoot` | The same root as an absolute path in the run's snapshot |
| `targetFile` | File relative to `sourceRoot` (`--targetFile`), e.g. `org/greenrobot/eventbus/EventBus.java` |
| `memberKind` | `METHOD`, `CONSTRUCTOR` or `FIELD` |
| `targetMethod` | `pkg.Outer.Inner#name(T1, T2)` (`--targetMethod`), for methods and constructors |
| `targetField` | `pkg.Outer.Inner#field` (`--targetField`) |
| `requiresDisableRootValidation` | The member's top-level class is not the file's primary class, so Specimin needs `--disable-root-validation` |
| `status`, `reason`, `notes` | `RESOLVED` or `UNRESOLVED`, the reason when unresolved, and context notes (lambda, anonymous or local class) |

Signature rules:
- Nested classes are joined with `.`, not `$`.
- Constructors use the class's simple name: `a.Outer.Inner#Inner(int)`.
- Parameter types are written as in the source: simple names stay simple,
  generics are kept, arrays are `T[]` (also for `T x[]`), and annotations,
  parameter names, return types, modifiers and type parameters are dropped.
- Method varargs are written `T...`. Constructor varargs are written as the
  element type alone, without `...`, because that is how JavaParser 3.28.2
  (used by Specimin) prints constructors.
- Specimin ignores whitespace, so the `, ` separators don't matter.

Unresolved by design, each with a reason:
- the position is in a class header, an annotation, or outside any class;
- the position is in an initializer block, an enum constant, a record
  component, a compact record constructor, or an annotation type element;
- fields declared together can't be told apart;
- the file is missing, doesn't parse, or its directory doesn't match its
  package.

## Generating Specimin slices (`slice`)

```sh
export GA_SPECIMIN_HOME=/Users/mushfiqurrahmanchowdhury/Documents/specimin
./gradlew run --args="slice runs/<run id>"                     # all warnings, one after another
./gradlew run --args="slice runs/<run id> --warning W0003"     # only one warning
```

`slice` reads the run's `warning-targets.json` (from `check` or `targets`) and
runs Specimin once per warning ID. Warnings that share a target still get
separate runs. Neither the checker nor anything in the original project is run
or changed.

| Variable | Required | Meaning |
|---|---|---|
| `GA_SPECIMIN_HOME` | yes | Absolute path of a local Specimin checkout (https://github.com/mushfiqiit/specimin; must contain `gradlew` and `build.gradle`) |
| `GA_SPECIMIN_JAVA_HOME` | no | JDK used to build and run Specimin. Default: the JDK running GeneralAnnotator (21). Specimin's own build uses a Java 17 toolchain. |
| `GA_SPECIMIN_JAR_PATH` | no | Directory of dependency jars, passed as Specimin's `--jarPath`. Default: none, so no `--jarPath`. EventBus core needs none. |
| `GA_TIMEOUT_SECONDS` | no | Timeout for each Specimin build and Specimin run. Default: 1200. |

How Specimin is used:
1. **Setup** (once per `slice` command): `./gradlew --console=plain installDist`
   runs in `GA_SPECIMIN_HOME`, Specimin's own build mechanism (Gradle
   `application` plugin). The jars in `build/install/specimin/lib` and the
   main class from `specimin.jar`'s manifest are then used. Logs go to
   `slices/logs/`. The first build downloads Specimin's Gradle version and
   takes a few minutes.
2. **Per warning**: GeneralAnnotator runs `java` directly with Specimin's six
   `--add-exports=jdk.compiler/...` flags, the same flags its Gradle `run` task
   uses, which google-java-format needs. The generated start script lacks them
   and fails. The arguments are passed as a list, so signatures with spaces
   or generics such as `Class<?>` are passed unchanged:
   `--root <copy> --targetFile <targetFile> --targetMethod|--targetField <target>
   --modularityModel nullaway --outputDirectory slices/<ID>/output
   [--jarPath <dir>] [--disable-root-validation]`. The modularity model comes
   from the graph's checker; only `nullaway` is supported.
3. **Isolation**: `--root` is a **disposable copy** of the target's
   `isolatedSourceRoot` in `slices/<ID>/root-copy`, deleted after the run.
   Specimin may write into `--root`, for example when decompiling
   `--jarPath` jars. The run's `workspace/target` snapshot and the original
   project are only read. `isolatedSourceRoot` must lie inside the run's own
   snapshot, and `targetFile` must exist under it; otherwise nothing runs.

Statuses (each entry also has `"reproduction": "NOT_VALIDATED"`):
- `GENERATED`: Specimin exited 0 within the timeout and wrote at least one
  `.java` file.
- `FAILED`: Specimin couldn't start, timed out, exited non-zero (the reason
  quotes its exception, for example "could not locate the following target
  methods"), or wrote no Java files. Processing continues with the next
  warning.
- `SKIPPED`: the target is `UNRESOLVED`; the reason is copied from
  `warning-targets.json`.

Output in the run directory:

```
slices/slice-results.json          one entry per warning ID: target, status, reproduction,
                                   command (argv, exit code, duration, timeout), logs,
                                   outputDirectory, outputFiles, failureReason; plus the
                                   Specimin commit, classpath, JVM flags, model, jarPath
slices/<ID>/output/                the slice (Java files under their package directories)
slices/<ID>/logs/specimin.stdout.txt, specimin.stderr.txt
slices/logs/                       Specimin setup (installDist, git) logs
```

Previous results are **never overwritten**. If a selected warning already has a
`slices/<ID>/` directory or an entry in `slice-results.json`, `slice` refuses
before running anything. Results for other warnings are kept, and new ones are
merged in, so you can slice warning by warning.

| Exit | `slice` meaning |
|---|---|
| 0 | Every selected resolved target was GENERATED (SKIPPED entries allowed) |
| 2 | Invalid argument, configuration, run files or paths, a missing snapshot, or a conflict with earlier results; nothing was run |
| 3 | Specimin setup failed, or at least one slice FAILED (see `slice-results.json`) |
| 4 | Internal error |

## Extracting usage context (`context`)

```sh
./gradlew run --args="context runs/<run id>"                   # all warnings
./gradlew run --args="context runs/<run id> --warning W0020"   # only one warning
```

`context` collects evidence about where the target's values come from and how
they are used, as input for a later proposer. The approach is inspired by
bidirectional type slicing, but it is a practical one-hop Java heuristic, not
that formal algorithm. It reads `warning-targets.json` (warning IDs are kept),
`warning-graph.json` (for warning messages) and `report.json` (configured
source roots, classpath, `--release`). It then parses **and attributes** every
Java file under the configured source roots of the run's `workspace/target`
snapshot in one javac task. It does not use the Specimin slice. The checker,
Specimin and any LLM are not run, and no file outside `context/` is written.

**Matching uses compiler symbols, not names.** A reference counts as a usage
only if the symbol javac resolved for it is the target's symbol. So overloads,
fields shadowed by locals or parameters, and same-name members of other classes
are excluded and counted as `sameNameOtherSymbols`. References that carry the
target's name but that javac could not resolve, for example because of missing
dependencies, are listed under `unresolvedReferences` and are *not* counted.
So are references resolved while javac reported an error at that reference,
and calls with an erroneous argument type when another overload with that
number of parameters exists.

Evidence categories (in this order):

| Target | Categories |
|---|---|
| method | `warningSite`, `callSites`, `methodReferences`, `parameterUses`, `returnStatements`, `overrideRelations`, `unresolvedReferences` |
| constructor | `warningSite`, `callSites` (`new`, `this(...)`, `super(...)`, implicit `super()`, anonymous classes), `methodReferences` (`::new`), `parameterUses`, `unresolvedReferences` |
| field | `warningSite`, `fieldWrites` (initializer, assignments, `+=`, `++`), `fieldReads`, `unresolvedReferences` |
| unresolved target | `warningSite` only (status `SKIPPED`) |

Each evidence item has a location (path relative to the snapshot), the
enclosing member, the relevant expression, a small snippet, and these fields:

- **Details by kind.** For calls: the arguments, with their parameter, the kind
  of expression (`NULL_LITERAL`, `PARAMETER`, `LOCAL_VARIABLE`, `FIELD`,
  `METHOD_CALL`, ...) and declared annotations. Also `resultUse`, which says how
  the returned value is consumed: dereferenced, compared with null, assigned
  (and then the local's later uses in the same member, see *local aliases*
  below), returned, passed as an argument (callee, parameter and its
  annotations), discarded, and so on.
  For parameter uses and field reads: the same consumption kinds, plus
  `nullGuards`.
- **`conditions`.** The enclosing `if`, loop, ternary, `&&`/`||`, `case` and
  `catch` conditions, nearest first.
- **`nullGuards`.** Syntactic null checks of the same variable that guard the
  site: an enclosing `x != null`, `instanceof`, an early exit
  `if (x == null) return;`, `assert`, or `Objects.requireNonNull`.
- **`localDefinitions`.** Declarations (and up to two earlier assignments) of
  local variables used by the relevant expression.
- **`observations`.** Tags such as `NULL_LITERAL`, `DEREFERENCE`,
  `NULL_COMPARISON`, `GUARDED_BY_NULL_CHECK`, `NULL_ARGUMENT`, `RETURNED`,
  `IN_CONSTRUCTOR`, and `ERRONEOUS_ARGUMENT_TYPES`.

These are observations about the code, not proofs of the right annotation.
Usages are not filtered by any safe/unsafe region policy.

**Local aliases** (`laterUsesOfLocal`). When the value is stored in a local,
later uses of that local in the same member are followed conservatively:

- A use is `DEFINITE` only if no reassignment can run in between and the
  storing assignment runs on every path to the use.
- A reassignment that runs on every path before the use (a plain statement
  earlier in a block that contains the use) ends attribution. Such uses are
  not listed, only counted in `afterReassignment`.
- A use is `MAY_BE_REASSIGNED` when a reassignment runs on some paths only
  (inside a branch, or later in an enclosing loop), listed in `reassignedAt`,
  or when the storing assignment itself runs on some paths only
  (`assignedOnSomePathsOnly`, with the local's `declaredAt` line).
- Exceptions and labeled jumps are ignored.

| Variable | Default | Meaning |
|---|---|---|
| `GA_CONTEXT_MAX_ITEMS` | 20 | Evidence items kept per category; the rest are counted in `omitted`. Distinct evidence is kept first (round robin over kind, how the value is used, and observations), then shown in source order |
| `GA_CONTEXT_MAX_SNIPPET_LINES` | 6 | Lines kept per snippet (a window around the site); cut lines are counted in `omittedLines` |
| `GA_CONTEXT_MAX_CONDITIONS` | 4 | Enclosing conditions kept per item; the rest are counted in `omittedConditions` |
| `GA_CONTEXT_MAX_LOCAL_USES` | 5 | Later uses of a local that stores the value; the rest are counted in `omitted` |

Evidence is deduplicated by kind, source location and symbol (not by text)
and ordered deterministically, so repeated runs give identical files. Output
(regenerated on every run):

```
context/<ID>.json   full extraction details, schemaVersion 1: warning, target identity
                    (declaration, modifiers, annotations, parameters), references summary,
                    evidence by category (total, omitted, items with all fields above),
                    limitations, limits, analysis (files, classpath, javac options, errors)
context/<ID>.txt    compact view for an LLM or a reader (below)
```

The `.txt` file is the **compact view**. It contains:

- The warning once, without checker documentation URLs, and the target
  declaration once.
- `Found:` counts; `Shown:` truncation, `Excluded:` same-name references to
  other symbols and unresolved references only when non-zero.
- One block per enclosing member, in file and line order. A block has the
  member's header, then every relevant source line **once, in source order**:
  evidence statements, enclosing conditions, early-exit branches of null
  checks and guards, local definitions, and alias uses. Overlapping snippets
  of several evidence items are merged.
- Short `//` notes on the lines: `read`, `write`, `call`, `return`,
  `param x`, `warning`, `reassigns h`, `h holds the value from line 7`,
  `h may hold the value from line 7 or 10 (reassigned at 10, 12 on some
  paths)`. There are also notes for annotations the snippet does not show,
  such as a callee parameter's `@Nullable`. For class files, only
  nullness-named and type-use annotations are reported.
- Omitted code as an explicit `...  // N lines omitted` row. Lines made only
  of closing brackets are shown rather than elided, and nothing is invented.
- Run-specific limitations, and one line for the standard ones.

The compact view is built only from the kept evidence. It is not a minimal or
sound slice.

javac errors during attribution, such as a missing classpath, don't stop the
command. They are recorded in `analysis.compilerErrors` and in the
limitations, and references inside erroneous code end up under
`unresolvedReferences`. Configure `GA_TARGET_CLASSPATH` for `check` so that the
run's `report.json` carries the classpath.

| Exit | `context` meaning |
|---|---|
| 0 | Context written for every selected warning (`EXTRACTED`, or `SKIPPED` for an unresolved target) |
| 2 | Invalid argument, limit, run files, or missing snapshot; nothing written |
| 3 | No attribution possible, or a target member was not found in the snapshot (`FAILED`) |
| 4 | Internal error, including failure to write the files |

Example (EventBus W0001, the field `EventBus#defaultInstance`, `context/W0001.txt`, abridged):

```
W0001  NullAway ERROR  EventBus/src/org/greenrobot/eventbus/EventBus.java:46:30
@NonNull static field 'defaultInstance' not initialized

Target: FIELD org.greenrobot.eventbus.EventBus#defaultInstance  (EventBus/src/org/greenrobot/eventbus/EventBus.java)
  46  static volatile EventBus defaultInstance;  // warning
Found: 2 writes, 4 reads

EventBus/src/org/greenrobot/eventbus/EventBus.java  org.greenrobot.eventbus.EventBus#getDefault()
  82  public static EventBus getDefault() {
  83      EventBus instance = defaultInstance;  // read
  84      if (instance == null) {  // instance holds the value from line 83
  85          synchronized (EventBus.class) {
  86              instance = EventBus.defaultInstance;  // read; reassigns instance
  87              if (instance == null) {  // instance holds the value from line 86
  88                  instance = EventBus.defaultInstance = new EventBus();  // write; reassigns instance
  89              }
  90          }
  91      }
  92      return instance;  // instance may hold the value from line 83 or 86 (reassigned at 86, 88 on some paths; line 86 runs on some paths only)
  93  }

EventBus/src/org/greenrobot/eventbus/EventBusBuilder.java  org.greenrobot.eventbus.EventBusBuilder#installDefaultEventBus()
  177  public EventBus installDefaultEventBus() {
  178      synchronized (EventBus.class) {
  179          if (EventBus.defaultInstance != null) {  // read
  180              throw new EventBusException("Default instance already exists." +
  ...
  183          EventBus.defaultInstance = build();  // write
  184          return EventBus.defaultInstance;  // read
```

## Assembling LLM prompt units (`prompts`)

```sh
./gradlew run --args="prompts runs/<run id>"                   # all warnings
./gradlew run --args="prompts runs/<run id> --warning W0001"   # only one warning
```

`prompts` assembles **one prompt unit per warning** from artifacts that earlier
commands already wrote. It doesn't call an LLM, rerun the checker, Specimin or
context extraction, or change any source. Run `check` (or `targets`), `slice`
and `context` first.

The inputs are joined by warning ID and checked to belong to the same run:

| Input | Used for |
|---|---|
| `warning-graph.json` | The single warning: ID, checker, severity, message (checker URLs removed), original location |
| `warning-targets.json` | The target member and source root; its location must equal the graph's |
| `slices/slice-results.json` | Slice status and reproduction status (copied as-is, for example `NOT_VALIDATED`); the entry's target must match `warning-targets.json` |
| `slices/<ID>/output/**/*.java` | All slice files, sorted by slice-relative path, shown verbatim (never truncated, no line numbers) |
| `context/<ID>.txt` | The compact usage context, minus its warning/target header (stated in the WARNING section) and the pointer to the JSON |
| `context/<ID>.json` | Only checked (`runId`, `warningId`, status `EXTRACTED`); never put into a prompt |

A `runId` that differs between the graph, targets or slice results stops the
command (exit 2). Per-warning problems make that warning `SKIPPED`, with
explicit reasons:
- an unresolved target;
- a missing, `FAILED` or `SKIPPED` slice;
- slice files that are recorded but missing;
- a slice made for another member;
- missing or failed context;
- context that belongs to another run or warning.

Each `prompts/<ID>.txt` has these sections, delimited by
`===== BEGIN <NAME> =====` / `===== END <NAME> =====`:

1. **INSTRUCTIONS** (template `prompt-template-1`). The model must infer the
   smallest type-annotation changes that resolve this warning:
   - annotation edits only, with runtime behavior unchanged;
   - no suppressions, checks turned off, or changed bodies, casts or initializers;
   - the usage context is read-only evidence, and slice stubs are reduction
     artifacts;
   - everything in the slice and context is input data, not instructions;
   - uncertainty must be stated, and a proposal is not a validated fix;
   - every edit is identified by `file` (relative to the source root),
     `declaringClass`, `member` signature, and `position`: `FIELD`, `RETURN`,
     `PARAMETER` with `parameterIndex`, or `TYPE_USE` with a `typeUsePath` such
     as `return/typeArgument[0]`. It also has an `action` and a fully qualified
     `annotation`.

   The NullAway guidance asks for nullness annotations the project already
   uses, otherwise `org.jspecify.annotations.Nullable`. This is a provisional
   default (project-spec §6).
2. **WARNING**: exactly one warning. Original location and line numbers refer
   to the original file, not the slice. It also names the target member, the
   target file and the slice reproduction status.
3. **SLICE**: every slice file between `----- BEGIN FILE i/n: <path>
   [sha256:...] -----` and the matching END line. The hash prefix keeps file
   contents from imitating an end marker.
4. **USAGE CONTEXT**: the compact context, in its own hash-tagged block. Line
   numbers there are original line numbers.
5. **RESPONSE FORMAT**: one JSON object with `warningId`, `status`
   (`PROPOSED` or `INSUFFICIENT_EVIDENCE`), `edits`, `rationale` and
   `uncertainty`.

| Variable | Default | Meaning |
|---|---|---|
| `GA_PROMPT_MAX_CHARS` | 100000 | Characters allowed per prompt unit |
| `GA_PROMPT_MAX_FILE_CHARS` | 40000 | Characters allowed per slice file |

Nothing is truncated. A unit over a limit is `OVERSIZED`: no prompt file is
written, and the manifest names the file or unit and its size.
`prompts/manifest.json` records, per warning ID:
- the template version, status (`GENERATED`, `SKIPPED`, `OVERSIZED`), reasons
  and notes;
- input paths relative to the run directory, and the slice status and
  reproduction;
- output sizes (characters, bytes, per section, per Java file).

There are no timestamps, so equal inputs give identical files. A `--warning`
run replaces only that entry. When a warning isn't `GENERATED`, any old
`prompts/<ID>.txt` for it is deleted, so no stale prompt is left behind.

| Exit | `prompts` meaning |
|---|---|
| 0 | Manifest written (`SKIPPED` and `OVERSIZED` entries allowed) |
| 2 | Invalid argument or limit, missing graph or targets, artifacts from different runs, an unsupported checker, or an unknown warning; nothing written |
| 4 | Internal error, including failure to write |

On EventBus: 41 units are `GENERATED` (6.9 to 14.4 thousand characters). W0033
is `SKIPPED` because its target is an initializer block.

## Preparing Wulver inference (`prepare-inference`)

```sh
./gradlew run --args="prepare-inference runs/<run id>"                   # all GENERATED prompts
./gradlew run --args="prepare-inference runs/<run id> --warning W0001"   # one prompt
./gradlew run --args="prepare-inference runs/<run id> --warning W0001,W0002"   # only these prompts
GA_INFERENCE_PROMPTS_PER_TASK=10 ./gradlew run --args="prepare-inference runs/<run id>"
```

This is the first half of the inference stage. It prepares an **immutable
submission directory** and the GPU worker that will run inside Slurm. It does
not upload anything, submit a job, download results or load a model; the
`inference` commands (next section) do the remote part. Run `prompts` first.

`prepare-inference` reads `prompts/manifest.json` and takes only `GENERATED`
entries. On EventBus that is 41 of 42 (W0033 is listed under `excluded`).

It validates:
- the manifest structure, and that its `runId` equals the warning graph's;
- unique, well-formed warning IDs (`W0001`, ...);
- each `output.file` is exactly `prompts/<ID>.txt`, and the file is a regular
  file inside `prompts/` (not a symlink out of it);
- each file's size matches the manifest, and its header names the same run
  and warning;
- the template version is supported.

Any problem stops it (exit 2) before anything is written. It writes
`inference/<UTC timestamp>-inference/`:

```
submission.json        submission and run IDs, checker, source manifest SHA-256, and per prompt:
                       warningId, file, full SHA-256, bytes, templateVersion, slice status and
                       reproduction (copied unchanged, e.g. NOT_VALIDATED), taskIndex; the task
                       assignments; excluded (non-GENERATED) entries with reasons; hashes of
                       settings.json, the worker and the batch script
settings.json          model, sampling, server limits, environment and Slurm settings (below)
prompts/<ID>.txt       the prompt files, byte for byte
run_inference.sbatch   Slurm array job, one task per assignment group
worker/ga_inference_worker.py   the GPU worker (standard-library Python 3.10+)
logs/                  empty; Slurm writes %x.%A_%a.out/.err here
```

The directory is built under a temporary name and renamed when complete. Its
files are then read-only, and an existing submission is never overwritten.

**Task assignment.** Prompts are sorted by warning number and cut into
consecutive groups of `GA_INFERENCE_PROMPTS_PER_TASK`. The default is 1, the
allowed range is 1 to 10, and anything else is rejected. 41 prompts with batch
size 10 give tasks of 10, 10, 10, 10 and 1. Every prompt is still its own
request. `GA_INFERENCE_ARRAY_CONCURRENCY` (default 4) is the separate `%N`
throttle of `--array=0-<n-1>%N`.

**Batch script.** Each array task:
- refuses to run outside `sbatch` or from another directory, so submit it from
  the submission directory;
- runs `module purge`, loads the configured modules, and activates the conda
  environment;
- sets `HF_HOME`, `HF_HUB_OFFLINE=1` and `TRANSFORMERS_OFFLINE=1`, so weights
  come from the cache and nothing is downloaded or installed;
- `exec`s the worker for `$SLURM_ARRAY_TASK_ID`.

`--signal=B:TERM@120` gives the worker time to stop its server before the
time limit.

**Worker** (inside the GPU allocation, never on a login node):
1. It refuses to start a model without `SLURM_JOB_ID`, with an empty
   `CUDA_VISIBLE_DEVICES` or without `nvidia-smi`, or when an allocated GPU has
   less than `GA_INFERENCE_MIN_GPU_MEMORY_MIB`. It checks that the configured
   conda environment is the active one and that `HF_HOME` exists.
2. It starts its own `vllm serve <model> --host 127.0.0.1 --port <free port>
   --served-model-name ... --max-model-len ... [--reasoning-parser ...]`. Before
   starting, it checks that the port is free. A collision ("Address already in
   use") is retried on another port, a fixed `GA_INFERENCE_PORT` is never
   shared, and `/v1/models` must list the served name.
3. It waits for `/health` up to the startup timeout. If the server exits or
   never becomes healthy, the end of its log is recorded.
4. It runs the task's prompts one after another. Before sending a prompt it
   counts its tokens with `/tokenize`. A prompt that would leave fewer than
   `GA_INFERENCE_MAX_TOKENS` (or `GA_INFERENCE_MIN_COMPLETION_TOKENS`) answer
   tokens is `PROMPT_TOO_LONG` and is not sent. Prompts are never truncated.
5. It stops its server (process group: SIGTERM, then SIGKILL) on exit,
   including on `scancel` and at the time limit.

**Results** (on the cluster, under the submission directory):

```
results/<ID>/attempt-NNN/raw-response.json   the complete API response (raw-response.txt if not JSON)
results/<ID>/attempt-NNN/reasoning.txt       reasoning, when the server returns it (or inline <think>)
results/<ID>/attempt-NNN/answer.json         the validated answer
results/<ID>/attempt-NNN/metadata.json       this attempt's metadata
results/<ID>/result.json                     the decisive attempt (written last, atomically)
results/tasks/task-NNNN.json                 per task: node, GPUs, server port/startup/version, statuses
results/logs/task-NNNN/job-<SLURM_JOB_ID>/vllm-attempt-K.log    the server log of each Slurm job
```

Metadata records:
- the prompt's SHA-256 (rechecked before sending), the template version, and
  the slice status and reproduction, unchanged;
- the model and sampling settings, the request parameters, timing, token
  usage and finish reason;
- the status, error kind and message, and validation errors.

Answers are checked against the `prompt-template-1` format:
- one JSON object (one ```json fence is tolerated, surrounding prose is not);
- the matching `warningId`;
- `status` is `PROPOSED` (at least one edit) or `INSUFFICIENT_EVIDENCE` (no
  edits);
- each edit has a relative `.java` file, a declaring class and member, a
  position kind with `parameterIndex` or a `typeUsePath` in the template
  grammar, `ADD`/`REMOVE`, and a fully qualified annotation;
- `rationale` and `uncertainty` are present.

The statuses are `COMPLETED_PROPOSED`, `COMPLETED_INSUFFICIENT_EVIDENCE` (a
completed outcome), or `FAILED`. A failure has one of these error kinds:
`MALFORMED_ANSWER`, `INVALID_ANSWER`, `WRONG_WARNING_ID`, `EMPTY_ANSWER`,
`TRUNCATED` (`finish_reason=length`), `HTTP_ERROR`, `TIMEOUT`,
`PROMPT_TOO_LONG`, `PROMPT_HASH_MISMATCH`, `SERVER_UNAVAILABLE`,
`STARTUP_FAILED`, `STARTUP_TIMEOUT`, `PORT_IN_USE`, `NOT_IN_SLURM_JOB`,
`NO_GPU`, `GPU_TOO_SMALL`, `ENVIRONMENT_ERROR` or `TERMINATED`.

A failed request doesn't touch other prompts' results. A resubmitted task
keeps completed results with the same prompt hash, without sending them again,
and retries the rest as a new attempt. When nothing is left, it starts no
server. A result is a proposal: it is not checker-validated and doesn't mean
the warning is resolved.

Worker exit codes: 0 all assigned prompts completed, 2 invalid submission or
task, 3 some prompts failed, 4 the server never ran (node, environment or
startup failure).

| Variable | Default | Meaning |
|---|---|---|
| `GA_SLURM_ACCOUNT` / `_PARTITION` / `_QOS` | `mjk76` / `gpu` / `standard` | Slurm account, partition, QoS |
| `GA_SLURM_GRES` | `gpu:1` | GPU request, `gpu[:type]:count` |
| `GA_SLURM_CONSTRAINT`, `GA_SLURM_MEM` | unset | optional node constraint and memory |
| `GA_SLURM_CPUS_PER_TASK` | 8 | CPUs per array task |
| `GA_SLURM_TIME` | `02:00:00` | time limit per array task (startup plus its prompts) |
| `GA_SLURM_SIGNAL_SECONDS` | 120 | SIGTERM this long before the time limit (0 disables) |
| `GA_SLURM_JOB_NAME` | `ga-inference` | job name |
| `GA_SLURM_MODULES` | `wulver Miniforge3` | modules loaded before activation |
| `GA_INFERENCE_PROMPTS_PER_TASK` | 1 | prompts per array task, 1 to 10 |
| `GA_INFERENCE_ARRAY_CONCURRENCY` | 4 | array tasks running at once |
| `GA_INFERENCE_CONDA_ENV` | `/project/mjk76/mc2399/envs/llm` | conda environment (Python 3.11.16, vLLM 0.30.0 observed) |
| `GA_INFERENCE_HF_HOME` | `/project/mjk76/mc2399/hf_cache` | Hugging Face cache, used offline |
| `GA_INFERENCE_MODEL` | `Qwen/Qwen3.8-27B` | cached model |
| `GA_INFERENCE_SERVED_MODEL_NAME` | `qwen3.8-27b` | served name |
| `GA_INFERENCE_MAX_MODEL_LEN` | 32768 | context length (prompt + answer) |
| `GA_INFERENCE_REASONING_PARSER` | `qwen3` | vLLM reasoning parser, or `none` |
| `GA_INFERENCE_VLLM_ARGS` | empty | extra `vllm serve` arguments, for example `--gpu-memory-utilization 0.92`; host, port, model name, context length and parser are reserved |
| `GA_INFERENCE_TEMPERATURE` / `_TOP_P` / `_TOP_K` / `_SEED` | 0.6 / 0.95 / 20 / 0 | sampling; `TOP_K=0` doesn't send it |
| `GA_INFERENCE_MAX_TOKENS` | 0 | `max_tokens` per answer; 0 leaves the rest of the context |
| `GA_INFERENCE_MIN_COMPLETION_TOKENS` | 4096 | answer tokens a prompt must leave free when `MAX_TOKENS` is 0 |
| `GA_INFERENCE_PORT` / `_PORT_ATTEMPTS` | 0 / 3 | fixed port (0 picks a free one) and start attempts |
| `GA_INFERENCE_STARTUP_TIMEOUT_SECONDS` | 1800 | deadline for the vLLM server to become ready (`/health`): model loading, compilation and CUDA graph capture. Not the request timeout. 10 to 86400 |
| `GA_INFERENCE_HEALTH_POLL_SECONDS` | 5 | polling interval |
| `GA_INFERENCE_REQUEST_TIMEOUT_SECONDS` | 2700 | one request |
| `GA_INFERENCE_STOP_TIMEOUT_SECONDS` | 30 | grace period when stopping the server |
| `GA_INFERENCE_MIN_GPU_MEMORY_MIB` | 70000 | refuse smaller GPUs (27B bf16 weights alone need about 54 GB) |

Values used in the batch script are limited to safe characters. Not yet
verified on a GPU: the model, served name, 32768 context, `qwen3` parser,
sampling defaults, memory threshold and time limit all come from the
historical Specimin scripts and the Qwen3 model card. A GPU smoke test is still
needed (see docs/tasks.md, T13d).

| Exit | `prepare-inference` meaning |
|---|---|
| 0 | Submission prepared |
| 2 | Invalid argument or setting, invalid or inconsistent manifest or prompt file, or an existing submission directory; nothing written |
| 4 | Internal error |

## Running inference on Wulver (`inference`)

The second half of the inference stage takes a prepared submission
(`runs/<run id>/inference/<submission id>`) to the cluster and back:

```sh
./gradlew installDist                       # once: builds the launcher
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS; the launcher needs JDK 21
GA=build/install/GeneralAnnotator/bin/GeneralAnnotator
SUB=runs/<run id>/inference/<submission id>

$GA inference connect              # reuse the SSH master, or open it with one login (password + Duo)
$GA inference run     $SUB         # upload, submit once, monitor and collect until every task ends
$GA inference upload  $SUB         # the steps of run, one at a time
$GA inference submit  $SUB
$GA inference status  $SUB
$GA inference collect $SUB
$GA inference retry   $SUB         # failed or missing prompts as new attempts
$GA inference attach  $SUB <job id> | --not-submitted
```

Use the installed launcher for `connect` and `run`. The login has to happen in
your terminal, and a process started by `./gradlew run` has none: there
`connect` prints the exact `ssh` command to run yourself instead. The other
operations also work through `./gradlew run --args="inference status $SUB"` once
the connection is open.

### Submit, disconnect, come back later

The job does not need your computer. The worker and vLLM run entirely inside
the Slurm allocation. Once `submit` has saved the job ID, no SSH session, local
process or awake laptop is needed. The recommended workflow is:

```sh
$GA inference connect              # one login (password + Duo)
$GA inference upload  $SUB         # copy inputs, verify SHA-256 on the cluster
$GA inference submit  $SUB         # one sbatch; returns as soon as the job ID is saved
# Now disconnect the VPN, close the terminal, sleep or shut down the laptop.
# ... later, VPN back on ...
$GA inference connect              # reuses a healthy connection, or replaces a stale one (one login)
$GA inference status  $SUB         # fresh squeue/sacct state of every array task
$GA inference collect $SUB         # download and verify results into $SUB/remote/collected/
```

`submit` never monitors. It prints the job ID, where it is saved
(`$SUB/remote/state.json`), and the commands for later. Every later command
uses that saved state. Coming back never submits another job, and it works
from a new terminal or after a reboot. `run` is optional: it keeps a terminal
open and polls while you stay connected. If its monitoring stops (Ctrl-C, VPN
off, sleep), the job keeps running; use `status` and `collect`, or `run`
again.

What a disconnection affects:
- **Nothing on the cluster.** Running and pending tasks continue. Results
  accumulate in the remote submission directory until you collect them.
- **The SSH connection.** After a VPN change or sleep, the master is usually
  dead. It may also linger as a local process whose network connection is
  gone; `ssh -O check` still answers, but every session fails ("read from
  master failed: Broken pipe"). `connect` detects both cases (below).
- **A command that was running.** `status`, `collect`, `submit`, `retry` and
  `run` stop with exit 5 and change nothing. If the connection drops during
  `sbatch` itself, that call is UNKNOWN and needs `attach` (see Submission).

**One login, one shared connection.** A connection counts as healthy only if
both hold:
- the OpenSSH master answers `ssh -O check`;
- a bounded remote round trip through it, `printf '%s-%s %s\n' GA HEALTH ok`
  with at most `GA_SSH_CONNECT_TIMEOUT_SECONDS` + 15 s, prints the line
  `GA-HEALTH ok`.

`inference connect` reuses a healthy connection without a login. A managed
master that answers but fails the round trip is stale: `connect` asks it to
exit (`ssh -O exit`). If it does not exit, `connect` reports its pid and stops
instead of killing anything. With no usable master, `connect` runs this in
your terminal, where ssh asks for the password and Duo itself;
GeneralAnnotator never sees or stores them:

```
ssh -M -N -f -o ControlPath=~/.ga-ssh/%C -o ControlPersist=4h -o ConnectTimeout=30 \
    -o ServerAliveInterval=15 -o ServerAliveCountMax=3 mc2399@wulver.njit.edu
```

`ServerAliveInterval` (`GA_SSH_SERVER_ALIVE_SECONDS`, 0 to omit) makes a
master whose network path vanished exit by itself after about 45 s.

The socket directory (`GA_SSH_CONTROL_DIR`, default `~/.ga-ssh`) is created
with mode 0700. An existing one must be private and yours, or it is refused.
Every later ssh and rsync call uses the same socket with `-o
ControlMaster=no -o BatchMode=yes -o ProxyCommand=false`. `BatchMode` alone is
not enough: when the socket is missing or refuses a session, OpenSSH silently
opens a fresh connection, which could still log in with keys or an agent. A
multiplexed session never uses ProxyCommand, so `ProxyCommand=false` only makes
that fallback fail at once. So `status`, `collect`, `submit`, `retry` and
`upload` can neither prompt nor log in afresh. Each checks the connection's
health first; without a healthy connection it stops with exit 5 and tells you
to run `inference connect`, and all progress is kept. A connection lost in the
middle of a command is also exit 5. That includes a command that hangs until
its timeout and then fails the health check. Host keys are checked as your SSH
configuration says, which is never edited. To reuse a connection you own
instead, set `GA_SSH_CONTROL_PATH=config` (the `ControlPath` of your SSH
config, for example for a host alias in `GA_SSH_TARGET`) or a socket path. In
those modes GeneralAnnotator never creates, stops or closes the master. If
yours is stale, `connect` prints the `ssh -O exit` and login commands for you
to run. `connect` prints how to
close a managed master early (`ssh -O exit ...`); otherwise it ends after
`GA_SSH_CONTROL_PERSIST` idle.

**Upload.** Each submission goes to its own directory:
`<GA_REMOTE_BASE>/<GA_REMOTE_SUBDIR>/<run id>/<submission id>`, by default
`/project/mjk76/mc2399/generalannotator/inference/...`.
1. The local inputs are checked against `submission.json` first.
2. An existing remote directory is used only if it holds the same
   `submission.json` (same SHA-256) or is one this submission began
   uploading. Otherwise the upload is refused.
3. It creates the directory and `logs/` (Slurm writes there).
4. rsync copies only `submission.json` (first, so a partial directory is
   recognizable), `settings.json`, the batch script, the worker and the listed
   prompts, read-only. It never uses `--delete`, and no remote command removes
   anything.
5. `sha256sum` on the cluster must match every recorded hash. Only then is the
   upload VERIFIED.

Prompt files are only copied, never executed. Remote paths and values put in
remote commands are validated (safe characters) and quoted.

**Submission.** `submit` re-verifies the remote hashes, then calls `sbatch
--parsable --array=<tasks>%<concurrency> --comment=GeneralAnnotator:<submission>:<label>
run_inference.sbatch` once from the remote directory. Tasks and concurrency come
from the prepared submission: one prompt per task by default, at most 10, and
`GA_INFERENCE_ARRAY_CONCURRENCY` as the throttle. The intent (label `job-001`,
array) is saved in `remote/state.json` before the call, and the job ID right
after. Duplicates are prevented three ways:
- ordinary reruns see the saved job and don't submit;
- concurrent invocations are refused by an exclusive lock on `remote/lock`;
- on the cluster, the sbatch call first creates `.ga-sbatch/<label>/` with
  `mkdir`, which fails if that label was used before, and records sbatch's
  output and exit code there.

If the connection drops or the output is unreadable during submission, the
call is **UNKNOWN** and is never retried automatically. `submit`, `run` and
`retry` refuse until it is resolved, and they show the remote record and any
jobs in the submission's directory. `attach <job id>` accepts a job only if
sacct/squeue report its name and working directory as this submission's and its
array tasks as the requested ones. `attach --not-submitted` is accepted only if
the remote marker shows sbatch was never reached, or that it failed with no job
in that directory. A rejected sbatch call (bad account, limits) is recorded as
REJECTED, and nothing was submitted.

**Status.** `status` lists the sbatch calls (local record) and a fresh
scheduler query for every array task of the latest job that includes it.
squeue covers active tasks and sacct the history; squeue's "invalid job id"
just means the job left the queue. A task that neither reports is `UNKNOWN`,
never finished. A lost connection is not scheduler uncertainty. `status` then
exits 5 instead of listing every task as UNKNOWN, and it shows the last
successful snapshot, labeled `CACHED snapshot from <time> (may be outdated)`.
`remote/status.json` keeps that snapshot (`queriedAt`) and records the failure
in `refreshFailure` (`at`, `reason`); the next successful query clears it.
Scheduler states describe tasks, not prompts: a COMPLETED task can have failed
prompts. `status` never downloads results; it shows the last collection's
prompt counts labeled `CACHED from the last collection at <time>`. Run
`collect` for current ones. `status` takes no lock, so it works while `run` is
monitoring.

**Collection.** `collect` does not use the scheduler, so it works while jobs
run, after any disconnection, and when squeue/sacct have no record of the job.
A prompt without a result is `NO_RESULT` (not yet written, or the task ended
first), never inferred as finished or failed from missing scheduler records.
It first checks that the remote `submission.json` still has the local SHA-256. It then rsyncs `results/`, `logs/` and `.ga-sbatch/`
into `remote/incoming/`, skipping files still being written atomically, and
merges them into `remote/collected/`:
- attempt files and sbatch records are write-once, so a differing remote copy
  is a conflict and the local copy is kept;
- a completed `result.json` is never replaced by one that is not completed;
- logs and task summaries are replaced by newer versions.

Each prompt's `result.json` must name this submission, run, warning and prompt
SHA-256, and must match its attempt's `metadata.json`. Completed results must
also have their raw response and an `answer.json` with the same warning and
status. `remote/collection.json` records per prompt the status
(`COMPLETED_PROPOSED`, `COMPLETED_INSUFFICIENT_EVIDENCE`, `FAILED`,
`NO_RESULT`, `INVALID_RESULT`), the error kind, attempts, and paths to the
answer, raw response and reasoning. It also lists conflicts and result
directories of unknown warnings. Partial collection while jobs run is safe.

**Retry.** `retry` runs only if:
- no sbatch call is UNKNOWN;
- the scheduler reports every task of every job as finished;
- a fresh collection is clean.

It then resubmits the array tasks of prompts that are `FAILED` or `NO_RESULT`,
as a new job in the same remote directory (`--array=0,2%3`). Failures a retry
cannot fix are listed and skipped: `PROMPT_TOO_LONG`, `PROMPT_HASH_MISMATCH`,
`PROMPT_MISSING`, `UNSUPPORTED_TEMPLATE`. The worker keeps completed results,
including other prompts of a 10-prompt task, without requesting them again. It
writes new `attempt-NNN` directories for the rest, and server logs go to a
per-job directory, so nothing earlier is overwritten. Nothing retries
automatically.

**End to end.** `inference run`:
1. connects (one login if needed);
2. uploads unless already verified;
3. refuses if any sbatch call (initial or retry) is UNKNOWN; otherwise submits
   unless a job exists;
4. polls status and collects every `GA_REMOTE_POLL_SECONDS` (default 300,
   minimum 60), until every task has finished.

It exits 0 only when every prompt has a completed result. Ctrl-C, a lost
connection (exit 5; the last snapshot is kept as cached) or
`GA_REMOTE_MAX_UNKNOWN_POLLS` polls in a row with unknown scheduler state stop
only the local monitoring. Jobs are never cancelled, and
rerunning `run` resumes without resubmitting.

| Variable | Default | Meaning |
|---|---|---|
| `GA_SSH_TARGET` | `mc2399@wulver.njit.edu` | `user@host` or a host alias |
| `GA_SSH_CONTROL_PATH` | unset | unset: managed socket in `GA_SSH_CONTROL_DIR`; `config`: your SSH config's ControlPath; or an existing socket path (reused only) |
| `GA_SSH_CONTROL_DIR` | `~/.ga-ssh` | private socket directory (short: socket paths are limited to about 100 characters) |
| `GA_SSH_CONTROL_PERSIST` | `4h` | idle lifetime of a managed master |
| `GA_SSH_SERVER_ALIVE_SECONDS` | 15 | ServerAliveInterval of a managed master (3 missed replies end it); 0 omits it |
| `GA_SSH_CONNECT_TIMEOUT_SECONDS` | 30 | SSH ConnectTimeout; the health round trip may take this + 15 s |
| `GA_SSH_COMMAND_TIMEOUT_SECONDS` / `_TRANSFER_` / `_AUTH_` | 300 / 3600 / 600 | one remote command, one rsync, the interactive login |
| `GA_SSH_EXECUTABLE`, `GA_RSYNC_EXECUTABLE` | `ssh`, `rsync` | executables |
| `GA_REMOTE_BASE` | `/project/mjk76/mc2399` | remote base directory |
| `GA_REMOTE_SUBDIR` | `generalannotator/inference` | below the base: `<run id>/<submission id>` |
| `GA_REMOTE_POLL_SECONDS` | 300 | monitoring interval of `run` (minimum 60) |
| `GA_REMOTE_MAX_UNKNOWN_POLLS` | 3 | `run` stops after this many polls in a row with unknown task states |
| `GA_SLURM_MAX_ARRAY_SIZE` | 100001 | the cluster's MaxArraySize; larger indices are refused before sbatch |

Local files, all in the submission's `remote/` directory: `state.json`,
`lock`, `events.log` (append-only record of remote actions), `status.json`,
`incoming/`, `collected/`, `collection.json`. The prepared inputs stay
unchanged. Changing `GA_SSH_TARGET`, `GA_REMOTE_BASE` or `GA_REMOTE_SUBDIR`
after an upload is refused for that submission.

| Exit | `inference` meaning |
|---|---|
| 0 | Done; for `run`, every prompt has a completed result |
| 2 | Invalid arguments, settings or submission; or nothing to attach |
| 3 | Incomplete or refused: failed or missing prompts, unknown scheduler state, an UNKNOWN or rejected sbatch call, hash or identity mismatch, transfer failure, or another process holds the submission |
| 4 | Internal error |
| 5 | The shared SSH connection is not available: reconnect (`inference connect`) and rerun; progress is kept |

**Tested, and what isn't verified yet.** Unit tests cover the command lines,
output parsing and the whole flow against fakes. That includes a live local
master with a broken remote side, stale-master recovery, transport loss during
squeue/sacct, collection without scheduler history, and cached versus fresh
status. A manual cross-check ran `inference run`, `retry` and `status` with real
rsync, bash and `sha256sum`, through a fake `ssh` and fake
`sbatch`/`squeue`/`sacct` that ran the real worker with a fake vLLM.

On Wulver, the previous version uploaded, submitted (array jobs 1628816 and
1629432), monitored and collected results. That showed the Duo login,
`bash -l` finding the Slurm tools, `sha256sum` and rsync work there. The
incident behind the health check also came from there: after a VPN change, a
lingering master answered `-O check` while sessions failed with "Broken pipe".
Not yet verified on Wulver:
- the new health check, stale-master recovery, `ProxyCommand=false` and
  `ServerAliveInterval`;
- a real VPN-off, sleep and reconnect cycle with this version;
- that sacct reports `WorkDir` (needed by `attach`);
- the effective account and QoS limits for large arrays.

The GPU smoke test (T13d) is still needed before relying on results.

## Applying a proposal (`apply-proposal`)

```sh
$GA apply-proposal runs/<run id>/inference/<submission id> --warning W0001
```

This imports the collected answer for one warning and applies its annotation
edits to a **fresh copy of the run snapshot** (`workspace/target`). The
original project, the snapshot, the Specimin slices and the inference
artifacts are only read. Neither the LLM nor the checker is run, and the
warning graph is not changed. An applied edit is a source change, not evidence
that the warning is resolved, or even that the code compiles. To recheck proposals with
the checker, use `evaluate-annotations` (next section), which evaluates every
proposal's complete edit set as one package.

**Import.** The result used is the one `remote/collection.json` selected. It is
verified again with the collection's own checks:
- the submission's inputs still have their recorded SHA-256;
- the result names this submission, run, warning and prompt hash;
- `result.json` matches the attempt's `metadata.json`, and the answer and raw
  response exist;
- the entry in `collection.json` agrees with that verification. Otherwise the
  command asks for `inference collect` again.

Only `COMPLETED_PROPOSED` results are imported. `FAILED`, `NO_RESULT`,
`INVALID_RESULT` and `COMPLETED_INSUFFICIENT_EVIDENCE` are reported (exit 3)
and never treated as an empty proposal. The answer must follow
`prompt-template-1`; each edit becomes an `AnnotationEdit` with:
- source root and source-relative file;
- fully qualified declaring class, nested classes joined by `.`;
- the field name, or the method or constructor with its parameter types;
- the position: `FIELD`, `RETURN`, `PARAMETER` with its index, or `TYPE_USE`
  with a structured path;
- `ADD` or `REMOVE`;
- the fully qualified annotation.

Rationale, uncertainty and provenance stay outside the edit's identity. The
edits of one answer are one proposal, applied together. Identical edits are
merged, and every origin is kept. Proposals of different warnings are never
merged. The source root is taken from the run, not from the answer: the
warning's resolved target in `warning-targets.json` (the package root the
prompt named), whose configured root must be one of `report.json`'s source
roots. Absolute paths, `..`, and files or directories reached through symbolic
links are rejected.

**Locating declarations.** The applier parses each file with JavaParser 3.28.2
and finds the declaration structurally:
- the package and the nested-class path of the declaring class;
- a field by name, never a local variable or parameter;
- a method or constructor by name and parameter types.

Parameter types are normalized: whitespace, annotations, `final`, and `...`
versus `[]` don't matter. Names are not resolved, so `Map` and `java.util.Map`
stay different. A raw type matches the same type with type arguments, in
either direction, and the record says so; Java does not allow overloads that
differ only in type arguments. Anything else must be equal. Ambiguous or
missing targets are rejected with the candidates. Parameter indices are
checked, and `TYPE_USE` paths (`typeArgument[i]`, `arrayComponent`,
`wildcardBound`) are followed explicitly.

**Writing annotations.** New annotations are written fully qualified, at the
exact type position, and no import or dependency is added:

| Position | Result |
|---|---|
| field, return or parameter type | `private @A Object x`, `public <T> @A T get(...)`, `f(@A String s)` |
| qualified or nested type | `java.util.@A Map<K, V>`, `Outer.@A Inner` |
| array (the array itself) | `String @A [] a` |
| `field/arrayComponent` of `String[][]` | `String[] @A [] m` |
| type argument or wildcard bound | `List<@A String>`, `List<? extends @A Number>` |

Whether a placement is legal Java depends on the annotation's `@Target`, which
the tool does not load. A provisional table (`edits.AnnotationKinds`) handles
this:
- known type-use annotations (JSpecify, Checker Framework) get the exact
  placements above;
- known declaration annotations (`javax.annotation`, AndroidX, FindBugs) go
  before the declaration's modifiers and cannot annotate nested type uses;
- other annotations are placed only on simple, unqualified, non-array declared
  types, where both readings give the same source.

**Rejected rather than guessed:**
- fields declared together (`Object a, b;`), which would annotate the siblings;
- C-style array declarators (`String a[]`) and varargs parameters;
- an existing annotation of the same simple name whose qualified name the
  imports don't determine (for example under an on-demand import);
- a different or contradictory nullness annotation already at the position;
- ADD and REMOVE of the same annotation, or two different nullness
  annotations, at one position.

Adding an annotation that is already present, or removing one that is absent,
is an explicit `NO_OP`.

**Rewriting.** Files are changed by inserting or deleting only the annotation
text, at positions taken from JavaParser's tokens. Comments, formatting and all
other code stay byte for byte. JavaParser's `LexicalPreservingPrinter` was
tried first. It ignored in-place changes to field types, and after a type was
replaced it printed `@A` glued to a primitive type (`@…Nullableint`). Each
rewritten file is parsed again and checked:
- its comments are unchanged;
- its tree, without comments and without annotations named like the edited
  ones, is identical to the original;
- every applied edit is observable at its position.

**Atomicity and isolation.** Every edit of a proposal is checked before
anything is written. If any edit is rejected, no file is written and no
workspace is kept. Each application copies the snapshot afresh, so one
proposal's edits never reach another's. Output goes to a new directory, built
under a temporary name and renamed when complete; an earlier application is
never overwritten, and a second one gets a `-2` suffix:

```
runs/<run id>/applications/<UTC timestamp>-<ID>/
  application.json   run, submission, warning, inference attempt, prompt and answer SHA-256; canonical
                     edits with their origins and APPLIED / NO_OP / REJECTED and a reason; overall
                     APPLIED / NO_CHANGE / REJECTED; changed files with SHA-256 before and after
  changes.diff       unified diff (APPLIED only)
  workspace/         the edited copy of the snapshot (APPLIED only)
```

The edited files of the snapshot are hashed before and after, so a change to
the snapshot during application is detected. The run records no hashes of the
analyzed sources, though, so a snapshot edited *after* the check cannot be
detected. That limitation is stated in `application.json`.

| Exit | `apply-proposal` meaning |
|---|---|
| 0 | APPLIED, or NO_CHANGE (every edit already satisfied) |
| 2 | Invalid arguments or inconsistent artifacts (wrong run, stale collection, source root not configured); nothing written |
| 3 | No applicable proposal (failed, missing, invalid or insufficient-evidence result; nothing written), or the proposal was REJECTED (record written, no workspace) |
| 4 | Internal error |

On a scratch copy of the EventBus run, all 39 collected proposals were
APPLIED, and W0008 and W0011 were reported as failed results. Every one of the
39 workspaces compiled with plain `javac` with JSpecify on the classpath.

## Evaluating annotations (`evaluate-annotations`)

```sh
export GA_CHECKER=nullaway
export GA_CHECKER_HOME=$HOME/Documents/NullAway
export GA_TARGET_CLASSPATH=$HOME/.gradle/caches/modules-2/files-2.1/org.jspecify/jspecify/1.0.0/7425a601c1c7ec76645a78d22b8c6a627edee507/jspecify-1.0.0.jar
$GA evaluate-annotations runs/<run id>/inference/<submission id>              # every package
$GA evaluate-annotations runs/<run id>/inference/<submission id> --warning W0007
$GA evaluate-annotations runs/<run id>/inference/<submission id> \
    --reuse-from runs/<run id>/evaluations/<earlier evaluation id>            # rebuild, see below
$GA visualize runs/<run id>/evaluations/<evaluation id>                      # evaluation-graph.svg
```

The checker settings are the same environment variables as for `check`
(`GA_CHECKER`, `GA_CHECKER_HOME`, `GA_TARGET_CLASSPATH`,
`GA_CHECKER_JAVA_HOME`, `GA_JAVAC_RELEASE`, `GA_TIMEOUT_SECONDS`,
`GA_NULLAWAY_ANNOTATED_PACKAGES`). The target and source roots are the run's
own (`report.json`), so `GA_TARGET_PROJECT` and `GA_TARGET_SOURCE_ROOTS` are
not used. Proposed edits name annotations such as
`org.jspecify.annotations.Nullable` fully qualified, without adding a
dependency, so put the annotation jar on `GA_TARGET_CLASSPATH`. Otherwise every
applied edit ends as `COMPILATION_FAILED`.

What it does:

1. **Packages.** Every validated COMPLETED_PROPOSED answer of the submission
   is imported (the same checks as `apply-proposal`). Its complete edit set is
   an **annotation package**. Answers whose complete edit sets are equal share
   **one package** `A0001, ...`, whatever warnings they were about.
   - **Equivalence.** Two edits are equal when source root, source-relative
     file, declaring class, member signature, exact position (including the
     parameter index and type-use path), action and annotation name are all
     equal. Edit sets are compared as sets, so edit order and repeated entries
     don't matter.
   - **What is normalized.** Only what the edit model already normalizes:
     whitespace, annotations and `final` in parameter types, varargs as `[]`,
     and a type-use path without steps as its root position. Names are not
     resolved, so `String` and `java.lang.String` stay different. Rationale,
     uncertainty, warning and provenance are not part of equivalence.
   - **Only identical sets merge.** Packages that merely overlap, or where one
     is a subset of another, stay separate. A package is never split.
   - **Origins.** Every answer of a package is kept as an origin: warning,
     answer (`W0037@1`), attempt, prompt hash, answer file and hash,
     rationale, uncertainty, and for each edit the answer entries that
     proposed it (indices and per-entry uncertainty).
   - **Order.** A package's edits are kept in canonical order. Package IDs
     follow the numeric order of each package's first warning.
   - **Excluded answers.** Failed, missing, invalid and insufficient-evidence
     answers are listed as excluded, with the reason.
   - **`--warning`** selects the package any of whose answers was about that
     warning; the package keeps all its origins. The checker still analyzes
     every source root, and the comparison uses every finding.
   - **Counts.** The output and `summary.json` report both the number of
     imported proposals and the number of unique packages.
2. **Baseline.** The run snapshot is copied explicitly into
   `baseline/source/` (a plain file copy: git is not consulted, so an ignored
   `runs/` directory or a parent repository cannot hide sources), and the
   checker runs on it. A failed baseline blocks the evaluation. It is kept in
   `baseline-failed/<time>/` and retried next time.
3. **Attempts.** For each package, in ID order:
   - Copy `baseline/source/` to `attempts/<A>/source/`.
   - Apply **all of its edits together** with the AST applier, which validates
     every edit before writing any file. If one edit is rejected or two edits
     conflict, the whole package is REJECTED and no workspace is kept. A
     mixture of NO_OP and applied edits is applied; a package whose edits are
     all NO_OP is reported as NO_OP without a checker run.
   - Run the checker **once** with identical settings.
   - A package containing an edit that would suppress or switch off checking
     (`@SuppressWarnings`, `@NullUnmarked`, removing `@NullMarked`) is not
     evaluated.
   - Each package starts from the same baseline; packages never see each
     other's edits.
4. **Comparison.** For a completed analysis with the same checker build and
   annotated packages as the baseline, the findings are matched by the
   provisional rule `anchored-1` (project-spec §6.1). The rule uses the path,
   checker, message, enclosing declaration and source line with annotations
   removed. It never uses counts, run-local IDs or raw line/column, so inserted
   annotations do not break it. Each finding ends up **resolved**,
   **persisting**, **introduced**, or **ambiguous** (no claim).

Outcomes per package: `COMPLETED`, `AMBIGUOUS` (compared, but some findings
undecidable), `NO_OP`, `REJECTED`, `COMPILATION_FAILED`, `TIMED_OUT`, `CRASHED`,
`EXECUTION_FAILED`, `NOT_COMPARABLE` (different checker build or scope). In the
graph only, a package can also be `INTERRUPTED` or `UNEVALUATED`. Each edit's own
applier outcome (APPLIED, NO_OP, REJECTED) is recorded as well. Counts are
printed only for `COMPLETED` and `AMBIGUOUS`. An incomplete analysis is never
zero warnings.

```
runs/<run>/evaluations/<submission id>-<key>/
  evaluation.json        key material, settings, matcher assumptions, limitations
  candidates.json        unique packages (every edit, every originating answer with its provenance), excluded answers
  baseline/source/       full copy of the run snapshot (state S0); baseline/check/ logs
  baseline/result.json   baseline findings with IDs and structural anchors, tool identity
  attempts/<A>/          source/, changes.diff (every changed file), check/ (applied packages only),
                         comparison.json, attempt.json (package outcome, each edit's applier outcome,
                         counts, resolved and introduced warnings, every origin)
  attempts/interrupted/<A>-<n>/   attempts found incomplete, moved aside before the rerun
  evaluation-graph.json  expanded graph (schemaVersion 5); summary.json (with the round's warning-ID sequence)
  reuse/<earlier id>.json   with --reuse-from: ID mapping and what was imported, conflicting or not reused
```

The `<key>` hashes everything results depend on: the snapshot's tree hash, the
checker configuration (including classpath file hashes and the JDK), the
candidate model (`equivalent-package-1`), the round, the package set and the
matching rule. Each attempt records its complete edit set, field by field,
and a hash of it as an index. A recorded result is reused only if its edit set
is equal field by field; an equal hash alone is not enough. A different
configuration, round or package therefore gets a new evaluation directory or
is refused. Results are reused only for the same baseline, configuration,
round and package. Rerunning the command resumes. Completed attempts
(those with `attempt.json`) are reused, incomplete ones are moved aside and
rerun, and the graph is rebuilt from the records, so nothing is duplicated. A
file lock refuses a second concurrent writer. The run snapshot,
`warning-graph.json` and the inference artifacts are only read.

**Graph** (`evaluation-graph.json`, `warning-graph.json` schemaVersion 5; extended propagation graphs may also hold `state` nodes and edge `provenance`, see propagation below):

- **Warning nodes.** Baseline warnings (`W0001`, ..., state `S0`, round 0,
  root, distance 0) are always kept. A warning introduced by package `A`
  becomes a new node with state `S0+A`, distance 1, and an ID from the
  inference round's sequence (see "Warning IDs by round" below). Introduced
  warnings of different attempts are always separate nodes.
- **Annotation nodes.** Each unique package is one annotation node, applied
  to `S0`. It holds **every edit** of the package (with its applier outcome),
  its outcome and counts, and **every answer** that proposed it (warning,
  answer, and the answer indices of each edit). These proposal associations
  are node data, not edges, so they are never confused with verified
  resolutions. Edits never get nodes of their own. A package shared by four
  answers has one node and one set of introduced warnings.
- **Edges.** `W -> A` (`resolved-by`) for **every** baseline warning the
  comparison found resolved by the whole package, whether or not `A` was
  proposed for it. `A -> N` (`introduces`) for every introduced warning.
  Resolution is never inferred from a lower count. Edges are never
  duplicated.
- **Nodes without edges.** An annotation node without edges states why in its
  outcome: unevaluated, no change, no-op, rejected, failed, or ambiguous.

`visualize` draws warnings as boxes (introduced ones with a thicker border) and annotations
as ellipses coloured by outcome, with `resolved-by` edges green and
`introduces` edges red. Annotation nodes show their outcome, number of edits
and, when shared, number of answers. Their tooltips list every edit with its
applier outcome and every originating answer. All tooltips name the node's
round.

**Warning IDs by round.** A *round* is one iteration of LLM inference and
evaluation. It is not one annotation attempt, and not a graph distance.

- **Initial warnings** keep `W0001`, `W0002`, ...
- **Introduced warnings.** Round 1 introduces `W1001`, `W1002`, ...; round 2
  starts at `W2001`; round 12 at `W12001`. That is `W`, the round number, and a
  three-digit sequence.
- **Shared sequence.** All attempts of a round draw from the round's one
  sequence. A warning gets a new ID only when the comparison finds it
  introduced. Persisting warnings keep their baseline IDs.
- **Round metadata.** The round is stored explicitly: `round` in
  `evaluation.json`, `attempt.json` and `summary.json`, and on every graph node
  (0 for initial warnings). It is never read from the ID, because the ID alone
  is ambiguous: `W1001` would also be the 1001st initial warning.
- **Allocation.** An ID is allocated when an attempt is recorded, continuing
  after the highest ID in the completed attempt records. IDs are therefore
  never renumbered on resume, and an interrupted attempt leaves no ID behind.
  An unfiltered evaluation numbers in candidate order. When `--warning` runs
  some candidates first, they get the earlier numbers.
- **Comparison records.** `comparison.json` maps each introduced result
  finding to its warning ID (`introducedWarnings`).
- **Limits.** A round holds at most 999 introduced warnings. The 1000th stops
  the evaluation (exit 3) rather than produce an ID of another round. An ID
  already in the graph, which happens if there are more than 999 initial
  warnings, is refused the same way.

`evaluate-annotations` always evaluates round 1: answers about the run's
initial warnings. Inference on introduced warnings, which would make round 2,
is not implemented. Later rounds exist only as a parameter of the evaluation
code and its tests.

**Migrating older evaluations.** Evaluations written before rounds were
recorded name introduced warnings `A0003-N1`. `evaluate-annotations` refuses
to continue them (exit 3) instead of rewriting them silently. Convert one
explicitly; nothing is rerun:

```sh
$GA migrate-warning-ids runs/<run id>/evaluations/<evaluation id>
$GA visualize runs/<run id>/evaluations/<evaluation id>     # the old SVG is removed
```

How the migration works:

- **Round.** It takes the round recorded in `evaluation.json`. Otherwise it
  establishes round 1 only if the evaluation started from the run snapshot
  and every candidate was proposed for an initial warning of the run (a root
  node of its `warning-graph.json`). Anything else is refused as ambiguous,
  and nothing is changed.
- **Renaming.** Introduced warnings are renamed in candidate order, then in
  their recorded order (`A0001-N1, A0001-N2, A0002-N1, ...` become `W1001,
  W1002, W1003, ...`). That is the order a fresh evaluation would use. Edges,
  attempt records, comparisons and the summary are updated. Baseline and
  annotation IDs stay the same.
- **Backup.** Every file it changes is first copied to
  `warning-id-migration-backup-<time>/` and verified.
- **Refusals.** It refuses overflow, IDs not in the old format, and a graph
  that disagrees with the attempt records.
- **Interruption.** The plan is kept in `.warning-id-migration.json` until
  the migration finishes. An interrupted migration blocks
  `evaluate-annotations` and is completed by running the command again.
- **Rerunning it** on a migrated directory reports that it is already
  current and changes nothing.

| Exit | `migrate-warning-ids` meaning |
|---|---|
| 0 | Migrated, or already current |
| 2 | Not an evaluation directory |
| 3 | Refused (round not established, overflow, inconsistent records) or another process is writing the directory |
| 4 | Internal error (rerunning completes an interrupted migration; the backup holds the originals) |

Limitations:
- **Packages are evaluated alone.** The graph does not show whether
  independently successful packages combine safely.
- **No per-edit attribution.** A package's result is that of its whole edit
  set; it doesn't say which edit caused which change.
- **Overlapping packages are checked separately.** Only identical edit sets
  share a package. Packages that share some edits are evaluated separately,
  so a common edit is checked once per package, and warnings it introduces
  can appear once per package.
- **Multi-field messages.** When NullAway lists several fields in one message
  (for example "initializer method does not guarantee @NonNull fields 'a',
  'b'"), a package that annotates only some of them changes the message. The
  rule then records one resolved and one introduced warning, not a narrowed
  one.

**Rebuilding from an earlier one-package-per-answer evaluation
(`--reuse-from`).** The previous version made one package per answer, so
equivalent answers (for example the four EventBus answers that all add
`@Nullable` to `SubscriberMethodFinder.FindState.clazz`) were evaluated four
times. Such an evaluation has a different key and is kept as it is;
`evaluate-annotations` lists it as "kept, not reused". To rebuild the merged
evaluation from its checker results instead of rerunning them, pass it
explicitly:

```sh
$GA evaluate-annotations runs/<run id>/inference/<submission id> \
    --reuse-from runs/<run id>/evaluations/<earlier evaluation id>
```

What `--reuse-from` checks and does:

- **Equivalence.** The earlier evaluation must be a one-package-per-answer
  evaluation (`answer-package-1`). Its key must name the same run,
  submission, snapshot tree hash, complete checker configuration, matcher and
  round. Otherwise nothing is reused (exit 3). The baseline is rerun (or
  reused from the new directory), and its findings and checker identity must
  equal the earlier baseline's.
- **Mapping.** Every earlier package is mapped to the merged package with
  exactly the same edit set, compared field by field. Its answer must be one
  of that package's origins, with the same answer hash. The mapping is
  written to `reuse/<earlier id>.json`.
- **Import.** A merged package's result is imported only if all of its
  complete earlier attempts have equivalent results. Equivalent means the
  same outcome, detail, counts, resolved, ambiguous and introduced findings,
  per-edit applier outcomes, diff, comparison and checker identity; paths
  inside each attempt's own directory are ignored.
  - The import copies the workspace, diff and logs, and gives introduced
    warnings the next IDs of the round, as a fresh evaluation would.
  - It records `importedFrom` (the earlier attempt, the equivalent attempts
    and the old-to-new warning IDs).
  - Earlier diagnostic lists are never merged into new results.
- **Conflict.** If earlier attempts of one package disagree, the conflict is
  printed and recorded, and the package is evaluated again. No earlier result
  is chosen.
- **Rejected and no-op results** are not imported. They are evaluated again,
  which runs no checker.
- **Read only.** The earlier directory is never changed. Later runs without
  `--reuse-from` simply resume.

**Earlier single-edit evaluations.** Before packages, each distinct edit was
evaluated alone; that split W0037's three-edit answer into three edits whose
separate results said nothing about the answer as a whole. Such evaluation
directories have a different key, so they are **kept as they are and never
reused**. `evaluate-annotations` lists them as "kept, not reused" and evaluates
every package afresh in a new directory; `--reuse-from` refuses them.
`migrate-warning-ids` still converts
their IDs for viewing and keeps their single-edit meaning (one edit per
annotation node, `candidateModel` marked historical). It never turns them into
package results.

| Exit | `evaluate-annotations` meaning |
|---|---|
| 0 | Every selected package has a recorded outcome (which may be a failure of that attempt) |
| 2 | Invalid arguments, configuration or inference artifacts |
| 3 | Nothing to evaluate, the baseline analysis failed, another process is writing the evaluation, the evaluation must be migrated first (`migrate-warning-ids`), the `--reuse-from` evaluation is not equivalent, or the round's 999 warning IDs are used up |
| 4 | Internal error (an interrupted attempt is moved aside and rerun next time) |

On a scratch copy of the EventBus run (NullAway 0.14.2-SNAPSHOT checkout, JSpecify 1.0.0
on the classpath), the baseline reproduced the run's 42 findings exactly.
- **Packages.** The 39 proposed answers gave **27 unique packages** with 30
  edits in all; W0008 and W0011 were excluded. Eight packages are shared by
  20 answers. The largest is the `FindState.clazz` edit, proposed for W0024,
  W0028, W0030 and W0031.
- **Outcomes.** All 27 packages completed. 17 reduced the finding count, 8
  kept it with matched changes, and 2 increased it.
- **Shared `clazz` package.** It became one annotation node (A0017): it
  resolves W0024, W0028, W0030 and W0031 and introduces five warnings, now
  once instead of four times. The graph has 19 introduced warning nodes, where
  the one-package-per-answer evaluation had 38.
- **W0037.** Its three-edit package resolves W0037 and introduces nothing.
- **Rebuild.** `--reuse-from` on the earlier one-package-per-answer evaluation
  verified the baseline (one checker run) and imported all 27 results in
  about 7 seconds. A fresh evaluation (27 checker runs) took about 70 seconds
  and wrote a byte-identical `evaluation-graph.json`.
- **Old directories.** The earlier evaluation directories were left
  byte-identical.

## Applying a whole round (`apply-round`)

`evaluate-annotations` checks each package alone. `apply-round` applies
**every** validated package of a round together and checks the resulting
source state once:

```sh
export GA_CHECKER=nullaway
export GA_CHECKER_HOME=$HOME/Documents/NullAway
export GA_TARGET_CLASSPATH=$HOME/.gradle/caches/modules-2/files-2.1/org.jspecify/jspecify/1.0.0/7425a601c1c7ec76645a78d22b8c6a627edee507/jspecify-1.0.0.jar
$GA apply-round runs/<run id>/inference/<submission id> --round 1
less runs/<run id>/rounds/<round id>/warnings.txt          # every warning of the combined state
```

The checker settings are the same as for `evaluate-annotations`. Only
`--round 1` (answers about the run's initial warnings) is supported.

What it does:

1. **Packages.** The answers are imported and merged exactly as for
   `evaluate-annotations`, keeping package boundaries and provenance.
   Excluded answers (failed, missing, invalid, insufficient evidence) are
   listed; the round then does not cover every warning. No package is left out
   because it increased warnings when evaluated alone.
2. **Union.** All edits of all packages are collected. An edit proposed by
   several packages is applied once and lists every package and answer that
   proposed it. Every package keeps all its edits.
3. **Validation before anything is published.** The AST applier compares each
   edit with the declarations of a fresh copy of the run snapshot.
   - An annotation that is already present is NO_OP.
   - These are rejected: ADD and REMOVE of the same annotation at one
     position, two nullness annotations at one position, missing or ambiguous
     targets, unsupported placements, and edits that would switch checking off
     (`@SuppressWarnings`, `@NullUnmarked`, removing `@NullMarked`).
   - One rejected edit rejects the round. Every conflict is reported with its
     packages and answers, nothing is applied, and no workspace is
     published. Conflicts are never settled by order, and no edit is dropped.
   - `changes.diff` files of earlier evaluations are not used; edits are
     matched structurally.
4. **One combined state.** All edits are applied together to one full copy of
   the run snapshot (an explicit file copy, so ignore rules of a parent git
   repository don't matter; symlinks are skipped). The result is one
   `changes.diff` with per-file hashes.
5. **Baseline.** An evaluation baseline of the same run is reused only if its
   snapshot tree hash, complete configuration fingerprint and matcher are
   equal and its source copy still has its recorded hash. Otherwise a fresh
   baseline is checked.
6. **One check.** NullAway runs once on the combined workspace with the same
   source roots, classpath, JDK, `--release` and annotated packages. The whole
   repository is copied, but only the configured source roots are analyzed;
   the output lists the Java files outside them as **not checked**.
7. **Every finding.** All findings of the combined state are saved, not only
   new ones. `anchored-1` classifies each one:
   - persisting findings keep their baseline ID;
   - introduced and ambiguous findings get round-1 IDs (`W1001, ...`, scoped
     to this round result);
   - resolved baseline warnings are listed by ID.
   Nothing is inferred from counts, and independent attempt results are never
   combined.
8. **Failures.** A compilation error (for example a missing annotation jar),
   crash or timeout is an execution failure, not zero warnings. The partial
   findings are marked INCOMPLETE, the logs are kept, and no comparison is
   made. If the checker build differs from the baseline's, the findings are
   saved but not compared (NOT_COMPARABLE).

```
runs/<run>/rounds/round1-<key>/      published by an atomic rename, never overwritten
  round.json         state ID, round, key (snapshot, configuration, packages, complete edit set, matcher),
                     status, baseline (fresh or reused, verified), packages, excluded answers,
                     every distinct edit with its packages, answers and applier outcome, conflicts,
                     changed files with hashes, analyzed scope, check commands, comparison counts
  candidates.json    the packages with full provenance
  workspace/target/  the annotated full repository copy
  changes.diff       every change of the round
  check/             raw checker logs (they name the temporary build directory)
  baseline/          a fresh baseline (source, check logs, result.json), if one was run
  findings.json      every finding of the combined state, with ID, round, status, anchor and diagnostic
  warnings.txt       the same list, readable
  comparison.json    resolved, persisting, introduced and ambiguous findings (matcher anchored-1)
  report.json, warning-graph.json   a run-directory view of the combined state (completed results only)
runs/<run>/rounds/round1-<key>-failed-<time>/   a failed attempt (baseline or combined check), kept for inspection
```

- **Reuse.** The `<key>` covers the run, submission, snapshot tree hash,
  checker configuration, package set with provenance, complete edit set and
  matcher. A completed, rejected or not-comparable result is reported again
  by later invocations without rerunning anything. A result whose
  `workspace/target` was modified afterwards is refused. A failed attempt is
  kept under `-failed-<time>`, and the next invocation tries again.
- **State.** The state ID (`round1-<key>`) means the run snapshot `S0` with all
  round-1 packages applied. It is not the state of any annotation attempt
  (`S0+A0001`). Combined resolutions and introductions are not attributed to
  individual packages or annotation nodes, and evaluation graphs are not
  changed.
- **Next round.** A completed result directory is laid out like a run
  directory, so `targets`, `slice` and `context` accept it:
  - `report.json` holds the effective configuration and points to
    `workspace/target`;
  - `warning-graph.json` holds every finding of the combined state as a root
    node, with the IDs above.
  Inference on it (round 2) is not implemented yet.

| Exit | `apply-round` meaning |
|---|---|
| 0 | The combined state was checked completely and compared (or that result was reported again) |
| 2 | Invalid arguments (`--round` other than 1), configuration or inference artifacts |
| 3 | Nothing to apply, an edit was rejected or conflicts, the baseline or combined check failed, a different checker build (not comparable), or another process is writing the result |
| 4 | Internal error, or the published workspace was modified |

On a scratch copy of the EventBus run, all 27 packages (30 distinct edits from
39 answers; W0008 and W0011 excluded) applied without conflicts.
- **Timing.** The evaluation's baseline was verified and reused, and the one
  NullAway run took about 5 seconds.
- **Findings.** 42 became 19: 40 resolved, 17 introduced and 2 persisting. The
  two persisting warnings are W0008 and W0011, the ones without an answer.
  The 17 introduced warnings (`W1001`–`W1017`) are mostly dereferences of
  fields the round made `@Nullable`.
- **Interaction.** The independent evaluations together had 19 introduced
  warnings, so the combined state is not the sum of the separate results.
- **Next round.** `targets` accepted the result directory as a run.
- **Originals.** The run snapshot, inference results and earlier evaluations
  stayed byte-identical.

## Single-pass nullability propagation (`propagate`)

A deterministic alternative to LLM proposals: one pass over a fixed set of
warnings that turns direct nullable flows into `@Nullable` edits. No LLM is
called. The checker settings are the same as for `evaluate-annotations`
(`GA_CHECKER`, `GA_CHECKER_HOME`, `GA_TARGET_CLASSPATH`,
`GA_CHECKER_JAVA_HOME`, ...).

```sh
$GA pipeline propagate runs/<run id>    # after pipeline finish: reads the combined round-1 result
$GA propagate runs/<run id>             # the same input selection, without recording the pipeline stage
```

**Input.** The input is chosen from the directory, never by sorting
directory names:
- **A pipeline directory:** the completed combined round-1 result named in
  `pipeline.json` (stage `combined`, `rounds/round1-<key>/`). That means its
  full annotated snapshot and its complete NullAway warnings.
- **Verification.** The snapshot's tree hash must equal `round.json`'s. The
  run IDs of `round.json`, `report.json` and `warning-graph.json` must agree,
  and so must the finding counts and IDs of the graph and `findings.json`.
- **Refusal.** If the combined stage hasn't completed, or anything fails to
  verify, the command stops (exit 3) and says what to do, typically to run
  `pipeline finish` first. It never falls back to the original program.
- **A combined round result directory** (`rounds/round1-<key>/`) is read the
  same way.
- **Any other run directory** (no pipeline) uses the run's own baseline,
  which the output labels "round 0".

The command prints the selected input round, its snapshot and its warning
count. For an EventBus pipeline after round 1, that is `round1-<key>` with its
23 warnings, not the original 42.

**What matches.** Every baseline warning in the run's `warning-graph.json` is
classified against the unchanged snapshot, which javac attributes. A warning
matches only if the NullAway message kind, the syntax tree at its position and
the resolved symbols all agree:

| Rule | NullAway message | Tree and symbols | Proposed edit |
|---|---|---|---|
| `FIELD_ASSIGNMENT` | assigning @Nullable expression to @NonNull field | `f = x` where `x` reads a field, or calls a method, explicitly annotated `@Nullable`, and `f` is a field | `@Nullable` on `f`'s type |
| `FIELD_INITIALIZER` | the same | a field declaration `T f = x` with such an `x` | `@Nullable` on `f`'s type |
| `RETURN` | returning @Nullable expression from method with @NonNull return type | `return x` with such an `x`, in a method (not a lambda) | `@Nullable` on the method's return type |
| `ARGUMENT` | passing @Nullable parameter 'x' where @NonNull is required | an argument `x` (a field read, a method call, or a local or parameter) of a call whose method or constructor javac resolved; the argument index gives the formal parameter | `@Nullable` on that parameter, and on the same parameter of every implementation and override in the editable sources; explicit non-null annotations are removed in the same package |

For the assignment and return rules, `x` may also be a local variable
initialized (directly or through other locals, up to four) from such a field
read or call. The proposed annotation is the value origin's own `@Nullable`
(for example `org.jspecify.annotations.Nullable`).

For `ARGUMENT`, the NullAway diagnostic is the evidence that the value may be
null; the argument needs no annotation of its own:
- **Resolution.** The call is the innermost one with an argument at the
  diagnostic's position whose text equals the expression NullAway quotes. The
  invoked executable is the one javac attributed, so overloads are told apart
  by their types, never by name. An inherited method is annotated where it is
  declared.
- **Annotation.** The origin's own `@Nullable` (traced through locals as
  above), or else the project's nullable annotation type if the sources use
  exactly one.
- **Explicit `@NonNull`.** The package removes it and adds `@Nullable` at the
  same position (two edits, applied atomically). `analysis.json` records the
  `contractChange`.
- **Interface, abstract and overridden methods.** When the resolved method
  can be overridden, the package also covers every method of the editable
  sources that overrides or implements it:
  - The relation is javac's (`Elements.overrides`, with type arguments
    substituted), so `put(String)` implementing `Sink<String>.put(T)` is
    found, while overloads and same-named methods are not.
  - It includes implementations inherited from another class (`class Impl
    extends Helper implements Handler` adds `Helper#handle`) and overrides of
    overrides, repeated until nothing new is found.
  - The method's ancestors are never widened. A call resolved to `L2#go`
    changes `L2` and its overrides, not `L1#go`: an override may accept more
    than what it overrides.
  - Implementations that are already `@Nullable` are kept. Explicit non-null
    ones are replaced, and each replacement is listed in `contractChange`.
  - All the edits form one package, which is one annotation node, applied
    and evaluated atomically. Two warnings that need the same complete
    package share it.
  - If a required declaration can't be edited consistently, the whole
    package is skipped (`INCOMPLETE_HIERARCHY`). That covers an
    implementation declared outside the editable sources, for example one
    inherited from `java.io.PrintStream`, and an override in an anonymous or
    local class.
  - Coverage: only the configured source roots are searched.
    Implementations in other modules, or in generated or library code, are
    not discovered, and the evidence says so. Lambdas and method references
    implementing the method are not edited. NullAway reports any resulting
    problems as new warnings in the evaluation.
- **Skipped:** varargs positions (`UNSUPPORTED_TARGET`), anonymous class
  creations, executables outside the editable sources (`NOT_EDITABLE`), and
  `null` literals, conditionals or casts as arguments.

Regression examples (EventBus, round 1):
- `Object event = pendingPost.event; invokeSubscriber(subscription, event);`,
  with `PendingPost.event` `@Nullable`, proposes `void
  invokeSubscriber(Subscription subscription, @org.jspecify.annotations.Nullable
  Object event)`.
- `logger.log(Level.SEVERE, msg, cause)` proposes one package that annotates
  `th` in `Logger#log(Level, String, Throwable)`, `JavaLogger#log` and
  `SystemOutLogger#log`. `log(Level, String)` is not touched.
  `AndroidLogger` lives in the `eventbus-android` module, outside the
  configured source root, so it isn't part of the package.

**What is skipped.** Everything else, with the reason recorded in
`analysis.json`:
- **`OTHER_CATEGORY`:** other warning kinds. For example, "dereferenced
  expression 'findState.clazz' is @Nullable" is a dereference and never
  produces an annotation.
- **`NOT_A_NULLABLE_FIELD_OR_METHOD`:** `null`, conditionals, or (for
  assignments and returns) a source, or a local's origin, that is not
  explicitly `@Nullable`.
- **`UNRESOLVED`:** positions or symbols that don't resolve to a matching tree.
- **`EXPLICIT_CONTRACT`:** a destination that is already `@Nullable`, has a
  non-null annotation, or is a method that overrides another method.
- **`NOT_EDITABLE`:** a destination outside the configured source roots, in an
  anonymous or local class, or a field declared together with others.
- **`UNSUPPORTED_TARGET`:** a varargs position or an anonymous class
  creation (see `ARGUMENT`).
- **`INCOMPLETE_HIERARCHY`:** an implementation or override that the package
  would also have to change cannot be edited (see `ARGUMENT`); nothing of the
  package is proposed.

The rule set is versioned (`nullable-flow-3`, generator
`nullability-propagation-3`). Its version is part of `analysis.json` and so
of every cache key. Results from earlier versions are never reused:
`nullable-flow-1` skipped argument warnings, and `nullable-flow-2` skipped
interface, abstract and overridden targets.

**What happens next.**
- **Candidates.** All candidates come from the fixed input, before any edit.
  Equal edit sets share one package (`P0001, ...`), which keeps every
  originating warning (`<warning>@propagation-r<source round>`).
- **Independent evaluation.** Each unique package is evaluated alone on a
  fresh copy of the input snapshot, so round 1's annotations are kept
  (`evaluate-annotations`' evaluator, round = source round + 1).
  - Baseline warnings keep the input's IDs (the fresh baseline must
    reproduce its findings exactly).
  - New warnings get the next round's IDs: `W2001, ...` after round 1.
  - There is one annotation node per package; its proposals are named
    `<warning>@propagation-1`, and the generator is recorded as
    `nullability-propagation-3`.
  - The graph gets `W -> A` for every resolved warning and `A -> N` for every
    introduced one.
  - Failed or incomplete evaluations claim nothing.
  - **Extended graph.** `propagation/<id>/evaluation-graph.json` keeps the
    earlier round-1 evaluation graph whole (every node and edge, including
    `A -> W1...` for warnings single packages introduced) and adds this
    stage. It has only two node kinds (warnings, annotation packages) and
    two solid edge kinds: `W -> A` (`resolved-by`: applying A resolved W)
    and `A -> W` (`introduces`: applying A introduced W). Every edge comes
    from one complete, independent package evaluation against its source
    state; the combined result adds no node and no edge. It numbers its
    warnings independently, so its `W1001` is often a different warning
    from the evaluation's `W1001`, and input warnings are never matched by
    ID or by diagnostic similarity alone:
    - **Persisting** from the original baseline: linked to the earlier root
      (`W0...`), checked by the combined comparison and the matcher.
    - **Introduced** in the combined state: linked to the earlier node N
      only if N was introduced by a package applied in the combined state
      (package key checked), in its own completed evaluation on the same
      base state, the matcher pairs them, and no other package's warning is
      equivalent. The link reuses N's ID.
    - **Otherwise** (ambiguous, several candidates, or no individual
      evaluation introduced it): a separate warning node with a fresh
      round-1 ID and no incoming edge. Its origin is not established, and
      none is invented; `propagation.json` states the limitation.
    - **This stage:** `W -> P` for every input warning a package resolves,
      `P -> W2001, ...` for genuinely new warnings, nothing more. A package
      that introduces nothing ends at its node.
    - **Metadata:** every new edge records its evaluation, source and result
      states, the matcher and the warning's ID in the combined report
      (`reportWarning`). `propagation.json` maps every combined-report ID to
      its graph node (`history.reportToGraph`), with each link's evidence or
      limitation (`history.inputWarnings`).
    - Loading and saving reject any other node or edge kind, a wrong edge
      direction, and dangling or duplicate edges. ID clashes are refused,
      never renamed.
- **Combined result.** Every successfully evaluated package (COMPLETED or
  AMBIGUOUS) that clashes with no other package is applied together to
  another copy of the same input, and NullAway runs once (`apply-round`'s
  mechanism, `rounds/round2-<key>/`). It reuses the evaluation's baseline,
  so persisting warnings keep their IDs, and new IDs continue after the
  evaluation's.
  Conflicting packages are reported and left out. The combined state's effect
  is never attributed to packages.
- **Exactly one pass.** Warnings introduced by the evaluations or by the
  combined check are recorded, not analyzed. If annotating `b` (assigned
  from a `@Nullable a`) makes `c = b` a new warning, `c` stays unchanged.

**Output:**
```
runs/<run>/propagation/<id>/        analysis.json (every warning: matched or skipped, reasons), propagation.json
                                    (input, packages, evaluation outcomes, combined inclusion, conflicts, history),
                                    summary.txt, evaluation-graph.json (the extended graph)
runs/<run>/evaluations/<id>-<key>/  the independent evaluations (this stage's layer of the graph)
runs/<run>/rounds/round2-<key>/     the combined workspace, changes.diff, findings.json, warnings.txt, report.json,
                                    warning-graph.json, comparison.json
```

**Regenerating the graph.** `propagate --rebuild-graph <propagation dir>`
rebuilds `evaluation-graph.json` (and the record's `history`) from the saved
artifacts only. It runs no checker, LLM or inference, and needs no
configuration. It refuses if the input or the evaluation no longer matches the
record, and a changed graph removes stale `.dot`/`.svg` renderings. The record
names the graph model (`graphModel`). A graph written under an earlier model
(T17a, or `reconciled-history-1` with `state` nodes) is stale and should be
rebuilt; this build cannot read graphs containing `state` nodes:
```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
GA=build/install/GeneralAnnotator/bin/GeneralAnnotator
$GA propagate --rebuild-graph runs/<run>/propagation/<id>
$GA visualize runs/<run>/propagation/<id>     # evaluation-graph.dot and .svg
```

**Reruns and exit codes.** Every key includes the input's snapshot, report
and warning-graph identity, so rerunning reuses only results for the same
input. Earlier results for another input stay where they are and are not
reused. That includes NO_CANDIDATES results computed from the original
baseline (`propagation1-*`); pipeline.json keeps them as `previous`.
Exit codes: 0 completed, no candidates, or nothing to combine; 2 invalid
arguments or configuration; 3 no completed and verified combined result (for
a pipeline), the evaluation baseline or the combined check failed, or the
combined set was rejected; 4 internal error.

**On real code.**
- **EventBus baseline:** all 42 warnings were skipped. The tree has no
  `@Nullable` annotations yet; its nullable flows are `null` literals,
  conditionals and locals.
- **A small JSpecify project** with real NullAway:
  - 5 of 12 warnings matched (an initializer, two assignments to one field, a
    method result and a return), giving 4 packages;
  - the explicit `@NonNull`, the dereference and the returned local were
    skipped;
  - every package was evaluated alone, and the combined state went from 12
    findings to 6;
  - the newly introduced `c = b` warning (W1001) was left for a later pass.
- **Copy of the EventBus pipeline after round 1:** the input was
  `round1-09d3783e43a5` with 23 warnings.
  - One warning matched: W1001, `mainThreadSupport =
    builder.getMainThreadSupport()`. Round 1 had made
    `getMainThreadSupport()` `@Nullable`, so the pass proposes `@Nullable`
    on `EventBus#mainThreadSupport`.
  - The package resolved W1001 and introduced nothing; the combined check
    went from 23 to 22 findings.
  - The rebuilt extended graph (`warning-annotation-history-2`) kept all 94
    earlier nodes and all 65 earlier edges. It linked 22 of the 23 input
    warnings to the earlier node of the one package that introduced them
    alone, and the remaining one (W0011) to its baseline root; none needed a
    separate node. The combined report's W1001 is the earlier W1007, so the
    chain reads `W0012 -> A0009 -> W1007 -> P0001` (the edge records
    `reportWarning: W1001`). There are no W2 nodes: 95 nodes (66 warnings, 29
    annotations) and 66 edges (42 `resolved-by`, 24 `introduces`).

## How the local NullAway checkout is used

1. **Setup.** GeneralAnnotator writes `generated/nullaway-processor-path.init.gradle`
   into the run directory and runs, in `GA_CHECKER_HOME`:
   `./gradlew --console=plain --init-script <that file> :nullaway:generalAnnotatorProcessorPath -PgeneralAnnotator.output=<file>`
   with `JAVA_HOME=<checker JDK>`. The init script adds a single task and edits no
   file in the checkout. The task depends on `:nullaway:jar`, so the jar is rebuilt
   from the checkout's current sources. It writes the processor path that
   NullAway's own `buildWithNullAway` task uses:
   - Error Prone jars from the checkout's `errorproneExtended` configuration
     (currently `error_prone_core` 2.50.0 and its dependencies),
   - `nullaway/build/libs/nullaway.jar` (the jar built from the checkout),
   - NullAway's `runtimeClasspath` (for example `dataflow-nullaway`, Guava).

   The resolved jar must lie inside `GA_CHECKER_HOME`; otherwise setup fails. A
   published NullAway artifact is never substituted. Side effects in the checkout
   are those of any normal build of it: ignored `build/` outputs, plus NullAway's
   `installGitHooks` task, which copies its pre-commit hook into `.git/hooks`.
   Do not run another Gradle build in the NullAway checkout at the same time
   (its `AGENTS.md` says parallel builds are unsupported).
2. **Analysis.** GeneralAnnotator runs `<checker JDK>/bin/javac` once, with the
   working copy as its working directory, Error Prone's required `-J--add-exports`
   / `--add-opens` flags, and an argument file `generated/javac.args` containing:
   `-XDcompilePolicy=simple --should-stop=ifError=FLOW -XDaddTypeAnnotationsToSymbol=true`,
   unlimited `-Xmaxerrs`/`-Xmaxwarns`, `-proc:none`, an empty `-sourcepath`,
   the configured `-classpath`, the processor path,
   `-Xplugin:ErrorProne -XepDisableAllChecks -Xep:NullAway:ERROR -XepOpt:NullAway:AnnotatedPackages=<scope>,generalannotator_probe`,
   and every `.java` file under the source roots plus a generated **probe** file.
3. **Probe.** `generated/probe/generalannotator_probe/GeneralAnnotatorProbe.java`
   dereferences a null local. If NullAway does not report it, the run is an
   execution failure (`CHECKER_NOT_ACTIVE`): empty output is never treated as
   "zero findings". Probe findings are reported separately and never counted as
   target findings.

## Isolation

The original target is only read. Each run copies it to
`<run>/workspace/target`:

- In a git work tree, the file list is `git ls-files --cached --others --exclude-standard`.
  It contains tracked files with their **current working-tree contents**
  (uncommitted edits included) and untracked, non-ignored files. Ignored files
  (build outputs, `.gradle/`, local `gradle.properties`, tool output directories)
  are **not** copied. Git runs with `--no-optional-locks`, so it does not
  refresh the target's index.
- Otherwise the tree is walked, skipping directories named `.git`, `.gradle`,
  `.idea`, `build`, `target`, `out`, and `bin`.
- **Symbolic links are not copied or followed**; they are listed in
  `isolation.skippedSymlinks`. Tracked files deleted from the working tree,
  submodules, and other directory entries are listed in `isolation.skippedOther`.
- Limitations: a source file that is git-ignored is not analyzed. Copies are
  full (not incremental). Run directories are never cleaned up automatically.

## Output

Each run creates `GA_OUTPUT_DIR/<UTC timestamp>-<checker>/`:

```
warnings.txt                   the findings, one per line with graph IDs (start here)
warning-graph.json             initial warning graph (only for a completed analysis)
warning-targets.json           Specimin target member per warning ID (only with a graph)
slices/                        written later by `slice` (not by `check`)
context/                       written later by `context` (not by `check`)
prompts/                       written later by `prompts`: <ID>.txt and manifest.json
inference/<submission id>/     written later by `prepare-inference` (immutable submission)
warning-graph.dot / .svg       written later by `visualize` (not by `check`)
report.json                    machine-readable report (below)
logs/<purpose>.stdout.txt      raw stdout of every external command
logs/<purpose>.stderr.txt      raw stderr (purposes: isolation-git-*, nullaway-setup, javac)
generated/                     init script, resolved processor path, javac.args, probe source
workspace/target/              the isolated working copy
classes/                       javac output directory (empty when NullAway reports errors)
```

The console summary shows the checker, target (with git HEAD), status, finding
count with severity counts, coverage, the warning graph's path and size (or
"not created"), the full paths of `warnings.txt` and `report.json`, and the exit
code. After a completed analysis it then prints the ID-to-warning mapping, one
line per node (lines longer than 160 characters are shortened with `...`). With the default `GA_OUTPUT_DIR`, run folders
are under `runs/` in the GeneralAnnotator checkout (not in the target).

### `warnings.txt`

`#` header lines give the checker, target, status and count. Then each finding
gets one line, prefixed with its warning-graph ID, in ID order. Paths are
relative to the target project:

```
W0001  EventBus/src/org/greenrobot/eventbus/EventBus.java:46:30: error: [NullAway] @NonNull static field 'defaultInstance' not initialized
```

The column is omitted when javac printed no caret. Only the first message line
is shown; the full text is in `report.json`. Probe findings are not listed. If
the analysis failed, there is no graph, so lines have no ID; the header says
`EXECUTION_FAILURE` and marks the list as **INCOMPLETE**. The file is written for every run that gets past configuration,
including runs with zero findings.

### `warning-graph.json` (schemaVersion 1)

Written only when the analysis **completed**. A completed analysis with zero
findings writes a graph with `"nodes": []`. An execution failure writes **no**
graph file, so an incomplete analysis can never look like an empty graph.
`report.json` then says `"warningGraph": {"created": false, ...}` with the reason.

```json
{
  "schemaVersion": 1,
  "graphType": "warning-graph",
  "runId": "20261118T000203Z-nullaway",
  "checker": "nullaway",
  "nodes": [
    {
      "id": "W0001",
      "kind": "warning",
      "root": true,
      "distance": 0,
      "targetPath": "EventBus/src/org/greenrobot/eventbus/EventBus.java",
      "diagnostic": {
        "sourcePath": ".../runs/20261118T000203Z-nullaway/workspace/target/EventBus/src/org/greenrobot/eventbus/EventBus.java",
        "line": 46,
        "column": 30,
        "severity": "error",
        "checker": "NullAway",
        "message": "@NonNull static field 'defaultInstance' not initialized\n(see http://t.uber.com/nullaway )",
        "sourceSnippet": "    static volatile EventBus defaultInstance;"
      }
    }
  ],
  "edges": []
}
```

- **Nodes:** one `warning` node per checker finding. Every initial node is a root
  (`"root": true`) with `"distance": 0`. `diagnostic` is the complete parsed
  diagnostic; `targetPath` is its path relative to the target, or null if it
  lies outside the working copy.
- **IDs:** `W0001`, `W0002`, ... (more digits beyond 9999). They are assigned
  after sorting findings by target path, line, column (missing first),
  severity, checker and message, so they don't depend on javac's output order.
  IDs are preserved when the graph is saved and loaded. They identify warning
  *occurrences within this run only*; nothing matches warnings across runs.
- **Edges:** always empty for now. Edge kinds and other node kinds, such as
  annotation actions, are left to later tasks.
- The graph is plain data, with no visualization library; see "Visualizing a
  warning graph". Loading
  (`WarningGraphJson.load`) validates the schema, unique IDs, edge endpoints,
  and that roots have distance 0.

### `report.json` (schemaVersion 1)

| Field | Content |
|---|---|
| `status` | `COMPLETED` or `EXECUTION_FAILURE` |
| `exitCode` | CLI exit code (below) |
| `configuration` | Effective configuration (checker, paths, source roots, classpath, JDK, release, timeout) |
| `isolation` | Original project, working-copy path, mechanism (`git` / `filesystem-walk`), `gitHead`, `gitStatusEntries`, `filesCopied`, skipped entries |
| `commands[]` | Every external command: argument list, working directory, environment overrides, exit code, duration, timeout, start failure, log files |
| `checker` | NullAway version and jar, `error_prone_core` jar, full processor path, annotated packages and their source, probe file, `checkerActive`, javac's reported counts, general messages, unrecognized output lines, output problems, argument file |
| `analysis` | `complete`, `findingsComplete`, `failure` (`kind`, `reasons[]`) or null, `findingCount`, `severityCounts`, `findings[]`, `otherDiagnostics[]` (non-NullAway diagnostics such as lint warnings or compiler errors) |
| `coverage` | Analyzed source roots and file count, Java files outside the roots per top-level directory, limitations |
| `warningGraph` | `created`, `file` (`warning-graph.json`), `nodeCount`, `edgeCount`; or `created: false` with a `reason` |

Each diagnostic has `targetPath` (relative to the original target), `isolatedPath`
(the file in the working copy), `reportedPath` (exactly as javac printed it),
`line`, `column` (from javac's caret, 1-based; may be null), `severity`,
`checker` (the bracketed tag, for example `NullAway`), the full `message`
including continuation lines, and `sourceSnippet`.

No warning identity is implied: diagnostics from different runs are not matched.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Analysis completed. The report lists all findings (zero or more). javac exiting 1 because of NullAway errors is still a completed analysis. |
| 1 | Not used deliberately (only an uncaught JVM failure produces it). |
| 2 | Usage or configuration error; nothing ran. |
| 3 | Execution failure: the analysis did not complete or cannot be trusted. Any findings in the report are incomplete. |
| 4 | Internal GeneralAnnotator error, including failure to write the report. |

Failure kinds: `ISOLATION_FAILED`, `SETUP_FAILED`, `NO_SOURCES`, `START_FAILED`,
`TIMED_OUT`, `CRASHED`, `INVALID_INVOCATION`, `COMPILATION_FAILED` (an error
that is not a NullAway finding), `CHECKER_NOT_ACTIVE` (no probe finding), and
`UNTRUSTWORTHY_OUTPUT` (unrecognized lines, a missing caret, missing or
mismatched javac counts, or an exit code inconsistent with the diagnostics).

## Supported targets and coverage limitations

- Only **NullAway** is supported. Other checker ids are rejected.
- The target's build system is **not** used. Gradle, Maven, Android, and other
  builds are not run. Any target can be analyzed as long as its chosen source
  roots compile with plain `javac` given `GA_TARGET_CLASSPATH`. Missing
  dependencies or generated sources produce `COMPILATION_FAILED`, not a
  partial "clean" result.
- Annotation processors are disabled (`-proc:none`). Code that needs generated
  sources will not compile.
- Without `GA_JAVAC_RELEASE`, sources are compiled at the checker JDK's
  language level and against its JDK API.
- Only code in AnnotatedPackages is checked by NullAway.
- `module-info.java` files under a source root are passed to javac like other
  sources; modular compilation has not been tested.
- Files outside the source roots (tests, other modules) are counted and listed
  in `coverage`, but not analyzed.

## Project layout

```
src/main/java/generalannotator/
  pipeline/     the resumable round-1 pipeline over the existing commands (pipeline.json)
  propagation/  single-pass, warning-guided nullability propagation (analysis and stage)
  cli/          check, visualize, targets, slice, context, prompts, prepare-inference, inference,
                apply-proposal, evaluate-annotations, apply-round and migrate-warning-ids commands; exit codes
  config/       environment configuration and validation
  exec/         CommandRunner (ProcessBuilder-based; a terminal-attached variant for ssh login),
                recorded command logs
  diagnostics/  diagnostic model and javac output parser
  isolation/    isolated working-copy creation
  checker/      Checker interface, outcome/coverage model, checker selection
  checker/nullaway/  NullAway adapter: setup, invocation, classification
  graph/        warning-graph model, initial construction, JSON save/load
  report/       report.json, warnings.txt, JSON writer and strict JSON parser
  visualization/  DOT export and Graphviz rendering (separate from the graph model)
  targets/      Specimin target-member extraction (JDK syntax trees; Specimin is not run)
  slicing/      Slicer interface, Specimin setup and invocation, slice-results.json
  context/      usage-context extraction (javac-attributed snapshot, symbol-based matching)
  prompts/      prompt-unit assembly: artifact join, template, manifest (no LLM calls)
  inference/    inference submissions: settings, task assignment, Slurm script
  inference/remote/  SSH connection sharing, upload, sbatch, squeue/sacct, collection, retry
  edits/        annotation edit model (checker- and parser-independent), applier interface, diff
  edits/javaparser/  JavaParser-based declaration locator and annotation applier
  proposals/    import of collected proposals, application to a snapshot copy (applications/)
  evaluation/   package import (equivalent answers merged), baseline and per-package attempts, combined
                round application (RoundApplication, rounds/), reuse, resume and
                locking, expanded graph (evaluations/)
  evaluation/matching/  WarningMatcher interface, provisional anchored-1 rule, structural anchors
src/main/resources/generalannotator/inference/ga_inference_worker.py   the GPU worker
src/test/python                   worker tests and fakes (./gradlew workerTest)
src/main/resources/generalannotator/checker/nullaway/processor-path.init.gradle
src/test/java                     deterministic unit tests (./gradlew test)
src/integrationTest/java          real-tool tests (./gradlew integrationTest)
src/integrationTest/fixtures      tiny target projects with known findings
docs/project-spec.md, docs/tasks.md, AGENTS.md
```

## Contributing

Read [AGENTS.md](AGENTS.md) before making changes.
