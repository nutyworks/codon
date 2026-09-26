# Codon agent guide

Maintain simple code and tests. Do not create or run excessive tests; ask the user
before expanding into a broad or repeated test campaign. Only run necessary tests.

## Start here

- Inspect `git status --short` and the relevant diff. Preserve existing work; stage
  only files belonging to the requested change.
- Use [the feature map](docs/verification/README.md) to find the user path, code and
  smallest relevant checks. Update the matching entry when that path changes.
- Use [ARCHITECTURE.md](docs/ARCHITECTURE.md) for design context; verify older prose
  against current code. Versions are in `gradle.properties`; use JDK 25.

## Boundaries

- `src/core` is Minecraft-free Java. Do not add Minecraft/Fabric dependencies to its
  compile or test classpath; Gradle enforces this boundary.
- The server owns execution, breakpoints and recorded flow. Client state presents
  acknowledged server state; pending requests must not enable stale actions.
- Observe real command execution. Never rerun commands to infer flow, or infer
  context lineage from UUID/position coincidence. Unmeasured is distinct from zero.
- Client UI and Mixins belong in `src/client`; server adapters and network/persistence
  code belong in `src/main`. Keep English/Korean translation keys in sync.

## Verification

Choose the smallest checks that establish the changed behavior; this is not a list
to run in full for every edit. Run commands from the repository root.

| Change | Starting check |
| --- | --- |
| Core logic | `./gradlew coreTest --tests '*RelevantTest'` |
| Client state/layout | `./gradlew clientTest --tests '*RelevantTest'` |
| Codecs, persistence or other adapters | `./gradlew test --tests '*RelevantTest'` |
| Integration/packaging | `./gradlew build` |
| In-game behavior, UI or Mixins | Relevant unit check, then `./gradlew runClientGameTest -PclientGameTest=RelevantGameTest` |
| Docs/CI only | Check links/configuration; validate changed Gradle task wiring when applicable |

`RelevantTest` and `RelevantGameTest` are placeholders: take actual names from the
feature map. `--tests` is for JUnit tasks, not `runClientGameTest`. The unfiltered
client task runs every registered test; CI uses that full task.

For user-visible fixes, reproduce the relevant interaction, inspect the produced
screenshot/log evidence, and report what it establishes. Compilation and unit
tests do not prove in-game rendering. A synthetic pause fixture does not prove a
server-driven breakpoint or dedicated-server behavior. If runtime verification is
unavailable, describe the remaining gap instead of claiming the behavior passed.

Do not weaken assertions, skip failing tests or alter unrelated work to get a
green result. Stop after relevant checks pass unless new evidence justifies more.
Record commands, results, evidence paths and any unverified acceptance criteria.
