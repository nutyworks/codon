package works.nuty.codon.client;

import io.netty.buffer.Unpooled;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.InputType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.client.testmixin.CommandBlockInvoker;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerIcon;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.CallFrame;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.service.DebuggerEngine;
import works.nuty.codon.network.PauseSyncPayload;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Native command-function chronology fixture. Actual step pauses provide an independent ordering
 * oracle for the shared observation sequence and read-only history navigation.
 */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerConditionalFunctionFlowGameTest implements FabricClientGameTest {
    private static final int MAX_PAUSES = 80;
    private static final AtomicReference<String> TRIGGER_STATE = new AtomicReference<>("not queued");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            Setup setup = world.getServer().computeOnServer(DebuggerConditionalFunctionFlowGameTest::setup);
            try {
                context.waitFor(client -> setup.driver().done(), 30);
                setup.driver().rethrowFailure();
                List<PauseSnapshot> pauses = setup.driver().snapshots();
                assertSnapshotIdentity(pauses);
                assertNativeFunctionChronology(pauses);
                assertRecordedNavigation(context, pauses);
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
        BlockPos first = player.blockPosition().offset(7, 0, 7);
        List<BlockPos> positions = List.of(first, first.relative(Direction.EAST), first.relative(Direction.EAST, 2),
            first.relative(Direction.EAST, 3), first.relative(Direction.EAST, 4));
        List<String> commands = List.of(
            "execute if function codon_test:conditional_true run say conditional-if-passed",
            "execute unless function codon_test:conditional_false run say conditional-unless-passed",
            "execute if function codon_test:conditional_false run say conditional-if-should-not-run",
            "execute unless function codon_test:conditional_long_false run say conditional-long-passed",
            "say conditional-chain-finished");
        for (int i = 0; i < positions.size(); i++) {
            boolean head = i == 0;
            level.setBlockAndUpdate(positions.get(i), (head ? Blocks.COMMAND_BLOCK : Blocks.CHAIN_COMMAND_BLOCK)
                .defaultBlockState().setValue(CommandBlock.FACING, Direction.EAST));
            CommandBlockEntity entity = require((CommandBlockEntity) level.getBlockEntity(positions.get(i)),
                "conditional fixture command block " + i + " is available");
            entity.getCommandBlock().setCommand(commands.get(i));
            entity.getCommandBlock().setTrackOutput(true);
            if (!head) entity.setAutomatic(true);
        }
        String dimension = level.dimension().identifier().toString();
        List<BlockLocation> blocks = positions.stream().map(pos -> block(pos, dimension)).toList();
        blocks.forEach(engine::toggleBlockBreakpoint);
        PauseDriver driver = new PauseDriver(server, engine, new SourceLocation.Block(blocks.getLast()));
        CommandBlockEntity firstEntity = (CommandBlockEntity) level.getBlockEntity(first);
        TRIGGER_STATE.set("queued");
        driver.start();
        server.schedule(server.wrapRunnable(() -> {
            TRIGGER_STATE.set("running");
            try {
                ((CommandBlockInvoker) (Object) Blocks.COMMAND_BLOCK).codon$execute(
                    level.getBlockState(first), level, first, firstEntity.getCommandBlock(), true);
            } finally {
                TRIGGER_STATE.set("finished; successCount=" + firstEntity.getCommandBlock().getSuccessCount());
            }
        }));
        return new Setup(positions, blocks, driver);
    }

    private static void assertSnapshotIdentity(List<PauseSnapshot> pauses) {
        require(!pauses.isEmpty(), "conditional-function fixture produced no pauses; ensure data/codon_test/function/*.mcfunction is loaded by the GameTest data pack");
        for (PauseSnapshot pause : pauses) {
            require(!pause.callStack().isEmpty(), "native pause lacks a call frame: " + describe(pause));
            CallFrame top = pause.callStack().getFirst();
            require(top.invocationId() >= 0 && top.flowStageIndex() >= 0,
                "native pause lacks modern invocation/stage metadata: " + describe(pause));
            ExecutionFlowTrace matching = pause.executionFlows().stream()
                .filter(flow -> flow.invocationId() == top.invocationId()).findFirst()
                .orElseThrow(() -> new AssertionError("top frame has no matching flow trace: " + describe(pause)));
            ExecutionFlowStage stage = matching.stages().stream().filter(value -> value.index() == top.flowStageIndex())
                .findFirst().orElseThrow(() -> new AssertionError("top frame stage ID is absent from its matching trace: "
                    + describe(pause)));
            require(stage.observationOrder() >= 0,
                "native stage lacks its shared chronology observation order: " + describe(pause));
            require(stage.callStack().equals(pause.callStack()),
                "Each native stage records the actual call stack at that observation: " + describe(pause));
        }
    }

    private static void assertNativeFunctionChronology(List<PauseSnapshot> pauses) {
        List<String> expected = List.of(
            "execute if function codon_test:conditional_true run say conditional-if-passed",
            "say codon-conditional-true-enter", "return 1", "say conditional-if-passed",
            "execute unless function codon_test:conditional_false run say conditional-unless-passed",
            "say codon-conditional-false-enter", "return 0", "say conditional-unless-passed",
            "execute if function codon_test:conditional_false run say conditional-if-should-not-run",
            "say codon-conditional-false-enter", "return 0",
            "execute unless function codon_test:conditional_long_false run say conditional-long-passed",
            "say codon-conditional-long-enter", "return 0", "say conditional-long-passed", "say conditional-chain-finished");
        int cursor = -1;
        List<Observation> observed = new ArrayList<>();
        for (String command : expected) {
            cursor = nextCommand(pauses, command, cursor + 1);
            require(cursor >= 0, "missing or reordered native command '" + command + "'; observed snapshots: " + observations(pauses));
            observed.add(observation(pauses.get(cursor)));
        }
        for (int index = 1; index < observed.size(); index++) {
            require(observed.get(index - 1).order() < observed.get(index).order(),
                "native pause chronology must increase with the actual command order; observed=" + observed);
        }
        Observation parentStart = observed.getFirst();
        Observation trueFunctionEnter = observed.get(1);
        Observation trueFunctionReturn = observed.get(2);
        Observation parentRun = observed.get(3);
        require(trueFunctionEnter.snapshot().callStack().size() == 2
                && trueFunctionEnter.snapshot().callStack().get(1).invocationId() == parentStart.invocationId(),
            "Entering an if-function keeps the calling execute frame below the function frame");
        require(observed.get(5).snapshot().callStack().size() == 2
                && observed.get(5).snapshot().callStack().get(1).invocationId() == observed.get(4).invocationId(),
            "Entering an unless-function keeps the calling execute frame below the function frame");
        ExecutionFlowTrace parentTrace = parentRun.snapshot().executionFlows().stream()
            .filter(flow -> flow.invocationId() == parentStart.invocationId()).findFirst()
            .orElseThrow(() -> new AssertionError("parent run snapshot lost its parent trace: " + describe(parentRun.snapshot())));
        ExecutionFlowStage parentLast = parentTrace.stages().getLast();
        require(parentLast.observationOrder() == parentRun.order(),
            "the parent trace's last stage must be the post-function run command; parent=" + parentTrace.stages());
        require(trueFunctionEnter.order() < parentLast.observationOrder()
                && trueFunctionReturn.order() < parentLast.observationOrder(),
            "function stages must be observed before their parent trace's final run stage; observed=" + observed);
        long blockedInvocation = observed.get(8).invocationId();
        ExecutionFlowTrace blocked = pauses.stream().flatMap(pause -> pause.executionFlows().stream())
            .filter(flow -> flow.invocationId() == blockedInvocation).reduce((before, after) -> after).orElseThrow();
        require(blocked.stages().getLast().inputCount() == 0 && blocked.executionCount() == 0,
            "an observed terminal stage with no surviving sources must not execute the false condition's say command");
        Observation longParent = observed.get(11);
        Observation longRun = observed.get(14);
        require(longParent.invocationId() == longRun.invocationId(),
            "a parent evicted by a long condition function must rejoin history under the same invocation ID");
        require(longRun.snapshot().executionFlows().size() <= works.nuty.codon.core.service.ExecutionFlowHistory.MAX_TRACES,
            "resuming an evicted parent preserves the history bound");
    }

    private static int nextCommand(List<PauseSnapshot> pauses, String command, int start) {
        for (int i = start; i < pauses.size(); i++) if (exactCommand(pauses.get(i), command)) return i;
        return -1;
    }

    private static void assertRecordedNavigation(ClientGameTestContext context, List<PauseSnapshot> pauses) {
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); });
        PauseSnapshot insideFunction = pauses.get(nextCommand(pauses, "say codon-conditional-false-enter", 0));
        PauseSnapshot afterReturn = roundTrip(pauses.get(nextCommand(pauses, "say conditional-unless-passed", 0)));
        long parentId = afterReturn.callStack().getFirst().invocationId();
        ExecutionFlowTrace parent = afterReturn.executionFlows().stream()
            .filter(flow -> flow.invocationId() == parentId).findFirst().orElseThrow();
        ClientDebuggerState state = new ClientDebuggerState();
        CodonScreen screen = context.computeOnClient(client -> {
            state.applyPause(insideFunction);
            InputManager input = new InputManager(state, ignored -> { });
            input.keepFreecamKey = Arrays.stream(client.options.keyMappings).filter(key -> key.getName().equals("key.codon.keep_freecam")).findFirst().orElseThrow();
            input.menuKey = Arrays.stream(client.options.keyMappings).filter(key -> key.getName().equals("key.codon.open_menu")).findFirst().orElseThrow();
            input.breakpointKey = Arrays.stream(client.options.keyMappings).filter(key -> key.getName().equals("key.codon.breakpoint")).findFirst().orElseThrow();
            input.resumeKey = Arrays.stream(client.options.keyMappings).filter(key -> key.getName().equals("key.codon.resume")).findFirst().orElseThrow();
            input.stepOverKey = Arrays.stream(client.options.keyMappings).filter(key -> key.getName().equals("key.codon.step_over")).findFirst().orElseThrow();
            input.stepIntoKey = Arrays.stream(client.options.keyMappings).filter(key -> key.getName().equals("key.codon.step_into")).findFirst().orElseThrow();
            CodonScreen result = new CodonScreen(input, new DebuggerOverlay(state));
            client.setScreenAndShow(result);
            return result;
        });
        try {
            context.waitTicks(2);
            context.runOnClient(client -> {
                require(state.displayedCallStack().size() == 2, "Native call path includes the caller and child");
                for (CallFrame frame : insideFunction.callStack()) {
                    require(screen.children().stream().anyMatch(child -> child instanceof DebuggerButton button
                            && button.getMessage().getString().equals(frameLabel(frame))),
                        "Native function and caller rows are visible without expanding: " + frame);
                }
            });
            context.takeScreenshot("codon-conditional-call-stack");
            context.runOnClient(client -> state.applyPause(afterReturn));
            context.waitTicks(2);
            context.runOnClient(client -> require(state.displayedCallStack().size() == 1,
                "Returning to the root retains its horizontal call path"));
            context.takeScreenshot("codon-conditional-returned-parent");
            clickHistory(context, screen, DebuggerIcon.HISTORY_PREVIOUS);
            context.runOnClient(client -> {
                require(state.selectedCommand().text().equals("return 0"),
                    "Previous from the returned caller selects the condition function's return command");
                assertHistoricalStack(screen, state, parentId);
            });
            context.takeScreenshot("codon-conditional-previous-return");
            clickHistory(context, screen, DebuggerIcon.HISTORY_PREVIOUS);
            context.runOnClient(client -> {
                require(state.selectedCommand().text().equals("say codon-conditional-false-enter"),
                    "Previous then selects the condition function's body");
                assertHistoricalStack(screen, state, parentId);
            });
            context.takeScreenshot("codon-historical-function-stack");
            String recordedCallerLabel = context.computeOnClient(client -> screen.children().stream()
                .filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
                .map(button -> button.getMessage().getString()).filter(label -> label.equals(frameLabel(insideFunction.callStack().get(1))))
                .findFirst().orElseThrow());
            clickHistory(context, screen, recordedCallerLabel);
            context.runOnClient(client -> {
                require(state.displayedCallStack().size() == 2 && state.selectedCallFrameIndex() == 1,
                    "Inspecting a historical caller preserves the child and caller frame list");
                require(state.selectedExecutionFlowStage().index() == insideFunction.callStack().get(1).flowStageIndex(),
                    "Historical caller selection uses the call-site stage, not the post-return say");
                require(state.selectedPauseSourceIndex() == -1 && state.nbt().executor() == null,
                    "A historical caller cannot become the live Watch/NBT selection");
            });
            context.takeScreenshot("codon-historical-caller-stack");
            String recordedChildLabel = context.computeOnClient(client -> screen.children().stream()
                .filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
                .map(button -> button.getMessage().getString())
                .filter(label -> label.equals(frameLabel(insideFunction.callStack().getFirst())))
                .findFirst().orElseThrow());
            clickHistory(context, screen, recordedChildLabel);
            clickHistory(context, screen, DebuggerIcon.HISTORY_PREVIOUS);
            context.runOnClient(client -> require(state.selectedExecutionFlow().invocationId() == parentId
                && state.selectedExecutionFlowStage().index() == parent.stages().getFirst().index(),
                "Previous returns to the original condition before function entry, not the caller's later say"));
            clickHistory(context, screen, DebuggerIcon.HISTORY_NEXT);
            context.runOnClient(client -> require(state.selectedCommand().text().equals("say codon-conditional-false-enter"),
                "Next from the condition enters the recorded function body"));
            clickHistory(context, screen, DebuggerIcon.HISTORY_NEXT);
            context.runOnClient(client -> require(state.selectedCommand().text().equals("return 0"),
                "Next follows the recorded function return"));
            clickHistory(context, screen, DebuggerIcon.HISTORY_NEXT);
            context.runOnClient(client -> require(state.selectedExecutionFlow().invocationId() == parentId
                && state.selectedExecutionFlowStage().index() == parent.stages().get(1).index(),
                "Next returns to the caller's continuation after the function"));
            context.takeScreenshot("codon-conditional-next-continuation");
            clickHistory(context, screen, "Current");
            context.runOnClient(client -> require(state.displayedCallStack().equals(afterReturn.callStack())
                    && state.isViewingCurrentCommand(), "Current restores the authoritative live frame list and stop"));
        } finally {
            context.runOnClient(client -> { state.reset(); client.setScreenAndShow(null); });
        }
    }

    private static String frameLabel(CallFrame frame) {
        return switch (frame.location()) {
            case SourceLocation.Function f -> f.location().function() + ":" + f.location().line();
            case SourceLocation.Block b -> "Command block " + b.block().x() + ", " + b.block().y() + ", " + b.block().z();
            case SourceLocation.Player player -> player.name();
        };
    }

    private static void assertHistoricalStack(CodonScreen screen, ClientDebuggerState state, long parentId) {
        require(state.displayedCallStack().size() == 2
                && state.displayedCallStack().get(1).invocationId() == parentId,
            "Browsing a returned function restores its recorded child and caller, not the live root-only stack");
        for (CallFrame frame : state.displayedCallStack()) {
            require(screen.children().stream().anyMatch(child -> child instanceof DebuggerButton button
                    && button.getMessage().getString().equals(frameLabel(frame))),
                "Historical call path frames are visible: " + frame);
        }
        require(screen.children().stream().noneMatch(child -> child instanceof DebuggerButton button
                && button.icon() == DebuggerIcon.PAUSE), "Historical frames do not claim to be the current stop");
        require(state.selectedPauseSourceIndex() == -1 && state.nbt().executor() == null,
            "Historical function frames remain read-only");
    }

    private static PauseSnapshot roundTrip(PauseSnapshot snapshot) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PauseSyncPayload.CODEC.encode(buffer, new PauseSyncPayload(snapshot));
            PauseSnapshot decoded = PauseSyncPayload.CODEC.decode(buffer).snapshot();
            require(buffer.readableBytes() == 0 && decoded.equals(snapshot), "Historical stacks survive the actual pause packet codec");
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static void clickHistory(ClientGameTestContext context, CodonScreen screen, DebuggerIcon icon) {
        String label = context.computeOnClient(client -> screen.children().stream()
            .filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.icon() == icon).map(button -> button.getMessage().getString())
            .findFirst().orElseThrow(() -> new AssertionError("History icon missing: " + icon)));
        clickHistory(context, screen, label);
    }

    private static void clickHistory(ClientGameTestContext context, CodonScreen screen, String label) {
        context.runOnClient(client -> {
            DebuggerButton button = screen.children().stream().filter(DebuggerButton.class::isInstance)
                .map(DebuggerButton.class::cast).filter(control -> control.getMessage().getString().equals(label))
                .findFirst().orElseThrow(() -> new AssertionError("History control missing: " + label));
            require(button.active, "History control must be active: " + label);
            client.setLastInputType(InputType.MOUSE);
            MouseButtonEvent event = new MouseButtonEvent(button.getX() + button.getWidth() / 2.0,
                button.getY() + button.getHeight() / 2.0, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            require(screen.mouseClicked(event, false), "History control accepts the native screen click");
            screen.mouseReleased(event);
        });
        context.waitTicks(2);
    }

    private static boolean exactCommand(PauseSnapshot pause, String expected) {
        if (pause.command().text().equals(expected)) return true;
        var command = pause.command();
        return command.text().substring(command.highlightStart(), command.highlightEnd()).strip().equals(expected);
    }

    private static Observation observation(PauseSnapshot snapshot) {
        CallFrame top = snapshot.callStack().getFirst();
        ExecutionFlowTrace trace = snapshot.executionFlows().stream().filter(flow -> flow.invocationId() == top.invocationId())
            .findFirst().orElseThrow(() -> new AssertionError("top frame trace missing while collecting chronology: " + describe(snapshot)));
        ExecutionFlowStage stage = trace.stages().stream().filter(value -> value.index() == top.flowStageIndex()).findFirst()
            .orElseThrow(() -> new AssertionError("top frame stage missing while collecting chronology: " + describe(snapshot)));
        return new Observation(snapshot, top.invocationId(), stage.observationOrder(), top.command().text());
    }

    private static String observations(List<PauseSnapshot> pauses) {
        List<String> result = new ArrayList<>();
        for (PauseSnapshot pause : pauses) result.add(describe(pause));
        return result.toString();
    }

    private static String describe(PauseSnapshot pause) {
        CallFrame frame = pause.callStack().isEmpty() ? null : pause.callStack().getFirst();
        return "{command=" + pause.command().text() + ", top=" + (frame == null ? "none"
            : frame.command().text() + "#" + frame.invocationId() + "/" + frame.flowStageIndex()) + "}";
    }

    private static void cleanup(MinecraftServer server, Setup setup) {
        DebuggerEngine engine = CodonMod.engine();
        if (engine != null) engine.clearBreakpoints();
        ServerLevel level = server.overworld();
        for (BlockPos position : setup.positions()) level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
    }

    private static BlockLocation block(BlockPos pos, String dimension) {
        return new BlockLocation(pos.getX(), pos.getY(), pos.getZ(), dimension);
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static <T> T require(T value, String description) {
        if (value == null) throw new AssertionError(description);
        return value;
    }

    private record Setup(List<BlockPos> positions, List<BlockLocation> blocks, PauseDriver driver) { }
    private record Observation(PauseSnapshot snapshot, long invocationId, long order, String command) { }

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
            Thread.ofPlatform().daemon(true).name("Codon conditional-function GameTest pause driver").start(this::run);
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
                        if (snapshots.size() > MAX_PAUSES) throw new AssertionError("conditional fixture exceeded " + MAX_PAUSES
                            + " pauses; trigger=" + TRIGGER_STATE.get() + "; observed=" + observations(snapshots));
                        DebuggerTaskQueue.execute(server, snapshot.location().equals(terminalBlock) ? engine::resume : engine::stepInto);
                    }
                    if (TRIGGER_STATE.get().startsWith("finished") && !engine.isPaused()) {
                        done = true;
                        return;
                    }
                    LockSupport.parkNanos(1_000_000L);
                }
                if (!closed) throw new AssertionError("conditional fixture timed out; trigger=" + TRIGGER_STATE.get()
                    + "; observed=" + observations(snapshots));
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
            throw new AssertionError("conditional-function pause driver failed", problem);
        }

        @Override
        public void close() {
            closed = true;
            if (engine.isPaused()) DebuggerTaskQueue.execute(server, engine::resume);
        }
    }
}
