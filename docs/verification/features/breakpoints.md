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


The condition layer exposes all nine kinds as a single icon strip; count icons
carry a tally badge to distinguish them from created/removed/changed events.
The selected kind's name stays below the strip. Count conditions expose six
comparison symbols and a numeric field directly; events hide both. Confirm,
close and delete use distinct icons with localized tooltip/narration labels.
Pointer hints retain the shared hover delay. The 320×240 count/event captures in
`DebuggerBreakpointUiGameTest` check compact layout, invalid count rejection,
real acknowledged count-condition saving and switching back to an event.
