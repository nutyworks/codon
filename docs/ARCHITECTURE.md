# Bastion architecture

Bastion is a step-debugger for Minecraft commands and datapack functions, structured as a
**hexagonal (ports & adapters)** application so the debugging logic stays independent of
Minecraft and runs identically in singleplayer and on dedicated servers.

## Layers

```
                 ┌───────────────────────────────────────────────┐
   driving       │                    core                        │      driven
   adapters ───▶ │   (pure Java — no Minecraft, no Fabric)         │ ───▶ adapters
                 │                                                │
  mixins         │   model/    value types (SourceLocation,       │   ExecutionController
  /bastion cmd   │             Breakpoint locations, CallFrame,    │     → McExecutionController
  key bindings   │             PauseSnapshot, …)                   │       (debugger task queue)
  sync packets   │   service/  DebuggerEngine, BreakpointRegistry, │   DebuggerEventSink
                 │             StepController, CallStack           │     → NetworkDebuggerEventSink
                 │   port/     ExecutionController,                │       (S2C sync packets)
                 │             DebuggerEventSink                    │
                 └───────────────────────────────────────────────┘
```

### `core` — `works.nuty.bastion.core` (source set `core`)
Pure Java with **no Minecraft/Fabric/Brigadier dependency** (only `jspecify` annotations). The
build enforces this: the `core` source set's classpath has no Minecraft, so a stray import fails
to compile. Bundled into the mod jar by the `jar` task.

- `model/` — immutable value types (`SourceLocation`, `BlockLocation`, `FunctionLocation`,
  `CommandSnippet`, `CallFrame`, `PauseSource`, `PauseSnapshot`, `StepMode`).
- `service/` — use cases: `DebuggerEngine` (the pause/resume/step state machine), plus
  `BreakpointRegistry`, `StepController`, `CallStack`. No blocking, no I/O.
- `port/` — interfaces the core needs from the outside: `ExecutionController` (suspend the
  executing thread) and `DebuggerEventSink` (publish state).

Tested in isolation by the `coreTest` source set: `./gradlew coreTest` runs the full domain
suite in ~1s without Minecraft.

### Minecraft adapters — `works.nuty.bastion.*` (source set `main`)
- `mixin/` — `BuildContextsMixin` translates a command stage into a core `CommandStageEvent`.
  `ExecutionContextMixin` passes invocation metadata to deferred continuations and reports queue
  completion. `CommandsMixin`, `ServerGamePacketListenerMixin`, and `DedicatedServerMixin` let
  validated debugger controls and `stop` reach the paused server through chat, console, or RCON.
  `CommandFunction`/`FunctionBuilder` mixins (with the `action/` + `entry/` helpers) attach
  function-line numbers during parsing. Macro substitutions map to the individual line's variable
  order, including repeated variables.
- `adapter/` — `McExecutionController` (parks on a dedicated `DebuggerTaskQueue`), `SourceMapper`
  (Minecraft types ↔ core value types).
- `command/BastionCommand` — the `/bastion` tree, delegating to the engine.
- `network/` — S2C sync payloads + `NetworkDebuggerEventSink`; this transport is what makes the
  in-game UI work on dedicated servers.
- `BastionMod` — composition root: constructs the core and wires adapters (constructor injection).
  Exposes the wired engine through a single static accessor, the seam mixins reach through.

Each command invocation receives a unique `CommandTrace` id and original source location.
Continuations inherit that trace, while later invocations of a cached function action receive
new ids. A line breakpoint is evaluated once per invocation; explicit stepping still observes
the modifier stages. The existing `BuildContexts.execute` observation points are retained.

Execution scopes nest: `CommandBlockMixin` wraps the initial block and its connected chain, while
`ExecutionContextMixin` wraps each command queue. Only the outermost completion clears pending
steps, chain bookkeeping, and stale stack frames. This lets step-into/over continue into the next
block without leaking into an unrelated chain. The tick boundary is a fallback when no scope is
active. Root-level step-out resumes because there is no caller to return to.
The call stack and stepping still use observed depth, not exact function/frame lifecycle events.

While paused, the server services only debugger mailbox work and bounded connection maintenance
(keepalive, flush, and disconnection cleanup). It does not drain the general server task/packet
queues or call `connection.tick()`. Ordinary commands wait for resume. Intentional pause time is
excluded from watchdog accounting through the tick-deadline reset. The suspension port returns
`RESUMED` or `CANCELLED`; cancellation, failures, and server shutdown clear stale pause state
while preserving breakpoint definitions.

### Client adapters — `works.nuty.bastion.client.*` (source set `client`)
- `state/ClientDebuggerState` — authoritative pause/breakpoint mirror, local source/frame selection,
  gizmo mode, and a pending-control latch cleared by server packets or a retry timeout.
- `network/ClientNetworking` — receivers that update the mirror and clear it on disconnect.
- `ui/DebuggerOverlay` — shared transparent HUD and cursor-mode presentation: control bar, source
  inspector and call stack on the left (clear of the scoreboard), and scrollable command text.
  `BastionScreen` registers its native widgets
  for mouse, keyboard, and narration. `ClientFormatting` renders core types as chat components.
  The older `Window` classes are no longer used by the client composition root.
- `ui/layout/` — Minecraft-free responsive panel and screen-space label placement. Overlapping
  labels can be grouped; crowded ungrouped labels move into free slots or one aggregate. Clicking
  a group filters the inspector without changing the underlying source positions.
- `render/` — `DebugHudElement` and `DebugLevelRenderer`: rings for entity-bearing sources,
  squares for position-only sources, one-block facing arrows, selected-source emphasis, red
  breakpoint outlines, and amber active stops. Sources in other dimensions remain in the inspector
  but are not drawn in the current world. Source anchors are execution reference points, not
  necessarily the attached entity's position.
- `input/InputManager` — keybinds; control actions go to the server as `/bastion` commands.
- `camera/DebuggerFreecam` — a client-only camera entity while paused and between debugger steps.
  Client mixins suspend local player and ridden-vehicle simulation, route mouse look into the
  camera, and block gameplay inputs. The camera never enters the level's entity list or supplies
  player movement packets. Resume/disconnect restores the original viewpoint and perspective.
  A separate step sync retains the camera's position and orientation across step acknowledgements;
  the server sends a terminal resume if the execution ends without another pause. Client pause
  snapshots are still cleared during advancement so stale command state cannot be inspected.
  The original player's body remains visible at its paused position through vanilla entity
  rendering; player/vehicle render interpolation is fixed so the pose stays still.
  First-person arms and held items are hidden while freecam is active and return through vanilla
  rendering on resume; the paused body's third-person arms and equipment remain visible.
- `BastionClientMod` — client composition root.

`B` opens/closes cursor mode. `F7` continues, `F8` steps over, `F9` steps into, `Shift+F9` steps
out, and `F10` toggles the targeted block breakpoint; UI hints follow remapped keys. Gizmo modes
are Grouped (default), Labels, and Focus. Source numbers identify entries in the current snapshot;
selection survives a step only when an exact source or unambiguous entity/dimension match exists.
Transition trails are not inferred: they need execution history beyond the current snapshot.

Pausing automatically enables freecam: movement keys follow the horizontal facing direction, jump/sneak move
up/down, and sprint accelerates. `B` switches between freecam and the cursor UI; open screens stop
camera motion. `F10` targets the block under the camera. Gameplay actions and inventory input are
disabled while paused, and already-open containers close. Stepping retains the freecam viewpoint;
resuming normal execution or finishing the execution restores the player camera. Camera navigation and
gameplay clicks (including accessibility toggle states) are cleared on exit so they do not turn into player actions. World/player
replacement abandons the old camera until a fresh pause snapshot arrives. The existing server
pause loop still processes only debugger controls and connection maintenance.

The synced pause state also drives vanilla client simulation and render interpolation pause:
entities, block entities, world/weather time, ambient particles, particle motion/lifetime,
HUD/chat/title timers, and delayed/ticking sounds stop advancing. Texture animation and music
scheduling also stop. Freecam uses the live camera interpolation fraction while the world keeps
its frozen fraction; input, the debugger UI, rendering, loading, and network maintenance stay live.
Wall-clock effects (glint, border texture, boss-bar/heart interpolation, subtitles, and toasts) use a
presentation clock that excludes debugger pause duration, preserving their remaining lifetime.
Existing audio channels, including music, pause at their playback position; channels whose buffers
finish loading during pause also pause immediately. Closing a screen cannot unpause them. New UI
click sounds are discarded during pause so they do not consume channels or burst on resume.
The now-playing toast's music-note color animation uses the same paused presentation clock.
Resume/disconnect restores audio while preserving any ordinary singleplayer menu pause.

Pause payloads use `bastion:pause_sync_v2` because each `PauseSource` now carries its dimension.
Client and server must both use the updated mod for pause visualization.

## Persistence

Client preferences are shared across worlds and servers in the Minecraft instance's
`config/bastion.json`: gizmo mode, inspector visibility, and inspector tab. Changes save immediately.
An unset (`null`) inspector visibility retains the responsive automatic default. Key bindings
continue to use Minecraft's own options file. Pause snapshots, source/frame selection, scroll
positions, and freecam state remain session-local.

Both block and function breakpoints live on the server in each world save's
`data/bastion-breakpoints.json`. Block entries retain their dimension and coordinates; function
entries retain their identifier and one-based line number. `WorldBreakpointPersistence` wraps the
network event sink, saving both sets after every toggle or clear while forwarding pause, step,
resume, and breakpoint notifications unchanged. It restores the registry at `SERVER_STARTING`,
before the first tick or player join, and clears it at server shutdown so opening a different
world cannot inherit the previous world's breakpoints. This also applies to dedicated servers;
clients receive the restored block set through the existing join sync.

The JSON files are versioned and written to a sibling temporary file before replacement. If
loading fails or the version is unsupported, the original file stays untouched and persistence
for that file is disabled until the next session; the failure is logged. Failed breakpoint writes
retain the in-memory state and are retried on the next change, world save, or shutdown.

## Why the indirection
The pause engine never touches Minecraft, so its logic is unit-tested directly and could later be
driven by a different front end. The `DebuggerEventSink` port is the extension point for a future
Debug Adapter Protocol bridge (external editors); roadmap features — conditional breakpoints,
watch/expression evaluation, deeper state inspection, execution trace, and command/NBT editors —
slot in behind these same ports.

## Verification

Use Java 25 and `./gradlew build` for compilation and the core/presentation regression suites.
They also run separately with `./gradlew coreTest clientTest`.
`./gradlew test` checks file persistence with temporary directories, including restart/restore,
world isolation, toggle/clear, invalid-file preservation, and failed-write recovery. These tests
exercise the adapters and engine without launching Minecraft; Fabric lifecycle wiring still
requires an in-game check to establish runtime behavior.

`./gradlew runClientGameTest` is an opt-in real-client presentation check using Fabric's
[client game test framework](https://docs.fabricmc.net/develop/automatic-testing). It creates a
temporary singleplayer world under `build/run/clientGameTest`, exercises the actual widgets and
renderer with an explicit pause fixture, checks the pause codec round trip, and saves screenshots
there. The freecam fixture separately exercises the production client mirror and mixins, checking
player immobility, horizontal/vertical camera input, blocked gameplay packets and controls,
accessibility toggle cleanup, mounted vehicle immobility, and restoration. Additional client tests
check chat opacity/lifetime, particles, world/entity clocks, texture/glint animation, and OpenAL
channel pause/resume with delayed sounds and music scheduling. The freecam step regression sends
pause/step/resume packets from the integrated server and checks camera identity and rendered pose
across both back-to-back and delayed step transitions, plus terminal restoration. Passing these
checks does not prove server-driven breakpoint/step synchronization or long-running dedicated
server pause behavior; those require separate end-to-end Minecraft runtime checks.
