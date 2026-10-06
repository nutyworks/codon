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
        double oldChatScale = context.computeOnClient(client -> client.options.chatScale().get());
        double oldChatSpacing = context.computeOnClient(client -> client.options.chatLineSpacing().get());
        double oldChatHeight = context.computeOnClient(client -> client.options.chatHeightUnfocused().get());
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1280, 720);
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.options.chatScale().set(1.0);
                client.options.chatLineSpacing().set(0.0);
                client.options.chatHeightUnfocused().set(1.0);
                client.resizeGui();
            });
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
                int[][] chatCases = language.equals("en_us")
                    ? new int[][]{{854, 480, 5}, {854, 480, 10}, {1280, 598, 3},
                        {1280, 600, 3}, {1024, 576, 0}}
                    : new int[][]{{854, 480, 10}};
                for (int[] chatCase : chatCases) {
                    context.getInput().resizeWindow(chatCase[0], chatCase[1]);
                    context.runOnClient(client -> {
                        state.preferences().setBackgroundOpacity(100);
                        state.preferences().setInspectorVisible(chatCase[0] == 1024);
                        state.applyPause(DebuggerPresentationGameTest.fixture(client));
                        client.gui.hud.getChat().clearMessages(false);
                        for (int row = 1; row <= chatCase[2]; row++)
                            client.gui.hud.getChat().addClientSystemMessage(Component.literal("Chat " + row));
                        client.setScreenAndShow(new CodonScreen(input, overlay));
                    });
                    context.waitTicks(3);
                    context.runOnClient(client -> {
                        var screen = (CodonScreen) client.gui.screen();
                        int inset = DebuggerHudInsets.bottom(state.preferences());
                        require(inset >= Math.max(60, 44 + 9 * chatCase[2]),
                            "All requested recent chat rows are reserved by the production inset");
                        var buttons = screen.children().stream().filter(DebuggerButton.class::isInstance)
                            .map(DebuggerButton.class::cast).filter(button -> button.visible).toList();
                        var command = buttons.stream()
                            .filter(button -> button.getMessage().getString().contains("run function"))
                            .findFirst().orElseThrow(() -> new AssertionError("Recent chat retains the selected clause"));
                        require(command.getHeight() == 16 && command.getBottom() <= screen.height - inset,
                            "The selected clause keeps its row above chat");
                        var marker = buttons.stream().filter(button -> button.getMessage().getString()
                            .equals(Component.translatable("codon.breakpoint.toggle").getString()))
                            .findFirst().orElseThrow(() -> new AssertionError("Recent chat retains breakpoint markers"));
                        require(marker.getWidth() == 18 && marker.getHeight() == 18
                            && marker.getBottom() <= screen.height - inset, "Breakpoint targets retain their hit area");
                        require(buttons.stream().anyMatch(button -> button.getMessage().getString().contains("demo:spawn_wave")),
                            "Compressed Command keeps the selected call path");
                        require(buttons.stream().anyMatch(button -> button.getMessage().getString()
                            .equals(Component.translatable("codon.ui.expand_command").getString())),
                            "Compressed Command keeps its actions");
                        if (chatCase[0] == 1024) {
                            var header = buttons.stream().filter(button -> button.getMessage().getString()
                                .equals(Component.translatable("codon.nbt.current_pause").getString()))
                                .findFirst().orElseThrow(() -> new AssertionError("Short inspector retains its NBT heading"));
                            var inspector = DebuggerLayout.create(screen.width, screen.height, true, 104, 230, inset).inspector();
                            require(header.getBottom() <= inspector.y() + inspector.height(),
                                "The NBT heading is contained in its short inspector viewport");
                        }
                    });
                    context.takeScreenshot("codon-readable-chat-" + language + "-" + chatCase[0]
                        + "x" + chatCase[1] + "-rows-" + chatCase[2]);
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
                client.options.chatScale().set(oldChatScale);
                client.options.chatLineSpacing().set(oldChatSpacing);
                client.options.chatHeightUnfocused().set(oldChatHeight);
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
