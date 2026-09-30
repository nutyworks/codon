# Read-only function source viewer

## Changes

The viewer uses the default Minecraft font, including its Unicode glyphs and
resource-pack metrics. Display-only syntax highlighting, fixed line numbers and
a breakpoint gutter improve readability while long source lines scroll horizontally.

Selected source rows place stage breakpoint controls between the server-confirmed
command segments, following the command-block editor. Original characters and
spacing are retained; markers add small display slots. Stage controls share the
original row, with no duplicate command or separate stage panel. Enabled markers
remain visible; hovering a stage reveals its disabled marker and saved condition.

Literal Find, keyboard navigation, full paths, function links and Back navigation
are retained. Search highlights, links and marker hitboxes use the same measured
glyph advances and marker slots. Live pause arrows and selection outlines remain
distinct. Editing, saving and server execution semantics are unchanged.

## Verification

- `./gradlew build`: 407 JVM tests passed.
- `./gradlew runClientGameTest -PclientGameTest=FunctionSourceScreenGameTest`
  checks the default font, Unicode, inline stage selection/toggling, retained
  conditions, original row positions, EOF controls, nested function links, Find,
  horizontal scroll, resize, native input at independent 1.5× UI scale and Back.
  Inspect the generated `codon-function-source-*` captures.
- The GameTest injects source, stage spans and a pause to check client presentation;
  it does not establish native datapack discovery, permissions or function execution.

See the [function source verification guide](../verification/features/function-source.md)
for reproduction steps and evidence expectations.
