# Tooltips

Codon's deferred text tooltips wrap within 240 logical pixels (or the viewport
minus 16 pixels when narrower). Mouse and keyboard placement retain a four-pixel
margin at every edge. The common boundary is `CodonGuiGraphics` and
`CodonTooltips`; inline controls in vanilla command editors also use the shared
positioner through `DebuggerButton` and `WrappedCommandEditBox`.

Source line breakpoints show their exact condition, click toggle hint and right-click
condition hint on separate English/Korean lines. Long unbroken paths wrap too.
Styles, explicit newlines, visual character order and deferred-tooltip priority
are retained. Full detail/error text is preserved. A diagnostic taller than the
entire viewport still needs a separate readable detail surface; width bounding
and edge placement cannot make arbitrarily long text fit vertically.

## Coverage ledger

This is a source inventory and a map to runtime checks, not a claim that each
surface has passed manual acceptance. Record run-specific evidence separately.

| Surface | Content and hover review | Runtime coverage / remaining manual check |
| --- | --- | --- |
| Source tree splitter, path and Find limit | Direct text now wraps; row/track hit regions remain separate | `DebuggerTooltipGameTest` tests actual Source saved-line hover; inspect path/splitter/Find manually |
| Source saved line, stage summary, stale/preview hints | Exact line condition and both mouse actions are separate localized lines; a distinct stage count never aliases the gutter target | `DebuggerTooltipGameTest`: English/Korean, enabled/disabled whole-line definition, separate legacy stage count and modal suppression |
| Source inline stage and navigation track | Direct stage/condition and existing wrapped navigation help share viewport placement | Existing `FunctionSourceScreenGameTest`; manually inspect an offscreen/long stage condition |
| Flow breadcrumbs, clauses, markers and observations | `Tooltip.create` labels/details retained; delayed button hover and scissor checks remain | `DebuggerPresentationGameTest`, `FlowLegacyConditionGameTest`; long details and paused real execution remain manual |
| Watches values, executor/grouping and row actions | Rich status/target/value text preserved; compact action hints wrap if necessary | `DebuggerCompactWatchGameTest`, `DebuggerWatchPinGameTest`; unusual long value diagnostics remain manual |
| Watches grouping menu and notices | Covered row controls/direct text cannot hover under menu; uncover resets button hover delay | Shared covered/uncovered regression; actual grouping-menu overlap remains manual |
| NBT node path/preview and pin | Separate component-list API uses shared wrapper; node preview excludes pin area so click hints retain priority | `DebuggerNbtTreeGameTest`: native pin click/right-click/path hover assertion and screenshot; shared component-list/long-identifier regression; manually inspect a long generated path |
| Toolbar, View menu, world-source controls | Icons retain labels/keys; covered controls cannot hover below View menu | `DebuggerPresentationGameTest`, `DebuggerUiScaleGameTest`; actual View overlap remains manual |
| Inspector context values and detail icons | Only clipped values echo; full diagnostic text preserved and wrapped | Shared native direct-text regression; inspect retained-entity/long-name details manually |
| Breakpoint list and condition layer | Action hints, clipped fragment and count field share wrapper; existing modal/dropdown suppression retained | `DebuggerBreakpointUiGameTest`; shared native Source modal check |
| Command-block inline marker | Existing width wrapping retained; mouse/focus placement now clamps to viewport | `DebuggerBreakpointUiGameTest`; vanilla editor scale remains independent of custom Codon scale |
| Watch editor and picker | Field labels, invalid values, option detail and unavailable choices share wrapper | `DebuggerWatchFormLayoutGameTest`, `DebuggerWatchPickerLayoutGameTest`; inspect localized validation errors manually |
| Help, UI-scale settings and Watch detail screen | Button labels use common delayed/clipped-label behavior; detail text already has a scrollable view | `DebuggerUiScaleGameTest`; no wording redesign |
| Opacity slider | Short localized percentage text shares native wrapper | `DebuggerOpacityGameTest`; slider hover remains mouse-only |

## Checks and evidence

`./gradlew test --tests '*CodonTooltipsTest' --tests '*DebuggerButtonTest'`
checks mouse corners, keyboard placement above a near-top tall widget and narrow
viewport margins. `./gradlew build` runs the configured full JVM checks.

`./gradlew runClientGameTest -PclientGameTest=DebuggerTooltipGameTest`
observes the actual deferred native tooltip components and positioner with a
test-only mixin. It checks Source's saved-line marker, disabled definitions,
modal suppression, direct text/component lists, styled long identifiers,
keyboard/disabled buttons, scissor clipping and hover-delay recovery once at
English 1.00 scale. Source hover also runs in Korean at 2.25 fractional scale;
direct-text edge placement covers Korean 2.25 and 4.00 scales plus the 320×240
minimum with a retained large-scale request. This representative set produces
ten screenshots without repeating every input mode across language/scale pairs.
Native captures use
`codon-tooltip-*`; inspect every image. Synthetic details establish rendering and
input presentation, not server-driven breakpoint execution.

For manual QA, hover the Source saved-line marker from issue #43; check its count
and both hints, then open/close the condition layer and menus without moving the
pointer. Repeat on the Flow marker, a long Watch/NBT path and an inspector value
near right/bottom edges. Check English/Korean in a narrow window and fractional
custom scale. Keep existing worlds and settings intact and restore changed test
settings after the run.
