package works.nuty.bastion.client;

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
import works.nuty.bastion.BastionMod;
import works.nuty.bastion.adapter.DebuggerTaskQueue;
import works.nuty.bastion.client.testmixin.CommandBlockInvoker;
import works.nuty.bastion.client.state.ClientDebuggerState;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.ExecutionFlowEdge;
import works.nuty.bastion.core.model.ExecutionFlowStage;
import works.nuty.bastion.core.model.ExecutionFlowTrace;
import works.nuty.bastion.core.model.PauseSnapshot;
import works.nuty.bastion.core.model.SourceLocation;
import works.nuty.bastion.core.service.DebuggerEngine;

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
                require(completed.executionCount() == 0 && completed.successCount() == 0,
                    "the final command has not run while its terminal stage is paused");

                PauseSnapshot secondPause = pauses.stream()
                    .filter(snapshot -> at(snapshot, setup.second()))
                    .findFirst().orElseThrow(() -> new AssertionError("the second command-block breakpoint did not pause"));
                ExecutionFlowTrace executed = flowAt(secondPause, setup.first());
                require(executed.executionCount() == 3 && executed.successCount() == 3,
                    "the next command-block pause retains the previous final attempt and success");

                context.waitFor(client -> {
                    PauseSnapshot recent = state().inspectionSnapshot();
                    if (state().isPaused() || recent == null) return false;
                    SourceLocation location = new SourceLocation.Block(setup.second());
                    return recent.executionFlows().stream().anyMatch(flow -> flow.location().equals(location)
                        && flow.executionCount() == 1 && flow.successCount() == 1);
                }, 5);
                require(!state().isPaused() && state().snapshot() == null,
                    "completed flow sync remains read-only and does not recreate a pause");
            } finally {
                setup.driver().close();
                world.getServer().runOnServer(server -> cleanup(server, setup));
                context.runOnClient(client -> state().reset());
            }
        }
    }

    private static Setup setup(MinecraftServer server) {
        DebuggerEngine engine = require(BastionMod.engine(), "server debugger engine is initialized");
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
            "execute as @e[type=minecraft:armor_stand,tag=bastion_flow,distance=..32] at @s "
                + "if entity @s[tag=bastion_keep] run say flow-ok");
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
                ((CommandBlockInvoker) (Object) Blocks.COMMAND_BLOCK).bastion$execute(
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
        stand.addTag("bastion_flow");
        if (keep) stand.addTag("bastion_keep");
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
        require(flow.finalContextCount() == 3,
            "exactly three contexts reach the final command");
        require(flow.stages().stream().anyMatch(stage -> movedAlongAnEdge(stage, branch)),
            "an at stage records a real position change along its occurrence edges");
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
        DebuggerEngine engine = BastionMod.engine();
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
        return require(BastionClientMod.state(), "client debugger state is initialized");
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
            Thread.ofPlatform().daemon(true).name("Bastion flow GameTest pause driver").start(this::run);
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
                        if (snapshot.location().equals(second)) {
                            DebuggerTaskQueue.execute(server, engine::resume);
                            awaitingCompletion = true;
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
