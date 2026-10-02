# Codon

Codon is an in-game step debugger for Minecraft commands and datapack functions.
Set command-block, function-line and command-stage breakpoints; step through
execution; inspect recorded call paths, `execute` contexts, scoreboard values and
NBT; and navigate the paused world with a detached camera.

**0.1.0-alpha.1 is an alpha prerelease.** Use a disposable world or a backed-up
copy first. Pausing stops server simulation for every player. Read the limitations
below before using it on a shared server. See the [alpha notes](CHANGELOG.md).

## Requirements and installation

| Component | Requirement for this alpha |
| --- | --- |
| Minecraft Java Edition | 26.3 |
| Fabric Loader | 0.19.5 or newer |
| Fabric API | A build for Minecraft 26.3; Codon builds against `0.160.5+26.3` |
| Java | 25 or newer; development builds use JDK 25 |
| Codon | The same version on the client and server |

1. Install a [Fabric Loader](https://fabricmc.net/use/) profile for Minecraft 26.3
   and configure the launcher to use Java 25 or newer.
2. Download the runtime `codon-0.1.0-alpha.1.jar` from the
   [GitHub prerelease](https://github.com/nutyworks/codon/releases), when available.
   Put it and the matching Fabric API JAR in that profile's `mods/` folder. A
   `-sources.jar` is for reading code and cannot be installed as the mod.
3. Start the Fabric profile and open a disposable world with cheats enabled.

For multiplayer, stop the server, back up its world, install Fabric Loader for
26.3, and put the same Codon runtime JAR plus Fabric API for 26.3 in the server's
`mods/` folder. Start it with Java 25 or newer. Install Codon and Fabric API on
each client that will use the debugger. A client-only installation cannot debug
a server without Codon. Keep client/server versions matched; the debugger uses
versioned custom network payloads.

## First breakpoint

1. In the scratch world, run `/give @s minecraft:command_block`. Place an impulse
   command block, enter `say codon check`, save it, and attach a button.
2. Aim at the block and press `F10` to toggle its breakpoint. Press the button:
   execution pauses before the command runs.
3. Press `V` for cursor mode. Inspect the current command and contexts, then use
   `F7` to step or `F9` to continue. Stepping to execution completion offers a final
   inspection stop; continue again to release it.
4. Open **Source** in the debugger toolbar to browse loaded datapack functions and
   set line/stage breakpoints. Source is read-only; change datapack files outside
   the viewer, resume execution, reload the datapack and refresh the viewer.

Watches support scoreboard scores, entity NBT and storage NBT. Use the toolbar's
Watches control to add a watch, or pin entity fields from the context inspector's
NBT tree. These inspection reads do not execute the watched command again.

## Default controls

Bindings can be changed in Minecraft's Controls menu under Codon's debugger
category. On keyboards with media keys, the function keys may require `Fn`.
Stepping and Continue work only at a live pause and wait for the server's reply.
The defaults follow [IDA stepping shortcuts](https://docs.hex-rays.com/8.5/user-guide/configuration/shortcuts).
Continue uses F9 to leave F7 available for Step Into. Step Out is Ctrl plus the
current Step Into binding, including a customized binding; on macOS use Ctrl, not Cmd.
Saved bindings are preserved. To adopt the new defaults in an existing profile,
reset only Step Into and Resume in Controls > Key Binds > Codon Debugger.

| Key | Action |
| --- | --- |
| `V` | Open/close debugger cursor mode |
| `F10` | Toggle a whole-command breakpoint on the targeted block |
| `F9` | Continue until another breakpoint or execution end |
| `F8` | Step over to the next observed stage at the same or shallower call depth |
| `F7` | Step into the next observed command stage, including deeper calls |
| `Ctrl+F7` | Step out to a shallower call depth; at the root, stop at execution completion |
| `G` | Toggle **Keep Freecam While Running** |
| `H` | Hide Codon UI immediately; a tap shorter than 250 ms toggles visibility, a hold restores the previous visibility on release |

While the camera is detached, movement keys navigate it, jump/sneak move vertically
and sprint accelerates. Cursor mode stops camera movement. With Keep Freecam
enabled, the player body resumes normal simulation when execution runs even
though the camera remains detached. Hiding UI does not resume execution; press
`F9` to continue. The hide gesture is inactive while typing in chat or text fields.
Use **View → Codon UI scale** to size Codon's interface independently of game GUI
scale. English and Korean interface translations are included.

## Permissions and pause impact

All `/codon` commands and server requests for Source, breakpoint edits, Watches
and NBT require Minecraft's **owner command permission** (`COMMANDS_OWNER`, level
4 in vanilla). Singleplayer requires cheats/owner access; on a dedicated server,
grant level-4 operator access only to the people allowed to debug it. Being able
to run ordinary level-2 commands is insufficient.

Command alternatives are `/codon breakpoint block <x> <y> <z>`,
`/codon breakpoint function <namespace:path> <line>` (one-based file line),
`/codon breakpoint list`, `/codon breakpoint clear`, `/codon resume`,
`/codon stepover`, `/codon stepinto` and `/codon stepout`. Stage breakpoints and
conditions are edited in the UI. Clearing deletes all world breakpoint definitions.

A breakpoint parks the server thread, so world ticks, physics, AI and ordinary
commands wait for resume across the server's worlds. The pause loop services
debugger requests and bounded connection maintenance. It publishes already-applied
world changes, but new chunk generation and effects requiring later ticks wait.
Resume promptly on shared servers. An authorized operator can also send
`codon resume` from the server console if the debugger client is unavailable.

Breakpoint definitions are shared world state stored in
`data/codon-breakpoints.json`. Watch definitions are stored per owner/player in
`data/codon-watches/`; client view preferences are in `config/codon.json`.
These definitions persist across sessions, so use a separate test world and
back up the complete world before experimenting. Continue paused execution before
leaving your debugging session.

## Prerelease limitations

- This alpha can contain bugs and UI rough edges. Compatibility with other mods,
  every operating system, dedicated-server deployment and long shared-server
  pauses is not established by a successful local build.
- The Source viewer does not edit datapack files. Recorded history is bounded
  inspection data, not a rewind or replay facility; browsing it does not undo
  world changes.
- Flow records have limits of 24 stages, 128 contexts, 256 edges and 32 captured
  stack frames. Unsupported modifiers and incomplete continuations may leave
  warnings or unavailable values. Missing data is not a measured zero.
- Stepping/call paths use observed command stages and call depth, rather than
  complete function-frame lifecycle information. Inspect the stop reason and
  recording warnings when interpreting a result.
- Small windows, long translated text and custom UI scale can expose layout
  limits. A physical window smaller than 320×240 may not fit all controls.

Report problems in [GitHub issues](https://github.com/nutyworks/codon/issues) with
the Codon/Minecraft/Fabric/Java versions, client/server setup and minimal steps.
Review logs and screenshots for private data before attaching them.

## Development

With JDK 25, run `./gradlew build`. The installable runtime artifact is produced
by `:jar` at `build/libs/codon-0.1.0-alpha.1.jar`; this build has no `remapJar` task.
The build compiles the mod and runs configured JVM tests; it does not launch
Minecraft or establish in-game acceptance. See the
[verification guide](docs/verification/README.md),
[architecture](docs/ARCHITECTURE.md) and
[manual alpha preparation checklist](docs/releasing.md).

Codon is licensed under [MIT](LICENSE).
