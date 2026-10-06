# Breakpoints

## User path and expected result

Deleting from the condition modal waits for server acknowledgement before closing
back to its existing parent (including Source). Pending, rejected and unavailable
requests remain visible in the same modal. The Active breakpoint list now only
navigates: its row overflow, toggle, condition, delete and Undo actions are removed.
Function entries open their original source line and matching stage; block entries
open an exact matching recorded/static stage in the current pause's Flow. Matching
uses location, stage identity and command fingerprint, never a guessed row index.
Entries lacking an available Flow destination remain visible but disabled with an
explanation; no world teleport or new block-source editor is implemented. Enabled
and disabled saved entries remain listed. Activation sends no breakpoint edits.
Source/Flow context menus provide condition editing at the destination. List focus
and scroll are retained on return; native behavior still needs manual verification.
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
   right-click the marker to edit its condition in a modal layer above the current
   screen. The original editor/source/list stays visible. A clean Cancel, Escape or
   outside click dismisses only the layer, preserving the underlying input and
   navigation state. Unsaved condition changes instead require **Discard** or
   **Keep editing**; Escape in that confirmation resumes editing, and outside
   clicks leave it open. Pending Save/Delete blocks edits and dismissal until ACK
   or failure; acknowledged success closes directly. Mouse and keyboard input
   must not reach the screen below.
   On the `if` stage, choose output
   count equal to zero. Disable the whole-command/other stage breakpoints to isolate
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
list retains enabled and disabled definitions with an explicit state label. The toolbar count and source stage summaries
still count enabled breakpoints. Disabling preserves the saved condition; use the
list to navigate to its source, then hover/focus its original marker to enable it again. The whole-command
marker at the front of the command-block editor is always visible, including when
unused or disabled. Text selection in the
wrapped editor must not toggle a marker, and soft wrapping must not change the
stored command. Server acknowledgement determines the displayed breakpoint state.
Check persistence by leaving/reopening the world after saving a definition.

Navigation-list rows align their labels to the left with neutral hover and keyboard
focus, without a persistent condition-edit selection color. Scrolling applies only over the list rows, not over the title,
actions, or surrounding world. The condition editor repeats its command fragment
in a tooltip only when the visible fragment is clipped. The navigation-only list
does not expose condition, delete or Undo controls. `DebuggerBreakpointUiGameTest`
checks that unavailable destinations cannot mutate definitions. Deleting from a
condition editor returns to the same parent input and cursor; the exact marker can
then recreate the breakpoint with its default Always condition.

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
condition creation/update/clear, rejected input leaving definitions unchanged, the owner
requirement, the loaded command-block path, `list` output and `/help` usage, with a tiny
test-registered `execute` tree instead of vanilla's. It does not execute a
native loaded function or prove execution beyond the Source response limits.

The active list retains widget identity only for currently rendered controls.
Replacement authoritative snapshots, scrolling and empty/disconnected lists release
obsolete labels, tooltips and actions; unchanged visible targets retain widget identity
and focus restoration. `BreakpointListCacheTest` exercises 100 acknowledged replacement
snapshots, stable widget focus and empty-list eviction. It does not render the native
Screen; actual list navigation and focus remain a UI acceptance check.

## Code entry points

- [InputManager](../../../src/client/java/works/nuty/codon/client/input/InputManager.java): F10 target and command dispatch.
- [Command-block editor mixin](../../../src/client/java/works/nuty/codon/mixin/client/AbstractCommandBlockEditScreenMixin.java), [WrappedCommandEditBox](../../../src/client/java/works/nuty/codon/client/ui/WrappedCommandEditBox.java), [BreakpointUi](../../../src/client/java/works/nuty/codon/client/ui/BreakpointUi.java): marker layout and input.
- [BreakpointConditionScreen](../../../src/client/java/works/nuty/codon/client/ui/BreakpointConditionScreen.java), [ScreenLayers](../../../src/client/java/works/nuty/codon/client/ui/ScreenLayers.java), [ClientBreakpointState](../../../src/client/java/works/nuty/codon/client/state/ClientBreakpointState.java): modal options, input isolation, pending edits and acknowledgement.
- [BreakpointRegistry](../../../src/core/java/works/nuty/codon/core/service/BreakpointRegistry.java), [BreakpointConditionEvaluator](../../../src/core/java/works/nuty/codon/core/service/BreakpointConditionEvaluator.java), [DebuggerEngine](../../../src/core/java/works/nuty/codon/core/service/DebuggerEngine.java): definition and stop semantics.
- [WorldBreakpointPersistence](../../../src/main/java/works/nuty/codon/persistence/WorldBreakpointPersistence.java): world storage.
- [CodonCommand](../../../src/main/java/works/nuty/codon/command/CodonCommand.java), [BreakpointEditCommands](../../../src/main/java/works/nuty/codon/command/BreakpointEditCommands.java), [BreakpointTargetValidator](../../../src/main/java/works/nuty/codon/adapter/BreakpointTargetValidator.java): command syntax, stage/condition edits and the target rules shared with the editor's network handler.

## Choose verification

| Concern | Existing tests |
| --- | --- |
| Definition/condition logic | `coreTest`: `BreakpointRegistryTest`, `BreakpointConditionEvaluatorTest`, `DebuggerEngineTest` |
| Acknowledgement and pending UI state | `clientTest`: `ClientBreakpointStateTest` |
| Persistence/preview codec | `test`: `WorldBreakpointPersistenceTest`, `BreakpointStagePreviewPayloadTest` |
| Native editor input, modal details without screen replacement, draft discard/cancel, pending/rejected saves, server edits, wrapping and narrow layouts | `DebuggerBreakpointUiGameTest` |
| Single-stage editor/Flow target, legacy toggle/clear and native first-occurrence stop | `SingleStageBreakpointGameTest` |
| Inactive condition marker retention, menus/Cancel and server-acknowledged Save enabling | `BreakpointConditionVisibilityGameTest`, `DebuggerBreakpointUiGameTest` |
| Flow legacy/line isolation, exact condition attribution, rejected toggle feedback and pending action gating (presentation fixture) | `FlowLegacyConditionGameTest` |
| Native execution and measured-zero result breakpoints, created through `/codon breakpoint ... stage 2 condition ...` | `DebuggerBreakpointResultGameTest` |
| Unauthorized command-block save does not acquire a chunk; authorized loaded edit succeeds | `DebuggerRequestTransportGameTest` |
| Raw function-line command validation, unavailable/truncated source, existing entry disabling, future coordinates, command stage/condition edits and `list`/help output | `test`: `CodonBreakpointCommandTest` |
| Numeric line/stage/coordinate list order | `clientTest`: `BreakpointListOrderTest` |

Example: `./gradlew runClientGameTest -PclientGameTest=DebuggerBreakpointUiGameTest`.
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


The compact condition layer places the selected kind, comparison and numeric
value on one row. Events and Always hide the comparison/value and expand the kind
selector. Both selectors open on a short hover (180 ms), a click, or Enter/Space
or an arrow key while focused. Hovering never changes the condition. Clicking an
already-open selector closes its menu after a 250 ms opening guard; clicks during
that guard, including just after hover-open, keep it open. Closing preserves the
draft selection and consumes the click. The menu stays closed while the pointer
remains on that selector, but an explicit click can reopen it immediately. The menu
stays open while crossing the gap from its trigger and closes after the pointer
leaves both for 220 ms. Re-entering the selector after that automatic close must
open it again, including before another render observes the outside pointer.
Clicking an option applies it to the draft and closes the
menu; Save still waits for server acknowledgement. Escape closes an open menu
first, then requests closing the layer (with discard confirmation for a dirty
draft). Tab closes the menu and continues through the form.

The exact edited marker remains visible while the layer or its menus are open. Opening
and cancelling preserve its saved enabled state; Save always enables the exact definition
with the chosen condition. Source/Flow marker menus now use the clicked target
exactly: a line marker never opens an old stage-zero definition. Their pending state
and Flow summary follow that same exact target. The native command-block editor's
legacy alias handling remains separate from these Source/Flow rules.

The menu opens above or below its trigger according to available space, with a
scrollbar when the viewport cannot hold every row. Mouse wheel and Up/Down reach
all nine kinds and all six comparisons; menu input must not reach covered form
controls. Full condition names and existing icons appear in the list, with short
localized names in the compact count selector. Save has a visible text label.
Panel height follows wrapped help and server feedback instead of reserving an
empty comparison row or feedback area. The 320×240 hover-menu/count/event captures
in `DebuggerBreakpointUiGameTest` check bounds, hover-only opening, guarded and
delayed trigger toggles for both selectors, repeated reopen/close, switching
selectors mid-interaction, preserved draft values, Tab dismissal, leaving and
crossing the menu gap, scrolling, keyboard selection/Escape, invalid count
rejection, real acknowledged count-condition saving and switching back to an event.
The closed-menu baseline moves the native cursor outside the resized layer and
waits beyond the hover-open delay before asserting that choices are hidden; the
following hover check then deliberately enters the selector. Reopening after an
outside-click dismissal first waits for the parent editor's marker layout to be
ready, keeping the visibility and hit-target assertion before the native click.
The anchored pending capture uses a controlled client pending state to check that
feedback growth keeps the panel and its Save button inside the viewport.
Saving after a resize uses native mouse dispatch, so the same scenario also
checks that the layer's input and rendering callbacks remain attached to
Fabric's newly created per-screen events.

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
condition editor directly, as does Shift+F10 on the current marker. Source text,
Flow clauses and Flow's selected detail band retain the shared bounded
`DebuggerContextMenu`; Watch menus are unchanged. The Source header's Line/Stage
condition buttons and Flow's selected-condition footer button are removed. Opening
a menu does not select an unobserved stage or enter a colored condition-edit mode.
Actual pause amber, inspection teal in Flow, breakpoint shapes and enabled state
remain distinct. Source keyboard navigation keeps a neutral line-number cue.
Menus refresh pending/preview eligibility before acting, consume dismissal clicks
and restore focus; new condition editors inherit source/flow validity guards.
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
