# Codon UI scale

Open cursor mode with `V`, then **View → Codon UI scale**. The default **Follow game
GUI scale** keeps the existing size. **Custom scale** uses the same absolute scale
units as Minecraft's GUI scale; it changes only Codon's HUD, screens and modal
layers. Inline controls embedded in vanilla command text retain their host's units
so they stay aligned with that text. Minecraft's GUI option and other menus stay
unchanged.

The requested range is **1.00×–4.00× in 0.25× steps**, initially 2.00×. The current
renderer lays out integer logical coordinates, while its pose supports fractional
scaling. A custom request is limited to the largest quarter step that leaves at
least 320×240 logical pixels, with a lower limit of 1.00×. The settings display both
the request and the applied value, with an explanation when limited. Enlarging the
window restores the saved request. Windows physically smaller than 320×240 can
still exceed the existing layouts' limits even at 1.00×.

Mode and requested scale save immediately in `config/codon.json`. Missing fields
in existing v1 files use the default. Invalid values preserve the original file
and disable writes for that session, like other settings. **Restore defaults**
resets only these two preferences; choosing Follow retains a custom request.

## Code path

- `DebuggerPreferences` / `ClientSettingsStore`: retained settings.
- `UiScale`: actual viewport, fractional pose factor, pointer mapping and fit cap.
- `ScaledCodonScreen` / `CodonScreenScaleMixin`: Codon-only layout and complete
  render pass, including deferred tooltips and preedit overlays.
- `MouseHandlerMixin`: pointer position and drag delta mapping before Fabric's
  screen events; wheel amounts and keyboard events keep their original meaning.
- `DebugHudElement`: the same scale in passive HUD mode.
- `UiScaleScreen`: user controls, range endpoints and applied-value explanation.

## Focused checks

```sh
./gradlew clientTest --tests '*UiScaleTest'
./gradlew test --tests '*ClientSettingsStoreTest'
./gradlew runClientGameTest -PclientGameTest=DebuggerUiScaleGameTest
```

The client scenario uses a synthetic pause to check native clicks and keyboard
activation of settings, GUI-scale changes, all Codon screens' Close hitboxes,
edit-field focus, help wheel/scrollbar dragging, a condition popup over an unchanged
vanilla-sized parent, a 640×480 window with a 320×240 logical viewport, a native 320×240 window,
passive HUD, Korean wrapping and restore defaults. Inspect
`*codon-scale-*.png` and the console log. The test restores its game GUI option and
removes its HUD fixture. It does not establish native server breakpoint behavior.

Existing `DebuggerPresentationGameTest`, `DebuggerKeyboardNavigationGameTest`,
`DebuggerScrollbarGameTest` exercise follow-mode presentation and navigation.
Compilation and screenshots alone do not establish all interactions. When doing
manual QA, also check language-dependent wrapping, full-screen/window changes,
large tooltips and legibility at the smallest scale. An independent scale option
does not automatically resolve CODON-003 small-screen Watch/status clipping.
