# Execution flow

## User path and expected result

The UI scaffold uses shared neutral surfaces and flat Contexts, call-path and Flow
buttons: idle rows have no repeated frame, hover has a neutral fill, selection keeps
its semantic fill and underline, and keyboard focus has a filled corner caret.
Foreground text and icons remain opaque when panel opacity is reduced. Live amber,
selected teal, context-change colors, breakpoint icons and counts retain their roles.
Unobserved stages keep the full observation text in their clause tooltip and
selection summary. Future stages no longer repeat an inline Unrun label or reserve
a status minimum width; Unreached/Unknown/Error remain distinct inline information.
Cell horizontal padding is 6 logical pixels (previously 10), with 2 pixels between
cells (previously 4). Both drawing and button text use that padding. The count-line
minimum is measured count width + 6, plus 17 only when a warning control is present;
it previously added 26 and another 15 for an editable source. Breakpoint and warning
icons are reserved once, by the command line's leading inset. Compact rows with
no count line reserve no count width. The shared count row remains when observed
counts are shown; hidden Unrun labels do not produce a replacement badge. Stage
targets, marker hitbox sizes, secondary actions and selection colors are unchanged.
Character wrapping, row scrolling and call-path horizontal scrolling retain their
existing behavior with the denser clause geometry.

The actual paused Flow stage uses amber text/counts and its existing amber selected
surface, without a pause glyph. The first clause fragment no longer reserves the
14-pixel pause-icon inset; a compact warning still reserves its own icon space when
needed. Amber is guarded by live paused state plus the actual flow/stage identity,
so browsing a different recorded stage retains teal selection without moving the
execution indicator. The stopped clause tooltip explicitly names Stop and the stage
number; the selection detail band's textual Stop state and narration target remain.
Breakpoint controls, toolbar controls and call-path markers are unchanged.

Pause-icon follow-up validation: `JAVA_HOME=<JDK 25> ./gradlew compileClientJava
processResources --console=plain` passed; evidence: `../flow-pause-color-compile.log` beside
the checkout. No tests were edited or run, and no game/GUI was launched. Post-change
rendering, native focus/narration, wrapping and live/history selection remain
unverified. Existing stale layout-test expectations noted below remain untouched.

The follow-up crop `Screenshot 2026-10-03 at 15.27.53.png` shows the space before
`at @s`, inside the clause after its breakpoint marker. Parsed stage text includes
the leading command separator; the display-only layout now strips that separator
before measuring/wrapping, so it no longer adds to the 3-pixel button inset. Full
command text, stage ranges, tooltips, marker hitboxes and spacing inside a clause
are preserved. Continuation fragments are not stripped. The 6-pixel total padding,
2-pixel cell gap and hidden inline Unrun remain in place.

Leading-space/Watch-alignment validation: the supplied Library image was
materialized and its actual pixels inspected. `JAVA_HOME=<JDK 25> ./gradlew
compileClientJava processResources --console=plain` passed as the only execution check;
evidence: `../leading-space-compile.log`. No tests were edited or run and no game/GUI
was launched. Post-change screenshots, wrapping and native input remain unverified.

Flow-spacing validation: the supplied Library screenshot `Screenshot 2026-10-03 at
15.12.15.png` was materialized and visually inspected. `JAVA_HOME=<JDK 25>
./gradlew compileClientJava processResources --console=plain` passed as the only
execution check; evidence: `../flow-spacing-compile.log` beside the checkout. No tests were
edited or run, and no game/GUI was launched. Post-change rendering, wrapping,
native hitboxes/scrolling and selected/live emphasis remain unverified. Existing
`CommandFlowLayoutTest` assertions encode the previous 10-pixel padding and exact
fragment widths; updating them requires a separately authorized test-update stage.
Contexts now labels the selected stop/recorded stage separately from the stage
which supplies its displayed contexts. Complete modifier records show outputs plus
excluded inputs; terminal, unfinished or incomplete-lineage records show inputs.
A predecessor's outputs retain that predecessor's stage number. Empty observed
context sets are not relabeled as missing recordings. Very short inspector viewports
use the provenance caption as the heading to retain a selectable context row.

Flow has a persistent two-line selection detail band with a keyboard focus/narration
target. It names Stop/Recorded/Selected, the stage number, observation state and
counts. The collapsed panel reserves 28 additional logical pixels; stage widths,
wrapping rules and bottom action positions do not depend on the selected status.
Expanded Flow retains its existing outer size and allocates the same detail band.
Measured zero stays `0`; missing counts are `?`. Explicit stage-scoped execution
warnings produce Error; zero successes alone do not. A missing suffix is Unrun only
beyond the actual stop in the same invocation. A complete, reliable preceding stage
with zero outputs permits Unreached. Other absent historical stages remain Unknown,
with recording-missing/execution-unknown text. No lifecycle facts or error attribution
are inferred for unrecorded stages or warnings without a stage identity.

The trace model does not carry an authoritative lifecycle state for every absent
parsed stage. Distinguishing all other non-execution versus missing-capture cases
would require additional server evidence; this patch does not extend the protocol.

Validation for this follow-up: `JAVA_HOME=<JDK 25> ./gradlew compileClientJava
processResources --console=plain` passed; evidence is `../ui-provenance-compile.log` beside
the checkout. No tests or GUI runs. Native layout, clipping, keyboard narration and
live/historical selection acceptance remain unverified.

Use the [shared setup](../README.md#prepare-and-launch). In a fresh scratch world,
summon two tagged armor stands before pausing:

```mcfunction
summon minecraft:armor_stand ~2 ~ ~ {Tags:["codon_verify","codon_keep"]}
summon minecraft:armor_stand ~4 ~ ~ {Tags:["codon_verify"]}
```

Save this command in an impulse command block, add a whole-command breakpoint,
and trigger it:

```mcfunction
execute as @e[type=minecraft:armor_stand,tag=codon_verify] at @s if entity @s[tag=codon_keep] run say kept
```

1. Step with `F7` and inspect the command's stages and context markers using `V`.
   The `as` stage branches into two contexts; `at` changes their recorded anchors;
   `if` retains one and removes the other. These are context changes, not entity
   creation/deletion.
2. Select earlier recorded stages/frames and compare input/output contexts. Their
   counts, anchors and call path must come from the selected record. Browsing a
   previous stage must not change the actual stopped command.
   The command panel uses left/right arrows for recorded-command navigation and
   four-corner icons for expand/collapse. Its action row has no Watch button;
   open Watches from the debugger toolbar. Expanding and collapsing must keep
   the action buttons in place. Command text wraps at character (Unicode code-point)
   boundaries rather than backing up to spaces, both with recorded stages and in
   the raw-command view. Spaces and active-range highlighting are preserved;
   recorded stage fragments retain their stage identity and breakpoint target.
   Only the first fragment reserves breakpoint/pause/warning icon and count-label
   space. Continuation rows use the full text width with normal text padding.
   Flow also requests the saved command's parse-only server preview. Statically
   known stages that have not been observed remain selectable: their first fragment
   keeps its marker visible; left-click the marker to toggle its breakpoint,
   or right-click the clause to edit its condition. Continue must stop on the
   stage's first occurrence. Selecting a static stage shows no recorded contexts
   or measured counts or a captured call path; Current restores the actual stop.
   Previous/Next retains the recorded visit from which the static stage was
   selected, including repeated visits to the same invocation. A rejected marker
   edit shows translated server feedback in its tooltip. Failed parse previews
   retry once on a later snapshot; an in-flight preview keeps its request ID.
   `Not executed yet`,
   `Filtered out` after a measured zero-output filter, and `Stage data unavailable`
   for missing/truncated evidence remain distinct. Changed saved text or
   unavailable previews must never retarget a recorded invocation's suffix.
3. After the terminal command executes, the completed record has one execution
   and one success. Before that observation, terminal values may be unmeasured;
   do not display them as measured zero.
4. Repeat with a condition that matches neither stand. The completed filter has
   measured zero output and no terminal execution. For nested/conditional function
   cases, use the native fixture tests below and inspect their chronology.

Created contexts are green, removed contexts are red, and changed contexts are
purple in both the inspector and world markers. Incomplete or
truncated lineage must retain its warning/unknown state. Do not infer edges from
similar UUIDs/positions or replay a command to reconstruct its effects.

## Code entry points

- [BuildContextsMixin](../../../src/main/java/works/nuty/codon/mixin/BuildContextsMixin.java), [CommandTrace](../../../src/main/java/works/nuty/codon/adapter/CommandTrace.java): native execution instrumentation.
- [ExecutionFlowRecorder](../../../src/core/java/works/nuty/codon/core/service/ExecutionFlowRecorder.java): recorded stages and lineage.
- [ExecutionFlowTimeline](../../../src/client/java/works/nuty/codon/client/state/ExecutionFlowTimeline.java), [ClientDebuggerState](../../../src/client/java/works/nuty/codon/client/state/ClientDebuggerState.java): selection and chronology.
- [CommandPanel](../../../src/client/java/works/nuty/codon/client/ui/CommandPanel.java): command, stage and call-path presentation.

## Choose verification

| Concern | Existing tests |
| --- | --- |
| Recorded inputs/outputs/lineage | `coreTest`: `ExecutionFlowRecorderTest` |
| Trace adapter | `test`: `CommandTraceTest` |
| Recorded navigation/layout | `clientTest`: `ClientExecutionFlowTimelineTest`, `ClientCommandSelectionTest`, `CommandFlowLayoutTest` |
| Native branching/filtering and stage stops | `DebuggerExecutionFlowGameTest` |
| Native `if function` / `unless function` chronology | `DebuggerConditionalFunctionFlowGameTest` |
| Flow options/markers for never-observed stages, rejection feedback, recovered previews, then first-occurrence terminal and conditional stops | `DebuggerUnobservedFlowBreakpointGameTest`; `clientTest`: `ClientUnobservedFlowSelectionTest`, `ClientFlowPreviewRequestsTest`, `CommandFlowLayoutTest` |
| Continuations and incomplete-record warnings | `DebuggerContinuationRecordingGameTest` |
| Rendered command/context UI with injected data | `DebuggerPresentationGameTest` |

Example: `./gradlew runClientGameTest -PclientGameTest=DebuggerExecutionFlowGameTest`.
The conditional-function test concerns Minecraft function conditions; conditional
breakpoint coverage is listed in [Breakpoints](breakpoints.md). Keep native-flow
assertions separate from screenshots produced by synthetic presentation fixtures.

Condition/navigation follow-up: Flow's footer condition button is removed. Right-
click a breakpoint marker (or Shift+F10 on that focused marker) to open its exact
condition editor directly. Clauses and the selected detail band retain their
condition menu, without a line-versus-stage chooser. Right-clicking a future
stage does not select it or alter displayed contexts. Saved disabled breakpoints and unset breakpoint-capable stages
stay visible as neutral hollow circles/diamonds, including run and terminal function
stages. No definition is created until the existing marker action is activated.
Only the first fragment of each exact parsed/recorded stage owns its marker; wrapped
continuations do not invent additional targets. Unparsed suffixes do not invent stage targets. A separate whole-command marker
now precedes the command root/execute prefix when the command has multiple stages;
a one-stage command has only its line marker. Parsed stage count, not a literal
execute check, decides this mapping. The actual pixels in the supplied Library screenshot
`Screenshot 2026-10-03 at 15.59.01.png` were inspected before this change. Actual pause amber and functionally necessary inspection
selection remain distinct. The Active breakpoint list selects an exact matching
Flow/stage and reveals it without sending breakpoint edits; unavailable destinations
remain listed with an explanation. Compilation/resources passed, with evidence in
`../breakpoint-context-navigation-compile.log`. No tests or game/GUI were run; native
menu/focus/scroll behavior and appearance remain unverified.

Flow keyboard traversal follows visual reading order: the whole-command marker
comes first when present, then each stage's marker immediately precedes its clause
and optional warning. Tab and Right follow this order through wrapped rows;
Shift+Tab and Left reverse it. Up/Down move between visual rows. Tab leaves Flow at
the last control, and Shift+Tab leaves at the first; entering Flow starts at the
corresponding edge. Other debugger regions keep their existing Tab behavior.
Off-screen controls reveal their row, traversal skips pending/disabled markers, and
wrapped continuations do not add breakpoint markers. Warning focus IDs use the
recorded stage index so sparse recordings do not create phantom targets.
Shift+F10 continues to use the focused control's exact condition target and return
focus to that control. Single-stage line mapping, Source, Watch and pause colors
are unchanged. Validation is limited to client compilation and resources; no tests
or game/GUI runs were performed. Native focus traversal and menu return remain
unverified. Compile log: `../flow-focus-order-compile.log`.

Toggling a focused breakpoint with Enter/Space keeps focus on that exact target
while the request is pending and after its server update. Its disabled button and
focus caret remain present; it cannot accept another toggle until ready. Focus
identity includes the invocation and complete breakpoint target (location, stage
and command fingerprint), rather than the recorded-stage list position or whether
the stage has been observed. Explicit traversal or a different clicked control
replaces that focus immediately; acknowledgements never restore an older target.
The direct condition editor returns focus to its opening marker only while its
source/flow context remains current. Opening or cancelling does not save a
breakpoint. Validation: client compilation/resources only, log
`../breakpoint-focus-direct-editor-compile.log`; no tests or game/GUI runs.
