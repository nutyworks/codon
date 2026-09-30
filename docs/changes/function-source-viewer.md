# Read-only function source viewer

## Changes

The viewer keeps original mcfunction lines visible in a Minecraft resource font
with fixed ASCII advances and Unicode glyphs. Display-only highlighting separates
commands, arguments and comments. Line numbers and breakpoint controls stay fixed
while long source lines scroll horizontally; existing stage controls wrap below
the selected line.

Literal source search, keyboard navigation, full path tooltips and retained
function-reference Back navigation improve browsing. A live pause uses an arrow
and amber row, while selection uses an outline. Searching within the same line
retains its acknowledged stage preview. Glyph geometry and syntax spans are cached,
and only visible source slices are rendered. Function editing/saving and server
execution semantics are unchanged.

## Verification

- `./gradlew build`: 392 JVM tests passed.
- `./gradlew runClientGameTest -PclientGameTest=FunctionSourceScreenGameTest`:
  source font advances, Unicode comments, original line/stage selection, disabled
  marker hover, source search, horizontal navigation, resize and function-reference
  Back navigation passed. Inspect the generated `codon-function-source-*` captures.
- The GameTest injects source, parse spans and a pause to verify client presentation;
  it does not establish native datapack discovery, permissions or function execution.

See the [function source verification guide](../verification/features/function-source.md)
for reproduction steps and evidence expectations.
