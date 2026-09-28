# Stepping

## User path and expected result

Use the [shared setup](../README.md#prepare-and-launch) and place an impulse command
block pointing into an unconditional, always-active chain command block. Give them
`say first` and `say second`. Add a breakpoint to the first, then trigger it once.

1. At the stop, press `V`. Inspect the current command, call path and execution
   contexts. The world remains paused while navigating the UI.
2. `F9` (Into) advances to the next command stage, including deeper function calls.
   One step must not silently execute the whole remaining chain.
3. Repeat the fixture with `F8` (Over): the next stop is at the same or shallower
   call depth. To distinguish Over from Into, use a loaded function that calls
   another function; record those functions' text with the evidence.
4. From inside that call, `Shift+F9` (Out) stops after returning to a shallower
   depth. Root-level Out is satisfied by the final execution-complete stop.
5. `F7` continues until another breakpoint or execution end. A completed step
   offers a final inspection stop; continuing from it releases the execution.

The controls must work from the cursor screen as well as the world view, follow
remapped key hints and reject duplicate actions while a request is pending.
After completion, current-stop controls must not act on a historical snapshot.
The command panel's Current action is disabled at the live command; select a
historical visit before testing keyboard focus and navigation from that action.
Camera retention has a separate [freecam](freecam.md) contract.

Shared debugger buttons use a light outline for keyboard focus, distinct from
teal selection and hover accents. Tab/arrow navigation exposes icon labels and
truncated text beside the focused button, without requiring pointer hover.
Pointer tooltips wait 350 ms on shared buttons, while keyboard descriptions remain
immediate. Fully visible labels are not repeated in tooltips; icon labels,
clipped text and additional explanations remain available. Watch rows use their
explicit details rather than appending a second generated inspection label.
Borderless controls also show a focus outline; pending controls keep their
position but show the unavailable cursor while input is blocked. Verify these
states with keyboard navigation and pointer hover; the breakpoint UI test also
captures a selected row alongside a keyboard-focused, truncated action label.

## Code entry points

- [StepController](../../../src/core/java/works/nuty/codon/core/service/StepController.java), [DebuggerEngine](../../../src/core/java/works/nuty/codon/core/service/DebuggerEngine.java): call-depth and execution lifetime.
- [McExecutionController](../../../src/main/java/works/nuty/codon/adapter/McExecutionController.java), [CodonCommand](../../../src/main/java/works/nuty/codon/command/CodonCommand.java): server pause/control path.
- [InputManager](../../../src/client/java/works/nuty/codon/client/input/InputManager.java), [ClientDebuggerState](../../../src/client/java/works/nuty/codon/client/state/ClientDebuggerState.java), [DebuggerStatus](../../../src/client/java/works/nuty/codon/client/ui/DebuggerStatus.java): input, pending control and UI enablement.

## Choose verification

| Concern | Existing tests |
| --- | --- |
| Depth, chain boundaries and completion | `coreTest`: `StepControllerTest`, `CommandBlockSteppingTest`, `DebuggerEngineTest` |
| Pending state, current/history separation | `clientTest`: `ClientDebuggerStateTest`, `ClientHistoricalCallStackTest`, `DebuggerStatusTest` |
| Native command-chain execution/stage recording | `DebuggerExecutionFlowGameTest` |
| Native conditional function chronology | `DebuggerConditionalFunctionFlowGameTest` |
| Client camera across server-sent step/pause packets | `DebuggerFreecamStepGameTest` |
| Keyboard focus/navigation | `DebuggerKeyboardNavigationGameTest` |

Example: `./gradlew coreTest --tests '*CommandBlockSteppingTest'`.
For runtime execution changes, select the relevant native GameTest as well. A
camera test that sends synthetic packets does not establish command-step semantics.
Record the before/after command and call depth, not merely that the screen opened.
