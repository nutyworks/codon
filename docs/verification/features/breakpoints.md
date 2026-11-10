# Breakpoints

## User path and expected result

Conditions are edited in an inline **condition strip**, like an IDE's inline
breakpoint editor. It opens under the Flow stage (in place of Flow's two-line detail
band, so the panel keeps its height), under the Source line (the following lines move
down), or under the command-block editor's input. A right-click on a gutter or stage
marker, Shift+F10 on a focused marker, Shift+Enter on a command-block marker and the
edit action on a docked Breakpoints row open it; there are no marker menus or modal
condition screens. Row 1 reads **Stop when** with a segmented **Always / Event / Count**
control and that mode's controls on the same row: Event chooses created, removed or
changed; Count chooses a metric (In, Out, +, −, Δ, each with a tooltip), a comparison
(click for the next of = ≠ < ≤ > ≥, right-click for the previous) and a whole number
(type it, use −/+, Up/Down or the mouse wheel). Row 2 states the rule as a sentence
(Always stops *before* the stage or line runs; Event and Count stop *after* it), then
for Count the metric's last recorded value for that exact stage (unmeasured and not
recorded are distinct from zero), then **Remove**, **Cancel** and **Save**. A narrow
window drops the "Stop when" label and then clips mode labels before hiding a control.

Save is disabled when nothing would change (the acknowledged definition already has
that rule and is enabled) or the number is invalid; it saves and enables the exact
definition. Remove is enabled only for an acknowledged definition. Both wait for the
server acknowledgement before the strip closes; pending, rejected and unavailable
requests show in the sentence slot (the sentence moves to its tooltip). While a
request is pending every control, Cancel and Esc are inert. Cancel or Esc closes a
clean draft; with unsaved changes the buttons are replaced in place by
"Discard changes?" with **Keep editing** (focused) and **Discard**, and Esc there
keeps editing. Selecting another Flow stage, command or pause, a different Source
document/function, or a changed command-block input closes the strip and drops the
draft. A click outside the strip only moves focus; while a strip control has focus
it receives all keys, so typing and shortcuts do not reach the screen below.

Inline toggle rejections additionally appear in a wrapped Codon toast without
hovering the marker; the marker tooltip retains its detail. A missing edit reply
after ten seconds reports that changes are unconfirmed, without changing the
acknowledged definitions. Hidden markers also expire. Only the matching pending
request may produce a rejection, so late/duplicate replies cannot release a retry.
Notices disappear after six seconds. Save/Remove errors remain in the strip
without a duplicate toast. If a context change closes the strip while an edit is
pending, its rejection or timeout also appears in a toast even if the result
and the context change arrive before the next screen tick.

The breakpoint list is a docked **Breakpoints** panel, opened and closed by the
toolbar breakpoint-count button. In wide layouts it stacks under Watches in the side
column; in compact layouts it takes the shared auxiliary slot and closes the other
panel. Its visibility lasts for the session and is not saved in settings. It only
navigates: row overflow, toggle, condition, delete and Undo actions are removed.
Function entries open their original source line and matching stage; block entries
open an exact matching recorded/static stage in the current pause's Flow. Matching
uses location, stage identity and command fingerprint, never a guessed row index.
Entries lacking an available Flow destination remain visible but disabled with an
explanation; no world teleport or new block-source editor is implemented. Enabled
and disabled saved entries remain listed. Activation sends no breakpoint edits.
Each row also has a hover/focus **edit** action (keyboard: the next column of the
row) that navigates like the row and then opens the condition strip for that exact
definition at its destination. A saved stage 1 on a confirmed single-stage command
navigates to its sole line marker; its edit action is the way to change or remove that
existing definition, since the line marker always edits the line itself (left-click
toggles only the line). The former Saved stage 1 Disable/Enable menu item was removed
with the marker menus; Save enables it again and Remove deletes it. Panel focus
and scroll are retained while it stays open; native behavior still needs manual verification.
Rows sort by source identity with numeric function lines, coordinates and stage indices.
Each row shows state, whole-command/stage identity and location on its first line,
then the condition on a separate line; full location/condition and navigation availability
remain in the tooltip. The narrow English/Korean list captures check this presentation.

Use the [shared setup](../README.md#prepare-and-launch). In a disposable Creative
world, obtain a command block with `/give @s minecraft:command_block`, place it,
enter `say codon breakpoint check`, save with Done, and attach a button.

1. Aim at the block and press `F10`, or reopen its editor and click the whole-command
   marker to the left of the input. Close the editor. The block is marked as a
   breakpoint; pressing its button pauses before the command executes.
2. Press `V` to interact with the debugger. The current stop is distinguished from
   other breakpoints. Continue with `F9`; the command executes.
3. Reopen the editor and toggle the marker off/on. Existing conditions are preserved
   by a toggle; deletion is a separate action in breakpoint options.
4. For stage conditions, save this command in the block first:
   `execute as @a if entity @e[tag=codon_verify_absent] run say unreachable`.
   Ensure no entity has that tag. Hover a stage boundary and click its marker;
   right-click the marker to open the condition strip under the input. The editor
   stays usable around it. A clean Cancel or Escape closes only the strip, preserving
   the input and cursor. Unsaved changes instead show **Discard** or **Keep editing**
   in place; Escape there resumes editing. Pending Save/Remove blocks edits and
   dismissal until ACK or failure; acknowledged success closes directly.
   On the `if` stage, choose **Count**, **Out**, `=` and `0`. Disable the whole-command/other stage breakpoints to isolate
   this case. Trigger the block: it stops after the filter records zero output,
   and the `say` command does not run.
5. Change the text without saving. Markers for the old saved command must not be
   applied to that edited text. Save and reopen to request fresh stage spans.

Plain markers are circles and conditional markers are diamonds. All editor, source,
flow and management-list markers use the same symmetric 9-pixel artwork, with
solid enabled markers and hollow disabled/unused markers instead of font glyphs. Empty affordances
appear on hover/focus; disabled markers also appear only on hover/focus, while
enabled breakpoints remain visible. Flow keeps both saved disabled markers and unset breakpoint-capable stage affordances
visible as neutral hollow circles/diamonds, including run and terminal function stages.
Rendering an unset affordance does not create a saved breakpoint. The
list retains enabled and disabled definitions with an explicit state label. Its header
shows the total definitions and enabled count. The toolbar's short count and source
stage summaries still count enabled breakpoints; the toolbar tooltip explicitly shows
enabled and total counts. `CodonLabelsTest` checks enabled-only, disabled-only and mixed
captions in English/Korean, alongside all Watch-kind prefix permutations.
`CodonLabelsGameTest` retains one mixed snapshot per language at the 320×240 logical
viewport, including the maximum-count header width, representative Watch Details
and rotation. This produces eight screenshots rather than repeating every permutation.
Capacity failures from whole block/function toggles and stage/condition commands retain
client translation keys with a readable English fallback for console/RCON sources
without client language assets. Disabling preserves the saved condition; use the
list to navigate to its source, then hover/focus its original marker to enable it again. The whole-command
marker at the front of the command-block editor is always visible, including when
unused or disabled. Text selection in the
wrapped editor must not toggle a marker, and soft wrapping must not change the
stored command. Server acknowledgement determines the displayed breakpoint state.
Check persistence by leaving/reopening the world after saving a definition.

Navigation-list rows align their labels to the left with neutral hover and keyboard
focus, without a persistent condition-edit selection color. Scrolling applies only over the list rows, not over the title,
actions, or surrounding world. The list does not expose toggle, delete or Undo
controls; its only row action is edit, which opens the strip at the destination.
`DebuggerBreakpointUiGameTest` checks that unavailable destinations cannot mutate
definitions. Removing from the command-block strip keeps the same input and cursor;
the exact marker can then recreate the breakpoint with its default Always condition.

Function-line and stage targets use the [Source viewer](function-source.md).
Saved line hover shows its exact condition, click toggle and right-click condition hints
on separate localized lines; see [tooltip coverage](tooltips.md) for wrapping,
viewport placement and the rendering checks.
The command alternatives are `/codon breakpoint block <x> <y> <z>` and
`/codon breakpoint function <namespace:path> <line>` (one-based file line).
Alone they toggle whole-command targets. Both accept `[stage <n>] [condition <condition>]`
(see the [README](../../../README.md) for the syntax): `stage <n>` uses the editor's one-based
stage number and is refused for single-stage commands; `condition` sets, updates (`created`,
`removed`, `changed`, or a `*_count` with `eq|ne|lt|le|gt|ge <n>`) or removes (`clear`) a condition
and, like the editor's Save, enables the breakpoint. These edits use the editor's server-side
target validation (`BreakpointTargetValidator`): a missing/unloaded command block, unreadable or
macro source, unparsable command, unknown stage, or result condition on the final stage is reported
and changes no definition. `/codon breakpoint list` includes each enabled stage and condition.
Explicit block-coordinate commands retain position-based targets, including future or
unloaded locations, without acquiring a chunk. F10 uses the camera's centre ray up to
20 blocks and shows a localized action-bar hint when that ray misses a block.
Function commands validate new/enabled targets against current loaded raw-file lines:
blank/comment and proven out-of-range lines are rejected, whole macro lines remain
eligible. Missing source or lines beyond the bounded Source response are unknown:
the CLI preserves its position-based toggle with a localized warning followed by the
usual server result; UI edits still require verifiable source. Existing enabled entries can be disabled after
their line changes. `CodonBreakpointCommandTest` verifies command dispatch and these
adapter decisions with mocked server resources, including a real Minecraft-parsed
20,001-command function returned by a mocked function manager. It also covers stage and
condition creation/update/clear, real-registry capacity rejection through Minecraft's
command source and RCON formatter (retained key, readable fallback and unchanged definitions),
rejected input leaving definitions unchanged, the owner
requirement, the loaded command-block path, `list` output and `/help` usage, with a tiny
test-registered `execute` tree instead of vanilla's. It does not execute a
native loaded function or prove execution beyond the Source response limits.

The breakpoint list retains widget identity only for currently rendered controls.
Replacement authoritative snapshots, scrolling and empty/disconnected lists release
obsolete labels, tooltips and actions; unchanged visible targets retain widget identity
and focus restoration. `BreakpointListCacheTest` exercises 100 acknowledged replacement
snapshots, stable widget focus and empty-list eviction. It does not render the native
panel; actual navigation and focus remain a UI acceptance check.

## Code entry points

- [InputManager](../../../src/client/java/works/nuty/codon/client/input/InputManager.java): F10 target and command dispatch.
- [Command-block editor mixin](../../../src/client/java/works/nuty/codon/mixin/client/AbstractCommandBlockEditScreenMixin.java), [WrappedCommandEditBox](../../../src/client/java/works/nuty/codon/client/ui/WrappedCommandEditBox.java), [BreakpointUi](../../../src/client/java/works/nuty/codon/client/ui/BreakpointUi.java): marker layout and input.
- [BreakpointConditionStrip](../../../src/client/java/works/nuty/codon/client/ui/BreakpointConditionStrip.java), [BreakpointConditionDraft](../../../src/core/java/works/nuty/codon/core/model/BreakpointConditionDraft.java), [ClientBreakpointState](../../../src/client/java/works/nuty/codon/client/state/ClientBreakpointState.java): inline condition strip, its draft rules, input routing, pending edits and acknowledgement. Hosts: [CommandPanel](../../../src/client/java/works/nuty/codon/client/ui/CommandPanel.java) (Flow), [FunctionSourceScreen](../../../src/client/java/works/nuty/codon/client/ui/FunctionSourceScreen.java) (Source), the command-block mixin and [BreakpointPanel](../../../src/client/java/works/nuty/codon/client/ui/BreakpointPanel.java) (row edit action).
- [BreakpointRegistry](../../../src/core/java/works/nuty/codon/core/service/BreakpointRegistry.java), [BreakpointConditionEvaluator](../../../src/core/java/works/nuty/codon/core/service/BreakpointConditionEvaluator.java), [DebuggerEngine](../../../src/core/java/works/nuty/codon/core/service/DebuggerEngine.java): definition and stop semantics.
- [WorldBreakpointPersistence](../../../src/main/java/works/nuty/codon/persistence/WorldBreakpointPersistence.java): world storage.
- [CodonCommand](../../../src/main/java/works/nuty/codon/command/CodonCommand.java), [BreakpointEditCommands](../../../src/main/java/works/nuty/codon/command/BreakpointEditCommands.java), [BreakpointTargetValidator](../../../src/main/java/works/nuty/codon/adapter/BreakpointTargetValidator.java): command syntax, stage/condition edits and the target rules shared with the editor's network handler.

## Choose verification

| Concern | Existing tests |
| --- | --- |
| Definition/condition logic | `coreTest`: `BreakpointRegistryTest`, `BreakpointConditionEvaluatorTest`, `DebuggerEngineTest` |
| Strip draft: modes, dirty state, number validation and stepper | `coreTest`: `BreakpointConditionDraftTest` |
| Acknowledgement and pending UI state | `clientTest`: `ClientBreakpointStateTest` |
| Non-hover inline rejection feedback (EN/KO), owner denial and unchanged definitions | `DebuggerRequestFeedbackGameTest` |
| Context change closes a pending strip before an edit failure | `DebuggerBreakpointDetachedGameTest` (native console step; simulated edit result and same-frame handler ordering) |
| Persistence/preview codec | `test`: `WorldBreakpointPersistenceTest`, `BreakpointStagePreviewPayloadTest` |
| Native editor input, strip without screen replacement, draft discard/cancel, pending/rejected saves, server edits, wrapping and narrow layouts | `DebuggerBreakpointUiGameTest` |
| Single-stage editor/Flow target, legacy toggle/clear and native first-occurrence stop | `SingleStageBreakpointGameTest` |
| Saved sole-stage row edit action, Source/Flow strip Remove ACK, loaded function/block stops and subsequent unpaused execution | `SingleStageLegacyManagementGameTest` |
| Exact marker/row strip targets, pending/deleted/stale guards and single-stage destinations | `test`: `SingleStageLegacyManagementTest` |
| Saved v2/v3 sole-stage persistence, disable/delete stop semantics and denied edit permissions | `test`: `WorldBreakpointPersistenceTest`, `BreakpointEditHandlerTest` |
| Inactive condition marker retention, Cancel/discard and server-acknowledged Save enabling | `BreakpointConditionVisibilityGameTest`, `DebuggerBreakpointUiGameTest` |
| Flow legacy/line isolation, exact condition attribution, rejected toggle feedback and pending action gating (presentation fixture) | `FlowLegacyConditionGameTest` |
| Native execution and measured-zero result breakpoints, created through `/codon breakpoint ... stage 2 condition ...` | `DebuggerBreakpointResultGameTest` |
| Unauthorized command-block save does not acquire a chunk; authorized loaded edit succeeds | `DebuggerRequestTransportGameTest` |
| Raw function-line command validation, unavailable/truncated source, existing entry disabling, future coordinates, command stage/condition edits and `list`/help output | `test`: `CodonBreakpointCommandTest` |
| Numeric line/stage/coordinate list order | `clientTest`: `BreakpointListOrderTest` |

Example: `./gradlew runClientGameTest -PclientGameTest=DebuggerBreakpointUiGameTest`.
`DebuggerRequestFeedbackGameTest` sends inline toggle requests for a deliberately
stale native command-block fingerprint and after owner permission is revoked. Its
EN/KO screenshots show the rejection toast with the pointer away from all markers;
the refused target is never added to acknowledged definitions. The same server
rejection is also rendered above Source and the vanilla command-block editor.
Requests use the inline edit API; these captures do not assert marker click routing.
Modal notice
suppression and hidden-marker expiry use the focused client state tests.

Inspect `*codon-breakpoint-*.png` in the shared screenshot directory. The UI test
checks editing and transport; use the result test or manual trigger path to prove
the execution actually pauses. Record manual world reload separately from the
file-adapter unit test.
`SingleStageBreakpointGameTest` also presses native F10 toward empty sky and captures
the no-target action-bar feedback without creating a definition. The UI test's narrow
list captures inspect explicit whole-command/stage labels and separate condition lines,
including `codon-breakpoint-disabled-list-ko-320x240.png` after a Korean resource reload.

When chaining edits in a client GameTest, wait for both the server acknowledgement
and the next control's enabled state. A received snapshot can precede the frame
that enables an inline marker; sending input in that interval tests a
disabled control instead of the intended follow-up action.

Disable a whole-command breakpoint and a conditional stage breakpoint, then
reopen the command-block editor. The whole-command marker must remain visible;
the stage marker must be hidden until hovered or keyboard-focused, then hide again
when hover/focus leaves. Both definitions must
remain visible as disabled entries in an already-open navigation list. The stage condition must
remain intact when enabled again. `DebuggerBreakpointUiGameTest` covers the real
server edit acknowledgements.

In the command-block editor, Tab/Shift+Tab reaches each inline whole-command and
stage marker, including unused markers. Focus reveals the marker and scrolls its
row into view. Enter/Space toggles it; Shift+Enter opens its condition. Narration
announces the target, enabled state, condition and keys, including the default
Always condition on unused markers. Pending edits and dirty
commands must not allow stale actions (`DebuggerBreakpointUiGameTest`).


The strip's rule row holds the mode and that mode's controls; Always shows none,
Event its three events, Count its metric, comparison and number. A mode switch keeps
the other modes' choices, so switching back restores them, and returning to the saved
rule makes the draft clean again. Keyboard: Tab/Shift+Tab and Left/Right move between
controls, Enter/Space activates, Up/Down step the focused number and Enter in the
number field saves.

The exact edited marker remains visible while the strip is open. Opening
and cancelling preserve its saved enabled state; **Save** always enables the exact
definition with the chosen condition, including a previously disabled breakpoint.
Remove is disabled until the exact target exists in acknowledged server state; opening
a new condition draft cannot submit a delete request. `BreakpointConditionVisibilityGameTest`
checks both actions in English/Korean at custom scale, including an unset line beside a
saved legacy stage. Source/Flow line markers use the clicked line
target exactly; a Breakpoints row's edit action uses only that row's existing
definition. Their pending state and Flow summary follow those exact targets. The native command-block editor's
legacy alias handling remains separate from these Source/Flow rules.

`SingleStageLegacyManagementGameTest` loads `codon_test:condition_visibility` from
the server and creates a real command block. It triggers each command with an enabled
saved sole-stage definition, uses its Breakpoints row edit action to open the strip at
the line marker (Source for the function, Flow for the block), removes it and waits
for server ACK. Subsequent execution does not pause, while the separate disabled line
and unrelated enabled breakpoint survive. Inspect `*codon-legacy-function-*.png` and
`*codon-legacy-block-*.png` for the strip and post-removal parent. Persistence reopen is covered separately by the v2/v3
file-adapter test; dedicated-server and physical-input verification remain manual.

`DebuggerBreakpointUiGameTest` drives the command-block strip through native input at
the 320×240 GUI viewport: Event and Save with a real acknowledgement, outside clicks
that keep the strip open, Esc/Cancel on clean and changed drafts, the in-place
discard prompt in English and Korean, an empty number, a real server rejection
(STALE_SOURCE) that keeps the draft, Count with metric/comparison/stepper keys, Remove
with the input and cursor kept, and a controlled pending request at 1280×720 that
keeps every control inside the viewport. `DebuggerUiScaleGameTest` checks that a
vanilla owner draws the strip in game GUI units at a custom Codon scale.

A command with one server-parsed stage offers only its whole-command/line breakpoint in
Source, Flow and the command-block editor. Multiple-stage commands retain separate stage
controls. Existing single-stage saved definitions are kept; see the
[Source target rules](function-source.md) for the changed exact-target behavior.
The legacy alias assertions described below predate this UI change and were not
updated or run.
`SingleStageBreakpointGameTest` checks actual acknowledged editor edits, a real vanilla
single-stage command stop, the Flow line target, disabled legacy condition access, one execution
on Continue, and the public Clear command. It also checks missing/LOADING preview actions,
then READY condition Save and toggle against the same legacy target. Its server/world is disposable.

Condition access: Source and Flow breakpoint marker right-clicks open the exact
condition strip directly, as does Shift+F10 on the current marker. A right-click on
Source text or a Flow clause opens the strip for that line or stage too; there are no
breakpoint context menus. Watch menus are unchanged. The Source header's Line/Stage
condition buttons and Flow's selected-condition footer button are removed. Opening
a menu does not select an unobserved stage or enter a colored condition-edit mode.
Actual pause amber, inspection teal in Flow, breakpoint shapes and enabled state
remain distinct. Source keyboard navigation keeps a neutral line-number cue.
Opening refreshes pending/preview eligibility, and closing restores the marker's
focus; each strip inherits the source/flow validity guard of its owner.
Each precise marker opens one exact condition target; there is no line/stage chooser
and no redirect from a Source/Flow line target to a legacy stage-zero definition.
Saved definitions are not rewritten when the UI renders or navigates.

Opening or cancelling an editor does not send a breakpoint edit. Flow's pending
toggle disables activation while retaining the exact focused marker, and explicit
navigation supersedes it without a later focus-stealing acknowledgement.
`DebuggerNavigationTest` checks widget replacement and late acknowledgements;
`FlowBreakpointInteractionGameTest` covers native screen events for exact targets,
pending focus, direct editor return and navigation-only destinations.
Cross-Flow list navigation resolves its exact marker during the first destination
render, before viewport reveal and visible-widget binding. It also opens hidden Flow
and reaches markers after long wrapped clauses. The request expires after that frame;
world, screen, pause or selection changes and newer keyboard/pointer/scroll navigation
cancel it. Missing or obsolete destinations cannot capture focus in a later frame.
See the [UI validation record](../ui-polish-validation.md) for passing checks and
the boundary between presentation fixtures and real server edits/execution.

Authoritative Source/Flow marker mapping:

| Surface | Marker target |
| --- | --- |
| Source, before the line number | Whole line |
| Source, before a parsed stage | That exact stage |
| Flow, one-stage command | Whole command/line |
| Flow, multi-stage command root (including `execute`) | Whole command/line |
| Flow, each individual stage in a multi-stage command | That exact stage |

The matching server parse preview supplies the stage count; conclusive recorded
evidence is the existing fallback. The code does not classify multi-stage commands
by the literal `execute` name. An 18×18 root target and 19-pixel inset are added
before the first displayed part of a multi-stage command. Its unset/disabled
affordance remains visible, like the stage controls, without creating a definition.

Flow reserves separate 18×18 targets for whole-command/stage markers and warnings,
including compact rows. Warning targets sit beside their clause, with their own
reserved width. Source's whole-line gutter target is 18×18; its right and bottom
edges are half-open, so adjacent line-number and next-row clicks cannot toggle it.
On an actual stopped line the amber `>` cue is excluded from that target: clicking it
only selects the line. `FunctionLineBreakpointGameTest` checks both the cue and marker.
The visible breakpoint artwork keeps its existing size. `FlowBreakpointInteractionGameTest`
checks target bounds and non-overlap at 320×240; `FunctionLineBreakpointGameTest`
checks the expanded gutter corner and adjacent excluded edges, including the
obsolete-stage warning tooltip and click region. These client
fixtures do not establish actual server breakpoint execution.

World outlines still include only enabled whole-block breakpoints. Plain outlines
are thin red; conditional outlines are thicker purple. Disabled and stage-only
breakpoints remain outside this legacy whole-block marker list; an active stop
keeps its amber outline and center point. `DebuggerWorldMarkerVisibilityGameTest`
checks the acknowledged marker list and conditional color in native frames while
paused and running, alongside the existing H-hide and unrelated-gizmo checks.
