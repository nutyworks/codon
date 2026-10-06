package works.nuty.codon.client;

import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permissions;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.network.WatchDefinitionsSyncPayload;
import works.nuty.codon.persistence.WatchDefinitions;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** A promotion handshake must not silently discard a local edit that the model accepted. */
@SuppressWarnings("UnstableApiUsage")
public final class WatchPromotionHandshakeGameTest implements FabricClientGameTest {
    private static final WatchSpec EDIT = new WatchSpec(WatchSpec.Kind.SCORE, "promotion_handshake", "");
    private record Reply(WatchDefinitionsSyncPayload payload, ClientPlayNetworking.Context context) { }

    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            var file = world.getWorldSave().getSaveDirectory().resolve("data/codon-watches/singleplayer.json");
            List<Reply> delayed = new CopyOnWriteArrayList<>();
            var normal = context.computeOnClient(client -> removeReceiver());
            require(normal != null, "normal restoration receiver exists");
            context.runOnClient(client -> require(ClientPlayNetworking.registerReceiver(WatchDefinitionsSyncPayload.TYPE,
                (payload, receiver) -> delayed.add(new Reply(payload, receiver))), "hold actual promotion snapshot"));
            try {
                world.getServer().runOnServer(server -> {
                    var player = server.getPlayerList().getPlayers().getFirst();
                    require(!player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_OWNER),
                        "connection joined without owner authorization");
                    server.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
                });
                context.waitFor(client -> !delayed.isEmpty() && delayed.getLast().payload.last(), 200);
                boolean accepted = context.computeOnClient(client -> CodonClientMod.state().watches().add(EDIT));
                require(stored(file).isEmpty(), "uninitialized local edit does not overwrite stored definitions");
                context.runOnClient(client -> delayed.forEach(reply -> normal.receive(reply.payload, reply.context)));
                context.waitTicks(3);
                boolean retained = context.computeOnClient(client -> CodonClientMod.state().watches().definitions().contains(EDIT));
                System.out.println("Promotion handshake edit: accepted=" + accepted + ", retained=" + retained);
                require(!accepted || retained, "promotion handshake silently discarded an accepted local edit");
                context.runOnClient(client -> {
                    if (!retained) require(CodonClientMod.state().watches().add(EDIT), "owner edit activates after complete handshake");
                    else CodonClientMod.state().watches().retrySave();
                });
                context.waitFor(client -> {
                    try {
                        return WatchDefinitions.decode(JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("watches"))
                            .equals(List.of(EDIT));
                    } catch (java.io.IOException | RuntimeException missing) { return false; }
                }, 200);
                delayed.clear();
                world.getServer().runOnServer(server -> {
                    var original = server.getPlayerList().getPlayers().getFirst();
                    var connection = original.connection;
                    var respawned = server.getPlayerList().respawn(original, false,
                        net.minecraft.world.entity.Entity.RemovalReason.KILLED);
                    require(respawned != original && respawned.connection == connection,
                        "26.3 respawn replaces ServerPlayer while retaining its connection");
                });
                context.waitTicks(5);
                require(delayed.isEmpty(), "respawn must not resend one-time Watch initialization");
                world.getServer().runOnServer(server -> server.getPlayerList().deop(
                    server.getPlayerList().getPlayers().getFirst().nameAndId()));
                context.waitTicks(3);
                world.getServer().runOnServer(server -> server.getPlayerList().op(
                    server.getPlayerList().getPlayers().getFirst().nameAndId(),
                    Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty()));
                context.waitTicks(5);
                require(delayed.isEmpty(), "re-promotion after respawn preserves one-time Watch initialization");
                require(context.computeOnClient(client -> CodonClientMod.state().watches().definitions().equals(List.of(EDIT))),
                    "respawn and re-promotion preserve the initialized definitions");
                System.out.println("Promotion handshake native PASS: durable owner save; reused respawn connection; no repeated initialization after re-promotion");
            } finally {
                context.runOnClient(client -> {
                    ClientPlayNetworking.unregisterReceiver(WatchDefinitionsSyncPayload.TYPE.id());
                    require(ClientPlayNetworking.registerReceiver(WatchDefinitionsSyncPayload.TYPE, normal), "restore normal snapshot receiver");
                });
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static ClientPlayNetworking.PlayPayloadHandler<WatchDefinitionsSyncPayload> removeReceiver() {
        return (ClientPlayNetworking.PlayPayloadHandler<WatchDefinitionsSyncPayload>)
            ClientPlayNetworking.unregisterReceiver(WatchDefinitionsSyncPayload.TYPE.id());
    }

    private static List<WatchSpec> stored(java.nio.file.Path file) {
        if (!Files.exists(file)) return List.of();
        try {
            return WatchDefinitions.decode(JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("watches"));
        } catch (java.io.IOException failure) { throw new AssertionError("read isolated watch data", failure); }
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
