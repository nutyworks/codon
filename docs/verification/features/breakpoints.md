# Breakpoints

## User path and expected result

Use the [shared setup](../README.md#prepare-and-launch). In a disposable Creative
world, obtain a command block with `/give @s minecraft:command_block`, place it,
enter `say codon breakpoint check`, save with Done, and attach a button.

1. Aim at the block and press `F10`, or reopen its editor and click the whole-command
   marker to the left of the input. Close the editor. The block is marked as a
   breakpoint; pressing its button pauses before the command executes.
2. Press `V` to interact with the debugger. The current stop is distinguished from
   other breakpoints. Continue with `F7`; the command executes.
3. Reopen the editor and toggle the marker off/on. Existing conditions are preserved
   by a toggle; deletion is a separate action in breakpoint options.
4. For stage conditions, save this command in the block first:
   `execute as @a if entity @e[tag=codon_verify_absent] run say unreachable`.
   Ensure no entity has that tag. Hover a stage boundary and click its marker;
   right-click the marker to edit its condition in a modal layer above the current
   screen. The original editor/source/list stays visible; Save, Cancel, Escape or
   an outside click dismisses only the layer, preserving the underlying input and
   navigation state. Mouse and keyboard input must not reach the screen below.
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
enabled breakpoints remain visible. The management list, toolbar count and source
stage summaries include only enabled breakpoints. Disabling preserves the saved
condition; hover/focus its original marker to enable it again. The whole-command
marker at the front of the command-block editor is always visible, including when
unused or disabled. Text selection in the
wrapped editor must not toggle a marker, and soft wrapping must not change the
stored command. Server acknowledgement determines the displayed breakpoint state.
Check persistence by leaving/reopening the world after saving a definition.

Management rows align their labels to the left and retain a teal selected
surface so the target of the bottom action buttons is visible after the pointer
moves away. Scrolling applies only over the list rows, not over the title,
actions, or surrounding world. The condition editor repeats its command fragment
in a tooltip only when the visible fragment is clipped. In
`DebuggerBreakpointUiGameTest`, the `hover-close` capture should show no redundant
label tooltip, while `hover-condition` retains the full clipped label after a
short hover. The keyboard capture keeps immediate access to the same label.

Function-line and stage targets use the [Source viewer](function-source.md).
Saved line hover shows its count, click toggle and right-click condition hints
on separate localized lines; see [tooltip coverage](tooltips.md) for wrapping,
viewport placement and the rendering checks.
The command alternatives are `/codon breakpoint block <x> <y> <z>` and
`/codon breakpoint function <namespace:path> <line>` (one-based file line).
They toggle whole-command targets; use the UI for stage/condition editing.

## Code entry points

- [InputManager](../../../src/client/java/works/nuty/codon/client/input/InputManager.java): F10 target and command dispatch.
- [Command-block editor mixin](../../../src/client/java/works/nuty/codon/mixin/client/AbstractCommandBlockEditScreenMixin.java), [WrappedCommandEditBox](../../../src/client/java/works/nuty/codon/client/ui/WrappedCommandEditBox.java), [BreakpointUi](../../../src/client/java/works/nuty/codon/client/ui/BreakpointUi.java): marker layout and input.
- [BreakpointConditionScreen](../../../src/client/java/works/nuty/codon/client/ui/BreakpointConditionScreen.java), [ScreenLayers](../../../src/client/java/works/nuty/codon/client/ui/ScreenLayers.java), [ClientBreakpointState](../../../src/client/java/works/nuty/codon/client/state/ClientBreakpointState.java): modal options, input isolation, pending edits and acknowledgement.
- [BreakpointRegistry](../../../src/core/java/works/nuty/codon/core/service/BreakpointRegistry.java), [BreakpointConditionEvaluator](../../../src/core/java/works/nuty/codon/core/service/BreakpointConditionEvaluator.java), [DebuggerEngine](../../../src/core/java/works/nuty/codon/core/service/DebuggerEngine.java): definition and stop semantics.
- [WorldBreakpointPersistence](../../../src/main/java/works/nuty/codon/persistence/WorldBreakpointPersistence.java): world storage.

## Choose verification

| Concern | Existing tests |
| --- | --- |
| Definition/condition logic | `coreTest`: `BreakpointRegistryTest`, `BreakpointConditionEvaluatorTest`, `DebuggerEngineTest` |
| Acknowledgement and pending UI state | `clientTest`: `ClientBreakpointStateTest` |
| Persistence/preview codec | `test`: `WorldBreakpointPersistenceTest`, `BreakpointStagePreviewPayloadTest` |
| Native editor input, modal details without screen replacement, server edits, wrapping and narrow layouts | `DebuggerBreakpointUiGameTest` |
| Single-stage editor/Flow target, legacy toggle/clear and native first-occurrence stop | `SingleStageBreakpointGameTest` |
| Inactive condition marker retention, menus/Cancel and server-acknowledged Save enabling | `BreakpointConditionVisibilityGameTest`, `DebuggerBreakpointUiGameTest` |
| Flow legacy condition labels, rejected toggle feedback, terminal condition attribution and pending action gating (presentation fixture) | `FlowLegacyConditionGameTest` |
| Native execution and measured-zero result breakpoints | `DebuggerBreakpointResultGameTest` |

Example: `./gradlew runClientGameTest -PclientGameTest=DebuggerBreakpointUiGameTest`.
Inspect `*codon-breakpoint-*.png` in the shared screenshot directory. The UI test
checks editing and transport; use the result test or manual trigger path to prove
the execution actually pauses. Record manual world reload separately from the
file-adapter unit test.

When chaining edits in a client GameTest, wait for both the server acknowledgement
and the next control's enabled state. A received snapshot can precede the frame
that enables Undo or an inline marker; sending input in that interval tests a
disabled control instead of the intended follow-up action.

Disable a whole-command breakpoint and a conditional stage breakpoint, then
reopen the command-block editor. The whole-command marker must remain visible;
the stage marker must be hidden until hovered or keyboard-focused, then hide again
when hover/focus leaves. Both definitions must
also disappear from an already-open management list. The stage condition must
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
first, then the layer. Tab closes the menu and continues through the form.

The exact edited marker remains visible while the layer or its menus are open. Opening
and cancelling preserve its saved enabled state; Save always enables the exact definition
with the chosen condition. A sole legacy stage-zero definition uses the line's marker
without creating a second whole-line target. Flow's selected-condition action and summary
use the same effective saved definition as its inline marker. Pending edits to a matching
legacy definition disable the action and prevent opening another editor.

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
[legacy line-control rules](function-source.md) for condition collisions, disabling and Clear.
`SingleStageBreakpointGameTest` checks actual acknowledged editor edits, a real vanilla
single-stage command stop, the Flow line target, disabled legacy condition access, one execution
on Continue, and the public Clear command. It also checks missing/LOADING preview actions,
then READY condition Save and toggle against the same legacy target. Its server/world is disposable.
