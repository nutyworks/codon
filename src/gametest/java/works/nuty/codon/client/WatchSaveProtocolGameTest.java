package works.nuty.codon.client;

import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import works.nuty.codon.client.state.ClientWatchState;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.network.WatchSavePageAckPayload;
import works.nuty.codon.network.WatchSavePayload;
import works.nuty.codon.network.WatchSaveSyncPayload;
import works.nuty.codon.network.WatchSaveV2Payload;
import works.nuty.codon.persistence.WatchDefinitions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual registered packet routes; connection-local observers preserve the production receivers. */
@SuppressWarnings("UnstableApiUsage")
public final class WatchSaveProtocolGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
            world.getConnection().waitForChunksRender();
            Path file = world.getWorldSave().getSaveDirectory().resolve("data/codon-watches/singleplayer.json");
            List<WatchSpec> first = definitions("v2", 128);
            var pages = WatchDefinitions.pages(first);
            require(pages.size() > 1, "fixture crosses actual wire page boundaries");
            Replies replies = context.computeOnClient(client -> new Replies());
            try {
                context.runOnClient(client -> {
                    require(ClientPlayNetworking.canSend(WatchSaveV2Payload.TYPE.id()), "server advertises negotiated v2");
                    require(ClientPlayNetworking.canSend(WatchSavePayload.TYPE.id()), "server retains legacy route");
                    require(CodonClientMod.state().watches().addAll(first), "normal client edit starts production upload");
                });
                context.waitFor(client -> !replies.pages.isEmpty(), 200);
                PageReply firstAck = replies.pages.getFirst();
                long transfer = firstAck.payload.transferId();
                require(firstAck.payload.nextOffset() == pages.getFirst().size(), "first ACK names the exact staged prefix");
                context.waitTicks(5);
                require(replies.pages.size() == 1 && replies.saves.isEmpty(), "withheld page ACK prevents another page/final save");
                require(!saved(file, first), "nonfinal staging does not become persisted definitions");
                context.runOnClient(client -> {
                    replies.holdPages.set(false);
                    replies.pageHandler.receive(firstAck.payload, firstAck.context);
                    replies.pageHandler.receive(firstAck.payload, firstAck.context); // duplicate must not advance twice
                });
                context.waitFor(client -> replies.save(transfer) != null, 200);
                SaveReply finalAck = replies.save(transfer);
                require(finalAck.payload.status() == WatchSaveSyncPayload.Status.SAVED && saved(file, first),
                    "actual final SAVED reply follows complete durable data");
                int offset = 0;
                require(replies.pages.size() == pages.size() - 1, "exactly one progress ACK per nonfinal page");
                for (int i = 0; i < replies.pages.size(); i++) {
                    offset += pages.get(i).size();
                    require(replies.pages.get(i).payload.transferId() == transfer
                        && replies.pages.get(i).payload.nextOffset() == offset, "ACK sequence has exact contiguous offsets");
                }
                context.runOnClient(client -> {
                    require(CodonClientMod.state().watches().saveStatus() == ClientWatchState.SaveStatus.SAVING,
                        "progress and disk data alone do not mark client Saved");
                    replies.saveHandler.receive(finalAck.payload, finalAck.context);
                });
                context.waitFor(client -> CodonClientMod.state().watches().saveStatus() == ClientWatchState.SaveStatus.SAVED, 200);

                // Lose a real nonfinal ACK, wait through the real fixed deadline, then replay it.
                replies.pages.clear();
                replies.saves.clear();
                replies.holdPages.set(true);
                replies.holdSaves.set(false);
                List<WatchSpec> second = new ArrayList<>(first);
                second.add(new WatchSpec(WatchSpec.Kind.SCORE, "protocol_retry", ""));
                context.runOnClient(client -> require(CodonClientMod.state().watches().add(second.getLast()), "start changed snapshot"));
                context.waitFor(client -> !replies.pages.isEmpty(), 200);
                PageReply lost = replies.pages.getFirst();
                require(lost.payload.transferId() > transfer, "replacement uses a newer transfer identity");
                long deadline = System.nanoTime() + 35_000_000_000L;
                while (context.computeOnClient(client -> CodonClientMod.state().watches().saveStatus() != ClientWatchState.SaveStatus.FAILED)) {
                    require(System.nanoTime() < deadline, "lost ACK times out within the fixed lifetime");
                    context.waitTick();
                }
                require(saved(file, first), "timed-out incomplete upload preserves old durable definitions");
                context.runOnClient(client -> replies.pageHandler.receive(lost.payload, lost.context));
                context.waitTicks(5);
                require(replies.pages.size() == 1 && replies.saves.isEmpty(), "late ACK cannot revive timed-out upload");
                replies.holdPages.set(false);
                context.runOnClient(client -> CodonClientMod.state().watches().retrySave());
                context.waitFor(client -> CodonClientMod.state().watches().saveStatus() == ClientWatchState.SaveStatus.SAVED, 200);
                require(saved(file, second), "explicit retry completes the actual production v2 route");

                // Send v1 packets explicitly: success must not depend on negotiated v2 or its page ACK.
                List<WatchSpec> legacy = definitions("legacy", 96);
                var legacyPages = WatchDefinitions.pages(legacy);
                require(legacyPages.size() > 1, "legacy control is multipage too");
                long legacyId = 8_000_001;
                context.runOnClient(client -> ClientPlayNetworking.send(new WatchSavePayload(legacyId, 0, false, legacyPages.getFirst())));
                context.waitTicks(5);
                require(replies.save(legacyId) == null && replies.pages.stream().noneMatch(p -> p.payload.transferId() == legacyId),
                    "legacy nonfinal page sends neither final nor v2 progress ACK");
                require(saved(file, second), "legacy first page does not partially overwrite data");
                context.runOnClient(client -> {
                    int start = legacyPages.getFirst().size();
                    for (int i = 1; i < legacyPages.size(); i++) {
                        var page = legacyPages.get(i);
                        ClientPlayNetworking.send(new WatchSavePayload(legacyId, start, i == legacyPages.size() - 1, page));
                        start += page.size();
                    }
                });
                context.waitFor(client -> replies.save(legacyId) != null, 200);
                require(replies.save(legacyId).payload.status() == WatchSaveSyncPayload.Status.SAVED && saved(file, legacy),
                    "legacy route produces durable all-or-nothing save and final ACK");
                long rejectedId = legacyId + 1;
                context.runOnClient(client -> ClientPlayNetworking.send(
                    new WatchSavePayload(rejectedId, 1, true, List.of(new WatchSpec(WatchSpec.Kind.SCORE, "invalid_gap", "")))));
                context.waitFor(client -> replies.save(rejectedId) != null, 200);
                require(replies.save(rejectedId).payload.status() == WatchSaveSyncPayload.Status.INVALID && saved(file, legacy),
                    "out-of-order legacy transfer rejects without replacing durable state");
                System.out.println("Watch protocol native PASS: negotiated v2 exact ACK/one-inflight/durable final; duplicate/drop/timeout/stale/retry; legacy multipage and invalid-gap preservation");
            } finally {
                context.runOnClient(client -> replies.restore());
            }
        }
    }

    private static List<WatchSpec> definitions(String prefix, int count) {
        List<WatchSpec> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", prefix + "p".repeat(80) + i));
        return List.copyOf(result);
    }

    private static boolean saved(Path file, List<WatchSpec> expected) {
        try {
            var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            return WatchDefinitions.decode(json.get("watches")).equals(expected);
        } catch (java.io.IOException | RuntimeException missing) { return false; }
    }

    private record PageReply(WatchSavePageAckPayload payload, ClientPlayNetworking.Context context) { }
    private record SaveReply(WatchSaveSyncPayload payload, ClientPlayNetworking.Context context) { }

    private static final class Replies {
        final List<PageReply> pages = new CopyOnWriteArrayList<>();
        final List<SaveReply> saves = new CopyOnWriteArrayList<>();
        final AtomicBoolean holdPages = new AtomicBoolean(true);
        final AtomicBoolean holdSaves = new AtomicBoolean(true);
        final ClientPlayNetworking.PlayPayloadHandler<WatchSavePageAckPayload> pageHandler;
        final ClientPlayNetworking.PlayPayloadHandler<WatchSaveSyncPayload> saveHandler;

        Replies() {
            pageHandler = remove(WatchSavePageAckPayload.TYPE);
            saveHandler = remove(WatchSaveSyncPayload.TYPE);
            require(pageHandler != null && saveHandler != null, "normal connection-local receivers exist");
            require(ClientPlayNetworking.registerReceiver(WatchSavePageAckPayload.TYPE, (payload, context) -> {
                pages.add(new PageReply(payload, context));
                if (!holdPages.get()) pageHandler.receive(payload, context);
            }), "observe actual page ACKs");
            require(ClientPlayNetworking.registerReceiver(WatchSaveSyncPayload.TYPE, (payload, context) -> {
                saves.add(new SaveReply(payload, context));
                if (!holdSaves.get()) saveHandler.receive(payload, context);
            }), "observe actual final replies");
        }

        SaveReply save(long id) { return saves.stream().filter(p -> p.payload.transferId() == id).findFirst().orElse(null); }

        void restore() {
            ClientPlayNetworking.unregisterReceiver(WatchSavePageAckPayload.TYPE.id());
            ClientPlayNetworking.unregisterReceiver(WatchSaveSyncPayload.TYPE.id());
            require(ClientPlayNetworking.registerReceiver(WatchSavePageAckPayload.TYPE, pageHandler), "restore page receiver");
            require(ClientPlayNetworking.registerReceiver(WatchSaveSyncPayload.TYPE, saveHandler), "restore final receiver");
        }

        @SuppressWarnings("unchecked")
        private static <T extends CustomPacketPayload> ClientPlayNetworking.PlayPayloadHandler<T> remove(CustomPacketPayload.Type<T> type) {
            return (ClientPlayNetworking.PlayPayloadHandler<T>) ClientPlayNetworking.unregisterReceiver(type.id());
        }
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
