# Development Instructions (AGENTS.md)

These instructions apply to anyone, human or AI agent, who changes this
repository.

## Before coding

1. Read `docs/project-spec.md`.
2. Read the task you are implementing in `docs/tasks.md`, including its
   acceptance criteria.
3. Inspect the existing code before changing it. Build on what is there; do not
   duplicate or replace it without a reason.

## Scope

- Implement only the requested task. Do not add classes, interfaces, or
  dependencies for future tasks.
- Do not claim that unimplemented behavior is supported, whether in code,
  docs, CLI output, or reports.
- If a task needs a decision that project-spec §6 lists as unresolved, do not
  quietly pick an answer. Either stop and ask, or make a clearly labeled
  provisional choice, keep it in one isolated place, and record it in §6.
- Record any new unresolved research decision you discover in project-spec §6.

## Design rules

- Keep checker, slicer, and LLM/proposer integrations behind interfaces once
  those integrations exist, so pipeline logic can be tested with fakes.
- Preserve repository-state isolation. Never modify the original target
  repository in place, and never let one attempt's edits leak into another
  state.
- Distinguish checker findings from execution or build failures. A crash,
  timeout, missing tool, or unrelated compilation error is not a checker warning,
  and a lack of output is not "zero warnings".
- Use deterministic fixtures, such as captured tool output and small fixture
  projects, for pipeline logic. Keep tests that call real external tools apart
  from unit tests.

## Build and verification

The Gradle Wrapper runs its daemon on JDK 21, selected by
`gradle/gradle-daemon-jvm.properties`. A local JDK 21 must be installed, but it
does not need to be the default `java`.

```sh
./gradlew build        # compile, run tests, assemble
./gradlew test         # tests only
./gradlew run          # run the CLI entry point
./gradlew clean build  # full rebuild
```

Run verification that fits each change. At minimum, run `./gradlew build`
before reporting a code change as done.

## Reporting

When you finish a task, report:

- The files you changed or created.
- The verification commands you ran and their results, including failures.
- Limitations, shortcuts, and provisional choices.
- Unresolved decisions you hit or recorded.
