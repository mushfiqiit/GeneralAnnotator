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
password-and-Duo login), submit one Slurm array job, monitor it and collect the
results. They are tested with fakes only: no job has been submitted to Wulver
yet, and the GPU smoke test is still to do. Inference results are proposals;
they are not applied or checker-validated. No other pipeline stage (slice validation, applying proposals, editing, graph
expansion, search, batching) is implemented. See [docs/project-spec.md](docs/project-spec.md) and
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

## Visualizing a warning graph

`visualize` renders an existing `warning-graph.json` with Graphviz. It does
**not** rerun the checker and does not change the JSON. You can pass either
a run directory or the JSON file itself:

```sh
./gradlew run --args="visualize runs/20261118T000930Z-nullaway"
./gradlew run --args="visualize runs/20261118T000930Z-nullaway/warning-graph.json"
open runs/20261118T000930Z-nullaway/warning-graph.svg     # macOS; or open it in any browser
```

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
| `GA_INFERENCE_STARTUP_TIMEOUT_SECONDS` | 1200 | wait for `/health` |
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

**One login, one shared connection.** `inference connect` asks a running OpenSSH
master whether it is alive (`ssh -O check`). If it isn't, it runs this in your
terminal, where ssh asks for the password and Duo itself; GeneralAnnotator
never sees or stores them:

```
ssh -M -N -f -o ControlPath=~/.ga-ssh/%C -o ControlPersist=4h -o ConnectTimeout=30 mc2399@wulver.njit.edu
```

The socket directory (`GA_SSH_CONTROL_DIR`, default `~/.ga-ssh`) is created
with mode 0700. An existing one must be private and yours, or it is refused.
Every later ssh and rsync call uses the same socket with `ControlMaster=no
-o BatchMode=yes`: it can only reuse the master, and it fails instead of
prompting. Host keys are checked as your SSH configuration says, which is never
edited. If the master is gone, a command stops with exit 5 and asks you to
reconnect; all progress is kept. To reuse a connection you own instead, set
`GA_SSH_CONTROL_PATH=config` (the `ControlPath` of your SSH config, for
example for a host alias in `GA_SSH_TARGET`) or a socket path. In those modes
GeneralAnnotator never creates or closes the master. `connect` prints how to
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

**Status.** `status` lists the sbatch calls and the state of every array task
of the latest job that includes it. squeue covers active tasks and sacct the
history; squeue's "invalid job id" just means the job left the queue. A task
that neither reports is `UNKNOWN`, never finished. Scheduler states describe
tasks, not prompts (a COMPLETED task can have failed prompts); `status` shows
the last collection's prompt counts separately. It also writes
`remote/status.json`. It takes no lock, so it works while `run` is monitoring.

**Collection.** `collect` first checks that the remote `submission.json` still
has the local SHA-256. It then rsyncs `results/`, `logs/` and `.ga-sbatch/`
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
3. submits unless a job exists (it refuses if one is UNKNOWN);
4. polls status and collects every `GA_REMOTE_POLL_SECONDS` (default 300,
   minimum 60), until every task has finished.

It exits 0 only when every prompt has a completed result. Ctrl-C, a lost
connection or `GA_REMOTE_MAX_UNKNOWN_POLLS` polls in a row with unknown
scheduler state stop only the local monitoring. Jobs are never cancelled, and
rerunning `run` resumes without resubmitting.

| Variable | Default | Meaning |
|---|---|---|
| `GA_SSH_TARGET` | `mc2399@wulver.njit.edu` | `user@host` or a host alias |
| `GA_SSH_CONTROL_PATH` | unset | unset: managed socket in `GA_SSH_CONTROL_DIR`; `config`: your SSH config's ControlPath; or an existing socket path (reused only) |
| `GA_SSH_CONTROL_DIR` | `~/.ga-ssh` | private socket directory (short: socket paths are limited to about 100 characters) |
| `GA_SSH_CONTROL_PERSIST` | `4h` | idle lifetime of a managed master |
| `GA_SSH_CONNECT_TIMEOUT_SECONDS` | 30 | SSH ConnectTimeout |
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
output parsing and the whole flow against fakes. A manual cross-check ran
`inference run`, `retry` and `status` with real rsync, bash and `sha256sum`
through a fake `ssh` and fake `sbatch`/`squeue`/`sacct` that ran the real worker
with a fake vLLM. Nothing has contacted Wulver yet. Still to confirm there:
- the Duo login with `ssh -M` and that `ControlPersist` keeps the master;
- that `bash -l` finds `sbatch`, `squeue`, `sacct`, `sha256sum` and rsync on
  the login node;
- that sacct reports `WorkDir` (needed by `attach`) and `--comment`;
- the effective account and QoS limits (MaxSubmitJobs and similar) for a
  41-task array.

The GPU smoke test (T13d) is still needed before relying on results.

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
  cli/          check, visualize, targets, slice, context, prompts, prepare-inference and
                inference commands; exit codes
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
