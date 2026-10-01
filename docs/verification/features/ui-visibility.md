# Debugger UI visibility

## User path and expected result

Use the [shared setup](../README.md#prepare-and-launch) and pause at a breakpoint.
The default binding is `H`; the same gesture works in world and debugger cursor mode.

1. Press the binding: debugger panels, HUD labels and world markers disappear immediately.
   The vanilla game HUD remains visible. Hidden panels cannot receive pointer or focused-button actions.
2. Release before 250 ms: toggle visibility. A second short press shows the debugger again.
3. Hold for at least 250 ms, then release: restore the visibility from before the press.
   A long press while already hidden keeps the debugger hidden. Key repeats do not toggle or restart the timer.
4. Type the binding in chat or a focused text field: it must not hide the debugger.
5. Change screens, leave the window, rebind/unbind the key, or disconnect/rejoin:
   visibility resets to shown. Releasing a cancelled gesture cannot toggle it.
   Regaining focus while still holding a mouse button must not restart hiding.
   If its release was missed outside the window, the first fresh click must work.
6. Resume and step normally: hiding does not send a debugger control request or change
   client/server pause snapshots. Visibility is session state and is not saved to settings.

## Code entry points

- [UiHideGesture](../../../src/client/java/works/nuty/codon/client/input/UiHideGesture.java): monotonic press/release classification and cancelled gestures.
- [InputManager](../../../src/client/java/works/nuty/codon/client/input/InputManager.java): binding, typing guard and lifecycle reset.
- [DebugLevelRenderer](../../../src/client/java/works/nuty/codon/client/render/DebugLevelRenderer.java): submits Codon's markers at `BEFORE_GIZMOS`, before the current frame is finalized. Skipping submission when hidden leaves vanilla and other mods' gizmos intact.
- [CodonScreen](../../../src/client/java/works/nuty/codon/client/ui/CodonScreen.java): hidden interaction suppression.
- [Client mixins](../../../src/client/resources/codon.client.mixins.json): native keyboard/mouse events and screen transitions.

## Choose verification

| Concern | Existing tests |
| --- | --- |
| Short/long boundary, repeated events and cancellation | `clientTest`: `UiHideGestureTest` |
| Native keyboard/mouse dispatch, screen input, rebind, chat, missed mouse release, focus flag and rejoin | `DebuggerPeekUiGameTest` |
| Real server breakpoint stays paused, then each command executes once after Resume | `DebuggerFreecamResumeGameTest` |
| First hidden/restored frame and sustained holds in world/cursor mode at a real entity-context pause; running breakpoint outlines, unrelated gizmos, disconnect/rejoin | `DebuggerWorldMarkerVisibilityGameTest` |

```sh
./gradlew clientTest --tests '*UiHideGestureTest'
./gradlew runClientGameTest -PclientGameTest=DebuggerPeekUiGameTest,DebuggerWorldMarkerVisibilityGameTest
```

Inspect the `codon-peek-*` screenshots: compare world and cursor-mode baselines,
held/toggled hidden presentation, and restored UI. The peek fixture injects a client
pause for visual coverage; the freecam resume fixture executes real command blocks.
The focus check sets the window's focused flag because Fabric cancels native focus
callbacks. The mouse regression omits the outside-window release callback and
checks the first fresh press, continued-hold suppression and text-field guard.
Manual window switching remains necessary to observe platform-specific lost releases.
Use real short taps and sustained holds when judging the 250 ms threshold; automated
boundary assertions establish classification, not a user's timing preference.

The world-marker regression records primitives finalized for each native rendered frame,
including the first frame after the keyboard callback. Its `codon-marker-*` PNGs capture
the first completed native frame, without the framework's additional screenshot render.
Compare the turquoise entity ring and amber/red block outlines against the magenta
unrelated-gizmo sentinel. Hidden captures retain the sentinel, vanilla hotbar and chat.
The actual server pause and snapshot must remain unchanged across H gestures, and
the scoreboard command must execute exactly once after Resume. This does not cover
physical focus changes, a separate dedicated server, or the original packaged VM runtime.
