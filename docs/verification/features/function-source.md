# Function source

## User path and expected result

Use the [shared setup](../README.md#prepare-and-launch) with a loaded scratch
datapack containing a known function. Record its resource ID and file text; include
a comment/blank line and a long `execute` line so line numbering, horizontal source scrolling and inline stage markers can
be checked. The viewer lists functions actually loaded by the current server.

1. Press `V`, then the toolbar's **Source** icon. Search by namespace/path, expand
   folders and select the function. Its source appears read-only; browsing does
   not require executing it. The header shows the datapack-relative
   `data/<namespace>/function/<path>.mcfunction` path (hover for the full path).
   The code pane uses the default Minecraft font and measures its actual glyph
   advances; Unicode comments retain their glyphs. Commands, `execute` keywords, strings, values,
   resource IDs and comments receive display-only lexical highlighting.
   Source lines stay intact and do not wrap or gain ellipses. Drag the horizontal
   scrollbar, use a horizontal wheel/Shift+wheel, or focus the code and use Left/Right;
   the line numbers and breakpoint gutter stay fixed.
2. Select an executable line. Toggle the line marker; select a parsed stage and
   toggle its marker. Use **Line condition…** or **Stage condition…** to edit
   conditions. Breakpoints refer to original file line numbers and saved stage
   offsets, not wrapped display rows. Stage markers are inserted at the server-confirmed boundaries inside the
   original row, following the command-block editor. Visual marker slots do not
   change source characters, whitespace or server offsets. No separate stage row
   or panel is shown. The vertical wheel moves original lines; the horizontal
   wheel exposes long lines and their markers. Stage counts stay in the gutter.
3. Close the viewer and execute `/function <namespace:path>`. Check the breakpoint
   stops at the selected location. Reopen Source at the pause and distinguish the
   actual stopped line from a manually inspected line/record. A live pause has an
   arrow and amber row; selection has an outlined row. After Continue, the retained
   location is labelled as a recorded line and has no live-pause arrow.
4. With execution resumed, change/reload the scratch datapack. Use Refresh to
   update the function list and Reload to reread the selected source. Removed
   functions and stale stage targets must be represented explicitly.
5. At a narrow GUI, open the Functions drawer, select the long line and scroll horizontally to its
   later stage markers. At a wider GUI, verify the docked viewer and parent controls. Text,
   markers and condition controls must remain reachable after resizing.
6. Use **Find in source** (`Ctrl/Cmd+F`) for literal case-insensitive search. Enter/F3
   advances, Shift+Enter/Shift+F3 goes back, and the buttons expose the same actions.
   Results include comments, retain original line numbers, and reveal matches past
   the horizontal viewport. A query with no matches shows `0/0` and disables result
   buttons. Query and viewport survive resizing and the compact drawer rebuild.
7. Click a code line, or press Esc from Find, to focus code navigation. Up/Down,
   PageUp/PageDown and Home/End select original lines. Tab/Shift+Tab traverses visible
   controls. Follow underlined loaded function references and use Back; the caller's
   line/stage and horizontal viewport must return. References in `return run function`
   and `schedule function` are linked; matching words in `say` text or comments are not. Source text remains read-only.

Owner permission is required for server source requests. This is a source browser
and breakpoint editor, not an in-game datapack file editor.

Disabled line/stage markers are hidden until hovered, and are excluded from stage
breakpoint counts. Hover the original target to enable it again with its saved
condition; enabled markers remain visible without hovering.

## Code entry points

- [FunctionSourceScreen](../../../src/client/java/works/nuty/codon/client/ui/FunctionSourceScreen.java), [SourceCodeLine](../../../src/client/java/works/nuty/codon/client/ui/SourceCodeLine.java), [SourceSyntax](../../../src/client/java/works/nuty/codon/client/ui/layout/SourceSyntax.java), [SourceLineLayout](../../../src/client/java/works/nuty/codon/client/ui/layout/SourceLineLayout.java), [CommandFlowLayout](../../../src/client/java/works/nuty/codon/client/ui/layout/CommandFlowLayout.java): tree, lines and stage layout.
- [ClientFunctionSourceState](../../../src/client/java/works/nuty/codon/client/state/ClientFunctionSourceState.java): list/source requests and browsing state.
- [FunctionSourceRepository](../../../src/main/java/works/nuty/codon/adapter/FunctionSourceRepository.java): loaded server functions and source reads.
- [FunctionSourceDocument](../../../src/core/java/works/nuty/codon/core/model/FunctionSourceDocument.java): source lines and metadata.

## Choose verification

| Concern | Existing tests |
| --- | --- |
| Line/source model | `coreTest`: `FunctionSourceDocumentTest` |
| Requests, selection and layout | `clientTest`: `ClientFunctionSourceStateTest`, `FunctionSourceScreenLayoutTest`, `SourceSyntaxTest`, `SourceLineLayoutTest`, `CommandFlowLayoutTest` |
| Network payloads | `test`: `FunctionSourcePayloadTest`, `BreakpointStagePreviewPayloadTest` |
| Real rendering, selection, resize and inline stage markers | `FunctionSourceScreenGameTest` |

Example: `./gradlew runClientGameTest -PclientGameTest=FunctionSourceScreenGameTest`.
The same GameTest checks the proportional default font, Unicode geometry, inline
stage targeting and condition preservation, keyboard selection, horizontal-wheel
state, literal search next/previous,
compact query/viewport retention and source immutability. Inspect the `code-*`
captures for distinct pause/selection rows, a long-line tail, highlighted search,
Unicode comments and the empty result/resumed-record presentation.
The `320x240-eof-inline` capture checks the native minimum viewport: the final
visible source row exposes its stage controls while the scrollbar stays below it.
The same pane geometry is unit-checked at 320×180 logical size. The
`custom-scale-inline` capture checks native stage input with independent 1.5× Codon scale.
The `nested-function-links` capture checks nested return and schedule references.
The `inline-second-line` capture verifies stage targeting after horizontal scroll
on an indented original line. The following source line stays directly below it
and can be selected without an expanded stage row intercepting the click.

Inspect `*codon-function-source-*.png`, including 320×240, 480×270 and 640×360 GUI
layouts. The disabled-hover capture shows a disabled line and conditional stage
revealed by hover; the other captures keep them hidden and count only the enabled
stage. This GameTest injects a source document, breakpoint definitions and stage spans: its function is
not installed in the server's datapack. It proves presentation/interaction, not
server source discovery, permission enforcement or native function breakpoints.
Use the manual loaded-function path for those acceptance criteria.

For search rebuilds, enter a query that excludes another known function, resize
the window, and open the compact Functions drawer. The query and filtered list
must survive both rebuilds (`FunctionSourceScreenGameTest`).

After each rebuild, click the remaining result and the row where the excluded
function used to appear: only the matching function may be selected. With a long
list, scroll down, enter a new query, then resize; the first filtered result must
still be at the top instead of restoring the old scroll position.
