# Codon verification

Start with the user's feature, follow its interaction, then choose the smallest
test that observes the behavior. These pages describe current implementation and
existing tests; they are not a record of passing runs.

## Feature map

| User task | Guide |
| --- | --- |
| Toggle a command-block, function-line or conditional stage breakpoint | [Breakpoints](features/breakpoints.md) |
| Pause, step into/over/out and continue | [Stepping](features/stepping.md) |
| Inspect `execute` stages, context changes and recorded calls | [Execution flow](features/execution-flow.md) |
| Add Watches, browse NBT, pin fields and inspect changed values | [Watches and NBT](features/watches-and-nbt.md) |
| Navigate while paused and retain/restore the camera | [Freecam](features/freecam.md) |
| Toggle or temporarily hide debugger panels and markers | [UI visibility](features/ui-visibility.md) |
| Search loaded functions and inspect line/stage source | [Function source](features/function-source.md) |
| Resize Codon independently of game GUI scale | [UI scale](features/ui-scale.md) |
| Read hover/focus details and action hints | [Tooltips](features/tooltips.md) |

## Prepare and launch

1. Inspect `git status --short`. Preserve unrelated edits and note whether the run
   includes uncommitted work.
2. Check `java -version` and `./gradlew --version`: both must use JDK 25. On macOS,
   if necessary set `JAVA_HOME` to `$(/usr/libexec/java_home -v 25)` before Gradle.
   Dependency versions are in [gradle.properties](../../gradle.properties).
3. First runs need network access for Gradle, Minecraft, Fabric and assets. Use
   `--offline` only when those are cached. A Gradle cache permission/lock failure
   is an environment problem; do not change production code to work around it.
4. For manual verification, run `./gradlew runClient`, use a disposable Creative
   world with cheats/owner permission, and prepare the fixture in the feature page.
   Run Codon on both client and server for multiplayer checks.

Default keys come from
[InputManager](../../src/client/java/works/nuty/codon/client/input/InputManager.java):
`V` opens/closes cursor mode, `F10` toggles the targeted block breakpoint, `F7`
continues, `F8` steps over, `F9` steps into, `Shift+F9` steps out, `G` toggles Keep
Freecam While Running, and tapping `H` toggles the debugger UI. Holding `H` for at least 250 ms hides it
only while pressed, then restores the previous visibility. Remapped keys and
on-screen hints take precedence. On keyboards with media keys, use the appropriate
Fn setting. The Source viewer is read-only.

## Select checks

All commands run from the repository root. Select actual classes from the linked
feature pages; do not run every command below for a small change.

```sh
./gradlew coreTest --tests '*BreakpointConditionEvaluatorTest'
./gradlew clientTest --tests '*ClientBreakpointStateTest'
./gradlew test --tests '*WorldBreakpointPersistenceTest'
./gradlew build
./gradlew runClientGameTest -PclientGameTest=DebuggerBreakpointUiGameTest
```

JUnit `--tests` selects classes/methods within its task. Client GameTests use a
separate runner and do not accept that option. The local `clientGameTest` project
property accepts exact simple or fully qualified class names, comma-separated:

```sh
./gradlew runClientGameTest -PclientGameTest=DebuggerBreakpointUiGameTest,DebuggerBreakpointResultGameTest
```

Selection changes only the generated test manifest under `build/resources/gametest`;
it does not change source files or exclude test compilation. Empty/unknown names
fail. Omitting the property restores all entries from the
[registered test list](../../src/gametest/resources/fabric.mod.json):

```sh
./gradlew runClientGameTest
```

CI partitions that same manifest across eight isolated runners with
`-PclientGameTestShard=1/8` through `8/8`. Entries are assigned by their position
modulo the shard count, preserving their order within each runner. Every registered
class runs once, including newly added classes. Sharding and `clientGameTest` cannot
be combined; malformed, out-of-range or empty shard requests fail. Omitting both
properties restores the full suite. Use separate checkouts for concurrent runs;
the generated manifest and cleared run directory belong to one runner.

The five-minute goal concerns the full CI critical path, including setup,
compilation, client startup and evidence upload. Queue time is reported separately.
Eight runners reduce elapsed native execution but increase total runner time.
Measure the slowest shard and the final status check; passing a focused subset or
an estimated division of a prior run does not establish this goal. Gradle profiles
in the evidence artifacts distinguish task execution from preparation.

Choose a focused run locally. Ask before a broad/repeated local test campaign.
Only one client GameTest process may use this checkout at a time: each run clears
`build/run/clientGameTest`. This is separate from the manual client's `run/`
directory. Copy needed evidence before starting another run or cleaning `build/`.

## Evidence and acceptance

| Check | Evidence | What it establishes |
| --- | --- | --- |
| JUnit | `build/reports/tests/{coreTest,clientTest,test}/index.html` and `build/test-results/` | Assertions in the selected JVM tests |
| Build | Gradle result and `build/libs/` | Compilation, packaging and configured JVM checks; no client GameTest |
| Client GameTest | `build/run/clientGameTest/logs/`, `screenshots/`, and failure `crash-reports/` | The scenarios actually executed by that run |
| Manual client | Saved screenshots/video plus exact reproduction steps | The observed interaction and appearance at the recorded resolution/GUI scale |

Screenshots are emitted by tests that call `takeScreenshot`; not every successful
test creates one. A crash may happen before screenshot capture, so retain the
console log too. Inspect the relevant images instead of treating file existence as
visual approval. Tests using injected snapshots prove client behavior under those
snapshots; use a native-execution scenario to verify actual breakpoint/step logic.
Dedicated-server and physical audio output acceptance require their own observations.

Report the revision/local changes, command, selected tests, exit result, evidence
paths, and any remaining gap. A stopped/timed-out run is not a pass. Reuse existing
coverage first; add a regression only when the bug needs one. After manual checks,
continue any paused execution, close the test client and restore changed settings.
Keep screenshots/logs out of commits.

## CI

[build.yml](../../.github/workflows/build.yml) runs `build` alongside eight
`client-game-test-shard` jobs on pull requests, main pushes and tags. Feature-branch
pushes use only the pull-request event, avoiding duplicate suites; post-merge main
validation and tag builds remain enabled. Together the shards run
the complete registered client suite with JDK 25, Xvfb/Mesa software rendering,
and a PulseAudio null sink for OpenAL channel tests. Each uses the same
`runClientGameTest` task as local development. Shards continue after another shard
fails, retaining independent failure evidence. The final `client-game-test` check
passes only when every shard succeeds, retaining the existing required-check name.

Minecraft 26.3 creates its OpenGL windows through SDL. Under Xvfb the job installs
Mesa EGL and sets [`SDL_VIDEO_FORCE_EGL=1`](https://wiki.libsdl.org/SDL3/SDL_HINT_VIDEO_FORCE_EGL)
so SDL uses EGL instead of GLX. Without this, client startup can fail with
`Couldn't find matching GLX visual` before any test executes; a subsequent Vulkan
fallback error does not establish a Codon test failure.

Artifacts are uploaded even after a failed test step and retained for 14 days:
`unit-test-results` contains JVM reports; `client-game-test-evidence-1` through `-8` contain the
Gradle console log plus client logs, screenshots and crash reports. A setup failure
may produce no files. The client test step has a 15-minute timeout within the
45-minute job limit, leaving time to upload evidence after a stuck client is
stopped. Neither limit suppresses test failures. Existing JAR artifacts remain
available from the build job.

If the client console stops advancing for two minutes, the job also captures JVM
thread dumps under `build/ci/` in the same evidence artifact. Use these stacks to
locate a stalled test, renderer or server handoff before changing test timing.

The test-only `IntegratedServerGameTestMixin` keeps Fabric's client/server phases
advancing while `IntegratedServer.halt()` awaits its server cleanup task. Without
this, the client can block before reaching Fabric's normal disconnect pump while
the server waits at the next test phase. It retains the server-thread cleanup and
its completion/error contract; no production shutdown behavior is changed.

The workflow creates status checks; making both jobs required for merging is a
separate GitHub branch-protection/ruleset setting. A local macOS run does not prove
the Ubuntu CI environment passed. See Fabric's
[automated-testing documentation](https://docs.fabricmc.net/develop/automatic-testing)
for the distinction between build checks and client GameTests and headless display
requirements. The repository uses its existing development run task rather than
adding a separate production-run configuration.
