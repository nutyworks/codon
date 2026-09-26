# Freecam

## User path and expected result

Use the [shared setup](../README.md#prepare-and-launch) and stop at a command-block
breakpoint. Record the current Keep Freecam While Running setting before changing it.

1. Enter the first breakpoint from first person. The initial viewpoint must stay
   at the player's eyes without the head/body obscuring the world. Back away and
   verify that the stationary body becomes visible. While paused, move the detached camera with the movement keys; jump/sneak move
   vertically and sprint accelerates. The player body stays at its paused position.
   Gameplay/inventory actions must not manipulate the world through the camera.
2. Press `V` to enter cursor mode. Open screens stop camera motion. Close cursor
   mode and verify movement resumes without leaving a stuck key.
3. Select an execution context and use **Move camera to context**. The camera
   moves to the recorded execution anchor/facing. It does not teleport the player
   or follow the executor's later live position. Unavailable/other-dimension
   targets have a disabled action with an explanation.
4. Move to a recognizable viewpoint, then step and continue into a later breakpoint.
   The viewpoint survives the transition; the player's first-person hand stays
   hidden while the camera is detached.
5. With Keep Freecam While Running off, finish the execution and leave its final
   inspection stop. The original player camera/perspective returns.
6. Repeat with the preference on (`G`). While the camera is retained during running,
   the player body must resume physics and server synchronization. Turn the
   preference off to restore the player camera. Disconnect/rejoin must not retain
   a camera entity from the old world. Restore the original preference afterward.

For mounted-player changes, repeat the relevant interaction on a vehicle. Record
both camera motion and player/vehicle behavior; a screenshot cannot prove packet
isolation or an absence of motion over time.

## Code entry points

- [DebuggerFreecam](../../../src/client/java/works/nuty/codon/client/camera/DebuggerFreecam.java): camera lifetime and recorded-anchor navigation.
- [InputManager](../../../src/client/java/works/nuty/codon/client/input/InputManager.java), [ClientDebuggerState](../../../src/client/java/works/nuty/codon/client/state/ClientDebuggerState.java): controls, pause and advancement state.
- [Client mixin registration](../../../src/client/resources/codon.client.mixins.json): input, player, vehicle and rendering hooks.

## Choose verification

| Concern | Existing tests |
| --- | --- |
| Pause/advancement state | `clientTest`: `ClientDebuggerStateTest` |
| Camera movement, input and player isolation | `DebuggerFreecamGameTest` |
| Mounted vehicle boundary | `DebuggerMountedFreecamGameTest` |
| Viewpoint across step/pause packets | `DebuggerFreecamStepGameTest` |
| Retained camera and running-player state | `DebuggerFreecamResumeGameTest` |
| World changes reaching paused client | `DebuggerWorldSyncGameTest` |
| Paused clocks, chat, sound, temporary UI hiding | `DebuggerClientPauseGameTest`, `DebuggerChatPauseGameTest`, `DebuggerSoundPauseGameTest`, `DebuggerPeekUiGameTest` |

Example: `./gradlew runClientGameTest -PclientGameTest=DebuggerFreecamResumeGameTest`.
Several camera tests inject snapshots or server-sent pause/step packets. They
exercise real client hooks but do not establish native breakpoint execution; pair
with the relevant [stepping](stepping.md) scenario when that path changes.
The sound test needs a working OpenAL output device (CI supplies a virtual sink).
Manual video/interaction remains necessary for reported jitter or visual clipping.
