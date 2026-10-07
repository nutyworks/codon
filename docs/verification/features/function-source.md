# Function source

Function-list and source-read requests now expire 30 seconds after request creation,
even when no page arrives. The browser shows its existing error state and permits
Refresh or Reread; an old reply cannot complete a newer request. Stage previews
also retry a timed-out loading request once with a new ID, then require Reload or
line re-selection after another timeout. `ClientTransferLimitsTest` and
`ClientFlowPreviewRequestsTest` cover the headless state transitions.

## User path and expected result

Use the [shared setup](../README.md#prepare-and-launch) with a loaded scratch
datapack containing a known function. Record its resource ID and file text; include
a comment/blank line and a long `execute` line so line numbering, horizontal source scrolling and inline stage markers can
be checked. The viewer lists functions actually loaded by the current server.

1. Press `V`, then the toolbar's **Source** (`</>`) icon. Search by namespace/path, expand
   folders and select the function. Its source appears read-only; browsing does
   not require executing it.
   The Functions tree shows a separate scrollbar only while its expanded or
   filtered rows overflow. Click its track or drag its thumb to navigate without
   selecting a row or scrolling Source. Search, folder collapse and resizing
   clamp its position and remove the track when all rows fit.
   The header shows the datapack-relative
   `data/<namespace>/function/<path>.mcfunction` path (hover for the provider and revision;
   the full path is added only when it is clipped). Source is the first consumer of shared neutral workspace
   colors in `DebuggerTheme`: its panel and code surface are opaque even when HUD
   background opacity is reduced. The compact header starts code at 79 logical
   pixels; removed condition controls no longer reserve a second row.
   Execution status and a truncated-source warning remain visible. Functions rows
   use a flat neutral hover and a teal selection rail; ordinary folders, dividers
   and scrollbars no longer use teal as decoration. Code inspection keeps a neutral surface; live stops
   retain their amber treatment.
   This is a presentation scaffold: shared tokens and header geometry are extension
   points. Flow, Contexts, Watches and the execution toolbar also use neutral chrome
   and quieter rows/actions; fonts and docking are not redesigned.
   Before visual acceptance, check opacity, narrow/wide layouts,
   truncation warnings and selected/live stage contrast in the native client.
   The code pane uses the default Minecraft font and measures its actual glyph
   advances; Unicode comments retain their glyphs. Commands, `execute` keywords, strings, values,
   resource IDs and comments receive display-only lexical highlighting.
   Source lines stay intact and do not wrap or gain ellipses. Drag the vertical
   scrollbar or use the vertical wheel to reach original lines. Drag the horizontal
   scrollbar, use a horizontal wheel/Shift+wheel, or focus the code and use Left/Right;
   the line numbers and breakpoint gutter stay fixed.
2. Select an executable line. Toggle its gutter marker to the **left of the line number**.
   The line number itself only selects the row. A line with exactly one server-parsed stage
   has only the line control: no inline stage marker, stage hit box, or Stage condition button.
   Gutter hover describes the exact whole-line condition and its two mouse actions.
   A separate stage count remains visible without aliasing a saved legacy stage into the line target.
   For multiple-stage lines, toggle the parsed stage's marker. Right-click a marker
   to open its exact condition editor directly. Right-click line/stage text to keep
   using its condition menu. The marker
   before the line number always owns the line target; a stage-prefix marker always
   owns its exact stage target. No line-versus-stage chooser is shown.
   The two header buttons are removed, including their extra 22-pixel compact row.
   Shift+F10 on the current marker opens its editor directly; other source code
   focus retains its line/stage menu. Closing the editor retains the exact marker
   target. Opening/cancelling does not create a breakpoint. Menus support arrows,
   Tab, Enter/Space, Escape and outside dismissal.
   Right-click does not change the inspected line/stage. Breakpoints refer to original
   file line numbers and saved stage
   offsets, not wrapped display rows. Stage markers are inserted at the server-confirmed boundaries inside the
   original row, following the command-block editor. Visual marker slots do not
   change source characters, whitespace or server offsets. Each visible stage marker
   uses a 20-pixel slot, with the icon centered and the whole slot clickable.
   Enabled line/stage markers remain visible; disabled or missing candidates appear
   only over their original line/stage. Selection and pause do not reveal an inactive
   marker. Leaving a stage removes its temporary slot and restores original glyph
   advances; enabled markers keep their necessary slot. No separate stage row or
   panel is shown. Hover the gutter marker for the enabled stage count.
   Clicking a line/stage no longer adds a condition-edit selection tint. A brighter
   neutral line number retains the keyboard navigation position. The actual stopped
   stage keeps its amber background (44% tint), using the top live
   pause frame's stage index only when its location and command match the source.
   A live stopped row requests its stage preview without requiring selection,
   hover or an enabled stage breakpoint.
   Selecting another stage/frame does not move that pause highlight. Unknown
   stage identity, changed commands and completed execution do not imply a live
   stage stop. If reload removes the selected stage, the row retains its selection
   line-number cue. Selection retains original text advances and stage/marker hitboxes.
3. Close the viewer and execute `/function <namespace:path>`. Check the breakpoint
   stops at the selected location. Reopen Source at the pause and distinguish the
   actual stopped line from a manually inspected line/record. A live pause has an
   arrow and amber row; ordinary line selection uses a neutral line-number cue.
   Use **Go to stop** in the Source header immediately after **Reread file**. In the paused
   function it selects and reveals the live paused line, resetting horizontal scrolling to its
   beginning; in any other file it opens the paused function's source and reveals that line
   once it loads. The action supports normal Tab/Enter activation and leaves debugger
   frame/flow selection intact. The button carries no line number. In the paused function it
   appears only for a ready document with an in-range line whose command matches the live
   pause; elsewhere it appears whenever a pause is live and no source is loading, and a
   command that no longer matches after loading opens the file without moving. Pending
   debugger controls disable it. Loading, changed source commands and resumed records cannot
   supply a live destination. Tight headers use an arrow with the full action tooltip;
   Back and Close remain separate. Tab reaches the action immediately after Reread
   file; hiding or disabling it releases its focus for code navigation. The status
   row retains the execution status and truncated-source warning.
   Actual pause highlighting remains independent of that navigation position. After Continue, the retained
   location is labelled as a recorded line and has no live-pause arrow.
4. With execution resumed, change/reload the scratch datapack. Use **Refresh list**
   in the Functions header to update the server's loaded function list and **Reread file**
   above the code to reread the selected source from active server resources.
   Their tooltips are one short line each: Refresh names its source (the server's
   loaded functions) and Reread says it does not run `/reload`.
   In drawer mode, list refresh appears with the open drawer and file reread with the code.
   Removed
   functions and stale stage targets must be represented explicitly. Hover a changed,
   unselected line after reload: a READY preview for the old command must refresh
   once; LOADING retains its in-flight request. An obsolete enabled fingerprint or
   server-confirmed stale target keeps an amber `!` review warning left of the line
   number, including after the new preview has nonempty stages. It never becomes a
   current stage marker or click target.
5. At a narrow GUI, open the Functions drawer, select the long line and scroll horizontally to its
   later stage markers. At a wider GUI, verify the docked viewer and parent controls. Text,
   markers and condition controls must remain reachable after resizing. Drag the
   divider to resize the function list (150 logical pixels minimum; reserve at least
   300 panel pixels for Source). Its requested width survives closing/reopening,
   compact drawer transitions and UI scale/window changes. A smaller wide viewport
   temporarily clamps it. Scrollbars preserve thumb grab position and clamp at both
   ends; dragging one axis preserves the other. The drawer, Source panel and modal
   must block hover and hit testing on controls they cover.
6. Use **Find in source** (`Ctrl/Cmd+F`) for literal case-insensitive search. Enter/F3
   advances, Shift+Enter/Shift+F3 goes back, and the buttons expose the same actions.
   Results include comments, retain original line numbers, and reveal matches past
   the horizontal viewport. Find retains and highlights the first 1,000 occurrences
   in source order and cycles within those results. An extra occurrence adds `+`
   to the count; hover the Find field for the limit explanation. The Find field and
   horizontal scrollbar have no standing key-list tooltip; those keys are in Help > Controls. Narrow
   the query to reach later occurrences. A query with no matches shows `0/0` and disables result
   buttons. Query and viewport survive resizing and the compact drawer rebuild.
   When the query survives a function switch or Reload, the first new result is
   selected and revealed without pressing Next if the previous result cannot be
   restored. An unchanged rebuild retains the selected result and viewport.
7. Click a code line, or press Esc from Find, to focus code navigation. Up/Down,
   PageUp/PageDown and Home/End select original lines. Tab/Shift+Tab traverses visible
   controls; with no focused widget, Tab starts at the first active visible control
   and Shift+Tab starts at the last.
   Navigation keys do not change Source selection or scrolling while a toolbar
   button or text field has focus. Find and function-list Search retain text input
   when a typed key is bound to a debugger shortcut, including cursor-mode `V` and
   Keep Freecam `G`. Escape, Tab and Find shortcuts retain their behavior; parent
   debugger shortcuts remain available after text focus leaves the field.
   Follow underlined loaded function references and use Back; the caller's
   line/stage and horizontal viewport must return. References in `return run function`
   and `schedule function` are linked; matching words in `say` text or comments are not.
   Unqualified `function helper` follows Minecraft 26.3's `minecraft:helper` default,
   even when the caller is `pack:main` and `pack:helper` is also loaded. Link hit boxes
   share the source row's half-open bounds and are clipped to the source viewport;
   the first pixel of the next row belongs to that next row. Source text remains read-only.
   Execute arguments literally named `run` (including score holders/objectives)
   do not begin a nested command. The lexical viewer skips known clause arguments;
   an unknown or incomplete clause suppresses subsequent nested links instead of
   guessing a delimiter. Server stage parsing and command execution are unchanged.
   The 26.3 `if`/`unless items` and `slots` entity/block forms are recognized:
   `items` consumes a slot source and item predicate; `slots` consumes only a slot
   source. Player names such as `run`, wildcard slots and quoted component predicates
   keep their argument positions and do not invent a nested function link.

Owner permission is required for server source requests. This is a source browser
and breakpoint editor, not an in-game datapack file editor.

Disabled line/stage markers are hidden until their actual line/stage is hovered;
enabled markers remain visible whether selected or hovered. Disabled definitions
are excluded from stage counts. Hover the original target to enable it again with
its saved condition. Native horizontal delta is positive toward the right; a
negative vertical delta with Shift moves right. Native X takes precedence when
both axes arrive with Shift, avoiding double inversion. Minecraft 26.3 uses SDL;
[SDL wheel semantics](https://wiki.libsdl.org/SDL3/SDL_MouseWheelEvent) and the local
MouseHandler/SDL event-handler path were checked without changing macOS natural
scroll settings. Synthetic callback input does not verify a physical trackpad.

## Code entry points

- [BreakpointTargetPolicy](../../../src/client/java/works/nuty/codon/client/state/BreakpointTargetPolicy.java): single-stage target selection shared with Flow and the command editor; partial recording never establishes a one-stage command.
- [FunctionSourceScreen](../../../src/client/java/works/nuty/codon/client/ui/FunctionSourceScreen.java), [SourceCodeLine](../../../src/client/java/works/nuty/codon/client/ui/SourceCodeLine.java), [SourceSyntax](../../../src/client/java/works/nuty/codon/client/ui/layout/SourceSyntax.java), [SourceLineLayout](../../../src/client/java/works/nuty/codon/client/ui/layout/SourceLineLayout.java), [CommandFlowLayout](../../../src/client/java/works/nuty/codon/client/ui/layout/CommandFlowLayout.java): tree, lines and stage layout.
- [ClientFunctionSourceState](../../../src/client/java/works/nuty/codon/client/state/ClientFunctionSourceState.java): list/source requests and browsing state.
- [FunctionSourceRepository](../../../src/main/java/works/nuty/codon/adapter/FunctionSourceRepository.java): loaded server functions and source reads.
- [FunctionSourceDocument](../../../src/core/java/works/nuty/codon/core/model/FunctionSourceDocument.java): source lines and metadata.

## Choose verification

| Concern | Existing tests |
| --- | --- |
| Line/source model | `coreTest`: `FunctionSourceDocumentTest` |
| Requests, selection and layout | `clientTest`: `ClientFunctionSourceStateTest`, `ClientTransferLimitsTest`, `FunctionSourceScreenLayoutTest`, `SourceSyntaxTest`, `SourceLineLayoutTest`, `SourceInteractionTest`, `ClientStagePreviewStateTest`, `ScrollbarInputTest`, `CommandFlowLayoutTest` |
| Network payloads | `test`: `FunctionSourcePayloadTest`, `BreakpointStagePreviewPayloadTest` |
| Line gutter, one-stage suppression, EN/KO/custom scale and hit boxes | `FunctionLineBreakpointGameTest` |
| Real rendering, selection, resize and inline stage markers | `FunctionSourceScreenGameTest`, `FunctionSourceInteractionGameTest`, `FunctionSourceReviewGameTest` |
| Header stop navigation, Reread/stop Tab order, focus lifecycle, EN/KO minimum and custom scales | `FunctionSourceScreenGameTest` |
| Active stage readability, adjacent stages, horizontal clipping and representative scales | `FunctionSourceStageHighlightGameTest` |
| Source toolbar icon, state styling, scale readability and native activation | `DebuggerSourceIconGameTest` |
| F3/Shift+F3 press/repeat/release ownership and vanilla behavior outside Source | `FunctionSourceKeyboardGameTest` |
| Exact gutter target with missing/loading/stale previews, direct marker editor/cancel, parsed as/at/run/function marker targets | `FunctionLineBreakpointGameTest`, `FlowBreakpointInteractionGameTest` |
| Focused Find/function-list Search key press before character input, bound/unbound/remapped cursor-mode keys, parent shortcuts and focus navigation | `FunctionSourceTextInputGameTest` |

Example: `./gradlew runClientGameTest -PclientGameTest=FunctionSourceScreenGameTest`.

`FunctionSourceStageHighlightGameTest` compares native source-row pixels before and
after selecting adjacent stages in five representative scenarios: minimum 1.00×,
fractional 1.25×/2.25× clipping, and maximum 4.50× compact/tail views in a
1920×1080 viewport. The native pixel comparison preserves glyph colors and coverage,
including the first/last viewport pixels. Manual selection must leave code pixels
unchanged; only the authoritative stopped stage receives the expected amber fill. Partially
transparent font texels must retain the same syntax color and alpha coverage over
both backgrounds, within 8-bit blend rounding.
Minecraft's rounded scissor bounds constrain the fill; the comparison still includes
the full logical viewport and requires pixels outside that native clip to stay identical.
Neighboring stages stay identical. Native clicks at both ends of each visible text hitbox preserve
the original stage target, enabled breakpoint and condition. Scrolled captures cut
through a stage at each edge and reach the long line's tail. A one-pixel mutation
over glyphs or background at either clipped edge must fail the same comparator.
The pause checks first verify preview loading without hover, selection or enabled
breakpoints. Six additional fractional-scale captures verify unknown/live stage identity,
stronger amber pause highlighting alongside manual/other-frame selection, stale
commands, execution completion and resume. One reload capture checks the row
selection fallback when its stage disappears. The five selection scenarios and state checks
retain 22 screenshots rather than the prior 135-capture scale/viewport matrix. Inspect
`*codon-stage-highlight-*.png`. The injected document/parse spans establish client
presentation and interaction; real server source discovery and stage breakpoints
remain separate checks.

`FunctionSourceTextInputGameTest` uses a real docked `CodonScreen` parent and sends
press/repeat/release and character callbacks through Minecraft's `KeyboardHandler`.
It checks that the press keeps Source open before `charTyped`, then verifies text,
cursor movement, Find shortcuts, Tab traversal and parent shortcuts after focus
leaves the field. Its source document is a client fixture; it does not establish
physical keyboard/IME behavior or server-driven breakpoint execution.

`DebuggerSourceIconGameTest` captures normal, hovered, keyboard-focused and disabled
Source buttons at every quarter step from 1.00× to 4.50× (including the larger-window
extension at 1920×1080). Native pixel assertions require separate outward chevrons
and a forward slash with unchanged state colors. The real toolbar captures retain
the Source tooltip and keyboard focus; Enter and clicks at both corners of the
20×20 target open the same viewer. This verifies presentation and activation,
not server source discovery or breakpoint execution.

Source owns F3/Shift+F3 releases as well as their Find navigation presses, so the
vanilla debug overlay retains its current visibility. `FunctionSourceKeyboardGameTest`
calls the real KeyboardHandler with press/repeat/release events, checks both overlay
states and unfocused/empty Find, then verifies vanilla F3/Shift+F3 after closing Source.
The same GameTest checks the proportional default font, Unicode geometry, inline
stage targeting and condition preservation, keyboard selection, horizontal-wheel
state, literal search next/previous,
compact query/viewport retention and source immutability. Inspect the `code-*`
captures for distinct pause/selection rows, a long-line tail, highlighted search,
Unicode comments and the empty result/resumed-record presentation.
The same check covers initial Tab/Shift+Tab and bidirectional wrap with results
and without them. `code-find-capped` shows the count and limit tooltip for a
700,000-character comment fixture; `code-find-narrowed` reaches its last line
after narrowing the query. Unit checks cover the global cap, exact-limit versus
extra-occurrence detection, original offsets and early termination without
reading later lines.
The `320x240-eof-inline` capture checks the native minimum viewport: the final
visible source row exposes its stage controls while the scrollbar stays below it.
The same pane geometry is unit-checked at 320×180 logical size. The
`custom-scale-inline` capture checks native stage input with independent 1.5× Codon scale.
The `nested-function-links` capture checks nested return and schedule references.
The `hover-browsed-*` and `hover-paused-*` captures use pixel assertions for
enabled, disabled and missing stage markers before hover, during hover and after
pointer leave. Enabled markers stay visible; selecting a disabled stage's text
does not retain its marker. Clicking the enabled stage also covers selected enabled and
unselected disabled states. The `matrix-enabled-*-selected-*` captures additionally
assert the complete enabled × hover × selection matrix for both line/stage icons,
and verify that pointer leave restores every original advance except enabled slots
when no marker owns keyboard focus. Hover preserves acknowledged state; clicking
each hovered slot requests its original stage target, including a missing breakpoint.
The clicked pending marker remains visible after pointer leave; Tab clears that
exact focus, after which inactive slots disappear without changing acknowledged state.
The `inline-second-line` capture verifies stage targeting after horizontal scroll
on an indented original line. The following source line stays directly below it
and can be selected without an expanded stage row intercepting the click.

Inspect `*codon-function-source-*.png`, including 320×240, 480×270 and 640×360 GUI
layouts. The disabled-hover capture shows a disabled line and conditional stage
revealed by hover; inactive controls without keyboard focus stay hidden elsewhere while enabled markers
remain visible. This GameTest injects a source document, breakpoint definitions and stage spans: its function is
not installed in the server's datapack. It proves presentation/interaction, not
server source discovery, permission enforcement or native function breakpoints.
Use the manual loaded-function path for those acceptance criteria.

The same GameTest verifies the header's **Go to stop** with native mouse and keyboard
activation, adjacent Reread/stop Tab order, an independently inspected historical
frame, pending-control and source-loading gates, stale command/resume changes
between render and activation, and focus release/retention across state changes
and resize. Back/Close remain separate in EN/KO minimum layouts and Korean 1.25×/4.50×
views; a second scenario opens it from a different file. Inspect `go-to-stop-live`, `go-to-stop-english-minimum`,
`go-to-stop-korean-minimum`, `go-to-stop-korean-1.25x` and `go-to-stop-korean-4.5x`.
These injected pause/source fixtures establish client
navigation and presentation; they do not establish server breakpoint execution
or unsolicited datapack revision detection.

For search rebuilds, enter a query that excludes another known function, resize
the window, and open the compact Functions drawer. The query and filtered list
must survive both rebuilds (`FunctionSourceScreenGameTest`).

After each rebuild, click the remaining result and the row where the excluded
function used to appear: only the matching function may be selected. With a long
list, scroll down, enter a new query, then resize; the first filtered result must
still be at the top instead of restoring the old scroll position.

`FunctionSourceInteractionGameTest` exercises Minecraft's native horizontal callback,
fractional X accumulation, Shift+vertical/native-X precedence, native vertical wheel
and both scrollbar drags. Its `qa-*` captures show vertical EOF, both bars, sidebar
maximum/minimum, custom scale, the compact drawer, a real HUD control before/after
Source covers it, a modal blocking Source and Korean minimum layout. It uses the
same injected-source limitation as the other UI fixture. Physical trackpad input
and the manual loaded-function path remain separate acceptance checks.

`FunctionListScrollbarGameTest` covers the Functions tree's native wheel direction,
track clicks, captured thumb drag, source-scroll isolation and row hit clipping.
It checks text focus/Tab, modal blocking, disappearance during a drag, empty and
exact-fit filters, one-row overflow, namespace collapse, taller windows, fractional
custom scale and the compact drawer. Its `functions-scrollbar-*` screenshots show
the track at the end/start, custom scale and a filtered drawer without overflow.
The decoded function/source fixtures establish UI behavior; they do not establish
real server function discovery or physical trackpad behavior.

`FunctionSourceReviewGameTest` targets the adjacent-row link boundary, unqualified
identifier collision and two cached previews followed by reload of an unselected
row. Its `review-*` captures show the reference targets and an obsolete fingerprint's
persistent amber review warning beside the new candidate. It asserts no repeated
LOADING request, no selection change during hover, retention of the other preview,
and no obsolete stage hit target. `test --tests '*SourceReviewRuntimeTest'` also
calls the actual 26.3 `Identifier.parse` without opening a game window.
The same GameTest's `followup-*` captures and assertions cover score arguments
named `run`, loaded function names that occur as score arguments, Find retained
through loading/function switch/Reload, and all eight navigation keys while a
toolbar button has focus. Code focus keeps its original keyboard navigation.
Its `items-slots-*` captures additionally verify the standard entity/block ×
if/unless × items/slots forms against the actual 26.3 server dispatcher (parse only),
then assert and click exactly the loaded nested function target. Relative/absolute
block positions, literal/wildcard slots, item/wildcard/component predicates and a
condition chain retain their links. Unit checks also cover literal `run` slot and
predicate tokens, unsupported target kinds and incomplete arguments. The lexical
viewer does not validate item/slot registry entries or execute these conditions.

Source gutter markers now read, toggle and edit only the exact whole-line target.
They do not alias a saved legacy stage-zero target into the line control. A stage
marker edits its own stage index/fingerprint, and the condition editor retains that
target through Save/Delete/Cancel. Existing saved definitions are not migrated or
deleted by display/navigation. `BreakpointTargetPolicyTest`,
`FunctionLineBreakpointGameTest` and `SingleStageBreakpointGameTest` check the exact
target mapping while retaining separate legacy definitions.

An inactive line/stage marker stays visible for the entire condition edit, including after
the pointer leaves and while a selector menu is open. Only the edited marker is retained;
unrelated inactive candidates remain hidden. Cancel, Escape and Delete retain the exact
marker/slot while it remains the Shift+F10 keyboard target; explicit navigation clears
that focus and hides an inactive marker again. This does not enable or create a definition.
Save enables the exact marker target after server
acknowledgement; saving a gutter line does not rewrite a separate legacy stage-zero definition. `BreakpointConditionVisibilityGameTest`
checks native pixels, line/stage/legacy/new targets, menus, retained focus after Cancel/Escape,
explicit navigation clearing, and real server Save/Delete edits
in English and Korean at a fractional custom scale. A controlled pending request also verifies
that condition-editor access is blocked until acknowledgement, without restoring removed header buttons. Its source page is a presentation fixture
matching the loaded `codon_test:condition_visibility` test function; it does not claim a native pause.

Context/navigation follow-up: menus and editors close when their source document,
function/world or confirmed stage identity becomes obsolete. They consume outside
clicks and restore parent focus. A breakpoint-list destination opens the original
function line and horizontally reveals an exact matching stage after its preview
arrives; stale fingerprints retain the line without selecting a different stage.
The [UI validation record](../ui-polish-validation.md) records passing Source
layout, native input, marker visibility and neutral/paused pixel checks. Manual
physical input and the full reload/invalidation acceptance path remain separate
from these selected regression scenarios.


Source receive assembly independently enforces the server reader's existing 20,000
line and 700,000 UTF-16 character limits. Function lists permit 32,768 identifiers
and 4,194,304 identifier characters. Each transfer is limited to 1,024 pages within
30 seconds from its first accepted page; further packets do not extend the deadline.
Empty intermediate pages and aggregate overflow fail with ERROR and discard staged
data. A valid empty final page remains supported, and user refresh starts a new
request. Unicode text is preserved exactly. `ClientTransferLimitsTest` exercises the
maximum Unicode document, one-extra-line and character overflow, timeout and recovery.

Observed locations are checked for stage-preview representability before creating
pending state or a network payload. Unknown/zero function lines, oversized fields and
control characters remain their original observed values but cannot trigger an
automatic preview request. `StagePreviewLocationTest`, `NetworkCodecsTest` and
`BreakpointStagePreviewPayloadTest` cover the request predicate and exact wire limits.
The latter headless test calls the central request helper for hostile locations without
initializing Minecraft; native HUD validation remains a separate GameTest requirement.
