# Release notes

## 0.1.0-alpha.1 — alpha prerelease

First alpha prerelease for Codon, intended for early testing and feedback.

### Included capabilities

- Command-block, function-line and command-stage breakpoints, with conditions
  and world-persistent definitions.
- Continue, step into/over/out and a final execution-complete inspection stop.
- Recorded command stages, call paths and `execute` context visualization, with
  explicit warnings when observation is incomplete or truncated.
- Scoreboard, entity NBT and storage NBT Watches, entity-field pinning and saved
  Watch definitions.
- Read-only browsing of loaded function source and breakpoint editing.
- Detached camera navigation, optional camera retention while running, cursor
  mode, tap/hold UI hiding and independent Codon UI scale.
- English and Korean interface translations.

### Requirements and caveats

Minecraft Java Edition 26.3, Fabric Loader 0.19.5+, Fabric API for 26.3 (build
dependency `0.160.5+26.3`) and Java 25+. Install the same Codon version on client
and server. Debugger operations require owner command permission (vanilla level 4).

Pausing affects the whole server. Use a disposable or backed-up world. This alpha
may contain UI, execution-observation and compatibility bugs; it does not claim a
full platform or dedicated-server test matrix. See the [installation guide,
controls and limitations](README.md) before use.
