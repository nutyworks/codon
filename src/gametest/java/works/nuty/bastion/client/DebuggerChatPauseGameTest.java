package works.nuty.bastion.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2f;
import works.nuty.bastion.client.state.ClientDebuggerState;
import works.nuty.bastion.client.testmixin.ChatComponentInvoker;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.CommandSnippet;
import works.nuty.bastion.core.model.PauseReason;
import works.nuty.bastion.core.model.PauseSnapshot;
import works.nuty.bastion.core.model.SourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Regression coverage for the HUD pause boundary. This uses a synthetic client debugger-state
 * pause fixture; it does not claim that a server breakpoint produced that pause.
 */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerChatPauseGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            Fixture fixture = context.computeOnClient(DebuggerChatPauseGameTest::prepare);
            try {
                // Let this message enter vanilla's final fade window before the debugger pauses.
                float freshOpacity = context.computeOnClient(client -> renderedChat(client, fixture.chat()).singleOpacity());
                context.waitTicks(181);
                RenderedChat beforePause = context.computeOnClient(client -> renderedChat(client, fixture.chat()));
                require(beforePause.singleOpacity() > 0 && beforePause.singleOpacity() < freshOpacity,
                    "an old background-chat line is visibly fading before the fixture pause");
                int guiTicksAtPause = context.computeOnClient(client -> client.gui.hud.getGuiTicks());

                context.runOnClient(client -> fixture.state().applyPause(pauseFixture(fixture.player())));
                context.waitTicks(201);
                context.runOnClient(client -> {
                    require(client.gui.hud.getGuiTicks() == guiTicksAtPause,
                        "debugger pause freezes vanilla HUD time for more than a chat lifetime");
                    RenderedChat paused = renderedChat(client, fixture.chat());
                    require(close(paused.singleOpacity(), beforePause.singleOpacity()),
                        "the already-fading chat line keeps its exact rendered opacity throughout pause");

                    fixture.chat().rescaleChat();
                    RenderedChat rescaled = renderedChat(client, fixture.chat());
                    require(close(rescaled.singleOpacity(), beforePause.singleOpacity()),
                        "chat rescaling during pause must not discard or age the preserved line");

                    // A duplicate pause packet must not restart or advance the frozen HUD clock.
                    fixture.state().applyPause(pauseFixture(fixture.player()));
                    require(client.gui.hud.getGuiTicks() == guiTicksAtPause,
                        "repeated debugger pause leaves the HUD clock frozen");
                    require(close(renderedChat(client, fixture.chat()).singleOpacity(), beforePause.singleOpacity()),
                        "repeated debugger pause leaves the visible chat line unchanged");
                    fixture.state().applyResume();
                });

                // Resume consumes only the nineteen ticks left before vanilla expiration.
                context.waitTicks(5);
                context.runOnClient(client -> require(renderedChat(client, fixture.chat()).singleOpacity() > 0,
                    "resuming consumes remaining chat lifetime instead of expiring it immediately"));
                context.waitTicks(15);
                context.runOnClient(client -> require(renderedChat(client, fixture.chat()).lineCount() == 0,
                    "resume restores normal chat aging after the remaining vanilla lifetime"));

                context.runOnClient(client -> {
                    fixture.state().applyPause(pauseFixture(fixture.player()));
                    fixture.chat().addClientSystemMessage(Component.literal("message received during pause"));
                });
                context.waitTicks(201);
                context.runOnClient(client -> {
                    require(close(renderedChat(client, fixture.chat()).singleOpacity(), freshOpacity),
                        "messages received during pause keep their full remaining lifetime");
                    fixture.state().reset();
                });
                context.waitTicks(201);
                context.runOnClient(client -> require(renderedChat(client, fixture.chat()).lineCount() == 0,
                    "reset also restores normal chat aging"));
            } finally {
                context.runOnClient(client -> {
                    fixture.state().reset();
                    fixture.chat().clearMessages(false);
                });
            }
        }
    }

    private static Fixture prepare(Minecraft client) {
        ClientDebuggerState state = require(BastionClientMod.state(), "client debugger state is initialized");
        LocalPlayer player = require(client.player, "local player is available");
        ChatComponent chat = client.gui.hud.getChat();
        state.reset();
        chat.clearMessages(false);
        chat.addClientSystemMessage(Component.literal("bastion pause chat fixture"));
        return new Fixture(state, player, chat);
    }

    private static PauseSnapshot pauseFixture(LocalPlayer player) {
        BlockLocation block = new BlockLocation(player.getBlockX(), player.getBlockY(), player.getBlockZ(),
            player.level().dimension().identifier().toString());
        return new PauseSnapshot(new SourceLocation.Block(block), CommandSnippet.plain("say chat fixture"), 0,
            List.of(), List.of(), PauseReason.BREAKPOINT);
    }

    private static RenderedChat renderedChat(Minecraft client, ChatComponent chat) {
        OpacityCapture capture = new OpacityCapture();
        ((ChatComponentInvoker) chat).bastion$extractRenderState(capture, client.getWindow().getGuiScaledHeight(),
            client.gui.hud.getGuiTicks(), ChatComponent.DisplayMode.BACKGROUND);
        return new RenderedChat(capture.opacities);
    }

    private static boolean close(float actual, float expected) {
        return Math.abs(actual - expected) < 0.0001F;
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static <T> T require(T value, String description) {
        if (value == null) throw new AssertionError(description);
        return value;
    }

    private record Fixture(ClientDebuggerState state, LocalPlayer player, ChatComponent chat) { }

    private record RenderedChat(List<Float> opacities) {
        int lineCount() { return opacities.size(); }

        float singleOpacity() {
            require(opacities.size() == 1, "expected exactly one rendered chat line, got " + opacities.size());
            return opacities.getFirst();
        }
    }

    private static final class OpacityCapture implements ChatComponent.ChatGraphicsAccess {
        private final List<Float> opacities = new ArrayList<>();

        @Override public void updatePose(Consumer<Matrix3x2f> updater) { }
        @Override public void fill(int x0, int y0, int x1, int y1, int color) { }
        @Override public boolean handleMessage(int textTop, float opacity, FormattedCharSequence message) {
            opacities.add(opacity);
            return false;
        }
        @Override public void handleTag(int x0, int y0, int x1, int y1, float opacity, GuiMessageTag tag) { }
        @Override public void handleTagIcon(int left, int bottom, boolean forceVisible, GuiMessageTag tag,
                                            GuiMessageTag.Icon icon) { }
    }
}
