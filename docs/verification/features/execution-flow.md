# Execution flow

Stage-preview requests now use a 30-second deadline from request creation. A dropped
reply permits one automatic retry with a new request ID; the older reply cannot replace
the retry. A second timeout stops automatic requests until explicit Source Reload or
line re-selection starts a fresh attempt. `ClientFlowPreviewRequestsTest` covers the timed-out loading state and
stale response guard. Native packet-loss and unobserved-stage presentation remain
separate acceptance checks.

## User path and expected result

The UI scaffold uses shared neutral surfaces and flat Contexts, call-path and Flow
buttons: idle rows have no repeated frame, hover has a neutral fill, selection keeps
its semantic fill and underline, and keyboard focus has a filled corner caret.
Foreground text and icons remain opaque when panel opacity is reduced. Live amber,
selected teal, context-change colors, breakpoint icons and counts retain their roles.
Unobserved stages keep the full observation text in their clause tooltip and
selection summary. Future stages no longer repeat an inline Unrun label or reserve
a status minimum width; Unreached/Unknown/Error remain distinct inline information.
Cell horizontal padding is 6 logical pixels (previously 10), with 2 pixels between
cells (previously 4). Both drawing and button text use that padding. The count-line
minimum is measured count width + 6, plus 17 only when a warning control is present;
it previously added 26 and another 15 for an editable source. Breakpoint and warning
icons are reserved once, by the command line's leading inset. Compact rows with
no count line reserve no count width. The shared count row remains when observed
counts are shown; hidden Unrun labels do not produce a replacement badge. Stage
targets, secondary actions and selection colors retain their existing semantics. Marker
and warning controls use separate 18×18 targets with a 19-pixel layout slot.
Character wrapping, row scrolling and call-path horizontal scrolling retain their
existing behavior with the denser clause geometry.

The actual paused Flow stage uses amber text/counts and its existing amber selected
surface, without a pause glyph. The first clause fragment no longer reserves the
14-pixel pause-icon inset; a compact warning still reserves its own icon space when
needed. Amber is guarded by live paused state plus the actual flow/stage identity,
so browsing a different recorded stage retains teal selection without moving the
execution indicator. The stopped clause tooltip explicitly names Stop and the stage
number; the selection detail band's textual Stop state and narration target remain.
Breakpoint controls, toolbar controls and call-path markers are unchanged.

The follow-up crop `Screenshot 2026-10-03 at 15.27.53.png` shows the space before
`at @s`, inside the clause after its breakpoint marker. Parsed stage text includes
the leading command separator; the display-only layout now strips that separator
before measuring/wrapping, so it no longer adds to the 3-pixel button inset. Full
command text, stage ranges, tooltips, marker hitboxes and spacing inside a clause
are preserved. Continuation fragments are not stripped. The 6-pixel total padding,
2-pixel cell gap and hidden inline Unrun remain in place.

Contexts now labels the selected stop/recorded stage separately from the stage
which supplies its displayed contexts. Complete modifier records show outputs plus
excluded inputs; terminal, unfinished or incomplete-lineage records show inputs.
A predecessor's outputs retain that predecessor's stage number. Empty observed
context sets are not relabeled as missing recordings. Very short inspector viewports
use the provenance caption as the heading to retain a selectable context row.

Flow has a persistent two-line selection detail band with a keyboard focus/narration
target. It names Stop/Recorded/Selected, the stage number, observation state and
counts. The collapsed panel reserves 28 additional logical pixels; stage widths,
wrapping rules and bottom action positions do not depend on the selected status.
Expanded Flow retains its existing outer size and allocates the same detail band.
Measured zero stays `0`; clause counts, summaries and detail values use `?` for
unmeasured counts. Watch/NBT `…` remains a pending request indicator. Explicit stage-scoped execution
warnings produce Error; zero successes alone do not. A missing suffix is Unrun only
beyond the actual stop in the same invocation. A complete, reliable preceding stage
with zero outputs permits Unreached. Other absent historical stages remain Unknown,
with recording-missing/execution-unknown text. No lifecycle facts or error attribution
are inferred for unrecorded stages or warnings without a stage identity.

The trace model does not carry an authoritative lifecycle state for every absent
parsed stage. Distinguishing all other non-execution versus missing-capture cases
would require additional server evidence; this patch does not extend the protocol.

Use the [shared setup](../README.md#prepare-and-launch). In a fresh scratch world,
summon two tagged armor stands before pausing:

```mcfunction
summon minecraft:armor_stand ~2 ~ ~ {Tags:["codon_verify","codon_keep"]}
summon minecraft:armor_stand ~4 ~ ~ {Tags:["codon_verify"]}
```

Save this command in an impulse command block, add a whole-command breakpoint,
and trigger it:

```mcfunction
execute as @e[type=minecraft:armor_stand,tag=codon_verify] at @s if entity @s[tag=codon_keep] run say kept
```

1. Step with `F7` and inspect the command's stages and context markers using `V`.
   The `as` stage branches into two contexts; `at` changes their recorded anchors;
   `if` retains one and removes the other. These are context changes, not entity
   creation/deletion.
2. Select earlier recorded stages/frames and compare input/output contexts. Their
   counts, anchors and call path must come from the selected record. Browsing a
   previous stage must not change the actual stopped command.
   The command panel uses left/right arrows for recorded-command navigation and
   four-corner icons for expand/collapse. Its action row has no Watch button;
   open Watches from the debugger toolbar. Expanding and collapsing must keep
   the action buttons in place. Command text wraps at character (Unicode code-point)
   boundaries rather than backing up to spaces, both with recorded stages and in
   the raw-command view. Spaces and active-range highlighting are preserved;
   recorded stage fragments retain their stage identity and breakpoint target.
   Only the first fragment reserves breakpoint/pause/warning icon and count-label
   space. Continuation rows use the full text width with normal text padding.
   Flow also requests the saved command's parse-only server preview. Statically
   known stages that have not been observed remain selectable: their first fragment
   keeps its marker visible; left-click the marker to toggle its breakpoint,
   or right-click the clause to edit its condition. Continue must stop on the
   stage's first occurrence. Selecting a static stage shows no recorded contexts
   or measured counts or a captured call path; Current restores the actual stop.
   Previous/Next retains the recorded visit from which the static stage was
   selected, including repeated visits to the same invocation. A rejected marker
   edit shows translated server feedback in its tooltip. Failed parse previews
   retry once on a later snapshot; an in-flight preview keeps its request ID.
   `Not executed yet`,
   `Filtered out` after a measured zero-output filter, and `Stage data unavailable`
   for missing/truncated evidence remain distinct. Changed saved text or
   unavailable previews must never retarget a recorded invocation's suffix.
3. After the terminal command executes, the completed record has one execution
   and one success. Before that observation, terminal values may be unmeasured;
   do not display them as measured zero.
4. Repeat with a condition that matches neither stand. The completed filter has
   measured zero output and no terminal execution. For nested/conditional function
   cases, use the native fixture tests below and inspect their chronology.

Created contexts are green, removed contexts are red, and changed contexts are
purple in both the inspector and world markers. Incomplete or
truncated lineage must retain its warning/unknown state. Do not infer edges from
similar UUIDs/positions or replay a command to reconstruct its effects.

Command wrapping grows measured prefixes only far enough to bracket each line,
preserving spaces, code points, font measurements, part identity and raw highlight
offsets. Flow and raw layouts retain one current result, invalidated by command and
highlights, immutable flow/preview identity, width/row mode, font, Language reload,
and Unicode/Japanese font options. Warning excerpts use at most 256 UTF-16 units
without splitting surrogate pairs; expanded warning lists use at most 8,192 characters
with visible omission counts. Captured command and warning evidence remains intact.
Unavailable or unrepresentable stage identities create no navigation nodes or actions;
valid sparse indices and single-stage aliases retain their exact mapping.

Focused checks are `CommandFlowLayoutTest`, `BreakpointTargetPolicyTest`,
`CommandPanelTest` and `DebuggerNavigationTest`. Native acceptance still requires
`FlowBreakpointInteractionGameTest`, `DebuggerPresentationGameTest`, and long-command,
highlight/font-reload and forged-index presentation checks. Headless checks do not
establish native pixels or malicious-server timing.

## Code entry points

- [BuildContextsMixin](../../../src/main/java/works/nuty/codon/mixin/BuildContextsMixin.java), [CommandTrace](../../../src/main/java/works/nuty/codon/adapter/CommandTrace.java): native execution instrumentation.
- [ExecutionFlowRecorder](../../../src/core/java/works/nuty/codon/core/service/ExecutionFlowRecorder.java): recorded stages and lineage.
- [ExecutionFlowTimeline](../../../src/client/java/works/nuty/codon/client/state/ExecutionFlowTimeline.java), [ClientDebuggerState](../../../src/client/java/works/nuty/codon/client/state/ClientDebuggerState.java): selection and chronology.
- [CommandPanel](../../../src/client/java/works/nuty/codon/client/ui/CommandPanel.java): command, stage and call-path presentation.

## Choose verification

Server publication checks live `COMMANDS_OWNER` permission and connection state before
sending pause snapshots, completed flows, or either breakpoint representation. Join
synchronization applies the same boundary before reading the joining player's saved
watches. Channel support alone grants no debugger access. Step/Continue go only to
owners; other connected clients receive the empty terminal Resume cleanup so those
transitions do not enable freecam. Terminal cleanup also reaches revoked owners.
Owners retain legacy channel fallback and complete paged watch restoration, including
the empty-list page. A player promoted after joining receives the same authorized
initial synchronization on the next server tick, including the handshake that
enables watch saving. Revocation preserves only the completed watch handshake so
re-promotion cannot replace local watch edits; other initial state is refreshed
once authorized again. All sends still check live owner permission. Disconnect
and server shutdown clear every initialization flag.
Each supported channel initializes once per authorized connection; a channel which
becomes available later initializes without resending the others. Promotion polling
waits while execution is parked because server ticks stop; `/codon resume` remains
available to authorized command senders, and synchronization follows the next tick.
If a live or join-time pause exceeds the wire collection limits, no partial snapshot
or watch-change page is sent. Owners receive terminal presentation cleanup and a
localized warning explaining that the server remains paused and `/codon resume`
continues execution. This does not truncate or remap source indices.

| Concern | Existing tests |
| --- | --- |
| Recorded inputs/outputs/lineage | `coreTest`: `ExecutionFlowRecorderTest` |
| Trace adapter | `test`: `CommandTraceTest` |
| Owner-only live and JOIN publication, revoked/disconnected recipients, legacy channels and terminal cleanup | `test`: `DebuggerRecipientAuthorizationTest`; authorized JOIN persistence: `DebuggerWatchPersistenceGameTest` |
| Recorded navigation/layout | `clientTest`: `ClientExecutionFlowTimelineTest`, `ClientCommandSelectionTest`, `CommandFlowLayoutTest` |
| BP/stage Tab order, wrapped reveal, pending-toggle focus, direct exact condition editors, navigation-only breakpoint list | `FlowBreakpointInteractionGameTest`; `test`: `DebuggerNavigationTest` |
| Native branching/filtering and stage stops | `DebuggerExecutionFlowGameTest` |
| Native `if function` / `unless function` chronology | `DebuggerConditionalFunctionFlowGameTest` |
| Flow options/markers for never-observed stages, rejection feedback, recovered previews, then first-occurrence terminal and conditional stops | `DebuggerUnobservedFlowBreakpointGameTest`; `clientTest`: `ClientUnobservedFlowSelectionTest`, `ClientFlowPreviewRequestsTest`, `CommandFlowLayoutTest` |
| Continuations and incomplete-record warnings | `DebuggerContinuationRecordingGameTest` |
| Rendered command/context UI with injected data | `DebuggerPresentationGameTest` |

Example: `./gradlew runClientGameTest -PclientGameTest=DebuggerExecutionFlowGameTest`.
The conditional-function test concerns Minecraft function conditions; conditional
breakpoint coverage is listed in [Breakpoints](breakpoints.md). Keep native-flow
assertions separate from screenshots produced by synthetic presentation fixtures.

Condition/navigation follow-up: Flow's footer condition button is removed. Right-
click a breakpoint marker (or Shift+F10 on that focused marker) to open its exact
condition editor directly. Clauses and the selected detail band retain their
condition menu, without a line-versus-stage chooser. Right-clicking a future
stage does not select it or alter displayed contexts. Saved disabled breakpoints and unset breakpoint-capable stages
stay visible as neutral hollow circles/diamonds, including run and terminal function
stages. No definition is created until the existing marker action is activated.
Only the first fragment of each exact parsed/recorded stage owns its marker; wrapped
continuations do not invent additional targets. Unparsed suffixes do not invent stage targets. A separate whole-command marker
now precedes the command root/execute prefix when the command has multiple stages;
a one-stage command has only its line marker. Parsed stage count, not a literal
execute check, decides this mapping. The actual pixels in the supplied Library screenshot
`Screenshot 2026-10-03 at 15.59.01.png` were inspected before this change. Actual pause amber and functionally necessary inspection
selection remain distinct. The Active breakpoint list selects an exact matching
Flow/stage and reveals it without sending breakpoint edits; unavailable destinations
remain listed with an explanation.

Flow keyboard traversal follows visual reading order: the whole-command marker
comes first when present, then each stage's marker immediately precedes its clause
and optional warning. Tab and Right follow this order through wrapped rows;
Shift+Tab and Left reverse it. Up/Down move between visual rows. Tab leaves Flow at
the last control, and Shift+Tab leaves at the first; entering Flow starts at the
corresponding edge. Other debugger regions keep their existing Tab behavior.
Off-screen controls reveal their row, traversal skips pending/disabled markers, and
wrapped continuations do not add breakpoint markers. Warning focus IDs use the
recorded stage index so sparse recordings do not create phantom targets.
Shift+F10 continues to use the focused control's exact condition target and return
focus to that control. Single-stage line mapping, Source, Watch and pause colors
are unchanged.

Toggling a focused breakpoint with Enter/Space keeps focus on that exact target
while the request is pending and after its server update. Its disabled button and
focus caret remain present; it cannot accept another toggle until ready. Focus
identity includes the invocation and complete breakpoint target (location, stage
and command fingerprint), rather than the recorded-stage list position or whether
the stage has been observed. Explicit traversal or a different clicked control
replaces that focus immediately; acknowledgements never restore an older target.
The direct condition editor returns focus to its opening marker only while its
source/flow context remains current. Opening or cancelling does not save a
breakpoint.

Regression follow-up: `FlowBreakpointInteractionGameTest` exercises actual screen
events with synthetic observations, including unset/disabled run and function
markers, Tab/Shift+Tab through wrapped rows, Enter/Space pending/acknowledgement
focus, direct marker editors and cancel/no-creation, and exact navigation-list
destinations. `DebuggerNavigationTest` separately replaces widget instances while
an edit is pending and verifies that explicit keyboard or pointer navigation wins
over a later acknowledgement. Both passed in the [UI validation run](../ui-polish-validation.md),
along with the focused Flow layout and keyboard checks. These fixtures do not
establish server execution; the validation record lists remaining acceptance work.

`DebuggerUnobservedFlowBreakpointGameTest` waits for both the acknowledged disabled
definition and its rendered marker to become active before opening conditions.
A controlled no-op save acknowledgement between renders reproduces the stale
pending button deterministically without changing the server's definitions. The
bounded readiness wait preserves the active-button and accepted-click assertions;
the rest of the test still checks real server edits and first-occurrence stops.
