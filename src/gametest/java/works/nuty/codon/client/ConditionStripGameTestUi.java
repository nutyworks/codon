package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.ui.BreakpointConditionStrip;
import works.nuty.codon.client.ui.ScaledCodonScreen;

/** Finds and drives the inline breakpoint condition strip of a screen, as its owner's input would. */
final class ConditionStripGameTestUi {
    private ConditionStripGameTestUi() { }

    static boolean open(Screen host) { return BreakpointConditionStrip.get(host) != null; }

    static BreakpointConditionStrip strip(Screen host) {
        var strip = BreakpointConditionStrip.get(host);
        if (strip == null) throw new AssertionError("Condition strip is open on " + host.getClass().getSimpleName());
        return strip;
    }

    static String text(String key, Object... args) { return Component.translatable(key, args).getString(); }

    /** A visible control with exactly this label. */
    static AbstractWidget widget(Screen host, String label) {
        return strip(host).widgets().stream().filter(widget -> widget.getMessage().getString().equals(label)).findFirst()
            .orElseThrow(() -> new AssertionError("Condition strip control " + label + " in "
                + strip(host).widgets().stream().map(widget -> widget.getMessage().getString()).toList()));
    }

    static boolean has(Screen host, String label) {
        var strip = BreakpointConditionStrip.get(host);
        return strip != null && strip.widgets().stream().anyMatch(widget -> widget.getMessage().getString().equals(label));
    }

    /** The click a screen routes to the strip, at the control's centre. */
    static void click(Screen host, String label) {
        var widget = widget(host, label);
        var event = new MouseButtonEvent(widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0,
            new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        strip(host).mouseClicked(event, false);
        strip(host).mouseReleased(event);
    }

    /** Types into the Count number field, as replacing its text would. */
    static void number(Screen host, String value) {
        ((net.minecraft.client.gui.components.EditBox) widget(host, text("codon.breakpoint.strip.count"))).setValue(value);
    }

    /** Selects Count with a metric, comparison and number through the strip's own controls. */
    static void count(Screen host, works.nuty.codon.core.model.BreakpointCondition condition) {
        click(host, text("codon.breakpoint.strip.mode.count"));
        click(host, text("codon.breakpoint.strip.metric." + condition.kind().name().toLowerCase(java.util.Locale.ROOT)));
        for (int i = 0; i < 6 && !strip(host).widgets().stream().anyMatch(widget -> widget.getMessage().getString()
            .equals(symbol(condition.comparison()))); i++) click(host, comparisonLabel(host));
        number(host, Integer.toString(condition.threshold()));
    }

    private static String comparisonLabel(Screen host) {
        return strip(host).widgets().stream().map(widget -> widget.getMessage().getString())
            .filter(label -> "=≠<≤>≥".contains(label) && label.length() == 1).findFirst().orElseThrow();
    }

    static String symbol(works.nuty.codon.core.model.BreakpointCondition.Comparison comparison) {
        return switch (comparison) { case EQ -> "="; case NE -> "≠"; case LT -> "<"; case LE -> "≤"; case GT -> ">"; case GE -> "≥"; };
    }

    static void key(Screen host, int key) { strip(host).keyPressed(new KeyEvent(key, 0, 0)); }

    static void type(Screen host, String value) {
        for (int i = 0; i < value.length(); i++) strip(host).charTyped(new CharacterEvent(value.charAt(i)));
    }

    /** A real pointer click, routed through the window, Fabric's screen events and the strip. */
    static void nativeClick(ClientGameTestContext context, Screen host, String label) {
        double[] position = context.computeOnClient(client -> {
            var widget = widget(host, label);
            double x = widget.getX() + widget.getWidth() / 2.0, y = widget.getY() + widget.getHeight() / 2.0;
            if (host instanceof ScaledCodonScreen scaled) { x = scaled.uiScale().toGame(x); y = scaled.uiScale().toGame(y); }
            var window = client.getWindow();
            return new double[] { x * window.getScreenWidth() / window.getGuiScaledWidth(),
                y * window.getScreenHeight() / window.getGuiScaledHeight() };
        });
        context.getInput().setCursorPos(position[0], position[1]);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
    }
}
