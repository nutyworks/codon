package works.nuty.codon.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;
import works.nuty.codon.adapter.PauseWatchChanges;
import works.nuty.codon.adapter.StorageReadStatus;
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
import works.nuty.codon.mixin.CommandStorageAccessor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Corrupt files exist only inside new disposable worlds; vanilla's partial read is preserved. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerStorageReadFailureGameTest implements FabricClientGameTest {
    private static final String BAD = "codon_failure_bad";
    private static final Identifier TARGET = Identifier.fromNamespaceAndPath(BAD, "acceptance");

    @Override public void runTest(ClientGameTestContext context) {
        for (String mode : List.of("truncated", "partial_only", "partial_mixed")) verify(context, mode);
    }

    private void verify(ClientGameTestContext context, String mode) {
        TestWorldSave save;
        try (var world = context.worldBuilder().create()) {
            world.getServer().runCommand("scoreboard objectives add storage_failure_points dummy");
            world.getServer().runCommand("scoreboard players set @a storage_failure_points 1");
            world.getServer().runOnServer(server -> {
                Path data = server.getWorldPath(LevelResource.DATA);
                try {
                    var bad = values();
                    if (!mode.equals("truncated")) {
                        var contents = new CompoundTag();
                        contents.putString("invalid", "not a compound");
                        if (mode.equals("partial_mixed")) contents.put("acceptance", bad);
                        write(data, BAD, contents);
                    } else {
                        write(data, BAD, targetContents(bad));
                        Path file = data.resolve(BAD + "/command_storage.dat");
                        Files.write(file, Arrays.copyOf(Files.readAllBytes(file), 8));
                    }
                    var good = new CompoundTag();
                    good.putInt("steady", 7);
                    write(data, "codon_failure_good", targetContents(good));
                    write(data, "codon_failure_empty", new CompoundTag());
                    Path badFile = data.resolve(BAD + "/command_storage.dat");
                    byte[] corruptBytes = Files.readAllBytes(badFile);
                    var storage = server.getCommandStorage();
                    var loaded = ((CommandStorageAccessor) storage).codon$loadedNamespaces();
                    require(!loaded.containsKey(BAD), "failure fixture starts cold");
                    var changes = new PauseWatchChanges();
                    var player = server.getPlayerList().getPlayers().getFirst();
                    var source = pause(player.getUUID(), player.getName().getString());
                    require(changes.capture(server, source).isEmpty(), "initial failed snapshot emits no delta");
                    require(!baseline(changes), "failed/partial decode must not establish Storage baseline");
                    if (mode.equals("truncated")) {
                        require(!loaded.containsKey(BAD), "read exception does not cache an empty container");
                    } else {
                        require(loaded.containsKey(BAD), "preserve vanilla's partial decoded container");
                        require(((StorageReadStatus) server.getDataStorage()).codon$hadIncompleteStorageRead(BAD),
                            "observe the partial decoder's explicit error");
                        require(storage.get(TARGET).getIntOr("changed", -1)
                            == (mode.equals("partial_mixed") ? 0 : -1), "vanilla partial values remain unchanged");
                    }
                    require(Arrays.equals(corruptBytes, Files.readAllBytes(badFile)), "failed snapshot preserves bytes");
                    require(loaded.values().stream().noneMatch(net.minecraft.world.level.saveddata.SavedData::isDirty),
                        "failed/partial snapshot does not dirty any container");
                    server.getScoreboard().getOrCreatePlayerScore(player,
                        server.getScoreboard().getObjective("storage_failure_points")).set(2);
                    player.setHealth(19);
                    var delta = changes.capture(server, source);
                    require(delta.stream().noneMatch(change -> change.spec().kind() == WatchSpec.Kind.STORAGE_NBT),
                        "cached partial read must not produce false Storage changes");
                    require(delta.stream().anyMatch(change -> change.spec().kind() == WatchSpec.Kind.SCORE
                        && change.before().value().equals("1") && change.after().value().equals("2")),
                        "Storage failure does not suppress ordinary score history");
                    require(delta.stream().anyMatch(change -> change.spec().kind() == WatchSpec.Kind.ENTITY_NBT
                        && change.spec().path().equals("\"Health\"") && change.after().value().equals("19.0f")),
                        "Storage failure does not suppress ordinary entity history");
                    require(!baseline(changes), "a repeated failed snapshot still preserves the uninitialized baseline");
                    write(data, BAD, targetContents(values()));
                    require(changes.capture(server, source).stream()
                        .noneMatch(change -> change.spec().kind() == WatchSpec.Kind.STORAGE_NBT),
                        "same-server repair does not turn a cached failed read into a fabricated delta");
                    require(!baseline(changes), "do not claim same-server repair bypasses vanilla's cached result");
                    System.out.println("Storage failure " + mode + ": baseline withheld, vanilla cache/bytes preserved; repair requires fresh reader");
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            save = world.getWorldSave();
        }
        try (var reopened = save.open()) {
            reopened.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                var source = pause(player.getUUID(), player.getName().getString());
                var changes = new PauseWatchChanges();
                require(changes.capture(server, source).isEmpty(), "fresh complete read establishes a neutral baseline");
                require(baseline(changes), "repaired saved world can establish its Storage baseline");
                require(!((StorageReadStatus) server.getDataStorage()).codon$hadIncompleteStorageRead(BAD),
                    "fresh reader's valid decode has no partial-error marker");
                var storage = server.getCommandStorage();
                var loaded = ((CommandStorageAccessor) storage).codon$loadedNamespaces();
                require(loaded.containsKey("codon_failure_empty"), "genuine empty persisted container remains valid");
                require(loaded.values().stream().noneMatch(net.minecraft.world.level.saveddata.SavedData::isDirty),
                    "complete baseline reads remain read-only");
                var values = storage.get(TARGET).copy();
                require(values.getIntOr("changed", -1) == 0 && values.getIntOr("removed", -1) == 1,
                    "fresh reader restores the complete repaired values");
                values.putInt("changed", 1);
                values.remove("removed");
                storage.set(TARGET, values);
                var delta = changes.capture(server, source);
                require(storageChange(delta, "\"changed\"", "0", "1", WatchResult.Status.VALUE), "recovery retains 0→1");
                require(storageChange(delta, "\"removed\"", "1", "", WatchResult.Status.VALUE_MISSING), "recovery retains 1→missing");
                require(delta.stream().filter(change -> change.spec().kind() == WatchSpec.Kind.STORAGE_NBT).count() == 2,
                    "healthy/missing/empty unchanged values do not become false new targets");
                require(changes.capture(server, source).isEmpty(), "unchanged recovery stop clears deltas");
                System.out.println("Storage recovery " + mode + ": fresh saved-world reopen retains 0→1 and 1→missing");
            });
        }
    }

    private static CompoundTag values() {
        var values = new CompoundTag();
        values.putInt("changed", 0); values.putInt("removed", 1); values.putInt("unchanged", 7);
        return values;
    }

    private static CompoundTag targetContents(CompoundTag values) {
        var contents = new CompoundTag(); contents.put("acceptance", values); return contents;
    }

    private static void write(Path data, String namespace, CompoundTag contents) throws Exception {
        var payload = new CompoundTag(); payload.put("contents", contents);
        var root = new CompoundTag(); root.put("data", payload);
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        Path path = data.resolve(namespace + "/command_storage.dat");
        Files.createDirectories(path.getParent()); NbtIo.writeCompressed(root, path);
    }

    private static boolean baseline(PauseWatchChanges changes) {
        try {
            var field = PauseWatchChanges.class.getDeclaredField("storageBaseline");
            field.setAccessible(true); return field.getBoolean(changes);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static boolean storageChange(List<WatchChange> changes, String path, String before,
                                         String after, WatchResult.Status status) {
        return changes.stream().anyMatch(change -> change.spec().kind() == WatchSpec.Kind.STORAGE_NBT
            && change.spec().target().equals(TARGET.toString()) && change.spec().path().equals(path)
            && change.before().value().equals(before) && change.after().value().equals(after)
            && change.after().status() == status);
    }

    private static PauseSnapshot pause(UUID id, String name) {
        return new PauseSnapshot(new SourceLocation.Block(new BlockLocation(0,80,0,"minecraft:overworld")),
            CommandSnippet.plain("say storage-read-failure"),0,List.of(),
            List.of(new PauseSource(new Vec3d(0,80,0),0,0,new EntityRef(id,name),"minecraft:overworld")),
            PauseReason.BREAKPOINT,1);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
