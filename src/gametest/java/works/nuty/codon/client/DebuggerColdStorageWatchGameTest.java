package works.nuty.codon.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.LevelResource;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientWatchState;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.mixin.CommandStorageAccessor;
import works.nuty.codon.persistence.WatchDefinitions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** A saved world is closed and reopened before real function breakpoints and Continue. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerColdStorageWatchGameTest implements FabricClientGameTest {
    private static final FunctionId FUNCTION = new FunctionId("codon_test", "cold_storage_update");
    private static final WatchSpec CHANGED = storage("codon_cold:acceptance", "changed");
    private static final WatchSpec REMOVED = storage("codon_cold:acceptance", "removed");
    private static final WatchSpec UNCHANGED = storage("codon_cold:acceptance", "unchanged");
    private static final WatchSpec MISSING = storage("codon_cold:acceptance", "absent");
    private static final WatchSpec OTHER = storage("codon_cold_other:baseline", "steady");
    private static final WatchSpec ABSENT_TARGET = storage("codon_cold_absent:missing", "value");

    @Override public void runTest(ClientGameTestContext context) {
        TestWorldSave save;
        Fixture fixture;
        try (var prepared = context.worldBuilder().create()) {
            prepared.getConnection().waitForChunksRender();
            prepared.getServer().runCommand("scoreboard objectives add cold_storage_points dummy");
            prepared.getServer().runCommand("data merge storage codon_cold:acceptance {changed:0,removed:1,unchanged:7}");
            prepared.getServer().runCommand("data merge storage codon_cold_other:baseline {steady:9}");
            fixture = prepared.getServer().computeOnServer(server -> {
                grantOwner(server);
                var player = server.getPlayerList().getPlayers().getFirst();
                var stand = new ArmorStand(player.level(), player.getX() + 2, player.getY(), player.getZ());
                stand.addTag("codon_cold_watch");
                stand.setNoGravity(true);
                stand.setCustomName(Component.literal("Cold Storage"));
                stand.setHealth(10);
                require(player.level().addFreshEntity(stand), "persist the fixture entity");
                server.getScoreboard().getOrCreatePlayerScore(stand,
                    server.getScoreboard().getObjective("cold_storage_points")).set(1);
                server.getCommandStorage().set(Identifier.parse("codon_cold_empty:none"), new net.minecraft.nbt.CompoundTag());
                var health = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health", stand.getUUID());
                var score = new WatchSpec(WatchSpec.Kind.SCORE, "cold_storage_points", "", stand.getUUID());
                return new Fixture(server, server.getCommandStorage(), stand.getUUID(), health, score,
                    List.of(CHANGED, REMOVED, UNCHANGED, MISSING, OTHER, ABSENT_TARGET, health, score),
                    server.getWorldPath(LevelResource.DATA));
            });
            save = prepared.getWorldSave();
            context.runOnClient(client -> require(CodonClientMod.state().watches().addAll(fixture.watches()),
                "persist watches before restart without taking a debugger snapshot"));
            context.waitFor(client -> saved(save, fixture.watches()), 200);
        }

        Path primaryFile = fixture.data().resolve("codon_cold/command_storage.dat");
        byte[] persisted;
        try { persisted = Files.readAllBytes(primaryFile); }
        catch (java.io.IOException failure) { throw new AssertionError("World close must persist Storage", failure); }

        try (var reopened = save.open()) {
            reopened.getConnection().waitForChunksRender();
            context.waitFor(client -> CodonClientMod.state().watches().definitions().equals(fixture.watches()), 200);
            reopened.getServer().waitFor(server -> server.overworld().getEntity(fixture.entity()) != null, 200);
            MinecraftServer active = reopened.getServer().computeOnServer(server -> {
                grantOwner(server);
                require(server != fixture.server() && server.getCommandStorage() != fixture.storage(),
                    "cold reopen creates a different server and CommandStorage");
                var loaded = ((CommandStorageAccessor) server.getCommandStorage()).codon$loadedNamespaces();
                require(loaded.keySet().stream().noneMatch(name -> name.startsWith("codon_cold")),
                    "saved watched and empty namespaces remain unloaded at normal startup");
                require(server.getCommandStorage().keys().noneMatch(id -> id.getNamespace().startsWith("codon_cold")),
                    "cold keys enumeration omits the fixture; the test must not warm it");
                CodonMod.engine().clearBreakpoints();
                for (int line : new int[]{2, 7, 9, 10}) {
                    CodonMod.engine().toggleFunctionBreakpoint(new FunctionLocation(FUNCTION, line));
                }
                System.out.println("Cold Storage restart: persisted namespaces are unloaded before execution");
                return server;
            });
            AtomicBoolean emptyLoadedAtFirstStop = new AtomicBoolean();
            try {
                context.runOnClient(client -> client.player.connection.sendCommand(
                    "execute as @e[tag=codon_cold_watch,limit=1] at @s run function codon_test:cold_storage_update"));
                awaitStop(context, 2);
                context.runOnClient(client -> {
                    value(CHANGED, "0"); value(REMOVED, "1"); value(UNCHANGED, "7"); value(OTHER, "9");
                    value(fixture.health(), "10.0f"); value(fixture.score(), "1");
                    require(row(MISSING).displayedResult().status() == WatchResult.Status.VALUE_MISSING, "initially missing leaf");
                    require(row(ABSENT_TARGET).displayedResult().status() == WatchResult.Status.TARGET_MISSING, "initially absent target");
                });
                parked(context, active, server -> {
                    var storage = server.getCommandStorage();
                    var loaded = ((CommandStorageAccessor) storage).codon$loadedNamespaces();
                    emptyLoadedAtFirstStop.set(loaded.containsKey("codon_cold_empty"));
                    require(!loaded.containsKey("codon_cold_absent") && !loaded.containsKey("codon_cold_new"),
                        "snapshot and missing watches do not create namespaces");
                    require(loaded.values().stream().noneMatch(net.minecraft.world.level.saveddata.SavedData::isDirty),
                        "snapshot reads do not dirty saved Storage containers");
                    require(storage.keys().noneMatch(id -> id.getPath().equals("__codon_snapshot_probe__")),
                        "snapshot discovery does not add probe keys");
                    try { require(Arrays.equals(persisted, Files.readAllBytes(primaryFile)), "baseline reads preserve persisted bytes"); }
                    catch (java.io.IOException failure) { throw new AssertionError(failure); }
                });
                screenshot(context, "codon-cold-storage-before");
                resume(context);
                awaitStop(context, 7);
                context.runOnClient(client -> {
                    delta(fixture.health(), "10.0f", "9.0f", ClientWatchState.Change.VALUE_CHANGED);
                    delta(fixture.score(), "1", "2", ClientWatchState.Change.VALUE_CHANGED);
                    delta(CHANGED, "0", "1", ClientWatchState.Change.VALUE_CHANGED);
                    var removed = row(REMOVED);
                    require(removed.displayedResult().status() == WatchResult.Status.VALUE_MISSING
                        && removed.displayedChange() == ClientWatchState.Change.VALUE_DISAPPEARED
                        && removed.displayedPreviousValue().equals("1"), "cold Continue retains removed 1→missing");
                    neutral(UNCHANGED); neutral(MISSING); neutral(OTHER); neutral(ABSENT_TARGET);
                    require(CodonClientMod.state().watches().displayedEntries().stream()
                        .noneMatch(entry -> entry.automatic() && entry.spec().kind() == WatchSpec.Kind.STORAGE_NBT),
                        "cold baseline does not turn preexisting unchanged leaves into automatic rows");
                    require(emptyLoadedAtFirstStop.get(), "initial snapshot also loaded the persisted empty container");
                });
                screenshot(context, "codon-cold-storage-changed-removed");
                resume(context);
                awaitStop(context, 9);
                context.runOnClient(client -> {
                    var created = CodonClientMod.state().watches().displayedEntries().stream()
                        .filter(entry -> entry.automatic() && entry.spec().target().equals("codon_cold_new:target"))
                        .findFirst().orElseThrow(() -> new AssertionError("genuine new Storage target remains a change"));
                    require(created.displayedResult().value().equals("2")
                        && created.displayedChange() == ClientWatchState.Change.AVAILABILITY_CHANGED,
                        "new target creation is distinct from a cold persisted target");
                    neutral(CHANGED); neutral(REMOVED); neutral(fixture.health()); neutral(fixture.score());
                });
                screenshot(context, "codon-cold-storage-new-target");
                resume(context);
                awaitStop(context, 10);
                context.runOnClient(client -> require(CodonClientMod.state().watches().displayedEntries().stream()
                    .noneMatch(ClientWatchState.Entry::automatic), "unchanged next stop clears temporary changes"));
                resume(context);
                context.waitFor(client -> !CodonClientMod.state().isPaused(), 200);
            } finally {
                parked(context, active, server -> {
                    CodonMod.engine().clearBreakpoints();
                    CodonMod.engine().resetSession();
                });
                context.runOnClient(client -> client.setScreenAndShow(null));
            }
        }
    }

    private static void grantOwner(MinecraftServer server) {
        var player = server.getPlayerList().getPlayers().getFirst();
        server.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
    }

    private static boolean saved(TestWorldSave save, List<WatchSpec> expected) {
        try {
            var json = com.google.gson.JsonParser.parseString(Files.readString(
                save.getSaveDirectory().resolve("data/codon-watches/singleplayer.json"))).getAsJsonObject();
            return WatchDefinitions.decode(json.get("watches")).equals(expected);
        } catch (java.io.IOException | RuntimeException ignored) { return false; }
    }

    private static void awaitStop(ClientGameTestContext context, int line) {
        context.waitFor(client -> CodonClientMod.state().isPaused()
            && CodonClientMod.state().snapshot().location().equals(new SourceLocation.Function(new FunctionLocation(FUNCTION, line)))
            && CodonClientMod.state().watches().entries().stream().allMatch(entry -> entry.result() != null), 200);
    }

    private static void resume(ClientGameTestContext context) {
        context.runOnClient(client -> client.player.connection.sendCommand("codon resume"));
    }

    private static ClientWatchState.Entry row(WatchSpec spec) {
        return CodonClientMod.state().watches().displayedEntries().stream().filter(entry -> entry.spec().equals(spec))
            .findFirst().orElseThrow(() -> new AssertionError("Missing saved Watch " + spec));
    }
    private static void value(WatchSpec spec, String value) {
        require(row(spec).displayedResult().value().equals(value), "first stop reads " + spec + " = " + value);
    }
    private static void delta(WatchSpec spec, String before, String after, ClientWatchState.Change change) {
        var row = row(spec);
        require(row.displayedPreviousValue().equals(before) && row.displayedResult().value().equals(after)
            && row.displayedChange() == change, "cold Continue retains " + spec + " " + before + "→" + after
                + "; actual=" + row.displayedPreviousValue() + "→" + row.displayedResult() + " " + row.displayedChange());
    }
    private static void neutral(WatchSpec spec) {
        var row = row(spec);
        require(!row.displayedChange().isValueChange() && row.displayedChange() != ClientWatchState.Change.AVAILABILITY_CHANGED
            && row.displayedPreviousValue().isEmpty(), "unchanged/missing value stays neutral: " + spec);
    }

    private static void parked(ClientGameTestContext context, MinecraftServer server, Consumer<MinecraftServer> check) {
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        DebuggerTaskQueue.execute(server, () -> {
            try { check.accept(server); } catch (Throwable thrown) { failure.set(thrown); }
            finally { done.set(true); }
        });
        context.waitFor(client -> done.get(), 200);
        if (failure.get() != null) throw new AssertionError("Parked server assertion failed", failure.get());
    }

    private static void screenshot(ClientGameTestContext context, String name) {
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(client -> {
            client.options.guiScale().set(2);
            client.resizeGui();
            var state = CodonClientMod.state();
            var input = new InputManager(state, ignored -> { });
            for (var key : client.options.keyMappings) {
                switch (key.getName()) {
                    case "key.codon.keep_freecam" -> input.keepFreecamKey = key;
                    case "key.codon.open_menu" -> input.menuKey = key;
                    case "key.codon.breakpoint" -> input.breakpointKey = key;
                    case "key.codon.resume" -> input.resumeKey = key;
                    case "key.codon.step_over" -> input.stepOverKey = key;
                    case "key.codon.step_into" -> input.stepIntoKey = key;
                }
            }
            client.setScreenAndShow(new CodonScreen(input, new DebuggerOverlay(state)));
        });
        context.waitTicks(3);
        context.takeScreenshot(name);
    }

    private static WatchSpec storage(String target, String path) { return new WatchSpec(WatchSpec.Kind.STORAGE_NBT, target, path); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private record Fixture(MinecraftServer server, CommandStorage storage, UUID entity, WatchSpec health, WatchSpec score,
                           List<WatchSpec> watches, Path data) { }
}
