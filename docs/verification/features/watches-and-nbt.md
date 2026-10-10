# Watches and NBT

Automatic pause changes use the same 4,096-row and 2,097,152-character aggregate
budget on send and receive. Oversized changes and failed captures send explicit
unavailable outcomes instead of partial rows or an empty success; older peers receive
a system notice.
The Watch panel displays an unavailable warning for rejected transfers. Details
enables Retry for server-reported `ERROR` or `UNAVAILABLE`, never for a local
timeout. A late reply after an attempted Retry cannot bypass the failed pause.
Entity option ellipses preserve UTF-16 surrogate pairs. Focused checks are
`DebuggerRecipientAuthorizationTest`, `ClientWatchStateTest`,
`WatchChangesUnavailablePayloadTest`, and `WatchEditorCompactTest`.

The compact Watch regression uses row context menus for Copy, Pin, Edit and
Delete. It checks a single full-row inspection target, including the value's right
edge, with no inline management controls. `WatchPanelLayoutTest` covers the same
name/value layout at narrow and wide widths; `DebuggerCompactWatchGameTest`
exercises the actual screen events and Details actions.

## User path and expected result

Regular Watches default to 280 logical pixels (previously 360), bounded by the same
free area above Flow. In cursor mode at viewport widths of 600 logical pixels or
more, drag the Watch panel's left edge or the Contexts inspector's right edge to
resize it. Both use the shared `PanelResizeInput`, a neutral edge highlight and
horizontal resize cursor. Watch requests range from 220–640 pixels; Contexts from
160–640, with its existing 190-pixel default. Screen bounds constrain the displayed
widths and Contexts leaves room for a visible Watch's readable minimum. Watch
names stay on the left and values stay right-aligned on the same line at every
panel width, including below 260 pixels. Narrow rows clip each side to its available
space; Details retains the full content. No blank action column is reserved.
The narrow auxiliary drawer and Source's separate function-list
splitter retain their existing behavior; Flow remains full-width below the panels.

Release saves the selected width once in the existing `config/codon.json` settings
(`watchWidth` / `inspectorWidth`); missing fields use defaults. Viewport clamps do
not overwrite a saved request, so enlarging the window restores it. Esc, UI hiding,
modal opening, screen removal and viewport/scale resize cancel an active preview.
The captured drag/release is consumed without invoking row actions. Source retains
its existing forwarding of drags that start on an exposed parent panel. Its init
and removal only cancel a pending parent-panel preview, leaving its own splitter
untouched. Width adjustment is scoped to these two HUD panels in the regular layout.

The following alignment-only change disables the narrow stacked-row policy; it
reuses the existing left-key/right-value renderer, 18/28-pixel row geometry and
single full-row inspection surface. Right-click management, saved widths, resize
capture and return focus remain unchanged. `WatchPanelLayoutTest` checks the
left-key/right-value geometry and full-row inspection without inline action slots.

Neutral headings and removal of the dot
leaders leave more room for names/values. Groups have an arrow, item count and
neutral header background, with spacing between groups. Click anywhere on the
header or focus it and press Enter/Space to collapse or expand it. Collapsed children
are excluded from rendering, hitboxes and keyboard navigation; explicit watch reveals
expand their group. This is local display state only. Add, grouping and Undo stay in the panel header.
Row management uses a right-click menu; left-clicking the name or value still opens Details.
Watch row hover uses a subtle neutral fill; keyboard focus uses a stronger neutral
fill plus a small filled corner caret; an explicitly revealed watch retains its
temporary teal fill. Group headers show Δ changed values and ! read issues
(invalid paths, size limits, errors and unavailable reads), including while collapsed.
Observation continues independently of collapse. Rows reclaim the 72 logical pixels
formerly reserved for inline actions. Name/value hit surfaces use the available row
width; hover does not change widths or cover values.
No row outline is drawn. Native clipping/keyboard/appearance checks remain manual acceptance work.
The nested NBT section uses only a top divider; value rows, pin controls and Retry
share flat chrome instead of a box around every item. NBT and Watch pin icons are
teal when present/fixed and muted otherwise; hover never changes that state color.
NBT shows a muted pin icon on unpinned rows and a teal remove icon on pinned rows,
without requiring hover. Its row and pin tooltips use the same 350 ms pointer delay.
Add/Remove Watch labels retain the exact action. The Watch pin separately
fixes its target or returns to following the selected executor. The NBT heading
explicitly identifies a current-pause read, or labels retained values while a new
read is pending. Retained rows keep their existing input-blocking guard. Historical
contexts with no current-pause occurrence cannot open a live NBT tree; when room
permits, the empty NBT area explains that no historical NBT was captured. A recorded
context which still maps to a live occurrence may show NBT, explicitly labeled as
a current-pause read rather than a historical value. Query/occurrence matching is unchanged. Keyboard focus uses a filled corner
caret, and the scrollbar is neutral. Foreground text/icons bypass panel opacity.
NBT expansion, Watch-add/right-click actions, disabled states and hit bounds are unchanged.
Watch add/update/duplicate/remove/copy notices replace the panel's title text for
four seconds without changing the row viewport or its scroll offset; hovering the
header retains the full notice when the visible text is clipped. Watch Details
shows a translated Copied confirmation beside its title for four seconds after
copying a value or path, preserving text scrolling and footer positions.
Watch forms and details return to the existing originating screen, retaining its
selection/search/scroll and restoring semantic widget or HUD-row focus. Returning
from Edit in details restores the same expanded view and text offset.

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
   the preview value. Before submitting an empty form, the first validation reason
   is visible beside disabled Add/Save. Tab visits each field followed by its
   Browse/Choose action, then Add/Save and Close; reverse Tab retraces that order.
   Use native clicks and Tab to check Browse, Retry, Add/Save
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
   Click the scrollbar track, grab its thumb without a jump, drag outside the
   list and release. Capture must end on release, resize, page loss or closing.
9. At 427x240, open View → Watches. Compact rows put a full-width name above a value
   line; scope moves into the full-text inspector (and into a row tooltip only when
   the row is clipped or has a status to explain). Inspect shortened
   names and long values, check numeric/error states, scroll past the visible rows,
   and use Add, pin/unpin, Copy, Edit, Delete and Undo. Compare a regular viewport
   in English/Korean, following game scale and using a custom Codon scale.
   Hover the far right of a clipped compact value line: its full-text tooltip must
   appear, while a healthy, fully drawn row stays silent. Click the same point to open Details. Right-click either line to manage that Watch.
   Focus the row and press Shift+F10, then use Up/Down, Tab and Enter/Space.
   Check Copy/Edit/Delete, target fixing/following, disabled fixing without an
   executor, and automatic-change Add to Watches. Storage rows have no executor
   action. NBT Watch-add behavior is separate from fixing an existing Watch target.
   The menu stays within the screen; small viewports scroll its items. Right-click
   another visible row to retarget it. Escape and outside clicks close it and restore
   row focus without clicking the row below. Menu actions dismiss before executing,
   and a removed Watch/world change invalidates the menu. Repeat with Source docked
   to check menu layering and that Source-covered pixels cannot retarget it.
   In Details at 320x240, the footer wraps to two rows clear of the scrollable text.
   Check Copy value/path, Retry, Edit and Close hitboxes. More/Less retains focus on
   the same toggle; Tab then reaches Edit when expanded, or skips hidden Edit and
   disabled Retry to reach Close for a successful value. A failed read enables Retry.

Queries while paused must remain read-only and must not cause an extra execution
step. Keep previous/current comparison tied to observed pauses. A brief retained
display during a pending reply must not enable actions on stale data.

On a pause with many Watches, the client sends at most 12 Watch reads, one editor
read and three NBT reads concurrently. It sends later Watch reads as replies
arrive. Step is sent only after all Watch reads for that pause have replied. A
five-second Watch timeout, or ten seconds waiting for the full pre-step capture,
cancels Step without advancing execution. The remaining rows show UNAVAILABLE,
the status explains the failure, and no more reads are sent for that pause.
Transport credit is not recycled on a local timeout because the server may still
hold the request. Resume remains available, and a new pause starts a fresh window.
Editor requests waiting for a credit also expire after five seconds; an explicit
Retry cannot remain loading indefinitely behind a lost reply.
This ten-second bound can cancel an otherwise healthy, very large Watch list.
Verify 33 Watches and 17 Entity NBT Watches at a paused breakpoint, including
Watch values before step, editor/NBT responsiveness, and explicit failure plus
Resume after a lost reply. The per-connection mailbox also receives other Codon requests, so
the reserved 16 slots are not an absolute guarantee under concurrent traffic.

For cold Storage history, persist `changed:0,removed:1,unchanged:7`, save/close the
world, then reopen before any Storage query. Pause before changing the values and
Continue to a later breakpoint after changing `changed` to 1 and deleting `removed`.
Watches must show `0→1` and `1→missing`, with unchanged/initially missing fields
neutral and no spurious preexisting automatic rows. Entity/score history must also
remain correct. The first automatic snapshot discovers only immediate valid
`DATA/<namespace>/command_storage.dat` files and loads existing containers through
vanilla's non-creating read path. This occurs once per execution baseline, not at
world startup or on every Continue. Already loaded containers are not reread;
reads must not dirty values, add probe keys or change persisted bytes.
Codec errors can return a partial vanilla container, including an empty one. The
SavedDataStorage mixin records incomplete Storage decodes without changing vanilla's
returned data or cache. Automatic baseline discovery rejects those namespaces,
including cached partial reads, and leaves its previous baseline intact. Truncated
files likewise withhold the baseline. Repairing a file in the same server does not
clear vanilla's cached failed/partial result; a fresh saved-world reopen can read
the repaired values and establish a complete baseline.

## Code entry points

- [WatchReader](../../../src/main/java/works/nuty/codon/adapter/WatchReader.java), [NbtTreeReader](../../../src/main/java/works/nuty/codon/adapter/NbtTreeReader.java): server-side reads.
- [ClientWatchState](../../../src/client/java/works/nuty/codon/client/state/ClientWatchState.java), [ClientNbtState](../../../src/client/java/works/nuty/codon/client/state/ClientNbtState.java): requests, values and selected target.
- [ClientQueryScheduler](../../../src/client/java/works/nuty/codon/client/state/ClientQueryScheduler.java): bounded paused-read dispatch and step ordering.
- [WatchPanel](../../../src/client/java/works/nuty/codon/client/ui/WatchPanel.java), [WatchScreen](../../../src/client/java/works/nuty/codon/client/ui/WatchScreen.java), [NbtTreePanel](../../../src/client/java/works/nuty/codon/client/ui/NbtTreePanel.java): user interaction.
- [WatchFormLayout](../../../src/client/java/works/nuty/codon/client/ui/layout/WatchFormLayout.java): shared form columns and vertical slots.
- [DebuggerContextMenu](../../../src/client/java/works/nuty/codon/client/ui/DebuggerContextMenu.java): shared modal dropdown hosted by ScreenLayers; Watch retains its existing guarded actions, row switching and keyboard/pointer handling.
- [WatchPickerScreen](../../../src/client/java/works/nuty/codon/client/ui/WatchPickerScreen.java), [WatchPickerLayout](../../../src/client/java/works/nuty/codon/client/ui/layout/WatchPickerLayout.java): Browse dialog drawing, controls and hit bounds.
- [WatchDetailsScreen](../../../src/client/java/works/nuty/codon/client/ui/WatchDetailsScreen.java), [WatchDetailsLayout](../../../src/client/java/works/nuty/codon/client/ui/layout/WatchDetailsLayout.java): full-value viewport and responsive footer.

## Choose verification

| Concern | Existing tests |
| --- | --- |
| Values, changes, pin identity | `clientTest`: `ClientWatchStateTest`, `ClientWatchChangesTest`, `ClientWatchPinTest` |
| Pending/paged data | `clientTest`: `ClientNbtStateTest`, `ClientNbtDisplayDelayTest`, `ClientWatchDisplayDelayTest` |
| Paused Watch bursts and step ordering | `clientTest`: `ClientQuerySchedulerTest`, `ClientWatchBurstProbeTest`; `test`: `DebuggerMailboxTest` |
| Files and transfer | `test`: `WorldWatchPersistenceTest`, `WatchDefinitionTransferTest`, `WatchSaveV2PayloadTest`; `clientTest`: `ClientWatchUploadStateTest`, `ClientTransferLimitsTest` |
| Delayed initial owner sync and local edits | `clientTest`: `ClientWatchInitializationTest`; `test`: `ClientWatchInitializationBudgetTest`; `WatchPromotionHandshakeGameTest`, `DebuggerWatchPinGameTest` |
| Registered v2 and legacy save routes | `WatchSaveProtocolGameTest`: held/duplicate page ACK, durable final ACK, fixed timeout, stale ACK, retry, legacy multipage and invalid gap |
| Watch readers and rendered values | `DebuggerWatchGameTest` |
| Cold persisted Storage baseline, real restart/Continue, deletion/creation and entity/score history | `test`: `PersistedStorageNamespacesTest`; `DebuggerColdStorageWatchGameTest`, `PauseWatchChangesGameTest`, `DebuggerAutomaticWatchGameTest` |
| Truncated/partial Storage decode, withheld baseline, and repaired-world reopen | `test`: `StorageReadFailureTest` (actual untransformed vanilla reader); `DebuggerStorageReadFailureGameTest` (actual mixin/snapshot/reopen) |
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
scrolling, custom-scale selection, native wheel isolation at each list edge, and
scrollbar track-click/thumb-drag/release outside the panel.
The Korean Entity NBT case uses Custom 2.00 at 1364×1024 to match the manual
search/footer wheel report. `WatchFormLayoutTest` and
`WatchPickerLayoutTest` retain the focused geometry and viewport boundary checks.
`DebuggerCompactWatchGameTest` uses six representative English/Korean cases at
427x240, 320x240 and regular viewports, with following/custom scales. It checks
native management and footer clicks, compact value right-edge click/tooltip hover,
More/Less focus, row overflow, copy-feedback viewport stability and pending
execution-control disabling, and captures `*codon-compact-watch-*.png` for visual
inspection. This scenario and `WatchPanelLayoutTest` passed in the
[UI validation run](../ui-polish-validation.md). Its injected observations establish
UI behavior, not live server reads. Panel-edge dragging/cancellation and manual
visual acceptance remain unverified by these selected checks.

Example: `./gradlew runClientGameTest -PclientGameTest=DebuggerNbtTreeGameTest`.
Choose the row for the changed behavior, not the whole table. Inspect the matching
`*codon-*.png` captures where emitted. UI fixtures alone do not prove paused-server
query timing; use the pause/transport scenario for that boundary.

At a 320x240 GUI viewport, Details initially folds into the View menu. Open
`View → Details` before inspecting NBT. `DebuggerNbtTreeGameTest` exercises that
route and then verifies that both the selected source and NBT data remain visible.
In short inspector viewports, a compact Contexts caption and one selected-source
row leave room for the NBT heading plus data, even with the Flow detail band open.


Client receive transfers have independent count, text, page and lifetime budgets. Watch
restoration/upload permits 8,192 definitions and 2,097,152 serialized JSON UTF-16
characters; automatic changes permit 4,096 received rows and 2,097,152 retained
text characters. Both permit at most 1,024 pages within a fixed 30-second window.
Repeated rows still consume the budget. Rejected/incomplete transfers never become
completed definitions; persisted JSON files retain their existing format and are not
truncated. Canonical watch identity lookup preserves the first expression and row order,
including quoted paths, pin/unpin, edits and undo. Exact and canonical identity keys
also have a total ordering, so chosen string-hash collisions cannot restore pairwise
lookup work in staging, restoration or displayed-change merging.

Updated peers negotiate `watch_save_v2`: one immutable page is in flight, and only
an acknowledgement of its exact transfer ID and next offset releases another page.
Only the final durable-save response can mark the upload Saved. Timeout, rejection,
or disconnect releases the pending upload; retry is an explicit user action. A lost
final acknowledgement leaves persistence unconfirmed, since the server may already
have committed. Legacy peers use the existing bounded all-or-nothing transfer and
can fail explicitly under overload. `ClientWatchUploadStateTest` includes 8,192
entries across 1,024 acknowledged pages and stale, duplicate and unsolicited replies.
The client UI and uploader share the same fixed 30-second start instant, before
page validation and copying. Once the UI times out, no further v2 page can advance;
unsent v2/legacy pages are checked again before dispatch. `ClientWatchSaveDeadlineTest`
covers validation/copy delays, exact-boundary ACKs, final replies and connection replacement.

The first completed owner watch sync merges the still-present local definitions
with saved server definitions by canonical identity. Local row IDs, expressions,
and live observations survive; missing server watches are appended. Only local
identities absent from the server list trigger one merged save. An unchanged or
empty startup state never writes back. Later initialization packets cannot replace
edits. An in-session reset silently clears rows while preserving the connection's
initialization and save callback; disconnect explicitly releases both. Session-local
editing remains available when no initial server sync is supported. If the complete union exceeds
the upload budget, local rows remain intact and persistence stays unconfirmed;
edits or explicit Retry recheck the full union without uploading a partial subset.
The initialization tests cover delayed empty/nonempty restores, edit/delete and
canonical aliases, reset/disconnect, and actual count/serialized-character limits.
`WatchPromotionHandshakeGameTest` holds the actual first owner snapshot after a
non-owner joins and is promoted, then verifies an accepted local edit survives
restoration and reaches durable storage. `DebuggerWatchPinGameTest` exercises the
existing immediate edit/pause ordering. `WatchSaveProtocolGameTest` observes the
registered receivers while preserving production handlers, including the real
30-second lost-ACK deadline and explicit legacy packets. The cold-storage and
completed-flow fixtures create an owner world before observing private state.

Before JOIN or promotion sends any restored Watch page, the server validates the
complete saved snapshot against the same aggregate budget as the client. An oversized
snapshot produces one explicit restore failure per connection, retaining the saved
file and local edits without installing a partial-save callback. The Watch warning
explains that edits remain session-only; repair the saved list while the world is
closed, then reopen it. Older clients receive the same chat notice. Both upload
protocols refuse to replace a stored snapshot that could not fit the restore budget.
The failure remains recorded through re-promotion, without retrying on every tick.
`DebuggerRecipientAuthorizationTest`, `WatchRestorePreservationTest` and
`ClientWatchInitializationTest` cover count/text overflow, JOIN/promotion, one-time
failure, local state and legacy/partial overwrite protection. `WatchRestoreFailureGameTest`
uses actual packets and a closed-world repair/reopen; its warning screenshot uses a
synthetic pause only to present the received failure in the Watch panel.

Server staging expires without further packets at ordinary END ticks and during the
parked server's existing one-second maintenance. This removes only unfinished uploads;
committed definitions and other active transfers survive. `WorldWatchIdleExpiryTest`
covers injected-clock deadlines, isolation, late continuation and fresh/reconnect
recovery. `WatchUploadIdleExpiryGameTest` checks running and debugger-parked lifecycles.
The 2,097,152-character budget counts serialized UTF-16 units, not actual heap bytes.

NBT pages must contain unique immediate child paths, also across loaded pages.
Self/ancestor links, skipped descendants, path aliases and inconsistent totals reject
the page. Quoted/escaped Unicode keys, empty quoted keys and multiple inaccessible
leaf paths remain supported. Traversal visits each branch once and emits a Too Large
status after 8,192 materialized rows or 16,384 traversal steps; sparse million-child scroll ranges still use
compressed gaps. `ClientNbtStateTest` and `ClientNbtDisplayDelayTest` cover both live
and retained display rows. `ClientTransferLimitsTest` covers the original watch
restoration/accumulation triggers and valid controls. These headless tests do not
establish native rendering or real multiplayer transport.
