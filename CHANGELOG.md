# Release notes

## 0.1.0-alpha.2 — alpha prerelease

First published alpha prerelease. 0.1.0-alpha.1 was prepared but never published, so
this release contains the alpha.1 capabilities below plus the following changes.

### Behavior changes since 0.1.0-alpha.1

- **Default stepping keys follow IDA:** `F7` steps into, `F8` steps over, `Ctrl+F7`
  steps out and `F9` continues. Existing profiles keep saved bindings; reset Step Into
  and Resume in Controls to adopt the new defaults.
- **Owner permission and traffic bounds:** every debugger request requires owner command
  permission (vanilla level 4). Server-side request, page and transfer limits apply.
- **Pause-correlated controls:** delayed controls aimed at an earlier pause are rejected.
  Rejected and timed-out requests appear as Codon toasts.
- **Oversized pauses stay paused:** a stop with more than 1,024 call-stack frames or 4,096
  execution contexts no longer appears as Running. The client stays paused with Continue
  and Step available, and the header says the details are too large to display.
- **Commands:** `/codon breakpoint block|function ... [stage <n>] [condition <condition>]`
  sets stage breakpoints and conditions.
- **Breakpoint condition editor:** unsaved drafts are protected when the editor is
  dismissed. Save is labelled "Save and enable". Delete is unavailable for a breakpoint
  that has not been saved yet.
- **Source viewer:**
  - navigates to the current stop;
  - `Ctrl+G` jumps to a line;
  - search matches every query term;
  - `Enter` opens the only search result;
  - the function list has its own scrollbar.
- **Readability:** text stays readable at low panel opacity and inside modal forms. The
  opacity control shows its percentage and steps by 10 with `Shift`+arrow keys. The idle
  "Running" badge can be hidden from the View menu and no longer covers the F3 screen.
- **Presentation:**
  - Watch actions show feedback and pin affordances;
  - changed contexts show a neutral dot;
  - breakpoint lists are ordered and their targets are clearer;
  - tooltips are shorter and appear after a hover delay;
  - more labels are translated into Korean.

### Known limitations

- No dedicated-server or cross-platform test matrix has been run for this prerelease.
- Deleting a saved breakpoint from the condition editor cannot be undone (#67).

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
