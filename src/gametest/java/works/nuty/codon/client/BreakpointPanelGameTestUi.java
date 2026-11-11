package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.ui.DebuggerButton;

import java.util.List;

/** Drives the docked Breakpoints panel through its toolbar button, as a player does. */
final class BreakpointPanelGameTestUi {
    private BreakpointPanelGameTestUi() { }

    /** The saved-breakpoint rows currently shown in the docked panel. */
    static List<DebuggerButton> rows(Screen screen) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.getClass().getSimpleName().equals("RowButton")).toList();
    }

    /** The edit action beside {@code row}. */
    static DebuggerButton edit(Screen screen, DebuggerButton row) {
        String label = Component.translatable("codon.breakpoint.edit_condition").getString();
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.getMessage().getString().equals(label) && button.getX() > row.getX()
                && button.getY() >= row.getY() && button.getY() < row.getBottom())
            .findFirst().orElseThrow(() -> new AssertionError("Edit action beside " + row.getMessage().getString()));
    }

    /** Opens the panel with its toolbar breakpoint-count button; a panel that is already open stays open. */
    static void open(ClientGameTestContext context) { press(context, false); }

    /** Closes the panel with the same button; a panel that is already closed stays closed. */
    static void close(ClientGameTestContext context) { press(context, true); }

    private static void press(ClientGameTestContext context, boolean wasOpen) {
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            String label = Component.translatable("codon.breakpoint.short_count", "").getString().strip();
            var button = screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
                .filter(value -> value.getMessage().getString().startsWith(label))
                .findFirst().orElseThrow(() -> new AssertionError("The BP toolbar button is visible on " + screen.getClass().getSimpleName() + " "
                    + screen.width + "x" + screen.height + " "
                    + screen.children().stream().filter(DebuggerButton.class::isInstance).map(w -> ((DebuggerButton) w).getMessage().getString()).toList()));
            if (button.selected() != wasOpen) return;
            var event = new MouseButtonEvent(button.getX() + 2, button.getY() + 2,
                new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            if (!screen.mouseClicked(event, false)) throw new AssertionError("The BP toolbar button accepts the click");
            screen.mouseReleased(event);
        });
        context.waitTicks(3);
    }
}
