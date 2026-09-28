# Execution flow

## User path and expected result

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

1. Step with `F9` and inspect the command's stages and context markers using `V`.
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
3. After the terminal command executes, the completed record has one execution
   and one success. Before that observation, terminal values may be unmeasured;
   do not display them as measured zero.
4. Repeat with a condition that matches neither stand. The completed filter has
   measured zero output and no terminal execution. For nested/conditional function
   cases, use the native fixture tests below and inspect their chronology.

Created, removed and changed contexts use distinct presentation. Incomplete or
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
| Continuations and incomplete-record warnings | `DebuggerContinuationRecordingGameTest` |
| Rendered command/context UI with injected data | `DebuggerPresentationGameTest` |

Example: `./gradlew runClientGameTest -PclientGameTest=DebuggerExecutionFlowGameTest`.
The conditional-function test concerns Minecraft function conditions; conditional
breakpoint coverage is listed in [Breakpoints](breakpoints.md). Keep native-flow
assertions separate from screenshots produced by synthetic presentation fixtures.
