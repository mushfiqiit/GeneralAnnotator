# GeneralAnnotator

GeneralAnnotator is a Java research tool for resolving pluggable type checker
warnings (starting with NullAway) through proposed type annotations, applied
and re-checked in isolated repository states.

**Status:** bootstrap only. The project builds, and the entry point prints a
status message. None of the pipeline stages are implemented yet. See
[docs/project-spec.md](docs/project-spec.md) for the intended design and
[docs/tasks.md](docs/tasks.md) for the planned task order.

## Requirements

- **JDK 21** installed locally. Gradle finds it automatically. The default
  `java` on your `PATH` can be an older version (verified with Java 11) that
  only starts the wrapper. The wrapper then runs the Gradle daemon and compiles
  on JDK 21.
- No system Gradle installation. The Gradle Wrapper (Gradle 9.8.2) downloads
  the distribution on first use.

## Build and verify

```sh
./gradlew build        # compile, run unit tests, assemble
./gradlew test         # run tests only
./gradlew run          # run the entry point
./gradlew clean build  # full rebuild from scratch
```

Expected results:

- `./gradlew build` ends with `BUILD SUCCESSFUL`, and
  `MainTest > mainReportsThatNoPipelineIsImplemented() PASSED`.
- `./gradlew run` prints
  `GeneralAnnotator: no pipeline stages are implemented yet. See docs/tasks.md.`

Test reports are written to `build/reports/tests/test/index.html`.

## Project layout

```
build.gradle.kts, settings.gradle.kts   Gradle build (Kotlin DSL)
gradlew, gradlew.bat, gradle/           Gradle Wrapper and daemon JVM selection
src/main/java                           Application sources
src/test/java                           JUnit Jupiter tests
docs/project-spec.md                    Pipeline, scope, terminology, open decisions
docs/tasks.md                           Ordered implementation tasks
AGENTS.md                               Development instructions
```

## Contributing

Read [AGENTS.md](AGENTS.md) before making changes.
