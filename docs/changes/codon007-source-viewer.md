# CODON007: read-only function source viewer

The source browser clipped long commands and displayed source in the proportional
UI font. Selecting a parsed line replaced the original text with wrapped stage
controls. This change keeps the original line visible and gives it a code font,
display-only lexical spans, a fixed gutter and horizontal navigation.

Scope is the client viewer. Existing loaded-function search, folder drawer,
function references/Back, server-acknowledged breakpoints and condition controls
remain. Find-in-source and keyboard line navigation operate on original line
indices. Source editing/saving is a separate low-priority backlog item and is
excluded. No source payload, server execution or persisted datapack is changed.

The code font references Minecraft's bundled Unihex asset, fixes printable ASCII
and space advances, and retains Unicode glyphs without bundling a font binary.
Glyph positions and syntax spans are cached per source revision/rebuild. Only
visible source slices are submitted to the renderer; matches are indexed by line.
Stage details keep the existing server-provided offsets and wrap separately.
Pause arrows and selection outlines provide a shape cue in addition to color.

The independent clone started at `17cfcb81580ae4c9185786975db22ca15ddd2d84`
and was rebased onto main `e06b8f34bb33a228a920041092c95f63dd1b5267` before the PR.
CODON006 owns the two-line `ScaledCodonScreen` inheritance/constructor change:
this branch keeps `Screen`/`super(title)` and uses the existing screen coordinates.
When combining branches, preserve CODON006's class/super changes; no additional
scale transform belongs here.

## Verification

JDK 25.0.2 on macOS. Relevant checks:

- `./gradlew --offline clientTest --tests '*SourceSyntaxTest' --tests '*ClientFunctionSourceStateTest' --tests '*FunctionSourceScreenLayoutTest' compileGametestJava`: passed, 11 selected JVM tests.
- `./gradlew --offline compileGametestJava build`: passed; packaged JAR and configured JVM checks.
- `./gradlew --offline runClientGameTest -PclientGameTest=FunctionSourceScreenGameTest`: passed, exit 0 in 19 seconds. All 15 screenshots inspected; Minecraft shutdown confirmed.

The configured JVM suites passed 392 tests (209 client, 98 core, 85 adapters).
The GameTest also follows a rendered function reference and verifies that Back
restores the exact caller line and horizontal viewport.

Local reports: `build/reports/tests/`; runtime screenshots/logs:
`build/run/clientGameTest/`. Keep runtime evidence out of Git.
The fixture injects source/parse spans and a synthetic pause. It establishes client
presentation and interactions; it does not establish native datapack discovery,
source permissions, native breakpoint execution or dedicated-server behavior.
Local evidence is preserved outside the checkout at
`../evidence/codon007/final-local/` (15 screenshots, client logs, Gradle console).
The draft PR records its exact-head CI results separately.
