package works.nuty.codon.client.ui;

import net.minecraft.network.chat.Component;
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

    public static String glyph(BreakpointDefinition definition) {
        if (definition == null) return "○";
        boolean conditional = definition.condition().kind() != BreakpointCondition.Kind.ALWAYS;
        return conditional ? (definition.enabled() ? "◆" : "◇") : (definition.enabled() ? "●" : "○");
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
