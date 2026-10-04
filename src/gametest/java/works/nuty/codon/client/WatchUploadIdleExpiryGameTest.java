package works.nuty.codon.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.adapter.McExecutionController;
import works.nuty.codon.client.state.ClientWatchState;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.TransferBudget;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.network.WatchSavePageAckPayload;
import works.nuty.codon.network.WatchSaveV2Payload;
import works.nuty.codon.persistence.WorldWatchPersistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Actual upload packets and real 30-second deadlines in running and debugger-parked worlds.
 * The test mixin only captures persistence. All staging observations run on the server thread,
 * never call isActive/expireTransfers, never adjust clocks, and never send a follow-up page.
 */
@SuppressWarnings("UnstableApiUsage")
public final class WatchUploadIdleExpiryGameTest implements FabricClientGameTest {
    private static final WatchSpec SAVED = new WatchSpec(WatchSpec.Kind.SCORE, "idle_saved", "");
    private static final WatchSpec PENDING = new WatchSpec(WatchSpec.Kind.SCORE, "idle_pending", "");
    private static final WatchSpec RECOVERED = new WatchSpec(WatchSpec.Kind.SCORE, "idle_recovered", "");
    private static final BlockLocation BREAKPOINT = new BlockLocation(83, 80, 0, "minecraft:overworld");
    private static volatile WorldWatchPersistence capturedPersistence;

    /** Called only by the GameTest observation mixin after the real world has opened. */
    public static void capturePersistence(WorldWatchPersistence persistence) {
        capturedPersistence = persistence;
    }

    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
            world.getConnection().waitForChunksRender();
            context.waitFor(client -> CodonClientMod.state().watches().initialDefinitionsReceived(), 200);
            MinecraftServer server = world.getServer().computeOnServer(value -> value);
            UUID owner = world.getServer().computeOnServer(value -> value.getPlayerList().getPlayers().getFirst().getUUID());
            WorldWatchPersistence persistence = capturedPersistence;
            require(persistence != null, "test probe captured the composition-root persistence");
            Path file = world.getWorldSave().getSaveDirectory().resolve("data/codon-watches/singleplayer.json");
            Replies replies = context.computeOnClient(client -> new Replies());
            AtomicBoolean returned = new AtomicBoolean(true);
            AtomicReference<Throwable> commandFailure = new AtomicReference<>();
            try {
                context.runOnClient(client -> {
                    client.setScreenAndShow(null);
                    require(ClientPlayNetworking.canSend(WatchSaveV2Payload.TYPE.id()), "native server advertises v2 upload");
                    require(CodonClientMod.state().watches().add(SAVED), "normal client edit creates committed baseline");
                });
                context.waitFor(client -> CodonClientMod.state().watches().saveStatus() == ClientWatchState.SaveStatus.SAVED, 200);
                byte[] savedBytes = readBytes(file);
                require(onServer(context, server, () -> persistence.get(owner)).equals(List.of(SAVED)),
                    "baseline reached actual server persistence");

                expireWithoutAnotherPage(context, server, persistence, owner, replies, file, savedBytes, 9_100_001, false);

                world.getServer().runCommand("scoreboard objectives add idle_expiry dummy");
                world.getServer().runOnServer(value -> {
                    CodonMod.engine().clearBreakpoints();
                    CodonMod.engine().toggleBlockBreakpoint(BREAKPOINT);
                });
                returned.set(false);
                // Vanilla Commands owns the execution scope; the real mixins drive the breakpoint.
                server.execute(() -> {
                    try {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack()
                            .withPosition(new Vec3(83, 80, 0)).withSuppressedOutput(),
                            "execute positioned ~ ~ ~ run scoreboard players add expiry_done idle_expiry 1");
                    } catch (Throwable problem) {
                        commandFailure.set(problem);
                    } finally {
                        returned.set(true);
                    }
                });
                context.waitFor(client -> CodonClientMod.state().isPaused() && McExecutionController.isParked(), 200);
                if (commandFailure.get() != null) throw new AssertionError("native command fixture failed", commandFailure.get());
                AtomicBoolean ordinaryTask = new AtomicBoolean();
                server.execute(() -> ordinaryTask.set(true));
                expireWithoutAnotherPage(context, server, persistence, owner, replies, file, savedBytes, 9_100_002, true);
                require(!ordinaryTask.get() && !returned.get(), "ordinary work and command remain parked through expiry");

                context.runOnClient(client -> require(CodonClientMod.state().watches().add(RECOVERED),
                    "fresh normal client edit starts upload while still parked"));
                context.waitFor(client -> CodonClientMod.state().watches().saveStatus() == ClientWatchState.SaveStatus.SAVED, 200);
                require(onServer(context, server, () -> persistence.get(owner)).equals(List.of(SAVED, RECOVERED)),
                    "fresh actual upload commits after expired staging was removed");
                require(!Arrays.equals(savedBytes, readBytes(file)), "fresh complete upload reaches durable file");
                require(!ordinaryTask.get() && !returned.get(), "fresh upload does not resume ordinary execution");
                context.runOnClient(client -> client.player.connection.sendCommand("codon resume"));
                context.waitFor(client -> returned.get() && ordinaryTask.get() && !CodonClientMod.state().isPaused()
                    && !McExecutionController.isParked(), 200);
                if (commandFailure.get() != null) throw new AssertionError("native command fixture failed", commandFailure.get());
                int score = world.getServer().computeOnServer(value -> value.getScoreboard().getPlayerScoreInfo(
                    net.minecraft.world.scores.ScoreHolder.forNameOnly("expiry_done"),
                    value.getScoreboard().getObjective("idle_expiry")).value());
                require(score == 1, "actual command resumes and mutates exactly once");
                System.out.println("Watch idle expiry native PASS: fresh upload SAVED while parked; network resume releases ordinary task; command executed once");
            } finally {
                onServer(context, server, () -> {
                    CodonMod.engine().clearBreakpoints();
                    CodonMod.engine().resetSession();
                    return true;
                });
                context.waitFor(client -> returned.get() && !McExecutionController.isParked(), 200);
                context.runOnClient(client -> {
                    replies.restore();
                    client.setScreenAndShow(null);
                });
            }
        }
    }

    private static void expireWithoutAnotherPage(ClientGameTestContext context, MinecraftServer server,
        WorldWatchPersistence persistence, UUID owner, Replies replies, Path file, byte[] savedBytes,
        long transferId, boolean paused) {
        long sentAt = System.nanoTime();
        context.runOnClient(client -> ClientPlayNetworking.send(new WatchSaveV2Payload(transferId, 0, false, List.of(PENDING))));
        context.waitFor(client -> replies.pages.stream().anyMatch(page -> page.transferId() == transferId), 200);
        Observation first = observe(context, server, persistence, owner);
        require(first.staged && first.parked == paused, "one nonfinal page stages in the requested server lifecycle");
        System.out.println("Watch idle expiry native waiting: phase=" + (paused ? "paused" : "running") + ", real timeout=30s, pages=1");
        Observation last;
        do {
            require(System.nanoTime() - sentAt < 40_000_000_000L, "idle staging removed by lifecycle maintenance within 40 seconds");
            context.waitTicks(5);
            last = observe(context, server, persistence, owner);
            require(last.parked == paused, "server stays in the requested lifecycle during the no-packet wait");
            if (paused) require(last.gameTime == first.gameTime, "ordinary world ticks remain frozen while expiry runs");
        } while (last.staged);
        long elapsed = System.nanoTime() - sentAt;
        require(elapsed >= TransferBudget.TIMEOUT_NANOS, "real transfer lifetime elapsed without clock changes");
        require(paused || last.gameTime > first.gameTime, "running expiry occurs while normal world ticks advance");
        require(replies.pages.stream().filter(page -> page.transferId() == transferId).count() == 1,
            "exactly one page was acknowledged; no later page caused cleanup");
        require(Arrays.equals(savedBytes, readBytes(file)), "idle eviction preserves committed file bytes");
        require(onServer(context, server, () -> persistence.get(owner)).equals(List.of(SAVED)),
            "idle eviction preserves committed in-memory definitions");
        System.out.println("Watch idle expiry native PASS: phase=" + (paused ? "paused" : "running")
            + ", elapsedMs=" + elapsed / 1_000_000 + ", stagingRemoved=true, gameTimeDelta="
            + (last.gameTime - first.gameTime) + ", savedBytesUnchanged=true, pages=1");
    }

    private static Observation observe(ClientGameTestContext context, MinecraftServer server,
                                       WorldWatchPersistence persistence, UUID owner) {
        return onServer(context, server, () -> {
            try {
                var field = WorldWatchPersistence.class.getDeclaredField("stagedTransfers");
                field.setAccessible(true);
                boolean staged = ((Map<?, ?>) field.get(persistence)).containsKey(owner);
                return new Observation(staged, server.overworld().getGameTime(), McExecutionController.isParked());
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("observe staging without evaluating or modifying a transfer", failure);
            }
        });
    }

    private static <T> T onServer(ClientGameTestContext context, MinecraftServer server, Supplier<T> read) {
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        require(DebuggerTaskQueue.execute(server, () -> {
            try {
                require(server.isSameThread(), "probe only reads server-owned state on the server thread");
                result.set(read.get());
            } catch (Throwable problem) {
                failure.set(problem);
            } finally {
                done.set(true);
            }
        }), "observation admitted to the existing debugger mailbox");
        context.waitFor(client -> done.get(), 200);
        if (failure.get() != null) throw new AssertionError("server-thread observation failed", failure.get());
        return result.get();
    }

    private static byte[] readBytes(Path file) {
        try { return Files.readAllBytes(file); }
        catch (java.io.IOException failure) { throw new AssertionError("read committed watch bytes", failure); }
    }

    private record Observation(boolean staged, long gameTime, boolean parked) { }

    private static final class Replies {
        final List<WatchSavePageAckPayload> pages = new CopyOnWriteArrayList<>();
        final ClientPlayNetworking.PlayPayloadHandler<WatchSavePageAckPayload> normal;

        @SuppressWarnings("unchecked")
        Replies() {
            normal = (ClientPlayNetworking.PlayPayloadHandler<WatchSavePageAckPayload>)
                ClientPlayNetworking.unregisterReceiver(WatchSavePageAckPayload.TYPE.id());
            require(normal != null, "normal progress receiver exists");
            require(ClientPlayNetworking.registerReceiver(WatchSavePageAckPayload.TYPE, (payload, receiver) -> {
                pages.add(payload);
                normal.receive(payload, receiver);
            }), "observe real progress replies without suppressing the production handler");
        }

        void restore() {
            ClientPlayNetworking.unregisterReceiver(WatchSavePageAckPayload.TYPE.id());
            require(ClientPlayNetworking.registerReceiver(WatchSavePageAckPayload.TYPE, normal), "restore normal progress receiver");
        }
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
