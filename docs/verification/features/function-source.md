# Function source

## User path and expected result

Use the [shared setup](../README.md#prepare-and-launch) with a loaded scratch
datapack containing a known function. Record its resource ID and file text; include
a comment/blank line and a long `execute` line so line numbering and wrapping can
be checked. The viewer lists functions actually loaded by the current server.

1. Press `V`, then the toolbar's **Source** icon. Search by namespace/path, expand
   folders and select the function. Its source appears read-only; browsing does
   not require executing it.
2. Select an executable line. Toggle the line marker; select a parsed stage and
   toggle its marker. Use **Line condition…** or **Stage condition…** to edit
   conditions. Breakpoints refer to original file line numbers and saved stage
   offsets, not wrapped display rows.
3. Close the viewer and execute `/function <namespace:path>`. Check the breakpoint
   stops at the selected location. Reopen Source at the pause and distinguish the
   actual stopped line from a manually inspected line/record.
4. With execution resumed, change/reload the scratch datapack. Use Refresh to
   update the function list and Reload to reread the selected source. Removed
   functions and stale stage targets must be represented explicitly.
5. At a narrow GUI, open the Functions drawer, select the long line and scroll its
   stages. At a wider GUI, verify the docked viewer and parent controls. Text,
   markers and condition controls must remain reachable after resizing.

Owner permission is required for server source requests. This is a source browser
and breakpoint editor, not an in-game datapack file editor.

## Code entry points

- [FunctionSourceScreen](../../../src/client/java/works/nuty/codon/client/ui/FunctionSourceScreen.java), [CommandFlowLayout](../../../src/client/java/works/nuty/codon/client/ui/layout/CommandFlowLayout.java): tree, lines and stage layout.
- [ClientFunctionSourceState](../../../src/client/java/works/nuty/codon/client/state/ClientFunctionSourceState.java): list/source requests and browsing state.
- [FunctionSourceRepository](../../../src/main/java/works/nuty/codon/adapter/FunctionSourceRepository.java): loaded server functions and source reads.
- [FunctionSourceDocument](../../../src/core/java/works/nuty/codon/core/model/FunctionSourceDocument.java): source lines and metadata.

## Choose verification

| Concern | Existing tests |
| --- | --- |
| Line/source model | `coreTest`: `FunctionSourceDocumentTest` |
| Requests, selection and layout | `clientTest`: `ClientFunctionSourceStateTest`, `FunctionSourceScreenLayoutTest`, `CommandFlowLayoutTest` |
| Network payloads | `test`: `FunctionSourcePayloadTest`, `BreakpointStagePreviewPayloadTest` |
| Real rendering, selection, resize and stage scrolling | `FunctionSourceScreenGameTest` |

Example: `./gradlew runClientGameTest -PclientGameTest=FunctionSourceScreenGameTest`.
Inspect `*codon-function-source-*.png`, including 320×240, 480×270 and 640×360 GUI
layouts. This GameTest injects a source document and stage spans: its function is
not installed in the server's datapack. It proves presentation/interaction, not
server source discovery, permission enforcement or native function breakpoints.
Use the manual loaded-function path for those acceptance criteria.

For search rebuilds, enter a query that excludes another known function, resize
the window, and open the compact Functions drawer. The query and filtered list
must survive both rebuilds (`FunctionSourceScreenGameTest`).
