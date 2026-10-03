package works.nuty.codon.client.ui;

import java.util.List;
import org.jspecify.annotations.Nullable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientBreakpointState;
import works.nuty.codon.client.state.BreakpointTargetPolicy;
import works.nuty.codon.client.network.ClientNetworking;
import works.nuty.codon.core.model.BreakpointCondition;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.SourceLocation;

public final class BreakpointUi {
    private BreakpointUi() { }

    public static String target(BreakpointTarget target) {
        String source = switch (target.location()) {
            case SourceLocation.Block block -> block.block().dimension() + " "
                + block.block().x() + "," + block.block().y() + "," + block.block().z();
            case SourceLocation.Function function -> function.location().function() + ":" + function.location().line();
            case SourceLocation.Player ignored -> tr("codon.breakpoint.player");
        };
        return target.wholeCommand() ? source : source + " · "
            + tr("codon.breakpoint.stage_target", target.stageIndex() + 1);
    }

    public static String condition(BreakpointCondition condition) {
        String kind = kindLabel(condition.kind());
        if (!condition.kind().isCount()) return kind;
        String comparison = switch (condition.comparison()) {
            case EQ -> "=";
            case NE -> "≠";
            case LT -> "<";
            case LE -> "≤";
            case GT -> ">";
            case GE -> "≥";
        };
        return kind + " " + comparison + " " + condition.threshold();
    }

    public static String kindLabel(BreakpointCondition.Kind kind) {
        return tr("codon.breakpoint.kind." + kind.name().toLowerCase(java.util.Locale.ROOT));
    }

    public static DebuggerIcon icon(BreakpointDefinition definition) {
        boolean enabled = definition != null && definition.enabled();
        boolean conditional = definition != null
            && definition.condition().kind() != BreakpointCondition.Kind.ALWAYS;
        return conditional
            ? (enabled ? DebuggerIcon.BREAKPOINT_CONDITIONAL : DebuggerIcon.BREAKPOINT_CONDITIONAL_EMPTY)
            : (enabled ? DebuggerIcon.BREAKPOINT : DebuggerIcon.BREAKPOINT_EMPTY);
    }

    public static List<BreakpointDefinition> lineDefinitions(ClientDebuggerState state, SourceLocation location,
                                                            String command, int stageCount) {
        return BreakpointTargetPolicy.lineDefinitions(location, command, stageCount, state.breakpoints().definitions());
    }

    public static @Nullable BreakpointDefinition lineDefinition(List<BreakpointDefinition> definitions) {
        return definitions.stream().filter(BreakpointDefinition::enabled).findFirst()
            .orElse(definitions.isEmpty() ? null : definitions.getFirst());
    }

    public static @Nullable BreakpointDefinition definition(ClientDebuggerState state, BreakpointTarget target,
                                                            String command, int stageCount) {
        return target.wholeCommand() ? lineDefinition(lineDefinitions(state, target.location(), command, stageCount))
            : state.breakpoints().get(target);
    }

    public static boolean editingMarker(Screen parent, BreakpointTarget target, String command) {
        return ScreenLayers.get(parent) instanceof BreakpointConditionScreen editor && editor.editsMarker(target, command);
    }

    public static @Nullable BreakpointDefinition editingDefinition(Screen parent, BreakpointTarget target, String command) {
        return ScreenLayers.get(parent) instanceof BreakpointConditionScreen editor && editor.editsMarker(target, command)
            ? editor.markerDefinition() : null;
    }

    public static boolean pending(ClientDebuggerState state, BreakpointTarget target, String command, int stageCount) {
        return waitingForPreview(state, target, command, stageCount) || state.breakpoints().pending(target)
            || target.wholeCommand() && stageCount == 1
            && lineDefinitions(state, target.location(), command, stageCount).stream()
                .anyMatch(definition -> state.breakpoints().pending(definition.target()));
    }

    public static ClientBreakpointState.@Nullable Result error(ClientDebuggerState state, BreakpointTarget target,
                                                               String command, int stageCount) {
        var direct = state.breakpoints().error(target);
        if (direct != null || !target.wholeCommand() || stageCount != 1) return direct;
        for (var definition : lineDefinitions(state, target.location(), command, stageCount)) {
            var error = state.breakpoints().error(definition.target());
            if (error != null) return error;
        }
        return null;
    }

    public static boolean waitingForPreview(ClientDebuggerState state, BreakpointTarget target,
                                           String command, int stageCount) {
        return target.wholeCommand() && (!state.breakpoints().ready()
            || BreakpointTargetPolicy.lineActionDeferred(target.location(), command, stageCount,
                state.breakpoints().definitions()));
    }

    public static void toggle(ClientDebuggerState state, BreakpointTarget target, String command, int stageCount) {
        if (pending(state, target, command, stageCount)) return;
        var definitions = target.wholeCommand() && stageCount == 1
            ? lineDefinitions(state, target.location(), command, stageCount) : List.<BreakpointDefinition>of();
        if (definitions.isEmpty()) {
            var existing = state.breakpoints().get(target);
            ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.TOGGLE,
                existing == null ? BreakpointDefinition.plain(target) : existing);
            return;
        }
        boolean enabled = definitions.stream().anyMatch(BreakpointDefinition::enabled);
        for (var definition : definitions) if (definition.enabled() == enabled)
            ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.TOGGLE, definition);
    }

    public static void openCondition(Screen parent, ClientDebuggerState state, BreakpointTarget target,
                                     String command, int stageCount, BreakpointConditionScreen.Anchor anchor) {
        openCondition(parent, state, target, command, stageCount, anchor, () -> true);
    }

    static void openCondition(Screen parent, ClientDebuggerState state, BreakpointTarget target,
                              String command, int stageCount, BreakpointConditionScreen.Anchor anchor,
                              java.util.function.BooleanSupplier current) {
        if (!current.getAsBoolean()) return;
        if (pending(state, target, command, stageCount)) return;
        var definitions = target.wholeCommand() && stageCount == 1
            ? lineDefinitions(state, target.location(), command, stageCount) : List.<BreakpointDefinition>of();
        if (definitions.size() > 1) {
            BreakpointContextMenu.openEditor(parent, state, target,
                anchor == null ? new works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds(parent.width / 2, parent.height / 2, 1, 1)
                    : new works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds(anchor.x(), anchor.y(), anchor.width(), anchor.height()),
                current, () -> { });
            return;
        }
        var existing = definitions.isEmpty() ? state.breakpoints().get(target) : definitions.getFirst();
        ScreenLayers.open(parent, new BreakpointConditionScreen(parent, state,
            existing == null ? BreakpointDefinition.plain(target) : existing, target, anchor).withContextGuard(current));
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
