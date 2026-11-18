# GeneralAnnotator

GeneralAnnotator is a Java research tool for resolving pluggable type checker
warnings (starting with NullAway) through proposed type annotations, applied
and re-checked in isolated repository states.

**Status:** pipeline stage 1 (*Check*) is implemented for NullAway. The `check`
command copies a target project into an isolated working copy, runs NullAway
from a local checkout on it, and writes a report. After a completed analysis it
also writes the **initial warning graph**: one root node per finding, no edges.
For each warning it also identifies the **Specimin target member** (the
enclosing method, constructor or field), but it does not run Specimin. The
`visualize` command renders a saved warning graph with Graphviz. No other
pipeline stage (slicing, context extraction, proposals, editing, graph
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
                             # real-tool tests: local NullAway on tiny fixtures and real Graphviz
                             # rendering (not part of build/check; needs NullAway and dot)
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
  cli/          check command, exit codes
  config/       environment configuration and validation
  exec/         CommandRunner (ProcessBuilder-based), recorded command logs
  diagnostics/  diagnostic model and javac output parser
  isolation/    isolated working-copy creation
  checker/      Checker interface, outcome/coverage model, checker selection
  checker/nullaway/  NullAway adapter: setup, invocation, classification
  graph/        warning-graph model, initial construction, JSON save/load
  report/       report.json, warnings.txt, JSON writer and strict JSON parser
  visualization/  DOT export and Graphviz rendering (separate from the graph model)
  targets/      Specimin target-member extraction (JDK syntax trees; Specimin is not run)
src/main/resources/generalannotator/checker/nullaway/processor-path.init.gradle
src/test/java                     deterministic unit tests (./gradlew test)
src/integrationTest/java          real-tool tests (./gradlew integrationTest)
src/integrationTest/fixtures      tiny target projects with known findings
docs/project-spec.md, docs/tasks.md, AGENTS.md
```

## Contributing

Read [AGENTS.md](AGENTS.md) before making changes.
