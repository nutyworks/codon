package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.ScaledCodonScreen;
import works.nuty.codon.client.ui.ScreenLayers;

/** Native row-menu input shared by the real-server Watch scenarios. */
@SuppressWarnings({"UnstableApiUsage", "unchecked"})
final class WatchGameTestUi {
    static DebuggerButton row(Screen screen, long id) {
        var overlay = (DebuggerOverlay) FunctionLineBreakpointGameTest.field(screen, "overlay");
        var buttons = (Map<String, DebuggerButton>) FunctionLineBreakpointGameTest.field(overlay.watchPanel(), "buttons");
        var row = buttons.get("watch-row-" + id);
        if (row == null || !row.visible || !screen.children().contains(row))
            throw new AssertionError("Visible Watch row for exact definition " + id);
        return row;
    }

    static void open(ClientGameTestContext context, long id) {
        boolean hidden = context.computeOnClient(client -> {
            var overlay = (DebuggerOverlay) FunctionLineBreakpointGameTest.field(client.gui.screen(), "overlay");
            return overlay.watchPanel().bounds().height() == 0;
        });
        if (hidden) {
            click(context, context.computeOnClient(client -> control(client.gui.screen(), "codon.ui.view")),
                InputConstants.MOUSE_BUTTON_LEFT);
            click(context, context.computeOnClient(client -> control(client.gui.screen(), "codon.watch.title")),
                InputConstants.MOUSE_BUTTON_LEFT);
        }
        context.runOnClient(client -> {
            var overlay = (DebuggerOverlay) FunctionLineBreakpointGameTest.field(client.gui.screen(), "overlay");
            overlay.watchPanel().select(id);
        });
        context.waitTicks(2);
        click(context, context.computeOnClient(client -> row(client.gui.screen(), id)), InputConstants.MOUSE_BUTTON_RIGHT);
        context.runOnClient(client -> {
            if (ScreenLayers.get(client.gui.screen()) == null)
                throw new AssertionError("Watch row right-click opens its management menu");
        });
    }

    static DebuggerButton action(Screen parent, String key) {
        Screen menu = ScreenLayers.get(parent);
        if (menu == null) throw new AssertionError("Watch menu is open");
        return menu.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.visible && button.getMessage().equals(Component.translatable(key)))
            .findFirst().orElse(null);
    }

    private static DebuggerButton control(Screen screen, String key) {
        String label = Component.translatable(key).getString();
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.visible && button.getMessage().getString().endsWith(label))
            .findFirst().orElseThrow(() -> new AssertionError("Visible Watch toolbar control: " + key));
    }

    static void choose(ClientGameTestContext context, String key) {
        var action = context.computeOnClient(client -> action(client.gui.screen(), key));
        if (action == null || !action.active) throw new AssertionError("Active Watch menu action: " + key);
        click(context, action, InputConstants.MOUSE_BUTTON_LEFT);
    }

    static void perform(ClientGameTestContext context, long id, String key) {
        open(context, id);
        choose(context, key);
    }

    private static void click(ClientGameTestContext context, DebuggerButton button, int mouseButton) {
        double[] point = context.computeOnClient(client -> {
            Screen parent = client.gui.screen();
            Screen layer = ScreenLayers.get(parent);
            var host = (ScaledCodonScreen) (layer == null ? parent : layer);
            var window = client.getWindow();
            return new double[]{host.uiScale().toGame(button.getX() + button.getWidth() / 2.0)
                * window.getScreenWidth() / window.getGuiScaledWidth(),
                host.uiScale().toGame(button.getY() + button.getHeight() / 2.0)
                * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(point[0], point[1]);
        context.getInput().pressMouse(mouseButton);
        context.waitTicks(2);
    }
}
