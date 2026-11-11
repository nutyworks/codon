# Debugger UI visibility

## User path and expected result

Use the [shared setup](../README.md#prepare-and-launch) and pause at a breakpoint.
The default binding is `H`; the same gesture works in world and debugger cursor mode.
While running, the empty Contexts section explains how to set a command-block or Source
line breakpoint and enter cursor mode using the current key bindings. The Source
label is preceded by the toolbar's Source icon, drawn in the wrapped text at any
UI scale, so the button is easy to find. Help → Basics
also provides the existing getting-started workflow with live binding labels.

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

The running idle badge (no screen, nothing paused) is shown by default and yields to
the vanilla F3 debug screen even when its preference says visible. **View → Idle badge**,
reached in cursor mode, saves `idleBadgeVisible` in `config/codon.json`: a missing field
means shown, and a non-boolean value is rejected like the adjacent settings, leaving the
file untouched. Hiding the badge changes nothing else: paused panels, cursor mode, world
markers, execution state and the H gesture keep their existing visibility. The preference
survives state reset, disconnect and restart; H remains the temporary session gesture and
is never saved. The Command workspace
reserves 60 vanilla GUI pixels above the hotbar/health and expands this inset to
clear recent wrapped chat rows, honoring vanilla chat scale/spacing. The inset is
converted to Codon's scale; fitting does not rewrite saved panel widths. Recent
chat first reduces the world/detail viewport to retain selectable Command rows.
Flow has no call-path row (the call stack is a dock section), so a 40-pixel panel
keeps an 18-pixel command/marker row above its actions. Opening
chat continues to suppress the passive debugger HUD.

At compact widths one slot shows one section at a time (View → Contexts, Watch or
Call stack, or the toolbar's Breakpoints count). Explicitly opened Watch and
Breakpoints use the available workspace above the HUD; narrow drawers do the same,
and closing them restores Command. Taller layouts retain context detail actions and
NBT rows by reducing Command height before removing those controls. A short dock
reserves the full NBT heading before source details; details disappear when they
would push that heading outside the dock.

HUD background opacity still follows the saved 0–100% preference. Modal forms,
pickers, breakpoint dialogs, Help and UI-scale settings keep an opaque
reading surface and their dim scrim independently of that preference. Text alpha
is not the cause of low-opacity world contrast. English/Korean Help describes this
boundary.

**View → Opacity** is a slider row in the View menu at the toolbar's right end. It always
shows its current percentage to the right of the track, without hover, in English and
Korean at 0 and 100%. The toolbar and the View menu keep a solid surface, so opacity can
always be restored. A focused slider uses Left/Right for 1 percent and Shift+Left/Right for
10 percent, clamped to 0–100; an adjustment at an endpoint writes no settings. Mouse
click and drag still preview live and write once when the gesture ends, including a
return to the starting value after another setting saved an intermediate preview. Closing
the menu or the debugger during a drag commits once. English/Korean usage narration and
Help describe both steps. **View → Codon UI scale** opens the modal UI-scale screen.

Gizmo collision cells retain at most 128 exact candidates before becoming a spatial
aggregate, including cells whose rectangles have no common intersection. Coarse groups
retain every source and the selected member; the existing 20-label spatial budget and
obstacle rules remain. `GizmoLabelLayoutTest` covers 10,000 split-height sources in both
input orders, plus grouping, selection, obstacle and visible-budget controls. Native
world projection and rendering remain separate acceptance checks.

`DebuggerIdleBadgeGameTest` clicks the real View row at the smallest 320×240 viewport,
writes a temporary settings file (never the live `config/codon.json`) and reloads it into
a new settings/state instance. Inspect `codon-idle-badge-*`: default, hidden, hidden after
reload, restored, F3 with a visible preference, the open View menu in both languages, and
the `-synthetic-pause` HUD/cursor captures. Presence of the badge or paused toolbar is
asserted from dark panel pixels at their shared corner, with sky behind it; F3's own text
and the screenshots themselves still need inspection. The production HUD element would draw
its own badge, so the test wraps it once in place with `HudElementRegistry.replaceElement`: the
wrapper draws the test-owned HUD while the fixture is active. Fabric's registry keeps removed
ids and rejects adding them again, so nothing is removed; after cleanup the wrapper stays
registered but renders the original production element again, in its original id and order.
The paused captures inject a client snapshot: they do not prove a server breakpoint, and world
markers remain the world-marker regression's concern (the renderer never reads the
preference).

```sh
./gradlew test --tests '*ClientSettingsStoreTest'
./gradlew runClientGameTest -PclientGameTest=DebuggerIdleBadgeGameTest
```

## Code entry points

- [UiHideGesture](../../../src/client/java/works/nuty/codon/client/input/UiHideGesture.java): monotonic press/release classification and cancelled gestures.
- [InputManager](../../../src/client/java/works/nuty/codon/client/input/InputManager.java): binding, typing guard and lifecycle reset.
- [DebugLevelRenderer](../../../src/client/java/works/nuty/codon/client/render/DebugLevelRenderer.java): submits Codon's markers at `BEFORE_GIZMOS`, before the current frame is finalized. Skipping submission when hidden leaves vanilla and other mods' gizmos intact.
- [CodonScreen](../../../src/client/java/works/nuty/codon/client/ui/CodonScreen.java): hidden interaction suppression.
- [DebuggerOverlay](../../../src/client/java/works/nuty/codon/client/ui/DebuggerOverlay.java): idle-badge condition (F3 and the saved preference) and the View menu row.
- [ClientSettingsStore](../../../src/client/java/works/nuty/codon/client/config/ClientSettingsStore.java) and [DebuggerPreferences](../../../src/client/java/works/nuty/codon/client/state/DebuggerPreferences.java): `idleBadgeVisible` schema and change callback.
- [Client mixins](../../../src/client/resources/codon.client.mixins.json): native keyboard/mouse events and screen transitions.

## Choose verification

| Concern | Existing tests |
| --- | --- |
| HUD/chat inset, modal backing and EN/KO compact/default readability | `clientTest`: `DebuggerLayoutTest`; `test`: `DebuggerThemeTest`, `UiScaleScreenRenderTest`; native: `DebuggerReadabilityGameTest`, `DebuggerOpacityGameTest`, `DebuggerPresentationGameTest`, `DebuggerNbtTreeGameTest`, `DebuggerUiScaleGameTest` |
| Short/long boundary, repeated events and cancellation | `clientTest`: `UiHideGestureTest` |
| Opacity percentage in the View menu, Shift steps, endpoints and commit counts | `test`: `BackgroundOpacitySliderTest` (real temporary settings file and an intervening setting save); native: `DebuggerOpacityGameTest` |
| Native keyboard/mouse dispatch, screen input, rebind, chat, missed mouse release, focus flag and rejoin | `DebuggerPeekUiGameTest` |
| Real server breakpoint stays paused, then each command executes once after Resume | `DebuggerFreecamResumeGameTest` |
| First hidden/restored frame and sustained holds in world/cursor mode at a real entity-context pause; running breakpoint outlines, unrelated gizmos, disconnect/rejoin | `DebuggerWorldMarkerVisibilityGameTest` |
| Idle-badge preference: default, old file, round trip, invalid types, change callback, state reset | `test`: `ClientSettingsStoreTest` |
| Native View toggle, saved-file reload, idle shown/hidden/restored, F3, paused HUD and cursor mode with the badge hidden, 320×240 menu reach, EN/KO label | `DebuggerIdleBadgeGameTest` |

```sh
./gradlew clientTest --tests '*UiHideGestureTest'
./gradlew test --tests '*BackgroundOpacitySliderTest'
./gradlew runClientGameTest -PclientGameTest=DebuggerPeekUiGameTest,DebuggerFreecamResumeGameTest,DebuggerWorldMarkerVisibilityGameTest
./gradlew runClientGameTest -PclientGameTest=DebuggerOpacityGameTest
```

`DebuggerOpacityGameTest` uses a synthetic pause (`pauseId` zero, so no server pause is
queryable) and a temporary settings listener (not the real settings file) to count
writes. It restores the window size, GUI scale, language and global theme afterwards.
Its evidence has three separate parts:

- **Direct-call sweeps** call the screen/slider methods for mouse preview and commit,
  1- and 10-percent steps, both endpoints and write counts.
- **A short actual keyboard dispatch sample** focuses the slider and sends Right, Left,
  Shift+Right and Shift+Left through Minecraft's `KeyboardHandler`. Plain keys use
  Fabric's `pressKey`; Shift arrows go to the same handler with the Shift modifier set,
  because Fabric's synthetic key events carry no modifier bits. It checks 51→50 and
  60→50, one write per effective press, and that the same screen is open with the
  slider focused after each waited tick. It does not repeat the endpoint sweep.
- **Percentage ink scan** covers only these frames, each with View opened by a click on
  its toolbar button: `codon-opacity-inline-100`, `-inline-50`, `-all-zero`, `-all-one`,
  `-inline-compact-50` (asserted to be a 320×240 GUI whose slider and percentage lie in the
  viewport below the toolbar), and the eight `codon-opacity-view-{en_us,ko_kr}-{1280,640}-{100,0}`
  frames (final-inspection pause at 0 and 100%, default and compact). `codon-opacity-toolbar-closed`,
  `codon-toolbar-{en_us,ko_kr}-{1280,640}`, `codon-opacity-hover-50` and the two
  `codon-opacity-editor-*` frames are not scanned; inspect them by eye.

The scan counts text-coloured pixels beside the slider, so it shows that something is
drawn there but does not recognize the digits. Inspect the screenshots to verify the
exact values. The idle badge has no slider, so it shows no percentage.

Inspect the `codon-peek-*` screenshots: compare world and cursor-mode baselines,
held/toggled hidden presentation, and restored UI. The peek fixture injects a client
pause for visual coverage; the freecam resume fixture executes real command blocks.
The focus check sets the window's focused flag because Fabric cancels native focus
callbacks. The mouse regression omits the outside-window release callback and
checks the first fresh press, continued-hold suppression and text-field guard.
The injected text-field guard places the cursor in a blank corner and requires
no row menu to open, so a prior test's pointer cannot change which screen receives
the following menu-close gesture. See the [UI polish validation record](../ui-polish-validation.md).
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

`DebuggerReadabilityGameTest` captures Command plus four recent chat messages and
survival health/hotbar at 854×480 and 1280×720, EN/KO, default/zero opacity. Modal
screenshots face bright sky and dark terrain for each combination. Additional
captures show idle/no-F3, idle/F3, paused/F3 and open chat in both languages. The
Targeted captures also check five/ten recent chat rows and the 299/300-pixel height
boundary. `DebuggerNbtTreeGameTest` checks heading containment in a 64-pixel dock
with an actual paused entity source; an injected flow fixture has no live NBT executor.
The fixture uses injected client snapshots, so use the real world-marker regression
for H and server-pause safety. Inspect `codon-readable-*` images; this matrix does
not establish arbitrary modded HUD placement or unusually many health rows.

`UiScaleScreenRenderTest` checks that UI-scale settings extract a full-viewport
dim scrim before the opaque panel at both 0% and 100% HUD opacity. The native
`DebuggerUiScaleGameTest` opens settings through View and checks the actual GUI
render-state rectangles at those two opacities. Inspect `codon-scale-scrim-hud-*`
alongside the existing scale/input screenshots; the world outside the panel stays
dim while the reading surface and the saved HUD-opacity intent remain intact.
