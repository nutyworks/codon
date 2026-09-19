package works.nuty.codon.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.testmixin.CommandBlockInvoker;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerIcon;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.ExecutionFlowEdge;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.service.DebuggerEngine;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Real command-queue coverage for continuation boundaries.  These cases used to leave the
 * parent execute stage incomplete even when Minecraft resumed it normally.
 */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerContinuationRecordingGameTest implements FabricClientGameTest {
    private static final int MAX_PAUSES = 72;
    private static final AtomicReference<String> TRIGGER_STATE = new AtomicReference<>("not queued");

    @Override
    public void runTest(ClientGameTestContext context) {
        try {
            Class.forName("net.minecraft.commands.execution.tasks.BuildContexts");
            Class.forName("net.minecraft.server.commands.ReturnCommand");
            Class.forName("net.minecraft.server.commands.ExecuteCommand");
        } catch (Throwable failure) {
            throw new AssertionError("continuation recording mixins failed to load", failure);
        }
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            world.getServer().runCommand("scoreboard objectives add codon_runs dummy");
            Setup setup = world.getServer().computeOnServer(DebuggerContinuationRecordingGameTest::setup);
            try {
                context.waitFor(client -> setup.driver().done(), 30);
                setup.driver().rethrowFailure();
                List<PauseSnapshot> pauses = setup.driver().snapshots();
                assertContinuationFlows(pauses, setup);
                world.getServer().runOnServer(server -> assertCommandsRanOnce(server, setup));
                world.getServer().runOnServer(DebuggerContinuationRecordingGameTest::assertConditionFunctionRuns);
                world.getServer().runOnServer(DebuggerContinuationRecordingGameTest::assertQuotaWarnings);
                assertWarningPresentation(context, pauses, setup);
            } finally {
                setup.driver().close();
                world.getServer().runOnServer(server -> cleanup(server, setup));
            }
        }
    }

    private static Setup setup(MinecraftServer server) {
        DebuggerEngine engine = require(CodonMod.engine(), "server debugger engine is initialized");
        engine.clearBreakpoints();
        ServerPlayer player = require(server.getPlayerList().getPlayers().getFirst(), "singleplayer player is available");
        ServerLevel level = player.level();
        BlockPos first = player.blockPosition().offset(10, 0, 10);
        List<BlockPos> positions = List.of(first, first.relative(Direction.EAST), first.relative(Direction.EAST, 2),
            first.relative(Direction.EAST, 3), first.relative(Direction.EAST, 4), first.relative(Direction.EAST, 5),
            first.relative(Direction.EAST, 6), first.relative(Direction.EAST, 7));
        List<String> commands = List.of(
            "return run say continuation-return-say",
            "return run function codon_test:continuation_return_outer",
            "execute if function codon_test:continuation_true run say continuation-if-true",
            "execute unless function codon_test:continuation_false run say continuation-unless-false",
            "execute if function codon_test:continuation_false run say continuation-if-false-should-not-run",
            "execute as @e[type=minecraft:armor_stand,tag=codon_continuation,distance=..32] if function codon_test:continuation_entity_gate run say continuation-entity-gate",
            stageCapCommand(),
            "say continuation-chain-finished");
        for (int index = 0; index < positions.size(); index++) {
            boolean head = index == 0;
            level.setBlockAndUpdate(positions.get(index), (head ? Blocks.COMMAND_BLOCK : Blocks.CHAIN_COMMAND_BLOCK)
                .defaultBlockState().setValue(CommandBlock.FACING, Direction.EAST));
            CommandBlockEntity entity = require((CommandBlockEntity) level.getBlockEntity(positions.get(index)),
                "continuation fixture command block " + index + " is available");
            entity.getCommandBlock().setCommand(commands.get(index));
            entity.getCommandBlock().setTrackOutput(true);
            if (!head) entity.setAutomatic(true);
        }

        ArmorStand keepOne = armorStand(level, first.getX() + 2.5, first.getY(), first.getZ() + 2.5, true);
        ArmorStand keepTwo = armorStand(level, first.getX() + 3.5, first.getY(), first.getZ() + 2.5, true);
        ArmorStand drop = armorStand(level, first.getX() + 4.5, first.getY(), first.getZ() + 2.5, false);
        level.addFreshEntity(keepOne);
        level.addFreshEntity(keepTwo);
        level.addFreshEntity(drop);

        String dimension = level.dimension().identifier().toString();
        List<BlockLocation> blocks = positions.stream().map(position -> block(position, dimension)).toList();
        blocks.forEach(engine::toggleBlockBreakpoint);
        PauseDriver driver = new PauseDriver(server, engine, new SourceLocation.Block(blocks.getLast()));
        CommandBlockEntity head = (CommandBlockEntity) level.getBlockEntity(first);
        TRIGGER_STATE.set("queued");
        driver.start();
        server.schedule(server.wrapRunnable(() -> {
            TRIGGER_STATE.set("running");
            try {
                ((CommandBlockInvoker) (Object) Blocks.COMMAND_BLOCK).codon$execute(
                    level.getBlockState(first), level, first, head.getCommandBlock(), true);
            } finally {
                TRIGGER_STATE.set("finished; successCount=" + head.getCommandBlock().getSuccessCount());
            }
        }));
        return new Setup(positions, blocks, List.of(keepOne.getUUID(), keepTwo.getUUID(), drop.getUUID()), driver);
    }

    private static ArmorStand armorStand(ServerLevel level, double x, double y, double z, boolean keep) {
        ArmorStand stand = new ArmorStand(level, x, y, z);
        stand.setNoGravity(true);
        stand.addTag("codon_continuation");
        if (keep) stand.addTag("codon_continuation_keep");
        return stand;
    }

    private static void assertContinuationFlows(List<PauseSnapshot> pauses, Setup setup) {
        require(!pauses.isEmpty(), "continuation fixture produced no pauses; ensure continuation_*.mcfunction is loaded");
        ExecutionFlowTrace returnSay = latestFlow(pauses, setup.blocks().get(0));
        ExecutionFlowTrace returnFunction = latestFlow(pauses, setup.blocks().get(1));
        ExecutionFlowTrace ifTrue = latestFlow(pauses, setup.blocks().get(2));
        ExecutionFlowTrace unlessFalse = latestFlow(pauses, setup.blocks().get(3));
        ExecutionFlowTrace ifFalse = latestFlow(pauses, setup.blocks().get(4));
        ExecutionFlowTrace entityGate = latestFlow(pauses, setup.blocks().get(5));

        assertCompleteLineage(returnSay, "return run say");
        assertCompleteLineage(returnFunction, "return run function");
        assertCompleteLineage(ifTrue, "if function returning true");
        assertCompleteLineage(unlessFalse, "unless function returning false");
        assertCompleteLineage(ifFalse, "if function returning false");
        assertCompleteLineage(entityGate, "per-entity function condition");
        require(returnSay.executionCount() == 1 && returnSay.successCount() == 1,
            "return run say executes its continuation exactly once");
        require(ifTrue.executionCount() == 1 && ifTrue.successCount() == 1,
            "true if-function runs its continuation exactly once");
        require(unlessFalse.executionCount() == 1 && unlessFalse.successCount() == 1,
            "false unless-function runs its continuation exactly once");
        require(ifFalse.finalContextCount() == 0 && ifFalse.executionCount() == 0 && ifFalse.successCount() == 0,
            "false if-function forwards no contexts and does not run its continuation");
        require(entityGate.finalContextCount() == 2 && entityGate.executionCount() == 2 && entityGate.successCount() == 2,
            "two passing entity occurrences reach the continuation while one is excluded");
        ExecutionFlowStage gate = entityGate.stages().stream().filter(stage -> !stage.terminal())
            .filter(stage -> stage.inputCount() == 3 && stage.outputCount() == 2).findFirst()
            .orElseThrow(() -> new AssertionError("entity condition did not record the 3 -> 2 decision"));
        require(gate.droppedCount() == 1 && gate.droppedContextIds().size() == 1,
            "the rejected entity occurrence is retained as an explicit dropped input");

        assertFunctionTraceClean(pauses, "return run function codon_test:continuation_return_inner");
        assertFunctionTraceClean(pauses, "return run say continuation-nested-return");

        ExecutionFlowTrace capped = latestFlow(pauses, setup.blocks().get(6));
        var warning = capped.warnings().stream().filter(value -> value.reason()
            == works.nuty.codon.core.model.ExecutionFlowWarning.Reason.STAGE_LIMIT).findFirst()
            .orElseThrow(() -> new AssertionError("stage-cap fixture has a specific stage-limit warning: " + capped.warnings()));
        require(capped.truncated() && warning.limit() == works.nuty.codon.core.service.ExecutionFlowRecorder.MAX_STAGES,
            "stage-cap warning preserves its configured recording limit");
        require(warning.stageIndex() == works.nuty.codon.core.service.ExecutionFlowRecorder.MAX_STAGES,
            "stage-cap warning identifies the first omitted stage");
        require(warning.command().text().equals(stageCapCommand())
                && warning.command().highlightStart() < warning.command().highlightEnd(),
            "stage-cap warning preserves the originating command and its omitted clause range");
    }

    private static void assertCompleteLineage(ExecutionFlowTrace flow, String label) {
        require(!flow.truncated() && flow.warnings().isEmpty(), label + " has no incomplete-recording warning: " + flow.warnings());
        require(!flow.stages().isEmpty(), label + " records at least one stage");
        for (int index = 0; index < flow.stages().size(); index++) {
            ExecutionFlowStage stage = flow.stages().get(index);
            require(stage.complete() && stage.lineageComplete() && !stage.truncated(),
                label + " stage " + index + " is complete with a verified lineage");
            assertStageEdges(stage, label);
            if (index > 0) {
                ExecutionFlowStage previous = flow.stages().get(index - 1);
                require(contextIds(stage.inputs()).equals(contextIds(previous.outputs())),
                    label + " continuation stage " + index + " reuses the exact output occurrence IDs from stage " + (index - 1));
            }
        }
    }

    private static void assertStageEdges(ExecutionFlowStage stage, String label) {
        if (stage.terminal()) {
            require(stage.edges().isEmpty(), label + " terminal stage has no synthetic modifier edges");
            require(contextIds(stage.inputs()).equals(contextIds(stage.outputs())),
                label + " terminal stage preserves its input occurrences");
            return;
        }
        require(stage.inputCount() == stage.inputs().size() && stage.outputCount() == stage.outputs().size(),
            label + " retains every input and output occurrence below fixture limits");
        Set<Long> inputIds = ids(stage.inputs());
        Set<Long> outputIds = ids(stage.outputs());
        Set<Long> edgedOutputs = new HashSet<>();
        for (ExecutionFlowEdge edge : stage.edges()) {
            require(inputIds.contains(edge.inputContextId()) && outputIds.contains(edge.outputContextId()),
                label + " edge endpoints refer to the stage's real input/output occurrences");
            require(edgedOutputs.add(edge.outputContextId()), label + " output occurrence has one observed origin edge");
        }
        require(edgedOutputs.equals(outputIds), label + " every retained output occurrence has an observed origin edge");
        for (long dropped : stage.droppedContextIds()) {
            require(inputIds.contains(dropped) && stage.edges().stream().noneMatch(edge -> edge.inputContextId() == dropped),
                label + " dropped occurrence has no invented output edge");
        }
    }

    private static void assertFunctionTraceClean(List<PauseSnapshot> pauses, String command) {
        ExecutionFlowTrace flow = pauses.stream().flatMap(pause -> pause.executionFlows().stream())
            .filter(trace -> trace.stages().stream().anyMatch(stage -> stage.command().text().equals(command)))
            .reduce((before, after) -> after)
            .orElseThrow(() -> new AssertionError("missing nested function trace for '" + command + "'"));
        assertCompleteLineage(flow, command);
    }

    private static ExecutionFlowTrace latestFlow(List<PauseSnapshot> pauses, BlockLocation location) {
        SourceLocation expected = new SourceLocation.Block(location);
        return pauses.stream().flatMap(pause -> pause.executionFlows().stream()).filter(flow -> flow.location().equals(expected))
            .reduce((before, after) -> after).orElseThrow(() -> new AssertionError("missing flow for " + location));
    }

    private static Set<Long> ids(List<works.nuty.codon.core.model.ExecutionFlowContext> contexts) {
        Set<Long> result = new HashSet<>();
        for (var context : contexts) {
            require(result.add(context.id()), "context occurrence IDs are unique within a retained stage");
        }
        return result;
    }

    private static List<Long> contextIds(List<works.nuty.codon.core.model.ExecutionFlowContext> contexts) {
        return contexts.stream().map(works.nuty.codon.core.model.ExecutionFlowContext::id).toList();
    }

    private static void assertCommandsRanOnce(MinecraftServer server, Setup setup) {
        ServerLevel level = server.overworld();
        int[] expected = {1, 1, 1, 1, 0, 2, 1, 1};
        for (int index = 0; index < expected.length; index++) {
            CommandBlockEntity block = require((CommandBlockEntity) level.getBlockEntity(setup.positions().get(index)),
                "continuation command block remains available for success-count assertion");
            require(block.getCommandBlock().getSuccessCount() == expected[index],
                "command block " + index + " ran its continuation the expected number of times");
        }
    }

    private static void assertConditionFunctionRuns(MinecraftServer server) {
        var objective = require(server.getScoreboard().getObjective("codon_runs"), "continuation score objective exists");
        require(score(server, "codon_true", objective) == 1,
            "the true condition function executes once, rather than once per result callback");
        require(score(server, "codon_false", objective) == 2,
            "the false condition function executes once for each of its two parent conditions");
        require(score(server, "codon_gate", objective) == 3,
            "the per-entity condition function executes once for each of three input occurrences");
    }

    private static int score(MinecraftServer server, String holder, net.minecraft.world.scores.Objective objective) {
        var value = server.getScoreboard().getPlayerScoreInfo(net.minecraft.world.scores.ScoreHolder.forNameOnly(holder), objective);
        return require(value, "score exists for " + holder).value();
    }

    private static void assertQuotaWarnings(MinecraftServer server) {
        DebuggerEngine engine = require(CodonMod.engine(), "engine is available for quota tests");
        var history = require(CodonMod.executionFlows(), "flow history is available for quota tests");
        var source = server.createCommandSourceStack().withSuppressedOutput();
        for (String suffix : List.of("return run say quota-return-never",
                "if function codon_test:continuation_true run say quota-condition-never")) {
            String command = "execute positioned ~ ~ ~ " + (suffix.startsWith("return") ? "run " : "") + suffix;
            var parsed = server.getCommands().getDispatcher().parse(command, source);
            var chain = com.mojang.brigadier.context.ContextChain.tryFlatten(parsed.getContext().build(command)).orElseThrow();
            // A surrounding inspection scope keeps the completed queue's flow available for assertions.
            engine.onExecutionStarted();
            try (var queue = new net.minecraft.commands.execution.ExecutionContext<net.minecraft.commands.CommandSourceStack>(
                    1, 100, net.minecraft.util.profiling.Profiler.get())) {
                net.minecraft.commands.execution.ExecutionContext.queueInitialCommandExecution(queue, command, chain, source,
                    net.minecraft.commands.CommandResultCallback.EMPTY);
                queue.runCommandQueue();
                var flow = history.snapshot().stream().filter(value -> value.stages().stream()
                    .anyMatch(stage -> stage.command().text().equals(command))).findFirst().orElseThrow();
                var warnings = flow.warnings().stream().filter(value -> value.reason()
                    == works.nuty.codon.core.model.ExecutionFlowWarning.Reason.COMMAND_LIMIT).toList();
                require(warnings.size() == 1 && warnings.getFirst().limit() == 1,
                    "unstarted continuation reports the actual command quota once: " + flow.warnings());
                require(warnings.getFirst().command().text().equals(command), "quota warning identifies its origin command");
                require(flow.stages().stream().noneMatch(ExecutionFlowStage::terminal),
                    "quota prevents the queued terminal command from starting");
            } finally {
                engine.onExecutionFinished();
            }
        }
    }

    private static void assertWarningPresentation(ClientGameTestContext context, List<PauseSnapshot> pauses, Setup setup) {
        PauseSnapshot snapshot = pauses.stream().filter(pause -> pause.executionFlows().stream()
            .anyMatch(flow -> flow.location().equals(new SourceLocation.Block(setup.blocks().get(6))) && !flow.warnings().isEmpty()))
            .reduce((before, after) -> after).orElseThrow(() -> new AssertionError("no pause carries the stage-cap warning flow"));
        long cappedId = snapshot.executionFlows().stream().filter(flow -> flow.location()
            .equals(new SourceLocation.Block(setup.blocks().get(6)))).findFirst().orElseThrow().invocationId();
        ClientDebuggerState state = new ClientDebuggerState();
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); });
        CodonScreen screen = context.computeOnClient(client -> {
            state.applyPause(snapshot);
            state.selectExecutionFlow(indexOfFlow(snapshot, cappedId));
            InputManager input = new InputManager(state, ignored -> { });
            input.menuKey = Arrays.stream(client.options.keyMappings).filter(key -> key.getName().equals("key.codon.open_menu"))
                .findFirst().orElseThrow();
            input.breakpointKey = Arrays.stream(client.options.keyMappings).filter(key -> key.getName().equals("key.codon.breakpoint"))
                .findFirst().orElseThrow();
            input.resumeKey = Arrays.stream(client.options.keyMappings).filter(key -> key.getName().equals("key.codon.resume"))
                .findFirst().orElseThrow();
            input.stepOverKey = Arrays.stream(client.options.keyMappings).filter(key -> key.getName().equals("key.codon.step_over"))
                .findFirst().orElseThrow();
            input.stepIntoKey = Arrays.stream(client.options.keyMappings).filter(key -> key.getName().equals("key.codon.step_into"))
                .findFirst().orElseThrow();
            CodonScreen result = new CodonScreen(input, new DebuggerOverlay(state));
            client.setScreenAndShow(result);
            return result;
        });
        try {
            context.waitTicks(2);
            double[] cursor = context.computeOnClient(client -> {
                require(state.selectedExecutionFlow().invocationId() == cappedId, "warning flow is selected in the native overlay");
                DebuggerButton warning = screen.children().stream().filter(DebuggerButton.class::isInstance)
                    .map(DebuggerButton.class::cast).filter(button -> button.icon() == DebuggerIcon.WARNING).findFirst()
                    .orElseThrow(() -> new AssertionError("warning flow has a visible footer warning icon"));
                return new double[] {
                    (warning.getX() + warning.getWidth() / 2.0) * client.getWindow().getScreenWidth() / screen.width,
                    (warning.getY() + warning.getHeight() / 2.0) * client.getWindow().getScreenHeight() / screen.height
                };
            });
            context.getInput().setCursorPos(cursor[0], cursor[1]);
            context.waitTicks(3);
            context.takeScreenshot("codon-recording-warning-reason");
            PauseSnapshot completeSnapshot = pauses.stream().filter(pause -> pause.executionFlows().stream()
                .anyMatch(flow -> flow.location().equals(new SourceLocation.Block(setup.blocks().get(0)))
                    && flow.executionCount() == 1)).reduce((before, after) -> after).orElseThrow();
            context.runOnClient(client -> {
                state.applyPause(completeSnapshot);
                state.selectExecutionFlow(indexOfFlow(completeSnapshot,
                    latestSnapshotFlow(completeSnapshot.executionFlows(), setup.blocks().get(0)).invocationId()));
            });
            context.waitTicks(2);
            context.runOnClient(client -> {
                require(screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
                    .noneMatch(button -> button.icon() == DebuggerIcon.WARNING),
                    "a selected complete clause does not inherit another flow's warning icon");
            });
        } finally {
            context.runOnClient(client -> { state.reset(); client.setScreenAndShow(null); });
        }
    }

    private static int indexOfFlow(PauseSnapshot snapshot, long invocationId) {
        for (int index = 0; index < snapshot.executionFlows().size(); index++)
            if (snapshot.executionFlows().get(index).invocationId() == invocationId) return index;
        throw new AssertionError("flow is absent from its own snapshot: " + invocationId);
    }

    private static ExecutionFlowTrace latestSnapshotFlow(List<ExecutionFlowTrace> flows, BlockLocation location) {
        SourceLocation expected = new SourceLocation.Block(location);
        return flows.stream().filter(flow -> flow.location().equals(expected)).reduce((before, after) -> after)
            .orElseThrow(() -> new AssertionError("missing flow for " + location));
    }

    private static String stageCapCommand() {
        return "execute " + "positioned ~ ~ ~ ".repeat(works.nuty.codon.core.service.ExecutionFlowRecorder.MAX_STAGES + 1)
            + "run say continuation-stage-cap";
    }

    private static void cleanup(MinecraftServer server, Setup setup) {
        DebuggerEngine engine = CodonMod.engine();
        if (engine != null) engine.clearBreakpoints();
        ServerLevel level = server.overworld();
        for (UUID uuid : setup.entities()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null) entity.discard();
        }
        for (BlockPos position : setup.positions()) level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
    }

    private static BlockLocation block(BlockPos position, String dimension) {
        return new BlockLocation(position.getX(), position.getY(), position.getZ(), dimension);
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static <T> T require(T value, String description) {
        if (value == null) throw new AssertionError(description);
        return value;
    }

    private record Setup(List<BlockPos> positions, List<BlockLocation> blocks, List<UUID> entities, PauseDriver driver) { }

    private static final class PauseDriver implements AutoCloseable {
        private static final long TIMEOUT_NANOS = 30_000_000_000L;
        private final MinecraftServer server;
        private final DebuggerEngine engine;
        private final SourceLocation terminalBlock;
        private final CopyOnWriteArrayList<PauseSnapshot> snapshots = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile boolean closed;
        private volatile boolean done;

        private PauseDriver(MinecraftServer server, DebuggerEngine engine, SourceLocation terminalBlock) {
            this.server = server;
            this.engine = engine;
            this.terminalBlock = terminalBlock;
        }

        private void start() {
            Thread.ofPlatform().daemon(true).name("Codon continuation GameTest pause driver").start(this::run);
        }

        private void run() {
            long deadline = System.nanoTime() + TIMEOUT_NANOS;
            PauseSnapshot previous = null;
            try {
                while (!closed && System.nanoTime() < deadline) {
                    PauseSnapshot snapshot = engine.currentSnapshot();
                    if (engine.isPaused() && snapshot != null && snapshot != previous) {
                        previous = snapshot;
                        snapshots.add(snapshot);
                        if (snapshots.size() > MAX_PAUSES) throw new AssertionError("continuation fixture exceeded " + MAX_PAUSES
                            + " pauses; trigger=" + TRIGGER_STATE.get());
                        DebuggerTaskQueue.execute(server, snapshot.location().equals(terminalBlock) ? engine::resume : engine::stepInto);
                    }
                    if (TRIGGER_STATE.get().startsWith("finished") && !engine.isPaused()) {
                        done = true;
                        return;
                    }
                    LockSupport.parkNanos(1_000_000L);
                }
                if (!closed) throw new AssertionError("continuation fixture timed out; trigger=" + TRIGGER_STATE.get());
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
            } finally {
                if (engine.isPaused()) DebuggerTaskQueue.execute(server, engine::resume);
                done = true;
            }
        }

        private boolean done() { return done; }
        private List<PauseSnapshot> snapshots() { return List.copyOf(snapshots); }

        private void rethrowFailure() {
            Throwable problem = failure.get();
            if (problem == null) return;
            if (problem instanceof AssertionError assertion) throw assertion;
            throw new AssertionError("continuation pause driver failed", problem);
        }

        @Override
        public void close() {
            closed = true;
            if (engine.isPaused()) DebuggerTaskQueue.execute(server, engine::resume);
        }
    }
}
