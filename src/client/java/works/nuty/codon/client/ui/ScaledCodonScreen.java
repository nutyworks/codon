package works.nuty.codon.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.CodonClientMod;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.client.ui.layout.UiScale;

/** Marks only Codon-owned screens for the shared render, layout and native-input boundary. */
public abstract class ScaledCodonScreen extends Screen {
    private final DebuggerPreferences uiPreferences;

    protected ScaledCodonScreen(Component title, DebuggerPreferences preferences) {
        super(title);
        uiPreferences = preferences;
    }

    public final DebuggerPreferences uiPreferences() { return uiPreferences; }

    public final UiScale uiScale() { return scale(uiPreferences); }

    @Override protected void repositionElements() {
        var focused = getFocused();
        super.repositionElements();
        // Vanilla rebuilds widgets on resize. Retain a semantic field/button rather than a
        // detached widget instance; a removed or disabled target must not receive input.
        if (focused instanceof AbstractWidget previous) {
            children().stream().filter(child -> child.getClass() == previous.getClass())
                .map(AbstractWidget.class::cast)
                .filter(child -> child.visible && child.active && child.getMessage().equals(previous.getMessage()))
                .findFirst().ifPresent(this::setFocused);
        }
    }

    public static DebuggerPreferences preferencesFor(@Nullable Screen parent) {
        return parent instanceof ScaledCodonScreen scaled ? scaled.uiPreferences() : CodonClientMod.state().preferences();
    }

    public static UiScale scale(DebuggerPreferences preferences) {
        var window = Minecraft.getInstance().getWindow();
        return UiScale.create(preferences, window.getWidth(), window.getHeight(), window.getGuiScale(),
            window.getGuiScaledWidth(), window.getGuiScaledHeight(), Minecraft.getInstance().isEnforceUnicode());
    }

    /** Before Fabric dispatch, including a Codon modal hosted over a vanilla editor. */
    public static double inputCoordinate(double coordinate) {
        Screen owner = Minecraft.getInstance().gui.screen();
        Screen layer = ScreenLayers.get(owner);
        Screen target = layer == null ? owner : layer;
        return target instanceof ScaledCodonScreen scaled ? scaled.uiScale().toLocal(coordinate) : coordinate;
    }

    /** Convert anchors from their owner's units; the vanilla command editor keeps game GUI units. */
    public final BreakpointConditionScreen.@Nullable Anchor localAnchor(Screen parent, BreakpointConditionScreen.@Nullable Anchor anchor) {
        if (anchor == null || parent instanceof ScaledCodonScreen) return anchor;
        UiScale scale = uiScale();
        return new BreakpointConditionScreen.Anchor((int) scale.toLocal(anchor.x()), (int) scale.toLocal(anchor.y()),
            (int) scale.toLocal(anchor.width()), (int) scale.toLocal(anchor.height()));
    }
}
