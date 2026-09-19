# Codon architecture

Codon is a step-debugger for Minecraft commands and datapack functions, structured as a
**hexagonal (ports & adapters)** application so the debugging logic stays independent of
Minecraft and runs identically in singleplayer and on dedicated servers.

## Layers

```
                 ┌───────────────────────────────────────────────┐
   driving       │                    core                        │      driven
   adapters ───▶ │   (pure Java — no Minecraft, no Fabric)         │ ───▶ adapters
                 │                                                │
  mixins         │   model/    value types (SourceLocation,       │   ExecutionController
  /codon cmd   │             Breakpoint locations, CallFrame,    │     → McExecutionController
  key bindings   │             PauseSnapshot, …)                   │       (debugger task queue)
  sync packets   │   service/  DebuggerEngine, BreakpointRegistry, │   DebuggerEventSink
                 │             StepController, CallStack           │     → NetworkDebuggerEventSink
                 │   port/     ExecutionController,                │       (S2C sync packets)
                 │             DebuggerEventSink                    │
                 └───────────────────────────────────────────────┘
```

### `core` — `works.nuty.codon.core` (source set `core`)
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

### Minecraft adapters — `works.nuty.codon.*` (source set `main`)
- `mixin/` — `BuildContextsMixin` translates a command stage into a core `CommandStageEvent`.
  `ExecutionContextMixin` passes invocation metadata to deferred continuations and reports queue
  completion. `CommandsMixin`, `ServerGamePacketListenerMixin`, and `DedicatedServerMixin` let
  validated debugger controls and `stop` reach the paused server through chat, console, or RCON.
  `CommandFunction`/`FunctionBuilder` mixins (with the `action/` + `entry/` helpers) attach
  function-line numbers during parsing. Macro substitutions map to the individual line's variable
  order, including repeated variables.
- `adapter/` — `McExecutionController` (parks on a dedicated `DebuggerTaskQueue`), `SourceMapper`
  (Minecraft types ↔ core value types).
- `command/CodonCommand` — the `/codon` tree, delegating to the engine.
- `network/` — S2C sync payloads + `NetworkDebuggerEventSink`; this transport is what makes the
  in-game UI work on dedicated servers.
- `CodonMod` — composition root: constructs the core and wires adapters (constructor injection).
  Exposes the wired engine through a single static accessor, the seam mixins reach through.

Each command invocation receives a unique `CommandTrace` id and original source location.
Continuations inherit that trace, while later invocations of a cached function action receive
new ids. A line breakpoint is evaluated once per invocation; explicit stepping still observes
the modifier stages. The existing `BuildContexts.execute` observation points are retained.

Execution scopes nest: `CommandBlockMixin` wraps the initial block and its connected chain, while
`ExecutionContextMixin` wraps each command queue. A pending step at the outermost normal return
creates one `EXECUTION_COMPLETE` pause after the last command, with a fresh pause ID and the last
stage's sources evaluated at completion. Watch reads therefore include the final command's changes.
Continue or any step at this stop releases it without executing that command again or arming a new
step. Root-level step-out also lands at this final inspection stop. Inner queue completions do not
stop or clear stepping, so connected command blocks remain one scope. Exceptional unwinding in any
nested scope cancels the pending step without another pause; vanilla-handled command errors and
quota exhaustion still count as normal Java returns. The tick boundary remains a cleanup fallback
when no scope is active. Normal Continue runs without the additional completion stop.
The call stack and stepping still use observed depth, not exact function/frame lifecycle events.
Each observed frame also retains the trace's invocation ID and the exact recorded stage index.
These identifiers travel in the pause payload, including invocation zero; `-1` means unavailable.
The stage index is captured at observation time, including for continuations, and is unavailable
when the recorder reaches its stage limit. Matching command text, depth, or entity identity never
establishes a frame-to-flow relationship. Each retained stage also carries a shared observation
order from its actual entry. Conditional functions can interleave their commands between two
stages of the same parent invocation; trace creation order is not execution order. History
navigation follows consecutive visits in observation order, including returning to a previously
visited invocation. Unknown observation order disables chronological navigation instead of
guessing from trace storage order. History retains recently observed invocations, so a resumed
parent returns to the bounded history after a long child function has evicted its earlier visit.
Each stage preserves its observed top-first call stack, bounded to 32 frames and marked truncated
when deeper. These immutable caller records survive returns and parent-trace eviction. Historical
selection displays that stack, while selecting a caller keeps the originating stack available.
Missing caller flow data still permits viewing its captured command, without borrowing live sources.
Only the exact authoritative invocation/stage receives the pause icon; Current restores the live stack.
`return run` records the actual source list forwarded at continuation enqueue. Conditional
functions associate each isolated result callback with the input occurrence which scheduled it;
the recorder observes only the outputs accepted by vanilla's callback, without re-evaluating the
condition or matching sources by entity identity. The parent stage stays pending during the child
function and closes when its continuation begins, reusing its recorded output IDs as continuation
input IDs. Empty function sets and rejected conditions retain measured zero output. A deferred
stage still open at execution completion reports that its continuation was not observed. Each
execution queue retains only unresolved continuations independently of display-history eviction.
Return-run stages also await the actual continuation start after recording their forwarded sources.
Queue completion reports command-quota exhaustion, queue overflow, thrown errors, or otherwise an
unobserved continuation, and republishes an evicted parent with that reason.

Incomplete-flow warnings carry a specific reason, originating stage and command range, optional
limit, and bounded detail text. A clause icon reports only that clause's missing data; the footer
summarizes the entire invocation and its tooltip lists all recorded causes. Context, stage, edge,
and historical-stack caps remain 128, 24, 256, and 32. Unsupported custom modifiers, early command
errors, Minecraft fork-limit exits, and inconsistent modifier observations have distinct reasons.
Reasons are retained once per stage and kind, and warning details are limited to 256 characters.
Warning payloads reuse the invocation's existing command text while preserving the originating
clause range, including the first stage omitted by the recording cap.
This format uses `pause_sync_v9` and `execution_flow_sync_v4`, so client and
server must use matching versions.

While paused, the server services only debugger mailbox work and bounded connection maintenance
(keepalive, flush, and disconnection cleanup). It does not drain the general server task/packet
queues or call `connection.tick()`. Ordinary commands wait for resume. Intentional pause time is
excluded from watchdog accounting through the tick-deadline reset. The suspension port returns
`RESUMED` or `CANCELLED`; cancellation, failures, and server shutdown clear stale pause state
while preserving breakpoint definitions.

At each stop, `PausedWorldState` publishes already-applied command changes before the server
parks: dirty blocks/block-entity update packets, tracked entity poses/metadata/attributes,
equipment (including cleared slots), passengers/leashes/motion, and player inventory, health,
experience, advancement and post-effect updates. Vanilla command-generated packets (for example,
scoreboards, teams, clocks, boss bars and effects) are flushed at the same boundary. Player tracking
is refreshed and one batch of already-ready destination chunks is sent before entity pairing.
Completed lighting notifications are also published while parked, without draining the ordinary
server/chunk task queues. Client pose packets snap during a pause instead of waiting for frozen
interpolation ticks. Entity tracker counters, physics, AI, effect durations, weather transitions,
and scheduled block/fluid work do not advance. Inventory snapshots bypass slot listeners so
advancement reward functions are not triggered by synchronization itself. This synchronizes vanilla
client-visible state; server-only NBT remains available through Watches/NBT. New chunk generation
and changes that require subsequent simulation ticks still wait for normal execution.

### Client adapters — `works.nuty.codon.client.*` (source set `client`)
- `state/ClientDebuggerState` — authoritative pause/breakpoint mirror, local source/frame selection,
  gizmo mode, and a pending-control latch cleared by server packets or a retry timeout.
- `network/ClientNetworking` — receivers that update the mirror and clear it on disconnect.
- `ui/DebuggerOverlay` — shared transparent HUD and cursor-mode presentation: control bar and source
  inspector on the left (clear of the scoreboard). `CommandPanel` combines a single horizontally
  scrollable call path (trackpad or mouse wheel, with clipped hit boxes and selected-frame visibility) and recorded command
  clauses with context counts below the world. Navigation, Current, Watch, and panel expansion occupy
  fixed slots in the bottom action row; Current remains visible but disabled at the live stop.
  The panel expands upward while its actions remain at the same screen coordinates. The call path
  stays above the clauses without a duplicate location/invocation caption. Frame and
  clause navigation share the explicit invocation/stage selection; the actual stop remains marked
  separately, and Current returns to it. Long commands wrap and scroll, with an expanded-height mode.
  Selecting an already visible clause preserves the scroll position; automatic scrolling only reveals
  an off-screen selection. The bottom row adds terminal run/success counts or recording warnings,
  without repeating each nonterminal clause's context counts.
  Unobserved command suffixes stay visible without invented counts. Repeated or nonordered ranges
  preserve the original command and show each recorded stage separately inside the same panel.
  Historical invocations and caller frames cannot supply live Watch/NBT executors. For the current
  invocation, live source mapping follows the paused stage's occurrence IDs and source ordering.
  `CodonScreen` registers its native widgets
  for mouse, keyboard, and narration. `ClientFormatting` renders core types as chat components.
  `DebuggerNavigation` keeps a logical focus order separate from rendering order: toolbar, source
  list, source details, NBT, world controls, call path, command clauses, and bottom actions. Tab and
  Shift+Tab switch directly between containers and remember each container's last focused item.
  Arrows traverse items, including off-screen rows, but never leave their container. Revealing a hidden control restores focus by its stable
  ID after rendering. Mouse scrolling clears focus from hidden controls without arming a replacement;
  returning to the container restores the remembered logical position. Watch and Help use the same
  container boundary rule; Watch fields retain caret movement, with unshifted arrows at a text edge
  navigating to the adjacent editor control.
  The older `Window` classes are no longer used by the client composition root.
- `ui/layout/` — Minecraft-free responsive panel and screen-space label placement. Overlapping
  labels can be grouped; crowded ungrouped labels move into free slots or one aggregate. Clicking
  a group filters the inspector without changing the underlying source positions.
- `render/` — `DebugHudElement` and `DebugLevelRenderer`: rings for entity-bearing sources,
  squares for position-only sources, one-block facing arrows, selected-source emphasis, red
  breakpoint outlines, and amber active stops. Sources in other dimensions remain in the inspector
  but are not drawn in the current world. Source anchors are execution reference points, not
  necessarily the attached entity's position.
- `input/InputManager` — keybinds; control actions go to the server as `/codon` commands.
- `camera/DebuggerFreecam` — a client-only camera entity while paused and while advancing the inspected execution.
  Client mixins suspend local player and ridden-vehicle simulation, route mouse look into the
  camera, and block gameplay inputs. The camera never enters the level's entity list or supplies
  player movement packets. Execution end/disconnect restores the original viewpoint and perspective.
  Separate step and continue syncs retain the camera's position and orientation across acknowledgements;
  the server offers a final inspection pause when a step reaches the end of execution. Client pause
  snapshots are still cleared during advancement so stale command state cannot be inspected.
  The original player's body remains visible at its paused position through vanilla entity
  rendering; player/vehicle render interpolation is fixed so the pose stays still.
  First-person arms and held items are hidden while freecam is active and return through vanilla
  rendering at execution end; the paused body's third-person arms and equipment remain visible.
- `CodonClientMod` — client composition root.

`B` opens/closes cursor mode. `F7` continues, `F8` steps over, `F9` steps into, `Shift+F9` steps
out, and `F10` toggles the targeted block breakpoint; UI hints follow remapped keys. Gizmo modes
are Grouped (default), Labels, and Focus. Source numbers identify entries in the current snapshot;
selection survives a step only when an exact source or unambiguous entity/dimension match exists.
Transition trails are not inferred: they need execution history beyond the current snapshot.

Pausing automatically enables freecam: movement keys follow the horizontal facing direction, jump/sneak move
up/down, and sprint accelerates. `B` switches between freecam and the cursor UI; open screens stop
camera motion. `F10` targets the block under the camera. Gameplay actions and inventory input are
disabled while freecam is active, and already-open containers close. Step and Continue retain the freecam viewpoint
through later breakpoints in the current execution. Finishing the execution or leaving its final inspection stop
restores the player camera. Camera navigation and
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

### Watches

While paused, open **B → Watch** in the command panel. Add scoreboard objectives,
entity NBT paths, or storage ID/path pairs; remove them in the same editor. Unpinned entity queries follow
the selected source in the inspector, not the selected caller frame. Examples: score objective
`points`, entity path `Health` or `Pos[0]`, storage `demo:state` with path `counter`. Storage queries
are independent of source selection. Definitions are saved per world and player, survive Continue,
and are restored on rejoin; disconnect clears only the current client session. There is no watch-count limit. Both the HUD and editor scroll through the full list; no entries are
replaced by a `+N` summary. Both views align values to the right and connect the left-hand entity/field label
with dot leaders. Long labels and values are clipped independently; full values remain available in tooltips.

Every continuous step also captures automatic changes, whether or not their fields were added to Watch.
The server compares NBT leaves and all score objectives for current and previously observed execution
entities, plus command storage, against the preceding stop. The first observation establishes a baseline.
Outgoing executors remain tracked even at an executor-free next stop. Saved watches appear first (UUID-bound
pins ahead of source-following watches), followed by every unregistered change; both views scroll the entire
list. Automatic rows last only for that pause, survive source selection, and have a **Pin** action to save the
actual changed entity/path. Equivalent saved fields suppress duplicate automatic rows. Continue clears the
visible rows but preserves the comparison for a later breakpoint in the same execution. Execution end,
disconnect, and world changes clear the automatic comparison session. These read-only captures run on the
server thread and are sent only to owner-authorized clients in 32-row `watch_changes_v1` pages correlated by
pause ID and offset. Large values retain exact server-side comparisons and display the existing `too large`
status; paths beyond the watch format are represented by the nearest valid parent (or the root).

The pin icon beside a score/entity-NBT row binds it to the displayed executor's UUID (the selected
executor when no value has arrived). An outgoing executor's completed value therefore pins that entity.
Its value then remains independent of inspector selection and is refreshed at each pause. Pinning
the same expression to different executors creates distinct rows, with one unpinned row also allowed.
The pin is highlighted when active; clicking it again restores following the selected executor.
Binding changes start a fresh comparison for that row and invalidate its old in-flight replies.
Duplicate bindings are rejected without removing either row. Pinning requires a displayed or selected entity;
unpinning also works while running. Storage has no executor or pin control. Entity rows show the displayed
executor first: `Pig #abcd1234 · Health ...... 20.0f`. Tooltips retain the full UUID; an unloaded or removed target
reports `no target`. Unknown names use the UUID alone until the server or a pause source supplies a name.

The Watches heading always includes a **+** button to open the definition editor, even when the
list is empty. The source inspector always shows NBT for its selected live entity source.
Selecting a non-entity or historical source, or hiding the inspector, hides the tree; pinned values remain
in Watches. There is one passive NBT heading, with no section toggle or repeated entity heading.
Inspector regions are sized from the viewport so loading or expanding fields does not move the heading
or source details. Compact layouts prioritize the selected source row and NBT data over secondary details.
Expanding or collapsing a field preserves that field's screen row, including at the tree's bottom;
wheel scrolling releases this anchor. Compound fields and list/array elements expand lazily; each tree pages
through 32 immediate children at a time. Left-clicking a field pin adds its exact path bound to that
source's UUID; an active pin removes only that binding. Right-clicking fills the same path for every
current entity source, skipping existing bindings and duplicate UUIDs. If all current entities already
have that path pinned, right-clicking removes the entire group in one edit. Other paths, floating watches,
and entities outside the current sources are preserved.
Both mouse actions are explained on hover. Non-entity sources have no NBT section.

`ClientNbtState` caches pages by executor UUID within one pause and correlates every reply with its
pause/request IDs. Steps and Continue discard pages and late requests while retaining each UUID's
expanded field paths, including across reordered sources and temporary non-entity stops. Closing and
reopening the debugger overlay keeps these preferences. Disconnect clears per-entity presentation state.
Legacy whole-tree and entity-section collapse settings no longer suppress NBT rows or reads.
Requests use the same owner-only `/codon nbt <pause> <request> <source> <offset> root|path ...` mailbox.
The server reads the loaded source entity across dimensions without loading chunks or mutating NBT.
Root data is bounded to 1 MiB estimated size, pages to 32 nodes, previews/names to 128 characters,
and navigation paths to 512 characters. Generated paths quote compound names and index collections;
they are never truncated into a different target. Paths that cannot fit a Watch's existing 128-character
limit remain browsable where possible but have disabled pins. Client caches retain the current source
entities with up to 64 expanded branches each, plus up to 256 absent UUIDs' presentation states, and issue at
most four NBT reads per drain. A five-second timeout reports unavailable; refresh is explicit.

`ClientWatchState` retains observations by watch and target across a continuous stepping session,
bounded to 256 targets per watch. Returning from executor B to A compares A with its last captured
value, and reselecting a source at the same stop preserves its capture and change highlight.
Each step also retains an unpinned watch's last selected executor UUID and re-reads it at the very next pause,
even if the new command has no executor or a different one. Its change is displayed immediately,
prefixed with that executor's actual name and short UUID. Hover explains that it is the previous executor
and shows the current selection's value/status separately.
This refreshed value becomes the next baseline, so reselecting that executor does not replay a
delayed change. There is at most one additional captured-executor read per watch and pause.
Changes for the same target receive an amber arrow, including value creation (`unset → 3`) and
removal (`3 → unset`). The absence label is localized and never becomes an invented zero. Previously
unseen executor UUIDs, missing executors/entities/objectives, invalid input, oversized results, and
unavailable replies have distinct states and do not imply a value change. Rows use compact labels,
with `↔` for availability changes. Entity names and short UUIDs identify both current and previous executors
without selector badges.
Hovering a row in the Watch editor or the interactive HUD shows full descriptions and the current
selection's status. Ordinary Continue/leaving
final inspection drops comparisons and stale values. An
unanswered query expires after five seconds, without automatic retry loops. Definitions added at a
stop get an initial observation; they cannot retroactively sample the preceding step.

The owner-only `/codon watch <pause-id> <request-id> <source-index>` subcommands (`score
<objective>`, `entity <path>`, `storage <id> <path>`) use the existing validated command mailbox.
`captured <uuid>` can replace the source index for score/entity queries of a pinned executor or the executor just stepped.
They run on the parked **server thread**, without draining general tasks/packets or changing the
pause/freecam lifecycle. `WatchReader` uses existing scoreboard scores (never creating them), loaded
entities by UUID across dimensions (including `execute in`), and vanilla read-only NBT paths.
Only the requesting player receives `watch_sync_v2`; no client world data is used for evaluation.
Replies include the loaded entity's display name (bounded to 128 characters), so restored pinned rows can
identify entities outside the current source list. Names are presentation metadata, never persisted bindings
or part of value comparison; the UUID still defines the target.
The server checks the active pause ID before reading, and the client checks pause/request IDs before
applying replies, including after source changes or item removal. Each engine stop gets a monotonically
increasing ID, even across session resets.
Reads are enqueued when a pause arrives and before a UI step command, preserving request/reply order
even when the next step is requested before the following client tick.

Parsed `/codon` commands always execute through the control dispatcher, including just after a
step unpauses the engine. Late watch requests are rejected by pause ID instead of being queued in
the inspected execution or becoming step targets themselves. `/stop` keeps its pause-only bypass.

This first version bounds inputs to 128 characters, NBT roots to 1 MiB estimated size, path matches
to 32, and returned text to 2,048 characters. Oversized values are reported explicitly, never compared
as truncated text. NBT output uses vanilla's sorted-key SNBT; multiple matches include their count.
There is no expression execution, fake-player score target, or NBT mutation.

`WorldWatchPersistence` keeps definitions in the server world save. The singleplayer owner's list
uses `data/codon-watches/singleplayer.json`, so a changed development-launch username/UUID does not
lose that world's list. LAN guests and dedicated-server players retain separate
`data/codon-watches/<player-uuid>.json` files (version 2; version 1 is still read). When the stable owner file is absent,
the current owner's legacy UUID file is migrated first, then the previous owner recorded by vanilla
in `WorldData.getSinglePlayerUUID()`. Migration leaves the legacy file intact and never scans
unrelated player files. An existing empty owner list stays empty. Only kind, objective/storage ID, NBT path,
and optional pinned executor UUID are saved; values, display-name hints, captures, and change history
are session-local. A bound target UUID is never rewritten when the singleplayer owner's UUID changes.
Join sync (`codon:watch_definitions_v3`) sends bounded definition pages, assembles the complete
list, then attaches the client edit listener, so initial empty state and disconnect cleanup cannot
overwrite the save. Adds/removals and pin changes send validated `/codon watch save_chunk`
commands through the existing owner-only control mailbox, including while paused. Individual pages
remain bounded to 8,192 JSON characters; the full list has no count or aggregate JSON-length cap.
Transfers carry an identity and contiguous offsets and replace definitions only after the final page;
incomplete, duplicate, or out-of-order chunks cannot partially overwrite the saved list.
The authenticated sender determines ownership; clients cannot specify another player or world.
Writes use temporary-file replacement, failed writes retry on later edits/world saves/shutdown,
and unreadable or unsupported files are preserved with writes disabled for that session. Failed save commands explicitly warn that edits remain session-only.

After each nonempty debugger mailbox batch, the parked server flushes outgoing connections before
waiting or resuming. Vanilla normally batches these sends until the end of the tick, which cannot
finish during a debugger pause. Watch replies and control acknowledgements therefore do not wait
for the separate one-second keepalive. This flush does not tick connections or drain ordinary tasks.

Pause payloads use `codon:pause_sync_v4` for the completion reason and the stop ID alongside source dimensions.
Client and server must both use the updated mod for pause visualization and watches.

## Persistence

Client preferences are shared across worlds and servers in the Minecraft instance's
`config/codon.json`: gizmo mode, inspector visibility, and inspector tab. Changes save immediately.
The legacy whole-NBT expansion setting is accepted for compatibility but no longer hides NBT.
An unset (`null`) inspector visibility retains the responsive automatic default. Key bindings
continue to use Minecraft's own options file. Pause snapshots, source/frame selection, scroll
positions, and freecam state remain session-local.

Both block and function breakpoints live on the server in each world save's
`data/codon-breakpoints.json`. Block entries retain their dimension and coordinates; function
entries retain their identifier and one-based line number. `WorldBreakpointPersistence` wraps the
network event sink, saving both sets after every toggle or clear while forwarding pause, step,
continue, resume, and breakpoint notifications unchanged. It restores the registry at `SERVER_STARTING`,
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
expression evaluation, deeper state inspection, execution trace, and command/NBT editors —
slot in behind these same ports.

## Verification

Use Java 25 and `./gradlew build` for compilation and the core/presentation regression suites.
They also run separately with `./gradlew coreTest clientTest`.

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

`DebuggerWorldSyncGameTest` executes a real command-block chain through `setblock`, summon/teleport,
equipment add/remove, entity name/scale, sign text, glowstone lighting, inventory, experience,
effects, damage, removal and clock changes. It checks each result in the client at the following
parked command boundary (or completion), and uses the debugger mailbox to assert unchanged server
game time and entity tick counts across client frames. It uses loaded chunks and the actual
production pause loop, with only the same test-harness phaser exemption described below.

`DebuggerFreecamResumeGameTest` powers three connected command blocks with a breakpoint on each,
then sends real client Resume commands. It checks camera identity and rendered pose at later
breakpoints, player-view restoration when the chain ends, and that every block executes once.
A gametest-only `WatchPauseTestMixin` releases the parked server from Fabric's client/server tick
phaser so the test can render and send controls during a real debugger pause. This hook does not
run in the shipped mod or process additional server work.

`DebuggerWatchGameTest` uses explicit command-stage fixtures to drive the production engine and
actually park the integrated server. It checks watch command/reply transport while ordinary server
tasks remain deferred, scoreboard/storage changes across a step, unchanged entity NBT, absent scores
without creation, cross-dimension executor lookup, codec correlation IDs, UI add/remove, and freecam
identity/terminal restoration. It checks final score/storage mutations at the completion stop, then
runs a real vanilla `execute as` scoreboard command through the production stage/queue mixins to
verify post-command inspection, no debugger-query stages, and no command replay on exit. It also
checks invalid/missing/oversized NBT reads. This tests the real
pause mailbox with fixture stages; it does not prove all function/mixin hook shapes or a separate
dedicated-server deployment. A gametest-only `WatchPauseTestMixin` temporarily withdraws the parked
server from Fabric's client/server tick phaser, then rejoins at a tick boundary; otherwise Fabric's
lockstep test runner cannot render or send controls during an intentional server pause. This hook
does not run in the shipped mod or drain any additional server work.

`DebuggerWatchChainGameTest` powers adjacent impulse/chain blocks containing `execute as @a run
scoreboard players add @s a 3` and then `4`, starting at zero and with no initial score. It uses the
actual F9 input path and requires `0 → 3` or `unset → 3` at the first next-block stop, while that stop
still has no executor, without another key press. It then requires an unchanged `3` when the player
becomes the executor again and `3 → 7` at execution completion. It also checks compact labels and
detailed tooltip formatting. Screenshots record the immediate changes, and the final score confirms
that each command ran exactly once in both scenarios. Each F9 logs control and Watch-read latency;
the combined response must stay below 750 ms, including the outgoing executor's read when required.

`NbtTreeReaderGameTest` checks exact generated paths (including punctuation, quotes, and backslashes),
compound/list/array paging, size limits, and the reply codec in the Minecraft runtime.
`DebuggerNbtTreeGameTest` parks the integrated server and exercises the empty Watches **+** button,
the selected live source's NBT tree inside the source inspector (absent for non-entity sources and a hidden inspector),
always-visible NBT and field expansion retained across F9, Continue, reordered/temporarily absent sources, and a recreated overlay,
scrolling and pagination, individual left-click array-element pins and right-click
all-source pins. Pinned values stay in Watches when the selected source changes or the inspector is hidden.
Repeated right-clicks remove and re-add the whole current-source group; left-click removal affects only its own UUID.
Screenshots include four simultaneous watch rows.

`DebuggerWatchPinGameTest` parks the actual server with two executor entities and clicks the editor's
pin/unpin controls. It checks independent score bindings, entity NBT pinning, source switches,
per-target changes at a stop without an executor, duplicate unpin rejection, and removed-target status.
Screenshots capture the two pinned rows and a detailed hover tooltip.

`DebuggerWatchPersistenceGameTest` creates two real worlds, stores 256 definitions spanning three
Watch kinds and pinned bindings (more than 32,767 JSON characters), then verifies chunked save/rejoin
restoration, world isolation, atomic empty-list removal,
and absence of stale runtime values. A seeded legacy save with a different recorded owner UUID checks
real startup migration and client restoration. Temporary-directory unit tests also check player isolation,
invalid-file preservation, complete input validation, and failed-write recovery.
