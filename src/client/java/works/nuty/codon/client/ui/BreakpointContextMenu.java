package works.nuty.codon.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.network.ClientNetworking;
import works.nuty.codon.client.state.ClientBreakpointState;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;

/** New markers own exact targets; an explicit menu keeps saved sole-stage targets manageable. */
final class BreakpointContextMenu {
    private BreakpointContextMenu() { }

    static void open(Screen parent, ClientDebuggerState state, BreakpointTarget target, String command, int stageCount,
                     Bounds anchor, BooleanSupplier current, Runnable restoreFocus) {
        if (!current.getAsBoolean()) return;
        ScreenLayers.open(parent, new DebuggerContextMenu(parent,
            Component.translatable("codon.breakpoint.condition_title"), anchor,
            Component.literal(BreakpointUi.target(target)), () -> {
                if (!current.getAsBoolean()) return List.of();
                var items = new ArrayList<DebuggerContextMenu.Item>();
                var definition = state.breakpoints().get(target);
                items.add(new DebuggerContextMenu.Item(Component.translatable("codon.breakpoint.condition_action"),
                    BreakpointUi.icon(definition), state.breakpoints().ready() && !state.breakpoints().pending(target),
                    definition == null ? null : Component.literal(BreakpointUi.condition(definition.condition())),
                    () -> openEditor(parent, state, target, anchor, current, restoreFocus)));
                var saved = savedSingleStage(state, target, command, stageCount);
                if (saved != null) {
                    boolean active = state.breakpoints().ready() && !state.breakpoints().pending(saved.target());
                    items.add(new DebuggerContextMenu.Item(Component.translatable("codon.breakpoint.saved_stage_toggle", 1,
                        Component.translatable(saved.enabled() ? "codon.breakpoint.disable_short" : "codon.breakpoint.enable_short")),
                        BreakpointUi.icon(saved), active, Component.literal(BreakpointUi.condition(saved.condition())), () -> {
                            if (!current.getAsBoolean()) return;
                            var latest = savedSingleStage(state, target, command, stageCount);
                            if (latest != null && !state.breakpoints().pending(latest.target()))
                                ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.TOGGLE, latest);
                        }));
                    items.add(new DebuggerContextMenu.Item(Component.translatable("codon.breakpoint.saved_stage_options", 1),
                        BreakpointUi.icon(saved), active, Component.literal(BreakpointUi.condition(saved.condition())), () -> {
                            if (!current.getAsBoolean() || !state.breakpoints().ready()) return;
                            var latest = savedSingleStage(state, target, command, stageCount);
                            if (latest == null || state.breakpoints().pending(latest.target())) return;
                            ScreenLayers.open(parent, new BreakpointConditionScreen(parent, state, latest, target,
                                new BreakpointConditionScreen.Anchor(anchor.x(), anchor.y(), anchor.width(), anchor.height()))
                                    .withContextGuard(() -> current.getAsBoolean()
                                        && savedSingleStage(state, target, command, stageCount) != null)
                                    .withRestoreFocus(restoreFocus));
                        }));
                }
                return items;
            }, ignored -> false, restoreFocus));
    }

    static void openEditor(Screen parent, ClientDebuggerState state, BreakpointTarget target, String command, int stageCount,
                           Bounds anchor, BooleanSupplier current, Runnable restoreFocus) {
        if (!current.getAsBoolean() || !state.breakpoints().ready() || state.breakpoints().pending(target)) return;
        if (savedSingleStage(state, target, command, stageCount) != null)
            open(parent, state, target, command, stageCount, anchor, current, restoreFocus);
        else openEditor(parent, state, target, anchor, current, restoreFocus);
    }

    private static @Nullable BreakpointDefinition savedSingleStage(ClientDebuggerState state, BreakpointTarget marker,
                                                                            String command, int stageCount) {
        if (!marker.wholeCommand() || stageCount != 1) return null;
        var saved = state.breakpoints().get(BreakpointTarget.stage(marker.location(), 0, command));
        return saved == null || saved.staleSource() ? null : saved;
    }

    static void open(Screen parent, ClientDebuggerState state, BreakpointTarget target, Bounds anchor, BooleanSupplier current, Runnable restoreFocus) {
        open(parent, state, target, "", 0, anchor, current, restoreFocus);
    }

    /** Markers open their exact editor directly, without creating a definition until Save. */
    static void openEditor(Screen parent, ClientDebuggerState state, BreakpointTarget target, Bounds anchor,
                           BooleanSupplier current, Runnable restoreFocus) {
        if (!current.getAsBoolean() || !state.breakpoints().ready() || state.breakpoints().pending(target)) return;
        var latest = state.breakpoints().get(target);
        // Do not redirect a line marker to a legacy stage-zero definition.
        ScreenLayers.open(parent, new BreakpointConditionScreen(parent, state,
            latest == null ? BreakpointDefinition.plain(target) : latest, target,
            new BreakpointConditionScreen.Anchor(anchor.x(), anchor.y(), anchor.width(), anchor.height()))
                .withContextGuard(current).withRestoreFocus(restoreFocus));
    }
}
