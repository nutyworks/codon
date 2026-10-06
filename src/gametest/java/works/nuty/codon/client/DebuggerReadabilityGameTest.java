package works.nuty.codon.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.gui.screens.ChatScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import works.nuty.codon.client.render.DebugHudElement;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.ui.*;
import works.nuty.codon.client.ui.layout.DebuggerLayout;

/** Native readability matrix with client snapshots; server execution is covered separately. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerReadabilityGameTest implements FabricClientGameTest {
    private static final Identifier HUD = Identifier.fromNamespaceAndPath("codon", "readability_test");

    @Override public void runTest(ClientGameTestContext context) {
        int oldScale = context.computeOnClient(client -> client.options.guiScale().get());
        String oldLanguage = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        boolean oldDebug = context.computeOnClient(client -> client.debugEntries.isOverlayVisible());
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1280, 720);
            context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); });
            world.getConnection().waitForChunksRender();
            world.getServer().runCommand("gamemode survival");
            var state = new ClientDebuggerState();
            var input = context.computeOnClient(client -> DebuggerPresentationGameTest.input(client, state));
            var overlay = context.computeOnClient(client -> {
                DebuggerTheme.usePreferences(state.preferences());
                var result = new DebuggerOverlay(state);
                HudElementRegistry.addLast(HUD, new DebugHudElement(result, input));
                return result;
            });
            for (String language : new String[]{"en_us", "ko_kr"}) {
                language(context, language);
                for (int[] size : new int[][]{{1280, 720}, {854, 480}}) {
                    context.getInput().resizeWindow(size[0], size[1]);
                    for (int opacity : new int[]{100, 0}) {
                        String name = language + "-" + size[0] + "-" + opacity;
                        context.getInput().lookAt(0, -55);
                        context.runOnClient(client -> {
                            client.debugEntries.setOverlayVisible(false);
                            state.preferences().setBackgroundOpacity(opacity);
                            state.preferences().setInspectorWidth(230);
                            state.preferences().setWatchWidth(280);
                            state.applyPause(DebuggerPresentationGameTest.fixture(client));
                            client.gui.hud.getChat().clearMessages(false);
                            for (int i = 1; i <= 4; i++) client.gui.hud.getChat().addClientSystemMessage(
                                Component.literal((language.equals("ko_kr") ? "최근 서버 메시지 " : "Recent server feedback ") + i));
                            client.setScreenAndShow(new CodonScreen(input, overlay));
                        });
                        context.waitTicks(3);
                        context.runOnClient(client -> {
                            var screen = (CodonScreen) client.gui.screen();
                            int inset = DebuggerHudInsets.bottom(state.preferences());
                            var layout = DebuggerLayout.create(screen.width, screen.height, true, 112, 230, inset);
                            require(layout.command().y() + layout.command().height() <= screen.height - inset,
                                "Command remains above recent chat and health/hotbar");
                            require(inset >= 80, "Four actual wrapped chat rows reserve their vertical area");
                            require(state.preferences().inspectorWidth() == 230 && state.preferences().watchWidth() == 280,
                                "Fitting the workspace does not rewrite saved widths");
                            require(state.preferences().backgroundOpacity() == opacity, "User opacity intent is preserved");
                            var command = screen.children().stream().filter(DebuggerButton.class::isInstance)
                                .map(DebuggerButton.class::cast)
                                .filter(button -> button.getMessage().getString().contains("run function"))
                                .findFirst().orElseThrow(() -> new AssertionError("Selected command remains visible"));
                            require(command.getBottom() <= screen.height - inset,
                                "Actual rendered command control clears the vanilla HUD/chat");
                        });
                        context.takeScreenshot("codon-readable-command-" + name);
                        context.runOnClient(client -> client.setScreenAndShow(new WatchScreen(input, state, overlay)));
                        context.waitTicks(3);
                        context.takeScreenshot("codon-readable-modal-sky-" + name);
                        context.getInput().lookAt(0, 65);
                        context.waitTicks(3);
                        context.takeScreenshot("codon-readable-modal-ground-" + name);
                    }
                }
                context.runOnClient(client -> {
                    state.applyResume();
                    client.setScreenAndShow(null);
                    client.gui.hud.getChat().clearMessages(false);
                });
                context.waitTicks(3);
                context.takeScreenshot("codon-readable-idle-" + language);
                context.runOnClient(client -> client.debugEntries.setOverlayVisible(true));
                context.waitTicks(3);
                context.takeScreenshot("codon-readable-f3-idle-" + language);
                context.runOnClient(client -> state.applyPause(DebuggerPresentationGameTest.fixture(client)));
                context.waitTicks(3);
                context.takeScreenshot("codon-readable-f3-paused-" + language);
                context.runOnClient(client -> {
                    require(state.isPaused(), "F3 does not change the acknowledged pause");
                    client.debugEntries.setOverlayVisible(false);
                    client.setScreenAndShow(new ChatScreen("readable chat input", false));
                });
                context.waitTicks(3);
                context.takeScreenshot("codon-readable-open-chat-" + language);
            }
        } finally {
            context.runOnClient(client -> {
                client.setScreenAndShow(null);
                HudElementRegistry.removeElement(HUD);
                client.debugEntries.setOverlayVisible(oldDebug);
                client.options.guiScale().set(oldScale);
                client.resizeGui();
                DebuggerTheme.usePreferences(CodonClientMod.state().preferences());
            });
            language(context, oldLanguage);
        }
    }

    private static void language(ClientGameTestContext context, String language) {
        if (context.computeOnClient(client -> client.getLanguageManager().getSelected()).equals(language)) return;
        var reload = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected(language);
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 200);
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
