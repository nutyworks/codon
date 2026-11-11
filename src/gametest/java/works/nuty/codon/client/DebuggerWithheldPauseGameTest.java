package works.nuty.codon.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.network.ClientboundLimits;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real server-driven stop whose contexts exceed the receive limit keeps the client paused with a
 * withheld-details notice, and the ordinary Continue key still releases the parked server.
 */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerWithheldPauseGameTest implements FabricClientGameTest {
    private static final int MARKERS = ClientboundLimits.MAX_PAUSE_SOURCES + 1;
    private static final BlockLocation BLOCK = new BlockLocation(4, 80, 0, "minecraft:overworld");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            var server = world.getServer().computeOnServer(s -> s);
            setup(world);
            try {
                world.getServer().runCommand("setblock 3 80 0 minecraft:redstone_block");
                context.waitFor(client -> CodonClientMod.state().isPaused(), 200);
                require(context.computeOnClient(client -> CodonClientMod.state().withheldPauseDetails() == null),
                    "the whole-command stop has one context and is sent in full");

                for (int attempt = 0; attempt < 4
                    && context.computeOnClient(client -> CodonClientMod.state().withheldPauseDetails() == null); attempt++) {
                    long previous = context.computeOnClient(client -> CodonClientMod.state().snapshot().pauseId());
                    context.getInput().pressKey(options -> CodonClientMod.input().stepIntoKey);
                    context.waitFor(client -> CodonClientMod.state().isPaused()
                        && CodonClientMod.state().snapshot().pauseId() != previous, 200);
                }
                context.runOnClient(client -> {
                    var state = CodonClientMod.state();
                    var withheld = state.withheldPauseDetails();
                    require(withheld != null && withheld.pauseSourceCount() == MARKERS,
                        "stepping into the oversized stage reports withheld contexts: " + withheld);
                    require(state.isPaused() && state.snapshot().pauseId() > 0 && state.snapshot().pauseSources().isEmpty(),
                        "the client stays paused at the live pause without a truncated context list");
                });
                require(isServerPaused(context, server), "the server remains parked at the oversized stop");

                context.runOnClient(client -> client.setScreenAndShow(
                    new CodonScreen(CodonClientMod.input(), new DebuggerOverlay(CodonClientMod.state()))));
                context.waitTicks(5);
                context.takeScreenshot("codon-withheld-pause-details");
                context.runOnClient(client -> client.setScreenAndShow(null));
                context.waitTicks(2);

                context.getInput().pressKey(options -> CodonClientMod.input().resumeKey);
                context.waitFor(client -> !CodonClientMod.state().isPaused()
                    || CodonClientMod.state().withheldPauseDetails() == null, 200);
                for (int attempt = 0; attempt < 4 && context.computeOnClient(client -> CodonClientMod.state().isPaused()); attempt++) {
                    // Continue may stop once more at execution completion; that stop has no oversized details.
                    context.getInput().pressKey(options -> CodonClientMod.input().resumeKey);
                    context.waitFor(client -> !CodonClientMod.state().isPaused(), 200);
                }
                require(!isServerPaused(context, server), "Continue releases the server from a withheld pause");
                AtomicInteger tagged = new AtomicInteger(-1);
                world.getServer().runOnServer(s -> tagged.set(s.overworld().getEntities(
                    net.minecraft.world.entity.EntityTypes.MARKER, entity -> entity.entityTags().contains("withheld_seen")).size()));
                require(tagged.get() == MARKERS, "execution continued through every context: " + tagged.get());
            } finally {
                AtomicBoolean cleaned = new AtomicBoolean();
                DebuggerTaskQueue.execute(server, () -> {
                    CodonMod.engine().clearBreakpoints();
                    CodonMod.engine().resetSession();
                    cleaned.set(true);
                });
                context.waitFor(client -> cleaned.get(), 200);
            }
        }
    }

    private static void setup(TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            server.getPlayerList().op(player.nameAndId(),
                Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER), Optional.empty());
            CodonMod.engine().clearBreakpoints();
        });
        world.getServer().runCommand("gamemode creative @a");
        world.getServer().runCommand("fill 3 79 -1 8 79 3 minecraft:stone");
        world.getServer().runCommand("tp @a 5.5 80.0 3.5");
        world.getServer().runOnServer(server -> {
            var source = server.createCommandSourceStack().withSuppressedOutput();
            for (int i = 0; i < MARKERS; i++) {
                server.getCommands().performPrefixedCommand(source, "summon minecraft:marker 6 80 0");
            }
        });
        world.getServer().runCommand("setblock 4 80 0 minecraft:command_block[facing=east]"
            + "{Command:\"execute as @e[type=minecraft:marker] run tag @s add withheld_seen\",auto:0b}");
        world.getServer().runOnServer(server -> CodonMod.engine().toggleBlockBreakpoint(BLOCK));
    }

    private static boolean isServerPaused(ClientGameTestContext context, net.minecraft.server.MinecraftServer server) {
        AtomicBoolean done = new AtomicBoolean();
        AtomicBoolean paused = new AtomicBoolean();
        DebuggerTaskQueue.execute(server, () -> {
            paused.set(CodonMod.engine().isPaused());
            done.set(true);
        });
        context.waitFor(client -> done.get(), 200);
        return paused.get();
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
