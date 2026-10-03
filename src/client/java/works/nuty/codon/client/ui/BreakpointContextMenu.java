package works.nuty.codon.client.ui;

import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;

/** The clicked marker owns one exact target; the menu never asks line versus stage. */
final class BreakpointContextMenu {
    private BreakpointContextMenu() { }

    static void open(Screen parent, ClientDebuggerState state, BreakpointTarget target, Bounds anchor, BooleanSupplier current, Runnable restoreFocus) {
        if (!current.getAsBoolean()) return;
        ScreenLayers.open(parent, new DebuggerContextMenu(parent,
            Component.translatable("codon.breakpoint.condition_title"), anchor,
            Component.literal(BreakpointUi.target(target)), () -> {
                if (!current.getAsBoolean()) return List.of();
                var definition = state.breakpoints().get(target);
                return List.of(new DebuggerContextMenu.Item(Component.translatable("codon.breakpoint.condition_action"),
                    BreakpointUi.icon(definition), state.breakpoints().ready() && !state.breakpoints().pending(target),
                    definition == null ? null : Component.literal(BreakpointUi.condition(definition.condition())), () -> {
                        if (!current.getAsBoolean() || !state.breakpoints().ready() || state.breakpoints().pending(target)) return;
                        var latest = state.breakpoints().get(target);
                        // Do not redirect a line marker to a legacy stage-zero definition.
                        ScreenLayers.open(parent, new BreakpointConditionScreen(parent, state,
                            latest == null ? BreakpointDefinition.plain(target) : latest, target,
                            new BreakpointConditionScreen.Anchor(anchor.x(), anchor.y(), anchor.width(), anchor.height()))
                                .withContextGuard(current));
                    }));
            }, ignored -> false, restoreFocus));
    }
}
