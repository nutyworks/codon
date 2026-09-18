package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientWatchState;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.WatchFormatting;
import works.nuty.codon.client.ui.WatchScreen;
import works.nuty.codon.client.ui.layout.DebuggerLayout;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.PauseReason;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** The user's two-command reproduction, powered through real adjacent command blocks. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerWatchChainGameTest implements FabricClientGameTest {
    private static final String FIRST = "execute as @a run scoreboard players add @s a 3";
    private static final String SECOND = "execute as @a run scoreboard players add @s a 4";
    private static final WatchSpec SCORE = new WatchSpec(WatchSpec.Kind.SCORE, "a", "");
    private static final BlockLocation FIRST_BLOCK = new BlockLocation(4, 80, 0, "minecraft:overworld");
    private static final BlockLocation SECOND_BLOCK = new BlockLocation(5, 80, 0, "minecraft:overworld");
    private static final long WATCH_READ_LIMIT_MILLIS = 750;

    @Override
    public void runTest(ClientGameTestContext context) {
        checkScenario(context, false);
        checkScenario(context, true);
    }

    private static void checkScenario(ClientGameTestContext context, boolean initiallyMissing) {
        String expectedFirstChange = initiallyMissing ? "unset → 3" : "0 → 3";
        String screenshotPrefix = initiallyMissing ? "codon-chain-missing-watch" : "codon-chain-watch";
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            var server = world.getServer().computeOnServer(s -> s);
            world.getServer().runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                s.getPlayerList().op(player.nameAndId(),
                    Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER), Optional.empty());
                CodonMod.engine().clearBreakpoints();
            });
            world.getServer().runCommand("scoreboard objectives add a dummy");
            if (!initiallyMissing) world.getServer().runCommand("scoreboard players set @a a 0");
            world.getServer().runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                var score = s.getScoreboard().getPlayerScoreInfo(player, s.getScoreboard().getObjective("a"));
                require(initiallyMissing ? score == null : score != null && score.value() == 0,
                    "the initial score is " + (initiallyMissing ? "absent" : "zero"));
            });
            world.getServer().runCommand("gamemode creative @a");
            world.getServer().runCommand("tp @a 5 82 5 180 25");
            world.getServer().runCommand("fill 3 79 -1 6 79 1 minecraft:stone");
            world.getServer().runCommand("setblock 4 80 0 minecraft:command_block[facing=east]{Command:\"" + FIRST + "\",auto:0b}");
            world.getServer().runCommand("setblock 5 80 0 minecraft:chain_command_block[facing=east]{Command:\"" + SECOND + "\",auto:1b}");
            world.getServer().runOnServer(s -> {
                var level = s.overworld();
                require(level.getBlockState(new BlockPos(4, 80, 0)).is(Blocks.COMMAND_BLOCK), "first block is an impulse command block");
                require(level.getBlockState(new BlockPos(5, 80, 0)).is(Blocks.CHAIN_COMMAND_BLOCK), "second block is a connected chain block");
                require(((CommandBlockEntity) level.getBlockEntity(new BlockPos(4, 80, 0))).getCommandBlock().getCommand().equals(FIRST), "exact first command installed");
                require(((CommandBlockEntity) level.getBlockEntity(new BlockPos(5, 80, 0))).getCommandBlock().getCommand().equals(SECOND), "exact second command installed");
                CodonMod.engine().toggleBlockBreakpoint(FIRST_BLOCK);
            });
            context.runOnClient(client -> {
                var state = CodonClientMod.state();
                state.watches().reset();
                state.watches().add(SCORE);
                client.setScreenAndShow(null);
            });
            context.getInput().resizeWindow(1280, 800);
            List<String> trace = new ArrayList<>();
            boolean sawFirstChange = false;
            boolean sawFinalChange = false;
            boolean reachedSecondBlock = false;
            try {
                // A redstone neighbor schedules the normal impulse-block tick and its connected chain.
                world.getServer().runCommand("setblock 3 80 0 minecraft:redstone_block");
                context.waitFor(client -> CodonClientMod.state().isPaused(), 200);
                screenshot(context, screenshotPrefix + "-initial");
                for (int stop = 0; stop < 16; stop++) {
                    context.waitFor(client -> CodonClientMod.state().isPaused() && watch().result() != null, 200);
                    var snapshot = context.computeOnClient(client -> CodonClientMod.state().snapshot());
                    boolean firstStopAfterFirstCommand = !reachedSecondBlock
                        && snapshot.location().equals(new SourceLocation.Block(SECOND_BLOCK));
                    if (firstStopAfterFirstCommand) {
                        reachedSecondBlock = true;
                        require(context.computeOnClient(client -> CodonClientMod.state().selectedSource().entity() == null),
                            "the first stop after scoreboard execution has no current executor");
                        // Do not take another step: the outgoing executor must be refreshed at THIS stop.
                        context.waitFor(client -> watch().completedStep() != null, 200);
                        require(context.computeOnClient(client -> CodonClientMod.state().snapshot().pauseId()) == snapshot.pauseId(),
                            "the post-command value arrives without another F9 press");
                    }
                    var entry = context.computeOnClient(client -> watch());
                    String display = context.computeOnClient(client -> WatchFormatting.value(watch(), true).getString());
                    String observation = snapshot.pauseId() + " " + snapshot.reason() + " " + snapshot.location()
                        + " | " + entry.result() + " | completed=" + entry.completedStep() + " | " + display;
                    trace.add(observation);
                    CodonMod.LOGGER.info("Command-block chain watch: {}", observation);
                    require(snapshot.command().text().equals(FIRST) || snapshot.command().text().equals(SECOND),
                        "only the two block commands are stepped: " + snapshot.command().text());
                    if (firstStopAfterFirstCommand) {
                        require(display.equals(expectedFirstChange), expectedFirstChange + " is shown immediately after the first scoreboard command");
                        require(entry.displayedChange() == (initiallyMissing
                                ? ClientWatchState.Change.VALUE_APPEARED : ClientWatchState.Change.VALUE_CHANGED),
                            "the immediate change distinguishes creation from a numeric update");
                        require(entry.displayedChange().isValueChange(), "the immediate change is highlighted");
                        require(context.computeOnClient(client -> WatchFormatting.fullValue(watch(), true).getString())
                                .equals(initiallyMissing ? "value missing → 3" : "0 → 3"),
                            "the tooltip retains the detailed absence label");
                        require(entry.result().status() == WatchResult.Status.NO_EXECUTOR,
                            "the prior executor's refreshed value never impersonates the next command's executor");
                        String executorLabel = entry.executorName() + " #" + entry.displayedExecutor().toString().substring(0, 8);
                        require(!entry.executorName().isBlank() && WatchFormatting.line(entry, true).getString()
                                .equals(executorLabel + " · a: " + expectedFirstChange),
                            "the previous executor's actual name and UUID precede the watch name and value");
                        require(WatchFormatting.changeBadge(entry).getString().isEmpty(),
                            "the previous executor has no redundant selector badge");
                        var tooltip = context.computeOnClient(client -> WatchFormatting.tooltip(watch(), true)
                            .stream().map(net.minecraft.network.chat.Component::getString).toList());
                        require(tooltip.contains("Previous executor: " + executorLabel)
                                && tooltip.contains("Current selection: no executor"),
                            "hover identifies the previous executor and preserves the current selection status");
                        require(WatchFormatting.specification(entry.spec()).getString().equals("a"),
                            "the score label omits the redundant kind prefix");
                    } else if (reachedSecondBlock && snapshot.reason() != PauseReason.EXECUTION_COMPLETE) {
                        require(display.equals("3"), "reselecting the executor does not replay the previous change");
                        require(entry.displayedChange() == ClientWatchState.Change.UNCHANGED,
                            "the already displayed value is the next baseline");
                    }
                    if (display.equals(expectedFirstChange)) {
                        require(snapshot.location().equals(new SourceLocation.Block(SECOND_BLOCK)), "first mutation is visible before the second block executes");
                        if (!sawFirstChange) {
                            screenshot(context, screenshotPrefix + (initiallyMissing ? "-unset-to-3" : "-0-to-3"));
                            if (initiallyMissing) checkHover(context);
                        }
                        sawFirstChange = true;
                    }
                    if (snapshot.reason() == PauseReason.EXECUTION_COMPLETE) {
                        require(snapshot.location().equals(new SourceLocation.Block(SECOND_BLOCK)), "completion belongs to the second block, not the first queue");
                        sawFinalChange = display.equals("3 → 7");
                        screenshot(context, screenshotPrefix + "-3-to-7");
                        break;
                    }
                    long previousPause = snapshot.pauseId();
                    long inputAt = System.nanoTime();
                    context.getInput().pressKey(InputConstants.KEY_F9);
                    context.waitFor(client -> hasNewPause(previousPause), 200);
                    long controlMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inputAt);
                    context.waitFor(client -> hasCurrentAndRequiredCapturedWatchRead(previousPause), 200);
                    long readsMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inputAt);
                    CodonMod.LOGGER.info("Command-block F9 latency (missing={}, pause={}): control={} ms, control+reads={} ms",
                        initiallyMissing, context.computeOnClient(client -> CodonClientMod.state().snapshot().pauseId()),
                        controlMillis, readsMillis);
                    require(readsMillis < WATCH_READ_LIMIT_MILLIS,
                        "F9 control plus current/captured watch reads took " + readsMillis
                            + " ms (limit " + WATCH_READ_LIMIT_MILLIS + " ms after warmup)");
                }
                require(sawFirstChange, expectedFirstChange + " must be shown in the chain. Trace: " + trace);
                require(sawFinalChange, "3 → 7 must be shown at completion. Trace: " + trace);
                context.runOnClient(client -> client.player.connection.sendCommand("codon resume"));
                context.waitFor(client -> !CodonClientMod.state().isPaused() && !CodonClientMod.state().isStepping(), 200);
                world.getServer().runOnServer(s -> {
                    var player = s.getPlayerList().getPlayers().getFirst();
                    require(s.getScoreboard().getPlayerScoreInfo(player, s.getScoreboard().getObjective("a")).value() == 7,
                        "the chain executes each block exactly once");
                });
            } finally {
                AtomicBoolean cleaned = new AtomicBoolean();
                DebuggerTaskQueue.execute(server, () -> {
                    CodonMod.engine().clearBreakpoints();
                    CodonMod.engine().resetSession();
                    cleaned.set(true);
                });
                context.waitFor(client -> cleaned.get(), 200);
                context.runOnClient(client -> client.setScreenAndShow(null));
            }
        }
    }

    private static ClientWatchState.Entry watch() {
        return CodonClientMod.state().watches().entries().getFirst();
    }

    private static boolean hasNewPause(long previousPause) {
        var state = CodonClientMod.state();
        return state.isPaused() && state.snapshot() != null && state.snapshot().pauseId() != previousPause;
    }

    /** A no-executor stop at the second block is the post-command case that must include the outgoing read. */
    private static boolean hasCurrentAndRequiredCapturedWatchRead(long previousPause) {
        if (!hasNewPause(previousPause)) return false;
        var state = CodonClientMod.state();
        ClientWatchState.Entry entry = watch();
        if (entry.result() == null) return false;
        boolean completedNoExecutorStop = state.snapshot().location().equals(new SourceLocation.Block(SECOND_BLOCK))
            && entry.result().status() == WatchResult.Status.NO_EXECUTOR;
        return !completedNoExecutorStop || entry.completedStep() != null;
    }

    private static void checkHover(ClientGameTestContext context) {
        double[] cursor = context.computeOnClient(client -> {
            var screen = client.gui.screen();
            int panelWidth = Math.min(500, screen.width - 24);
            return new double[] {
                ((screen.width - panelWidth) / 2.0 + 16) * client.getWindow().getScreenWidth() / screen.width,
                (Math.max(18, (screen.height - 250) / 2) + 99.0) * client.getWindow().getScreenHeight() / screen.height
            };
        });
        context.getInput().setCursorPos(cursor[0], cursor[1]);
        context.waitTicks(3);
        context.takeScreenshot("codon-watch-previous-executor-hover");
        context.runOnClient(client -> client.gui.screen().onClose());
        cursor = context.computeOnClient(client -> {
            var screen = client.gui.screen();
            var layout = DebuggerLayout.create(screen.width, screen.height, true);
            return new double[] {
                (screen.width - 40.0) * client.getWindow().getScreenWidth() / screen.width,
                (layout.world().y() + 22.0) * client.getWindow().getScreenHeight() / screen.height
            };
        });
        context.getInput().setCursorPos(cursor[0], cursor[1]);
        context.waitTicks(3);
        context.takeScreenshot("codon-watch-summary-hover");
        context.getInput().setCursorPos(1100, 400);
    }

    private static void screenshot(ClientGameTestContext context, String name) {
        context.runOnClient(client -> {
            client.options.guiScale().set(2);
            client.resizeGui();
            var state = CodonClientMod.state();
            var input = new InputManager(state, ignored -> {});
            for (var key : client.options.keyMappings) {
                switch (key.getName()) {
                    case "key.codon.open_menu" -> input.menuKey = key;
                    case "key.codon.breakpoint" -> input.breakpointKey = key;
                    case "key.codon.resume" -> input.resumeKey = key;
                    case "key.codon.step_over" -> input.stepOverKey = key;
                    case "key.codon.step_into" -> input.stepIntoKey = key;
                }
            }
            client.setScreenAndShow(new WatchScreen(input, state, new DebuggerOverlay(state)));
        });
        context.waitTicks(3);
        context.takeScreenshot(name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
