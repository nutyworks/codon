package works.nuty.codon.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.client.testmixin.CommandBlockInvoker;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.BreakpointCondition;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.PauseReason;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.service.DebuggerEngine;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Native command execution proves measured zero and one whole-command result stop per invocation,
 * with both conditions created through {@code /codon breakpoint}.
 */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerBreakpointResultGameTest implements FabricClientGameTest {
    private static final String COMMAND =
        "execute as @a if entity @e[type=minecraft:armor_stand,tag=codon_no_match] run say unreachable";
    private static final AtomicReference<String> TRIGGER = new AtomicReference<>("not queued");

    @Override public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            Setup setup = world.getServer().computeOnServer(DebuggerBreakpointResultGameTest::setup);
            try {
                context.waitFor(client -> setup.driver().done(), 30);
                setup.driver().rethrowFailure();
                List<PauseSnapshot> pauses = setup.driver().snapshots();
                require(pauses.size() == 2, "whole result stops once, then stage result stops at measured zero: " + pauses);
                require(pauses.stream().allMatch(snapshot -> snapshot.reason() == PauseReason.BREAKPOINT),
                    "both native stops are breakpoints");
                require(pauses.getFirst().callStack().getFirst().flowStageIndex() == 0,
                    "the whole-command condition matches the first completed modifier");
                require(pauses.get(1).callStack().getFirst().flowStageIndex() == 1,
                    "the stage-specific condition matches the second modifier");
                long invocation = pauses.getFirst().callStack().getFirst().invocationId();
                require(pauses.get(1).callStack().getFirst().invocationId() == invocation,
                    "Continue reaches a later breakpoint in the same command invocation");
                ExecutionFlowTrace flow = pauses.get(1).executionFlows().stream()
                    .filter(trace -> trace.invocationId() == invocation).findFirst().orElseThrow();
                ExecutionFlowStage filtered = flow.stages().stream().filter(stage -> stage.index() == 1)
                    .findFirst().orElseThrow();
                require(filtered.complete() && filtered.lineageComplete() && filtered.outputCount() == 0,
                    "the second stage has a finalized measured zero result");
                require(flow.stages().stream().noneMatch(stage -> stage.terminal() && stage.executionCount() > 0),
                    "the filtered command does not execute its run stage");
            } finally {
                setup.driver().close();
                world.getServer().runOnServer(server -> cleanup(server, setup));
            }
        }
    }

    private static Setup setup(MinecraftServer server) {
        DebuggerEngine engine = require(CodonMod.engine(), "server debugger engine is initialized");
        engine.clearBreakpoints();
        ServerLevel level = server.getPlayerList().getPlayers().getFirst().level();
        BlockPos position = server.getPlayerList().getPlayers().getFirst().blockPosition().offset(7, 0, 7);
        level.setBlockAndUpdate(position, Blocks.COMMAND_BLOCK.defaultBlockState());
        CommandBlockEntity entity = require((CommandBlockEntity) level.getBlockEntity(position), "command block exists");
        entity.getCommandBlock().setCommand(COMMAND);
        SourceLocation.Block location = new SourceLocation.Block(new BlockLocation(position.getX(), position.getY(),
            position.getZ(), level.dimension().identifier().toString()));
        // The user-facing command path creates the same exact definitions the UI editor would.
        String at = "%d %d %d".formatted(position.getX(), position.getY(), position.getZ());
        runCodon(server, "breakpoint block " + at + " condition output_count ge 0");
        runCodon(server, "breakpoint block " + at + " stage 2 condition output_count eq 0");
        require(Set.copyOf(engine.breakpointDefinitions()).equals(Set.of(
            new BreakpointDefinition(BreakpointTarget.whole(location), true,
                BreakpointCondition.count(BreakpointCondition.Kind.OUTPUT_COUNT, BreakpointCondition.Comparison.GE, 0)),
            new BreakpointDefinition(BreakpointTarget.stage(location, 1, COMMAND), true,
                BreakpointCondition.count(BreakpointCondition.Kind.OUTPUT_COUNT, BreakpointCondition.Comparison.EQ, 0)))),
            "commands saved the whole-command and stage-two conditions: " + engine.breakpointDefinitions());
        PauseDriver driver = new PauseDriver(server, engine);
        driver.start();
        TRIGGER.set("queued");
        server.schedule(server.wrapRunnable(() -> {
            TRIGGER.set("running");
            try {
                ((CommandBlockInvoker) (Object) Blocks.COMMAND_BLOCK).codon$execute(
                    level.getBlockState(position), level, position, entity.getCommandBlock(), true);
            } finally {
                TRIGGER.set("finished");
            }
        }));
        return new Setup(position, driver);
    }

    private static void runCodon(MinecraftServer server, String arguments) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "codon " + arguments);
    }

    private static void cleanup(MinecraftServer server, Setup setup) {
        DebuggerEngine engine = CodonMod.engine();
        if (engine != null) engine.clearBreakpoints();
        server.overworld().setBlockAndUpdate(setup.position(), Blocks.AIR.defaultBlockState());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static <T> T require(T value, String message) {
        if (value == null) throw new AssertionError(message);
        return value;
    }

    private record Setup(BlockPos position, PauseDriver driver) { }

    private static final class PauseDriver implements AutoCloseable {
        private final MinecraftServer server;
        private final DebuggerEngine engine;
        private final CopyOnWriteArrayList<PauseSnapshot> snapshots = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile boolean closed;
        private volatile boolean done;

        private PauseDriver(MinecraftServer server, DebuggerEngine engine) {
            this.server = server;
            this.engine = engine;
        }

        private void start() {
            Thread.ofPlatform().daemon(true).name("Codon breakpoint result GameTest pause driver").start(this::run);
        }

        private void run() {
            long deadline = System.nanoTime() + 30_000_000_000L;
            PauseSnapshot previous = null;
            try {
                while (!closed && System.nanoTime() < deadline) {
                    PauseSnapshot snapshot = engine.currentSnapshot();
                    if (engine.isPaused() && snapshot != null && snapshot != previous) {
                        previous = snapshot;
                        snapshots.add(snapshot);
                        if (snapshots.size() > 4) throw new AssertionError("too many breakpoint pauses");
                        DebuggerTaskQueue.execute(server, engine::resume);
                    }
                    if (TRIGGER.get().equals("finished") && !engine.isPaused()) {
                        done = true;
                        return;
                    }
                    LockSupport.parkNanos(1_000_000L);
                }
                if (!closed) throw new AssertionError("breakpoint result fixture timed out: " + TRIGGER.get());
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
            throw new AssertionError("breakpoint result driver failed", problem);
        }
        @Override public void close() {
            closed = true;
            if (engine.isPaused()) DebuggerTaskQueue.execute(server, engine::resume);
        }
    }
}
