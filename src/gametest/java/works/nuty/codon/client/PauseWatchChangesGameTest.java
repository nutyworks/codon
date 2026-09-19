package works.nuty.codon.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import works.nuty.codon.adapter.PauseWatchChanges;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.EntityRef;
import works.nuty.codon.core.model.PauseReason;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.Vec3d;
import works.nuty.codon.core.model.WatchChange;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.util.List;

/** Server-side contract checks for automatic stop-to-stop observations. */
@SuppressWarnings("UnstableApiUsage")
public final class PauseWatchChangesGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            world.getServer().runCommand("scoreboard objectives add pause_watch_points dummy");
            world.getServer().runCommand("scoreboard players set @a pause_watch_points 10");
            world.getServer().runCommand("data merge storage codon:pause_watch {value:10,removed:1}");
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                var changes = new PauseWatchChanges();
                PauseSnapshot source = pause(player.getUUID(), player.getName().getString());
                String longKey = "x".repeat(WatchSpec.MAX_INPUT_LENGTH + 1);
                var initialStorage = server.getCommandStorage().get(Identifier.parse("codon:pause_watch")).copy();
                initialStorage.put("empty", new net.minecraft.nbt.CompoundTag());
                initialStorage.put("empty_to_nonempty", new net.minecraft.nbt.CompoundTag());
                initialStorage.putInt("scalar_to_compound", 1);
                initialStorage.putString("huge", "a".repeat(WatchResult.MAX_VALUE_LENGTH + 20));
                initialStorage.putInt(longKey, 1);
                server.getCommandStorage().set(Identifier.parse("codon:pause_watch"), initialStorage);

                require(changes.capture(server, source).isEmpty(), "first stop is a baseline");
                server.getScoreboard().getOrCreatePlayerScore(player,
                    server.getScoreboard().getObjective("pause_watch_points")).set(11);
                player.setCustomName(Component.literal("pause-watch-mutated"));
                var storage = server.getCommandStorage().get(Identifier.parse("codon:pause_watch")).copy();
                storage.putInt("value", 11);
                storage.remove("removed");
                storage.putInt("created", 2);
                storage.put("empty", new net.minecraft.nbt.ListTag());
                var nonempty = new net.minecraft.nbt.CompoundTag();
                nonempty.putInt("x", 1);
                storage.put("empty_to_nonempty", nonempty);
                storage.put("scalar_to_compound", nonempty.copy());
                storage.putString("huge", "a".repeat(WatchResult.MAX_VALUE_LENGTH + 19) + "b");
                storage.putInt(longKey, 2);
                server.getCommandStorage().set(Identifier.parse("codon:pause_watch"), storage);

                List<WatchChange> delta = changes.capture(server, source);
                require(change(delta, new WatchSpec(WatchSpec.Kind.SCORE, "pause_watch_points", "", player.getUUID()),
                    WatchResult.Status.VALUE, WatchResult.Status.VALUE), "score mutation is included");
                require(changeAt(delta, WatchSpec.Kind.ENTITY_NBT, "\"CustomName\"", WatchResult.Status.VALUE),
                    "entity NBT mutation is included");
                require(changeAt(delta, WatchSpec.Kind.STORAGE_NBT, "\"value\"", WatchResult.Status.VALUE),
                    "storage value mutation is included");
                require(changeAt(delta, WatchSpec.Kind.STORAGE_NBT, "\"removed\"", WatchResult.Status.VALUE_MISSING),
                    "storage leaf deletion is explicit");
                require(changeAt(delta, WatchSpec.Kind.STORAGE_NBT, "\"created\"", WatchResult.Status.VALUE),
                    "storage leaf creation is included");
                require(changeAt(delta, WatchSpec.Kind.STORAGE_NBT, "\"empty\"", WatchResult.Status.VALUE),
                    "empty compound to empty list is included");
                require(changeAfterValue(delta, "\"empty_to_nonempty\"", "{x:1}"),
                    "empty to nonempty retains the actual parent NBT rather than VALUE_MISSING");
                require(changeAfterValue(delta, "\"scalar_to_compound\"", "{x:1}"),
                    "scalar to compound retains the actual parent NBT rather than VALUE_MISSING");
                require(delta.stream().anyMatch(change -> change.spec().kind() == WatchSpec.Kind.STORAGE_NBT
                    && change.spec().path().equals("\"huge\"")
                    && change.before().status() == WatchResult.Status.TOO_LARGE
                    && change.after().status() == WatchResult.Status.TOO_LARGE),
                    "oversized values compare their full server-local observation");
                require(changeAt(delta, WatchSpec.Kind.STORAGE_NBT, "{}", WatchResult.Status.TOO_LARGE),
                    "an unrepresentable top-level key collapses to the root row");

                var withoutLongKey = server.getCommandStorage().get(Identifier.parse("codon:pause_watch")).copy();
                withoutLongKey.remove(longKey);
                server.getCommandStorage().set(Identifier.parse("codon:pause_watch"), withoutLongKey);
                delta = changes.capture(server, source);
                require(changeAt(delta, WatchSpec.Kind.STORAGE_NBT, "{}", WatchResult.Status.TOO_LARGE),
                    "removing an unrepresentable child retains the existing root, not value missing");

                server.getScoreboard().resetSinglePlayerScore(player,
                    server.getScoreboard().getObjective("pause_watch_points"));
                server.getCommandStorage().set(Identifier.parse("codon:pause_watch"), new net.minecraft.nbt.CompoundTag());
                delta = changes.capture(server, pause());
                require(change(delta, new WatchSpec(WatchSpec.Kind.SCORE, "pause_watch_points", "", player.getUUID()),
                    WatchResult.Status.VALUE, WatchResult.Status.VALUE_MISSING), "score deletion is explicit after source disappears");
                require(changeAt(delta, WatchSpec.Kind.STORAGE_NBT, "\"value\"", WatchResult.Status.TARGET_MISSING),
                    "storage key deletion is target missing");

                require(changes.capture(server, pause()).isEmpty(), "unchanged following stop has no delta");
                changes.reset();
                require(changes.capture(server, pause(player.getUUID(), player.getName().getString())).isEmpty(),
                    "reset makes the next stop a baseline");
            });
        }
    }

    private static PauseSnapshot pause(java.util.UUID id, String name) {
        return new PauseSnapshot(new SourceLocation.Block(new BlockLocation(0, 80, 0, "minecraft:overworld")),
            CommandSnippet.plain("say pause-watch"), 0, List.of(),
            List.of(new PauseSource(new Vec3d(0, 80, 0), 0, 0, new EntityRef(id, name), "minecraft:overworld")),
            PauseReason.BREAKPOINT, 1);
    }

    private static PauseSnapshot pause() {
        return new PauseSnapshot(new SourceLocation.Block(new BlockLocation(0, 80, 0, "minecraft:overworld")),
            CommandSnippet.plain("say pause-watch"), 0, List.of(), List.of(), PauseReason.BREAKPOINT, 2);
    }

    private static boolean change(List<WatchChange> changes, WatchSpec spec,
                                  WatchResult.Status before, WatchResult.Status after) {
        return changes.stream().anyMatch(change -> change.spec().equals(spec)
            && change.before().status() == before && change.after().status() == after);
    }

    private static boolean changeAt(List<WatchChange> changes, WatchSpec.Kind kind, String path,
                                    WatchResult.Status after) {
        return changes.stream().anyMatch(change -> change.spec().kind() == kind && change.spec().path().equals(path)
            && change.after().status() == after);
    }

    private static boolean changeAfterValue(List<WatchChange> changes, String path, String value) {
        return changes.stream().anyMatch(change -> change.spec().kind() == WatchSpec.Kind.STORAGE_NBT
            && change.spec().path().equals(path) && change.after().status() == WatchResult.Status.VALUE
            && change.after().value().equals(value));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
