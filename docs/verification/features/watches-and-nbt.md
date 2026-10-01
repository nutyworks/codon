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
2. With no saved watches, check that the visible `+` add control matches the empty
   panel's `Use + to add` hint (English and Korean), then use it to open the editor.
   Add the executor's `codon_verify`
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
7. In the Watch add/edit form, check Score, Entity NBT and Storage NBT in English
   and Korean at normal and 320x240 GUI viewports. Labels sit above their fields;
   field actions share row height and the right column. Recommendations leave a
   gap before the next label, validation remains readable, and Retry sits beside
   the preview value. Use native clicks and Tab to check Browse, Retry, Add/Save
   and focus after returning from the picker. Also check an independent Codon UI
   scale when that option is available.
8. Open both Browse/Choose buttons. In Objectives, check a blank search with two
   short options: the search and list share their edges, labels are vertically
   centered, and the footer follows the rows without a large empty panel. In
   storage/entity NBT, inspect long details beside expansion arrows, expand and
   go Up. Check page navigation, Retry, scroll and keyboard selection in English
   and Korean at normal, narrow and custom Codon scales. Search retains its
   position as result sizes change; native selection updates only its draft field.
   Wheel over the search, title, footer and outside the panel must leave the
   results at the same offset. Wheel over a row, row gap or the list's scrollbar
   strip scrolls the results; confirm normal field drag and keyboard selection.
9. At 427x240, open View → Watches. Compact rows put name/actions above a value
   line; scope moves into the row tooltip and full-text inspector. Inspect shortened
   names and long values, check numeric/error states, scroll past the visible rows,
   and use Add, pin/unpin, Copy, Edit, Delete and Undo. Compare a regular viewport
   in English/Korean, following game scale and using a custom Codon scale.
   Hover the far right of the compact value line: its tooltip must appear. Click
   the same point to open Details, then check the name line's management buttons
   still use their own targets.
   In Details at 320x240, the footer wraps to two rows clear of the scrollable text.
   Check Copy value/path, Retry, Edit and Close hitboxes. More/Less retains focus on
   the same toggle; Tab then reaches Edit when expanded and Retry when collapsed.

Queries while paused must remain read-only and must not cause an extra execution
step. Keep previous/current comparison tied to observed pauses. A brief retained
display during a pending reply must not enable actions on stale data.

## Code entry points

- [WatchReader](../../../src/main/java/works/nuty/codon/adapter/WatchReader.java), [NbtTreeReader](../../../src/main/java/works/nuty/codon/adapter/NbtTreeReader.java): server-side reads.
- [ClientWatchState](../../../src/client/java/works/nuty/codon/client/state/ClientWatchState.java), [ClientNbtState](../../../src/client/java/works/nuty/codon/client/state/ClientNbtState.java): requests, values and selected target.
- [WatchPanel](../../../src/client/java/works/nuty/codon/client/ui/WatchPanel.java), [WatchScreen](../../../src/client/java/works/nuty/codon/client/ui/WatchScreen.java), [NbtTreePanel](../../../src/client/java/works/nuty/codon/client/ui/NbtTreePanel.java): user interaction.
- [WatchFormLayout](../../../src/client/java/works/nuty/codon/client/ui/layout/WatchFormLayout.java): shared form columns and vertical slots.
- [WatchPickerScreen](../../../src/client/java/works/nuty/codon/client/ui/WatchPickerScreen.java), [WatchPickerLayout](../../../src/client/java/works/nuty/codon/client/ui/layout/WatchPickerLayout.java): Browse dialog drawing, controls and hit bounds.
- [WatchDetailsScreen](../../../src/client/java/works/nuty/codon/client/ui/WatchDetailsScreen.java), [WatchDetailsLayout](../../../src/client/java/works/nuty/codon/client/ui/layout/WatchDetailsLayout.java): full-value viewport and responsive footer.

## Choose verification

| Concern | Existing tests |
| --- | --- |
| Values, changes, pin identity | `clientTest`: `ClientWatchStateTest`, `ClientWatchChangesTest`, `ClientWatchPinTest` |
| Pending/paged data | `clientTest`: `ClientNbtStateTest`, `ClientNbtDisplayDelayTest`, `ClientWatchDisplayDelayTest` |
| Files and transfer | `test`: `WorldWatchPersistenceTest`, `WatchDefinitionTransferTest` |
| Watch readers and rendered values | `DebuggerWatchGameTest` |
| Editor and server request/reply | `DebuggerWatchEditorGameTest`, `WatchEditorTransportGameTest` |
| Watch form alignment, languages and native input | `clientTest`: `WatchFormLayoutTest`; `DebuggerWatchFormLayoutGameTest` |
| Browse dialog alignment, short Objectives, paging and selection | `clientTest`: `WatchPickerLayoutTest`; `DebuggerWatchPickerLayoutGameTest`, `WatchEditorTransportGameTest` |
| Compact Watch values, Details footer, native actions and focus | `clientTest`: `WatchPanelLayoutTest`, `WatchDetailsLayoutTest`, `DebuggerHeaderLayoutTest`; `DebuggerCompactWatchGameTest` |
| Empty Watches hint, visible `+` and editor route | `DebuggerNbtTreeGameTest` (`codon-nbt-tree-empty-watch-plus` capture) |
| NBT reads, tree controls and stale buttons | `NbtTreeReaderGameTest`, `DebuggerNbtTreeGameTest`, `DebuggerNbtPendingButtonsGameTest` |
| Pinning, grouping, persistence | `DebuggerWatchPinGameTest`, `DebuggerWatchGroupingGameTest`, `DebuggerWatchPersistenceGameTest` |
| Pause-time changes and command chains | `PauseWatchChangesGameTest`, `DebuggerWatchChainGameTest`, `DebuggerAutomaticWatchGameTest` |

The two layout GameTests use six representative cases each, not a language ×
viewport × Watch-kind matrix. The form cases retain all three kinds in both
languages, normal/compact layouts, custom-scale resize/clamping, validation,
Retry, and Add/Save. The picker cases exercise each kind/field route once,
including two short Objectives, paging/Retry, NBT expand/Up, narrow Korean
scrolling, custom-scale selection and native wheel isolation at each list edge.
The Korean Entity NBT case uses Custom 2.00 at 1364×1024 to match the manual
search/footer wheel report. `WatchFormLayoutTest` and
`WatchPickerLayoutTest` retain the focused geometry and viewport boundary checks.
`DebuggerCompactWatchGameTest` uses six representative English/Korean cases at
427x240, 320x240 and regular viewports, with following/custom scales. It checks
native management and footer clicks, compact value right-edge click/tooltip hover,
More/Less focus, row overflow and pending
execution-control disabling, and captures `*codon-compact-watch-*.png` for visual
inspection. Its injected observations establish UI behavior, not live server reads.

Example: `./gradlew runClientGameTest -PclientGameTest=DebuggerNbtTreeGameTest`.
Choose the row for the changed behavior, not the whole table. Inspect the matching
`*codon-*.png` captures where emitted. UI fixtures alone do not prove paused-server
query timing; use the pause/transport scenario for that boundary.

At a 320x240 GUI viewport, Details initially folds into the View menu. Open
`View → Details` before inspecting NBT. `DebuggerNbtTreeGameTest` exercises that
route and then verifies that both the selected source and NBT data remain visible.
