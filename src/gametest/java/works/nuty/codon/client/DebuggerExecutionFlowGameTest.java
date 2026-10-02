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
import works.nuty.codon.client.testmixin.CommandBlockInvoker;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.ExecutionFlowEdge;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.PauseReason;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.service.DebuggerEngine;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Integrated-server acceptance coverage for the production command hooks and pause payload. It
 * drives a real command-block chain through {@code as}, {@code at}, and a partially failing
 * {@code if}, then observes the first command's final result at the next block's breakpoint.
 */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerExecutionFlowGameTest implements FabricClientGameTest {
    private static final int MAX_STAGE_STEPS = 16;
    private static final AtomicReference<String> TRIGGER_STATE = new AtomicReference<>("not queued");

    @Override
    public void runTest(ClientGameTestContext context) {
        try {
            Class.forName("net.minecraft.commands.execution.tasks.BuildContexts");
        } catch (Throwable failure) {
            throw new AssertionError("BuildContexts mixin failed to load", failure);
        }
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            context.runOnClient(client -> state().reset());
            Setup setup = world.getServer().computeOnServer(DebuggerExecutionFlowGameTest::setup);
            try {
                // Fabric's client GameTest phaser waits for the integrated-server tick as well as
                // the client tick. A real debugger pause parks that server tick, so controls must
                // be driven by an independent mailbox thread until the tick can finish.
                context.waitFor(client -> setup.driver().done(), 20);
                setup.driver().rethrowFailure();

                List<PauseSnapshot> pauses = setup.driver().snapshots();
                context.runOnClient(client -> assertDefaultWorldTransitions(pauses, setup.first()));
                PauseSnapshot firstPause = pauses.stream()
                    .filter(snapshot -> at(snapshot, setup.first()))
                    .findFirst().orElseThrow(() -> new AssertionError("the first breakpoint did not pause"));
                ExecutionFlowTrace initial = flowAt(firstPause, setup.first());
                require(!initial.stages().isEmpty() && !initial.stages().getLast().complete(),
                    "the breakpoint exposes the in-progress stage before its modifier runs");

                PauseSnapshot terminalPause = pauses.stream()
                    .filter(snapshot -> at(snapshot, setup.first()))
                    .filter(snapshot -> {
                        ExecutionFlowTrace flow = flowAt(snapshot, setup.first());
                        return !flow.stages().isEmpty() && flow.stages().getLast().terminal();
                    })
                    .findFirst().orElseThrow(() -> new AssertionError("the execute flow never reached its terminal stage"));
                ExecutionFlowTrace completed = flowAt(terminalPause, setup.first());
                assertCompletedFlow(completed);
                require(completed.executionCount() == ExecutionFlowStage.UNMEASURED
                        && completed.successCount() == ExecutionFlowStage.UNMEASURED,
                    "final command results are unmeasured while its terminal stage is paused");

                PauseSnapshot secondPause = pauses.stream()
                    .filter(snapshot -> at(snapshot, setup.second()))
                    .findFirst().orElseThrow(() -> new AssertionError("the second command-block breakpoint did not pause"));
                ExecutionFlowTrace executed = flowAt(secondPause, setup.first());
                require(executed.executionCount() == 3 && executed.successCount() == 3,
                    "the next command-block pause retains the previous final attempt and success");
                require(pauses.stream().filter(snapshot -> snapshot.reason() == PauseReason.EXECUTION_COMPLETE).count() == 1,
                    "stepping the last command reaches exactly one execution-complete inspection stop");

                context.waitFor(client -> {
                    PauseSnapshot recent = state().inspectionSnapshot();
                    if (state().isPaused() || recent == null) return false;
                    SourceLocation location = new SourceLocation.Block(setup.second());
                    ExecutionFlowTrace selected = state().selectedExecutionFlow();
                    return selected != null && state().selectedExecutionFlowStage() != null
                        && selected.location().equals(location)
                        && recent.executionFlows().stream().anyMatch(flow -> flow.location().equals(location)
                        && flow.executionCount() == 1 && flow.successCount() == 1);
                }, 5);
                require(!state().isPaused() && !state().isStepping() && !state().isContinuing()
                        && state().snapshot() == null && !state().beginControlRequest(),
                    "completed flow sync remains read-only and does not recreate a pause");
                CodonMod.LOGGER.info("Native terminal completion PASS: final command executed once, flow selected, controls inactive");
            } finally {
                setup.driver().close();
                world.getServer().runOnServer(server -> cleanup(server, setup));
                context.runOnClient(client -> state().reset());
            }
        }
    }

    private static Setup setup(MinecraftServer server) {
        DebuggerEngine engine = require(CodonMod.engine(), "server debugger engine is initialized");
        engine.clearBreakpoints();
        ServerPlayer player = require(server.getPlayerList().getPlayers().getFirst(),
            "singleplayer server player is available");
        ServerLevel level = player.level();
        BlockPos first = player.blockPosition().offset(4, 0, 4);
        BlockPos second = first.relative(Direction.EAST);

        level.setBlockAndUpdate(first, Blocks.COMMAND_BLOCK.defaultBlockState()
            .setValue(CommandBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(second, Blocks.CHAIN_COMMAND_BLOCK.defaultBlockState()
            .setValue(CommandBlock.FACING, Direction.EAST));
        CommandBlockEntity firstEntity = require((CommandBlockEntity) level.getBlockEntity(first),
            "impulse command block is available");
        CommandBlockEntity secondEntity = require((CommandBlockEntity) level.getBlockEntity(second),
            "chain command block is available");
        firstEntity.getCommandBlock().setCommand(
            "execute as @e[type=minecraft:armor_stand,tag=codon_flow,distance=..32] at @s "
                + "if entity @s[tag=codon_keep] run say flow-ok");
        firstEntity.getCommandBlock().setTrackOutput(true);
        secondEntity.getCommandBlock().setCommand("say flow-finished");
        secondEntity.getCommandBlock().setTrackOutput(false);
        secondEntity.setAutomatic(true);

        ArmorStand keepOne = armorStand(level, first.getX() + 2.5, first.getY(), first.getZ() + 1.5,
            true);
        ArmorStand keepTwo = armorStand(level, first.getX() + 3.5, first.getY(), first.getZ() + 1.5,
            true);
        ArmorStand keepThree = armorStand(level, first.getX() + 4.5, first.getY(), first.getZ() + 1.5,
            true);
        ArmorStand drop = armorStand(level, first.getX() + 5.5, first.getY(), first.getZ() + 1.5,
            false);
        level.addFreshEntity(keepOne);
        level.addFreshEntity(keepTwo);
        level.addFreshEntity(keepThree);
        level.addFreshEntity(drop);

        String dimension = level.dimension().identifier().toString();
        engine.toggleBlockBreakpoint(block(first, dimension));
        engine.toggleBlockBreakpoint(block(second, dimension));
        // Queue the production CommandBlock.execute body after this setup call returns. Calling it
        // inline would deadlock because the breakpoint intentionally parks the server thread.
        TRIGGER_STATE.set("queued");
        PauseDriver driver = new PauseDriver(server, engine, block(first, dimension), block(second, dimension));
        driver.start();
        server.schedule(server.wrapRunnable(() -> {
            TRIGGER_STATE.set("running");
            try {
                ((CommandBlockInvoker) (Object) Blocks.COMMAND_BLOCK).codon$execute(
                    level.getBlockState(first), level, first, firstEntity.getCommandBlock(), true);
            } finally {
                TRIGGER_STATE.set("finished; successCount=" + firstEntity.getCommandBlock().getSuccessCount()
                    + "; output=" + firstEntity.getCommandBlock().getLastOutput());
            }
        }));
        return new Setup(block(first, dimension), block(second, dimension), first, second,
            List.of(keepOne.getUUID(), keepTwo.getUUID(), keepThree.getUUID(), drop.getUUID()), driver);
    }

    private static ArmorStand armorStand(ServerLevel level, double x, double y, double z, boolean keep) {
        ArmorStand stand = new ArmorStand(level, x, y, z);
        stand.setNoGravity(true);
        stand.addTag("codon_flow");
        if (keep) stand.addTag("codon_keep");
        return stand;
    }

    private static void assertCompletedFlow(ExecutionFlowTrace flow) {
        ExecutionFlowStage branch = flow.stages().stream()
            .filter(stage -> !stage.terminal() && stage.inputCount() == 1 && stage.outputCount() == 4
                && stage.edges().size() == 4)
            .findFirst().orElseThrow(() -> new AssertionError("as stage did not branch one context into four"));
        ExecutionFlowStage condition = flow.stages().stream()
            .filter(stage -> !stage.terminal() && stage.inputCount() == 4 && stage.outputCount() == 3
                && stage.droppedCount() == 1)
            .findFirst().orElseThrow(() -> new AssertionError("if stage did not retain three contexts and exclude one"));
        require(condition.droppedContextIds().size() == 1
                && condition.isDroppedContext(condition.droppedContextIds().getFirst()),
            "the excluded input has an explicit occurrence id");
        require(branch.outputs().stream().allMatch(output -> branch.isCreatedContext(output.id())),
            "as creates four executor sources different from the command-block parent");
        require(condition.outputs().stream().noneMatch(output -> condition.isCreatedContext(output.id())),
            "the three real if-condition survivors are unchanged sources, not newly created ones");
        require(flow.finalContextCount() == 3,
            "exactly three contexts reach the final command");
        require(flow.stages().stream().anyMatch(stage -> movedAlongAnEdge(stage, branch)),
            "an at stage records a real position change along its occurrence edges");
    }

    private static void assertDefaultWorldTransitions(List<PauseSnapshot> pauses, BlockLocation first) {
        boolean sawCreated = false;
        boolean sawRemoved = false;
        for (PauseSnapshot pause : pauses) {
            if (!at(pause, first)) continue;
            ClientDebuggerState presentation = new ClientDebuggerState();
            presentation.applyPause(pause);
            require(presentation.isViewingCurrentCommand(), "every real pause opens on its actual frame and stage");
            require(pause.callStack().getFirst().invocationId() == presentation.selectedExecutionFlow().invocationId(),
                "the production pause payload preserves the frame's exact invocation ID");
            require(pause.callStack().getFirst().flowStageIndex() == presentation.selectedExecutionFlowStage().index(),
                "the production pause payload preserves the frame's exact stage ID");
            ExecutionFlowStage worldStage = presentation.worldSourceStage();
            ExecutionFlowStage current = presentation.selectedExecutionFlowStage();
            if (worldStage == null || worldStage == current) continue;
            require(!current.complete() || current.terminal(), "world transition precedes the pending current command");
            if (worldStage.inputCount() == 1 && worldStage.outputCount() == 4) {
                require(presentation.worldSources().size() == 4, "all four newly created sources appear by default");
                for (int index = 0; index < 4; index++) require(presentation.isWorldSourceCreated(index),
                    "real as outputs are green at the next pause without manual stage selection");
                sawCreated = true;
            }
            if (worldStage.inputCount() == 4 && worldStage.outputCount() == 3 && worldStage.droppedCount() == 1) {
                require(presentation.displayedSources().size() == 4 && presentation.worldSources().size() == 4,
                    "the inspector and world retain the just-removed source alongside the three live ones");
                require(presentation.isWorldSourceDropped(3), "removed input is red at the next pause by default");
                for (int index = 0; index < 3; index++) require(!presentation.isWorldSourceCreated(index),
                    "if survivors remain unchanged");
                sawRemoved = true;
            }
        }
        require(sawCreated && sawRemoved, "real stepping exposes both as creation and if removal in the default world view");
    }

    private static boolean movedAlongAnEdge(ExecutionFlowStage stage, ExecutionFlowStage branch) {
        if (stage.terminal() || stage.inputCount() != 4 || stage.outputCount() != 4
            || stage.index() <= branch.index()) return false;
        for (ExecutionFlowEdge edge : stage.edges()) {
            var input = stage.inputs().stream().filter(value -> value.id() == edge.inputContextId()).findFirst();
            var output = stage.outputs().stream().filter(value -> value.id() == edge.outputContextId()).findFirst();
            if (input.isPresent() && output.isPresent()
                && !input.get().source().anchor().equals(output.get().source().anchor())) return true;
        }
        return false;
    }

    private static boolean at(PauseSnapshot snapshot, BlockLocation location) {
        return snapshot.location().equals(new SourceLocation.Block(location));
    }

    private static ExecutionFlowTrace flowAt(PauseSnapshot snapshot, BlockLocation location) {
        SourceLocation expected = new SourceLocation.Block(location);
        return snapshot.executionFlows().stream().filter(flow -> flow.location().equals(expected)).findFirst()
            .orElseThrow(() -> new AssertionError("execution flow missing for " + location));
    }

    private static void cleanup(MinecraftServer server, Setup setup) {
        DebuggerEngine engine = CodonMod.engine();
        if (engine != null) engine.clearBreakpoints();
        ServerLevel level = server.overworld();
        for (UUID uuid : setup.entities()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null) entity.discard();
        }
        level.setBlockAndUpdate(setup.firstPos(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(setup.secondPos(), Blocks.AIR.defaultBlockState());
    }

    private static BlockLocation block(BlockPos pos, String dimension) {
        return new BlockLocation(pos.getX(), pos.getY(), pos.getZ(), dimension);
    }

    private static ClientDebuggerState state() {
        return require(CodonClientMod.state(), "client debugger state is initialized");
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static <T> T require(T value, String description) {
        if (value == null) throw new AssertionError(description);
        return value;
    }

    private record Setup(BlockLocation first, BlockLocation second, BlockPos firstPos, BlockPos secondPos,
                         List<UUID> entities, PauseDriver driver) { }

    private static final class PauseDriver implements AutoCloseable {
        private static final long TIMEOUT_NANOS = 20_000_000_000L;
        private final MinecraftServer server;
        private final DebuggerEngine engine;
        private final SourceLocation first;
        private final SourceLocation second;
        private final CopyOnWriteArrayList<PauseSnapshot> snapshots = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile boolean closed;
        private volatile boolean done;

        private PauseDriver(MinecraftServer server, DebuggerEngine engine,
                            BlockLocation first, BlockLocation second) {
            this.server = server;
            this.engine = engine;
            this.first = new SourceLocation.Block(first);
            this.second = new SourceLocation.Block(second);
        }

        private void start() {
            Thread.ofPlatform().daemon(true).name("Codon flow GameTest pause driver").start(this::run);
        }

        private void run() {
            long deadline = System.nanoTime() + TIMEOUT_NANOS;
            PauseSnapshot previous = null;
            int firstPauses = 0;
            boolean awaitingCompletion = false;
            try {
                while (!closed && System.nanoTime() < deadline) {
                    if (awaitingCompletion && TRIGGER_STATE.get().startsWith("finished")) {
                        done = true;
                        return;
                    }
                    PauseSnapshot snapshot = engine.currentSnapshot();
                    if (engine.isPaused() && snapshot != null && snapshot != previous) {
                        previous = snapshot;
                        snapshots.add(snapshot);
                        if (snapshot.reason() == PauseReason.EXECUTION_COMPLETE) {
                            DebuggerTaskQueue.execute(server, engine::resume);
                            awaitingCompletion = true;
                            continue;
                        }
                        if (snapshot.location().equals(second)) {
                            DebuggerTaskQueue.execute(server, engine::stepInto);
                            continue;
                        }
                        if (!snapshot.location().equals(first)) {
                            throw new AssertionError("unexpected debugger pause at " + snapshot.location());
                        }
                        if (++firstPauses > MAX_STAGE_STEPS) {
                            throw new AssertionError("execute flow exceeded " + MAX_STAGE_STEPS + " stage pauses");
                        }
                        DebuggerTaskQueue.execute(server, engine::stepInto);
                    }
                    LockSupport.parkNanos(1_000_000L);
                }
                if (!closed) throw new AssertionError("timed out driving debugger pauses; trigger=" + TRIGGER_STATE.get());
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
            throw new AssertionError("pause driver failed", problem);
        }

        @Override
        public void close() {
            closed = true;
            if (engine.isPaused()) DebuggerTaskQueue.execute(server, engine::resume);
        }
    }
}
