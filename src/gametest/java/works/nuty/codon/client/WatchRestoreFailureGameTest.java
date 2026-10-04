package works.nuty.codon.client;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import works.nuty.codon.client.state.ClientWatchState;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.network.WatchSavePayload;
import works.nuty.codon.network.WatchSaveSyncPayload;
import works.nuty.codon.network.WatchSaveV2Payload;
import works.nuty.codon.persistence.WatchDefinitions;

/** Real JOIN/promotion restore failures and wire saves; a synthetic pause only presents the warning. */
@SuppressWarnings("UnstableApiUsage")
public final class WatchRestoreFailureGameTest implements FabricClientGameTest {
    private static final WatchSpec LOCAL = new WatchSpec(WatchSpec.Kind.SCORE, "local_before_restore", "");
    private static final WatchSpec REPAIRED = new WatchSpec(WatchSpec.Kind.SCORE, "repaired_saved", "");

    @Override public void runTest(ClientGameTestContext context) {
        check(context, true, IntStream.range(0, 8193)
            .mapToObj(i -> new WatchSpec(WatchSpec.Kind.SCORE, "saved" + i, "")).toList());
        check(context, false, IntStream.range(0, 6000)
            .mapToObj(i -> new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:" + "x".repeat(120),
                "\\".repeat(120) + i)).toList());
        System.out.println("Watch restore native PASS: over-count JOIN and serialized-text promotion fail explicitly; "
            + "local edits and disk bytes survive legacy/v2 partial writes; closed-world repair/reopen restores saving");
    }

    private static void check(ClientGameTestContext context, boolean ownerOnJoin, List<WatchSpec> saved) {
        TestWorldSave save;
        try (var world = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(ownerOnJoin)).create()) {
            world.getConnection().waitForChunksRender();
            save = world.getWorldSave();
        }
        Path file = save.getSaveDirectory().resolve("data/codon-watches/singleplayer.json");
        write(file, saved);
        byte[] before = bytes(file);
        try (var world = save.open()) {
            world.getConnection().waitForChunksRender();
            context.runOnClient(client -> require(CodonClientMod.state().watches().add(LOCAL), "session-local edit accepted"));
            if (!ownerOnJoin) {
                context.runOnClient(client -> require(!CodonClientMod.state().watches().initialRestoreFailed(),
                    "private persisted data is not restored before promotion"));
                world.getServer().runOnServer(server -> {
                    var player = server.getPlayerList().getPlayers().getFirst();
                    server.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
                });
            }
            context.waitFor(client -> CodonClientMod.state().watches().initialRestoreFailed(), 200);
            context.runOnClient(client -> {
                var watches = CodonClientMod.state().watches();
                require(!watches.initialDefinitionsReceived(), "failure is not an empty successful restore");
                require(watches.definitions().equals(List.of(LOCAL)), "local rows survive failed remote restore");
                watches.retrySave();
                require(watches.saveStatus() == ClientWatchState.SaveStatus.RESTORE_FAILED, "partial retry is blocked");
            });
            List<WatchSaveSyncPayload> replies = new CopyOnWriteArrayList<>();
            var normal = context.computeOnClient(client -> removeSaveReceiver());
            require(normal != null, "production save receiver registered");
            context.runOnClient(client -> require(ClientPlayNetworking.registerReceiver(WatchSaveSyncPayload.TYPE, (payload, receiver) -> {
                replies.add(payload);
                normal.receive(payload, receiver);
            }), "observe actual save results"));
            try {
                context.runOnClient(client -> {
                    ClientPlayNetworking.send(new WatchSavePayload(9_000_001, 0, true, List.of(LOCAL)));
                    ClientPlayNetworking.send(new WatchSaveV2Payload(9_000_002, 0, true, List.of(LOCAL)));
                });
                context.waitFor(client -> replies.size() == 2, 200);
                require(replies.stream().allMatch(reply -> reply.status() == WatchSaveSyncPayload.Status.FAILED),
                    "both registered save routes refuse to erase an unseen oversized snapshot");
                require(Arrays.equals(before, bytes(file)), "disk bytes unchanged after both attempted partial uploads");
                if (!ownerOnJoin) captureWarning(context);
            } finally {
                context.runOnClient(client -> {
                    ClientPlayNetworking.unregisterReceiver(WatchSaveSyncPayload.TYPE.id());
                    require(ClientPlayNetworking.registerReceiver(WatchSaveSyncPayload.TYPE, normal), "restore save receiver");
                    client.setScreenAndShow(null);
                    CodonClientMod.state().applyResume();
                });
            }
        }
        require(Arrays.equals(before, bytes(file)), "world close cannot overwrite the saved list");
        write(file, List.of(REPAIRED)); // Explicit repair only while this disposable world is closed.
        try (var world = save.open()) {
            world.getConnection().waitForChunksRender();
            if (!ownerOnJoin) world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                server.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
            });
            context.waitFor(client -> CodonClientMod.state().watches().initialDefinitionsReceived(), 200);
            context.runOnClient(client -> {
                var watches = CodonClientMod.state().watches();
                require(!watches.initialRestoreFailed() && watches.definitions().equals(List.of(REPAIRED)),
                    "reopen releases failure and restores the complete repaired snapshot");
                require(watches.add(LOCAL), "owner editing resumes after a bounded restore");
            });
            context.waitFor(client -> CodonClientMod.state().watches().saveStatus() == ClientWatchState.SaveStatus.SAVED, 200);
            require(WatchDefinitions.fromJson(com.google.gson.JsonParser.parseString(new String(bytes(file), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject().get("watches").toString()).equals(List.of(REPAIRED, LOCAL)), "repaired union saves durably");
        }
    }

    @SuppressWarnings("unchecked")
    private static void captureWarning(ClientGameTestContext context) {
        int oldScale = context.computeOnClient(client -> client.options.guiScale().get());
        context.getInput().resizeWindow(1280, 800);
        var screen = context.computeOnClient(client -> {
            client.options.guiScale().set(2);
            client.resizeGui();
            var state = CodonClientMod.state();
            state.applyPause(DebuggerPresentationGameTest.fixture(client));
            var overlay = new DebuggerOverlay(state);
            var result = new CodonScreen(DebuggerPresentationGameTest.input(client, state), overlay);
            client.setScreenAndShow(result);
            return result;
        });
        context.waitTicks(3);
        var warning = context.computeOnClient(client -> {
            var overlay = (DebuggerOverlay) FunctionLineBreakpointGameTest.field(screen, "overlay");
            var buttons = (Map<String, DebuggerButton>) FunctionLineBreakpointGameTest.field(overlay.watchPanel(), "buttons");
            var button = buttons.get("watch-restore-failed");
            require(button != null && button.visible && !button.active, "visible warning cannot upload a partial snapshot");
            return button;
        });
        double[] pointer = context.computeOnClient(client -> new double[]{
            screen.uiScale().toGame(warning.getX() + warning.getWidth() / 2.0)
                * client.getWindow().getScreenWidth() / client.getWindow().getGuiScaledWidth(),
            screen.uiScale().toGame(warning.getY() + warning.getHeight() / 2.0)
                * client.getWindow().getScreenHeight() / client.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(pointer[0], pointer[1]);
        DebuggerTooltipGameTest.beginObservation();
        long hoverUntil = System.nanoTime() + 2_000_000_000L;
        String tooltip;
        do {
            context.waitTicks(5);
            tooltip = DebuggerTooltipGameTest.endObservation();
            if (!tooltip.isEmpty()) break;
            DebuggerTooltipGameTest.beginObservation();
        } while (System.nanoTime() < hoverUntil);
        DebuggerTooltipGameTest.endObservation();
        context.takeScreenshot("codon-watch-restore-failed");
        require(tooltip.replace('\n', ' ').contains("Saved watches are too large to restore."),
            "native warning tooltip explains the restore failure: " + tooltip + " / "
                + context.computeOnClient(client -> "hovered=" + warning.isHovered() + ", screen="
                    + (client.gui.screen() == screen) + ", mouse=" + client.mouseHandler.xpos() + "," + client.mouseHandler.ypos()
                    + ", target=" + pointer[0] + "," + pointer[1] + ", hoverAgeMs="
                    + (System.nanoTime() - (long) FunctionLineBreakpointGameTest.field(warning, "hoverStartedAt")) / 1_000_000
                    + ", label=" + warning.getMessage().getString()));
        context.runOnClient(client -> {
            client.options.guiScale().set(oldScale);
            client.resizeGui();
        });
    }

    @SuppressWarnings("unchecked")
    private static ClientPlayNetworking.PlayPayloadHandler<WatchSaveSyncPayload> removeSaveReceiver() {
        return (ClientPlayNetworking.PlayPayloadHandler<WatchSaveSyncPayload>)
            ClientPlayNetworking.unregisterReceiver(WatchSaveSyncPayload.TYPE.id());
    }
    private static void write(Path file, List<WatchSpec> definitions) {
        try {
            Files.createDirectories(file.getParent());
            var json = new JsonObject();
            json.addProperty("version", 2);
            json.add("watches", WatchDefinitions.encode(definitions));
            Files.writeString(file, json.toString());
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
    private static byte[] bytes(Path file) {
        try { return Files.readAllBytes(file); }
        catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
