# Codon UI scale

Open cursor mode with `V`, then **View → Codon UI scale**. The default **Follow game
GUI scale** keeps the existing size. **Custom scale** uses the same absolute scale
units as Minecraft's GUI scale; it changes only Codon's HUD, screens and modal
layers. Inline controls embedded in vanilla command text retain their host's units
so they stay aligned with that text. Minecraft's GUI option and other menus stay
unchanged.

First selection of Custom starts at the **actual applied game GUI scale**, including
Auto, without changing size. Later mode changes and client restarts retain the
user's request. Controls use **0.25× steps** from 1.00×; the usual upper endpoint is
4.00×, expanded when a larger window or Minecraft's font rounding permits more.
The renderer uses integer logical coordinates and Minecraft's ceiling convention,
while its pose supports fractional scaling. A custom request is limited to the
largest quarter step that leaves at least 320×240 logical pixels, or Minecraft's
font-rounded automatic limit if higher. This cap depends on the framebuffer/font
setting, not the selected game GUI scale. The settings display both
the request and the applied value, with an explanation when limited. Mouse presses
on the centered +/− controls keep the pressed point under the pointer when resizing,
with the dialog constrained to the viewport. Keyboard focus and Enter/Space
activation continue to work across scale changes. Enlarging the
window restores the saved request. Windows physically smaller than 320×240 can
still exceed the existing layouts' limits even at 1.00×.

Mode and requested scale save immediately in `config/codon.json`. Missing fields
in existing v1 files use the default. An absent custom request means first use;
legacy files with a numeric request keep it in either mode. Invalid values preserve the original file
and disable writes for that session, like other settings. **Restore defaults**
resets only the scale preferences and clears the request so the next Custom
selection starts from the game scale again; choosing Follow retains a custom request.

## Code path

- `DebuggerPreferences` / `ClientSettingsStore`: retained settings.
- `UiScale`: actual viewport, fractional pose factor, pointer mapping and fit cap.
- `ScaledCodonScreen` / `CodonScreenScaleMixin`: Codon-only layout and complete
  render pass, including deferred tooltips and preedit overlays.
- `MouseHandlerMixin`: pointer position and drag delta mapping before Fabric's
  screen events; wheel amounts and keyboard events keep their original meaning.
- `DebugHudElement`: the same scale in passive HUD mode.
- `CodonTextPose` / text extraction and glyph sampling Mixins: retain custom-scale
  ownership through deferred text and native widget collectors. Fractional and
  sub-2.00 scales filter glyph coverage, keeping dense Korean strokes visible.
  `CodonTextPipelines` integrates the glyph texels covered by each framebuffer pixel,
  uses the selected Minecraft font and preserves color/alpha
  coverage; geometry, wrapping, scissor bounds and native input retain the same scale.
  `CodonGlyphCoverage` provides half a framebuffer pixel of antialiasing room around
  glyph quads and extrapolates their original UV mapping; logical text metrics and
  widget geometry stay unchanged. Original glyph texel bounds travel as flat vertex
  data; samples outside those bounds are transparent and retain the full footprint
  denominator. This prevents neighboring atlas ink without brightening glyph edges.
  Bounds use Minecraft 26.3's fixed 256×256 `FontTexture` atlas and inclusive texel
  indices, packed in the filtered GUI pipeline's otherwise unlit UV2 attribute.
  Follow-game text and vanilla glyph draws retain their existing pipeline/sampler.
- `UiScaleScreen`: user controls, range endpoints and applied-value explanation.

## Focused checks

```sh
./gradlew clientTest --tests '*UiScaleTest'
./gradlew test --tests '*ClientSettingsStoreTest'
./gradlew runClientGameTest -PclientGameTest=DebuggerUiScaleGameTest
./gradlew runClientGameTest -PclientGameTest=DebuggerFontSamplingGameTest
./gradlew runClientGameTest -PclientGameTest=DebuggerFontAtlasIsolationGameTest
```

The client scenario uses a synthetic pause to check first manual/Auto mode switching,
reset and mode-round-trip preservation, ten native + clicks and ten − clicks at
unchanged pointer positions, keyboard activation of settings, GUI-scale changes,
all Codon screens' Close hitboxes,
edit-field focus, help wheel/scrollbar dragging, a condition popup over an unchanged
vanilla-sized parent, a 640×480 window with a 320×240 logical viewport, a native 320×240 window,
passive HUD, Korean wrapping and restore defaults. Inspect
`*codon-scale-*.png` and the console log. The test restores its game GUI option and
removes its HUD fixture. It does not establish native server breakpoint behavior.
The English/Korean idle captures remap the breakpoint to J and cursor mode to N
so the actual binding labels can be inspected. The Korean section captures Help
paragraphs at 1.50 and 2.00, a narrow Help panel,
and running View/HUD labels at 1.50.

`DebuggerFontSamplingGameTest` compares native framebuffer pixels against an
area-integrated 2.00 reference using the selected Minecraft font. Korean checks
1.00, 1.25, 1.50, 1.75, 2.00 and 2.25; English checks representative 1.50 and 2.25
cases and the Unicode font option at 1.50; Korean also checks a 640×480 window at
1.50. 0.75 is outside the supported
control range. String, Component, ordered text, collector, shadow, button, EditBox
and clipped paths have stroke/contrast and extra-ink assertions; deferred tooltips have screenshot
evidence. Fixed vanilla control text must retain identical pixels. The regression
fails on the unmodified renderer at 1.50. Inspect `*codon-font-*.png` and the reported
missing-pixel fractions/contrast errors; final packaged-client manual QA remains required.

`DebuggerFontAtlasIsolationGameTest` compares native screenshots of the same Korean
glyph packed alone and beside another glyph by the real `FontTexture` allocator.
Bitmap data comes from Minecraft's `unifont.zip`, with the default 2× oversampling
and Hangul 15×16 crop. The 1.25 case at logical (20,3) checks the established
one-texel-gap counterexample pixel directly; it also compares 1.00, 1.50, 1.75 and
2.25. A grayscale case near the atlas bottom checks signed packed bounds. Every
case requires visible target ink, zero neighbor-only pixels and matching target
coverage within two framebuffer levels. The previous candidate fails natively with
six extra pixels and counterexample brightness 30/255. The earlier source/packing
calculation and these native screenshot results are distinct evidence.

Existing `DebuggerPresentationGameTest`, `DebuggerKeyboardNavigationGameTest`,
`DebuggerScrollbarGameTest` exercise follow-mode presentation and navigation.
Compilation and screenshots alone do not establish all interactions. When doing
manual QA, also check language-dependent wrapping, full-screen/window changes,
large tooltips and legibility at the smallest scale. An independent scale option
does not automatically resolve small-screen Watch/status clipping.

For compact Watch/status and Details footer changes, use
`DebuggerCompactWatchGameTest` and the [Watch guide](watches-and-nbt.md). The header
reserves its key hint and opacity control, then gives live state priority over the
CODON prefix when the translated state needs more room.
