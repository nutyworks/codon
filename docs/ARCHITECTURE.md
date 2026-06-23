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
  key bindings   │             PauseSnapshot, …)                   │       (server.managedBlock)
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
- `mixin/` — thin mixins. `BuildContextsMixin` translates a command stage into a core
  `CommandStageEvent` and calls the engine; `CommandsMixin`/`ServerGamePacketListenerImplMixin`
  let players keep issuing commands while paused. `CommandFunction`/`FunctionBuilder` mixins (with
  the `action/` + `entry/` helpers) attach function-line numbers during parsing.
- `adapter/` — `McExecutionController` (parks via `managedBlock`), `SourceMapper`
  (Minecraft types ↔ core value types).
- `command/BastionCommand` — the `/bastion` tree, delegating to the engine.
- `network/` — S2C sync payloads + `NetworkDebuggerEventSink`; this transport is what makes the
  in-game UI work on dedicated servers.
- `BastionMod` — composition root: constructs the core and wires adapters (constructor injection).
  Exposes the wired engine through a single static accessor, the seam mixins reach through.

### Client adapters — `works.nuty.bastion.client.*` (source set `client`)
- `state/ClientDebuggerState` — client mirror updated only by sync packets; everything reads it.
- `network/ClientNetworking` — receivers that update the mirror.
- `ui/` — windowing (`Window`, `WindowManager`, `CallStackWindow`, `BastionScreen`) +
  `ClientFormatting` (core types → chat components).
- `render/` — `DebugHudElement`, `DebugLevelRenderer` (in-world gizmos), `DistinctColorGenerator`.
- `input/InputManager` — keybinds; control actions go to the server as `/bastion` commands.
- `BastionClientMod` — client composition root.

## Why the indirection
The pause engine never touches Minecraft, so its logic is unit-tested directly and could later be
driven by a different front end. The `DebuggerEventSink` port is the extension point for a future
Debug Adapter Protocol bridge (external editors); roadmap features — conditional breakpoints,
watch/expression evaluation, state inspection, execution trace, command/NBT editors, free-cursor
mode — slot in behind these same ports.
