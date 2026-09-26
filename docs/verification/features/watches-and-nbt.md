# Watches and NBT

## User path and expected result

Use the [shared setup](../README.md#prepare-and-launch). Before pausing, prepare a
score and a storage value in the scratch world:

```mcfunction
scoreboard objectives add codon_verify dummy
scoreboard players set @s codon_verify 1
data modify storage codon:verify counter set value 0
```

1. Put `execute as @a run scoreboard players add @s codon_verify 1` in an impulse
   command block, set a breakpoint, and trigger it. Step until the player executor
   is selected, then press `V` to interact with Watches and the context inspector.
2. Use the Watches add control to open the editor. Add the executor's `codon_verify`
   score and a storage watch for `codon:verify`, path `counter`. The score shows its
   current value and storage shows the valid value `0`.
3. Step through the update and inspect at the next stop. The score changes by one;
   the storage watch stays zero. Missing objective/path/target must have an explicit
   status rather than masquerading as zero.
4. Select the player context, expand entity NBT, and pin a scalar field such as
   `Health`. It appears in Watches. Remove/re-add it and check that it refers to
   the intended entity/path. Select another context and inspect target changes.
5. Expand compounds/lists, scroll beyond the first page, and return. Loaded rows
   stay aligned; pending rows do not operate on stale NBT. Check grouping and the
   watch editor at the viewport/GUI scale relevant to the reported issue.
6. For persistence changes, save, leave and reopen the same world. Verify saved
   watch definitions and any affected client view preferences separately.

Queries while paused must remain read-only and must not cause an extra execution
step. Keep previous/current comparison tied to observed pauses. A brief retained
display during a pending reply must not enable actions on stale data.

## Code entry points

- [WatchReader](../../../src/main/java/works/nuty/codon/adapter/WatchReader.java), [NbtTreeReader](../../../src/main/java/works/nuty/codon/adapter/NbtTreeReader.java): server-side reads.
- [ClientWatchState](../../../src/client/java/works/nuty/codon/client/state/ClientWatchState.java), [ClientNbtState](../../../src/client/java/works/nuty/codon/client/state/ClientNbtState.java): requests, values and selected target.
- [WatchPanel](../../../src/client/java/works/nuty/codon/client/ui/WatchPanel.java), [WatchScreen](../../../src/client/java/works/nuty/codon/client/ui/WatchScreen.java), [NbtTreePanel](../../../src/client/java/works/nuty/codon/client/ui/NbtTreePanel.java): user interaction.

## Choose verification

| Concern | Existing tests |
| --- | --- |
| Values, changes, pin identity | `clientTest`: `ClientWatchStateTest`, `ClientWatchChangesTest`, `ClientWatchPinTest` |
| Pending/paged data | `clientTest`: `ClientNbtStateTest`, `ClientNbtDisplayDelayTest`, `ClientWatchDisplayDelayTest` |
| Files and transfer | `test`: `WorldWatchPersistenceTest`, `WatchDefinitionTransferTest` |
| Watch readers and rendered values | `DebuggerWatchGameTest` |
| Editor and server request/reply | `DebuggerWatchEditorGameTest`, `WatchEditorTransportGameTest` |
| NBT reads, tree controls and stale buttons | `NbtTreeReaderGameTest`, `DebuggerNbtTreeGameTest`, `DebuggerNbtPendingButtonsGameTest` |
| Pinning, grouping, persistence | `DebuggerWatchPinGameTest`, `DebuggerWatchGroupingGameTest`, `DebuggerWatchPersistenceGameTest` |
| Pause-time changes and command chains | `PauseWatchChangesGameTest`, `DebuggerWatchChainGameTest`, `DebuggerAutomaticWatchGameTest` |

Example: `./gradlew runClientGameTest -PclientGameTest=DebuggerNbtTreeGameTest`.
Choose the row for the changed behavior, not the whole table. Inspect the matching
`*codon-*.png` captures where emitted. UI fixtures alone do not prove paused-server
query timing; use the pause/transport scenario for that boundary.
