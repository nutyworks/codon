package works.nuty.codon.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.adapter.BreakpointStageParser;
import works.nuty.codon.adapter.BreakpointTargetValidator;
import works.nuty.codon.adapter.BreakpointTargetValidator.Verdict;
import works.nuty.codon.core.model.BreakpointCondition;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.service.BreakpointRegistry;
import works.nuty.codon.core.service.DebuggerEngine;

/**
 * Stage and condition editing for {@code /codon breakpoint block|function}. It applies the same
 * server-side target rules as the graphical editor and changes nothing unless every check passes.
 */
final class BreakpointEditCommands {
    /** Reads the already-parsed location arguments of one invocation. */
    @FunctionalInterface
    interface LocationArgument {
        SourceLocation get(CommandContext<CommandSourceStack> context) throws CommandSyntaxException;
    }

    private enum Operation { TOGGLE, SET_CONDITION, CLEAR_CONDITION }

    private BreakpointEditCommands() {
    }

    /** Adds {@code [stage <stage>]} and {@code condition ...} after a location's last argument. */
    static void attach(RequiredArgumentBuilder<CommandSourceStack, ?> location, LocationArgument arguments,
                       DebuggerEngine engine) {
        location.then(condition(arguments, engine, false));
        location.then(Commands.literal("stage")
            .then(Commands.argument("stage", IntegerArgumentType.integer(1))
                .executes(c -> edit(c, engine, arguments, true, Operation.TOGGLE, BreakpointCondition.ALWAYS))
                .then(condition(arguments, engine, true))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> condition(LocationArgument arguments,
                                                                         DebuggerEngine engine, boolean staged) {
        var condition = Commands.literal("condition");
        condition.then(Commands.literal("clear")
            .executes(c -> edit(c, engine, arguments, staged, Operation.CLEAR_CONDITION, BreakpointCondition.ALWAYS)));
        for (BreakpointCondition.Kind kind : BreakpointCondition.Kind.values()) {
            if (kind == BreakpointCondition.Kind.ALWAYS) continue;
            var node = Commands.literal(kind.name().toLowerCase(Locale.ROOT));
            if (kind.isEvent()) {
                node.executes(c -> edit(c, engine, arguments, staged, Operation.SET_CONDITION,
                    BreakpointCondition.event(kind)));
            } else {
                for (BreakpointCondition.Comparison comparison : BreakpointCondition.Comparison.values()) {
                    node.then(Commands.literal(comparison.name().toLowerCase(Locale.ROOT))
                        .then(Commands.argument("value", IntegerArgumentType.integer(0))
                            .executes(c -> edit(c, engine, arguments, staged, Operation.SET_CONDITION,
                                BreakpointCondition.count(kind, comparison, IntegerArgumentType.getInteger(c, "value"))))));
                }
            }
            condition.then(node);
        }
        return condition;
    }

    private static int edit(CommandContext<CommandSourceStack> context, DebuggerEngine engine,
                            LocationArgument arguments, boolean staged, Operation operation,
                            BreakpointCondition condition) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        SourceLocation location = arguments.get(context);
        Integer stage = staged ? IntegerArgumentType.getInteger(context, "stage") : null;
        Component name = targetName(location, stage);
        BreakpointTarget target = resolve(source, location, stage);
        if (target == null) return 0;
        BreakpointDefinition existing = engine.breakpointDefinitions().stream()
            .filter(definition -> definition.target().equals(target)).findFirst().orElse(null);
        if (operation == Operation.CLEAR_CONDITION && existing == null) {
            source.sendFailure(Component.translatable("command.codon.breakpoint.condition.missing", name));
            return 0;
        }
        BreakpointDefinition base = existing != null ? existing : BreakpointDefinition.plain(target);
        BreakpointDefinition next = switch (operation) {
            case TOGGLE -> existing == null ? base : base.withEnabled(!base.enabled());
            // Like Save in the editor, setting or clearing a condition leaves the breakpoint enabled.
            case SET_CONDITION, CLEAR_CONDITION -> base.withCondition(condition).withEnabled(true);
        };
        if (next.enabled()) {
            Verdict verdict = BreakpointTargetValidator.validate(source.getServer(), next);
            if (verdict != Verdict.VALID) {
                source.sendFailure(rejection(verdict, location, name));
                return 0;
            }
        }
        try {
            engine.saveBreakpoint(next);
        } catch (BreakpointRegistry.LimitExceeded limit) {
            source.sendFailure(Component.translatable("command.codon.breakpoint.invalid.limit"));
            return 0;
        }
        Component message = switch (operation) {
            case TOGGLE -> Component.translatable(next.enabled()
                ? "command.codon.breakpoint.stage.success.set" : "command.codon.breakpoint.stage.success.disabled", name);
            case SET_CONDITION -> Component.translatable("command.codon.breakpoint.condition.success.set",
                name, conditionText(condition));
            case CLEAR_CONDITION -> Component.translatable("command.codon.breakpoint.condition.success.cleared", name);
        };
        source.sendSuccess(() -> message, false);
        return 1;
    }

    /**
     * Builds the exact target. Returns null after reporting why not: the source cannot be read, or
     * the stage is not one the editor would offer (a single-stage command is only targeted as a whole).
     */
    private static @Nullable BreakpointTarget resolve(CommandSourceStack source, SourceLocation location,
                                                      @Nullable Integer stage) {
        if (stage == null) return BreakpointTarget.whole(location);
        MinecraftServer server = source.getServer();
        Component name = targetName(location, null);
        var saved = BreakpointTargetValidator.savedCommand(server, location);
        if (saved.verdict() != Verdict.VALID) {
            source.sendFailure(rejection(saved.verdict(), location, name));
            return null;
        }
        if (location instanceof SourceLocation.Function && saved.command().startsWith("$")) {
            source.sendFailure(rejection(Verdict.MACRO_STAGE_OR_CONDITION, location, name));
            return null;
        }
        var stages = BreakpointStageParser.parse(server, server.createCommandSourceStack(), saved.command());
        if (stages.isEmpty()) {
            source.sendFailure(rejection(Verdict.UNPARSEABLE, location, name));
            return null;
        }
        int count = stages.get().size();
        if (count == 1) {
            source.sendFailure(Component.translatable("command.codon.breakpoint.invalid.single_stage", name));
            return null;
        }
        if (stage > count) {
            source.sendFailure(Component.translatable("command.codon.breakpoint.invalid.stage", name, stage, count));
            return null;
        }
        return BreakpointTarget.stage(location, stage - 1, saved.command());
    }

    private static Component rejection(Verdict verdict, SourceLocation location, Component name) {
        return switch (verdict) {
            case NO_TARGET -> location instanceof SourceLocation.Block
                ? Component.translatable("command.codon.breakpoint.invalid.block", name)
                : Component.translatable("command.codon.breakpoint.invalid.line", name);
            case SOURCE_UNAVAILABLE -> Component.translatable("command.codon.breakpoint.invalid.source_unavailable", name);
            case MACRO_STAGE_OR_CONDITION -> Component.translatable("command.codon.breakpoint.invalid.macro", name);
            case UNPARSEABLE -> Component.translatable("command.codon.breakpoint.invalid.unparseable", name);
            case STALE_SOURCE -> Component.translatable("command.codon.breakpoint.invalid.stale", name);
            case TERMINAL_STAGE -> Component.translatable("command.codon.breakpoint.invalid.final_stage", name);
            case NO_SUCH_STAGE, VALID -> Component.translatable("command.codon.breakpoint.invalid.target", name);
        };
    }

    static Component targetName(SourceLocation location, @Nullable Integer stage) {
        Component base = switch (location) {
            case SourceLocation.Block block -> Component.translatable("command.codon.breakpoint.target.block",
                block.block().x(), block.block().y(), block.block().z(), block.block().dimension());
            case SourceLocation.Function function -> Component.translatable("command.codon.breakpoint.target.function",
                function.location().function().toString(), function.location().line());
            case SourceLocation.Player ignored -> Component.translatable("codon.breakpoint.player");
        };
        return stage == null ? base : Component.translatable("command.codon.breakpoint.target.stage", base, stage);
    }

    static Component conditionText(BreakpointCondition condition) {
        Component kind = Component.translatable("codon.breakpoint.kind." + condition.kind().name().toLowerCase(Locale.ROOT));
        if (!condition.kind().isCount()) return kind;
        String comparison = switch (condition.comparison()) {
            case EQ -> "=";
            case NE -> "≠";
            case LT -> "<";
            case LE -> "≤";
            case GT -> ">";
            case GE -> "≥";
        };
        return Component.empty().append(kind).append(" " + comparison + " " + condition.threshold());
    }
}
