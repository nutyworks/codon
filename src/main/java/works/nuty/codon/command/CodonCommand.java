package works.nuty.codon.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.FunctionArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.commands.FunctionCommand;
import net.minecraft.server.permissions.Permissions;
import works.nuty.codon.adapter.SourceMapper;
import works.nuty.codon.adapter.FunctionSourceRepository;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.StepMode;
import works.nuty.codon.core.service.DebuggerEngine;

import java.util.Comparator;

/**
 * The {@code /codon} command tree: a thin driving adapter that maps Brigadier arguments to core
 * value types and delegates to the {@link DebuggerEngine}. Owner permission is required.
 */
public final class CodonCommand {
    private static final int BREAKPOINT_COLOR = 0xFFFFAA00;
    private static final Comparator<BreakpointDefinition> BLOCK_ORDER = Comparator
        .comparing((BreakpointDefinition d) -> ((SourceLocation.Block) d.target().location()).block().dimension())
        .thenComparingInt(d -> ((SourceLocation.Block) d.target().location()).block().x())
        .thenComparingInt(d -> ((SourceLocation.Block) d.target().location()).block().y())
        .thenComparingInt(d -> ((SourceLocation.Block) d.target().location()).block().z())
        .thenComparingInt(d -> d.target().stageIndex());
    private static final Comparator<BreakpointDefinition> FUNCTION_ORDER = Comparator
        .comparing((BreakpointDefinition d) -> ((SourceLocation.Function) d.target().location()).location().function().toString())
        .thenComparingInt(d -> ((SourceLocation.Function) d.target().location()).location().line())
        .thenComparingInt(d -> d.target().stageIndex());

    private CodonCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, DebuggerEngine engine) {
        dispatcher.register(Commands.literal("codon")
            .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_OWNER))
            .then(Commands.literal("breakpoint")
                .then(Commands.literal("list").executes(c -> listBreakpoints(c, engine)))
                .then(Commands.literal("clear").executes(c -> clearBreakpoints(c, engine)))
                .then(Commands.literal("function").then(functionLine(engine)))
                .then(Commands.literal("block").then(blockPos(engine))))
            .then(control("resume", engine, StepMode.NONE))
            .then(control("stepinto", engine, StepMode.INTO))
            .then(control("stepout", engine, StepMode.OUT))
            .then(control("stepover", engine, StepMode.OVER))
        );
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ?> functionLine(DebuggerEngine engine) {
        var line = Commands.argument("line", IntegerArgumentType.integer(1))
            .executes(c -> toggleFunctionBreakpoint(c, engine));
        BreakpointEditCommands.attach(line, c -> {
            Identifier function = FunctionArgument.getFunctionOrTag(c, "function").getFirst();
            return new SourceLocation.Function(new FunctionLocation(SourceMapper.toFunctionId(function),
                IntegerArgumentType.getInteger(c, "line")));
        }, engine);
        return Commands.argument("function", FunctionArgument.functions()).suggests(FunctionCommand.SUGGEST_FUNCTION)
            .then(line);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ?> blockPos(DebuggerEngine engine) {
        var pos = Commands.argument("pos", BlockPosArgument.blockPos())
            .executes(c -> toggleBlockBreakpoint(c, engine));
        BreakpointEditCommands.attach(pos, c -> new SourceLocation.Block(SourceMapper.toBlockLocation(
            BlockPosArgument.getBlockPos(c, "pos"), c.getSource().getLevel().dimension().identifier().toString())), engine);
        return pos;
    }

    private static int toggleFunctionBreakpoint(CommandContext<CommandSourceStack> context, DebuggerEngine engine) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Identifier funcId = FunctionArgument.getFunctionOrTag(context, "function").getFirst();
        int line = IntegerArgumentType.getInteger(context, "line");
        FunctionLocation location = new FunctionLocation(SourceMapper.toFunctionId(funcId), line);
        BreakpointTarget target = BreakpointTarget.whole(new SourceLocation.Function(location));
        boolean disabling = engine.breakpointDefinitions().stream()
            .anyMatch(definition -> definition.target().equals(target) && definition.enabled());
        boolean unverified = false;
        if (!disabling) {
            var sourceLine = FunctionSourceRepository.commandLine(context.getSource().getServer(), location);
            if (sourceLine.status() == FunctionSourceRepository.LineStatus.INVALID) {
                context.getSource().sendFailure(Component.translatable("command.codon.breakpoint.function.invalid_line",
                    Component.translationArg(funcId), line));
                return 0;
            }
            unverified = sourceLine.status() == FunctionSourceRepository.LineStatus.UNAVAILABLE;
        }
        boolean enabled;
        try {
            enabled = engine.toggleFunctionBreakpoint(location);
        } catch (works.nuty.codon.core.service.BreakpointRegistry.LimitExceeded limit) {
            context.getSource().sendFailure(Component.translatableWithFallback("command.codon.breakpoint.error.limit", "Breakpoint limit reached"));
            return 0;
        }
        if (unverified) context.getSource().sendSuccess(() -> Component.translatable(
            "command.codon.breakpoint.function.source_unavailable", Component.translationArg(funcId), line), false);
        String key = enabled
            ? "command.codon.breakpoint.function.success.set"
            : "command.codon.breakpoint.function.success.disabled";
        context.getSource().sendSuccess(() -> Component.translatable(key, Component.translationArg(funcId), line), false);
        return 1;
    }

    private static int toggleBlockBreakpoint(CommandContext<CommandSourceStack> context, DebuggerEngine engine) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
        String dimension = context.getSource().getLevel().dimension().identifier().toString();
        boolean enabled;
        try {
            enabled = engine.toggleBlockBreakpoint(SourceMapper.toBlockLocation(pos, dimension));
        } catch (works.nuty.codon.core.service.BreakpointRegistry.LimitExceeded limit) {
            context.getSource().sendFailure(Component.translatableWithFallback("command.codon.breakpoint.error.limit", "Breakpoint limit reached"));
            return 0;
        }
        String key = enabled
            ? "command.codon.breakpoint.block.success.set"
            : "command.codon.breakpoint.block.success.disabled";
        context.getSource().sendSuccess(() -> Component.translatable(key, pos.getX(), pos.getY(), pos.getZ()), false);
        return 1;
    }

    private static int listBreakpoints(CommandContext<CommandSourceStack> context, DebuggerEngine engine) {
        if (!engine.hasBreakpoints()) {
            context.getSource().sendSuccess(() -> Component.translatable("command.codon.breakpoint.list.empty"), false);
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable("command.codon.breakpoint.list.header"), false);
        var active = engine.breakpointDefinitions().stream().filter(BreakpointDefinition::enabled).toList();
        active.stream().filter(d -> d.target().location() instanceof SourceLocation.Block)
            .sorted(BLOCK_ORDER).forEach(d -> {
                BlockLocation b = ((SourceLocation.Block) d.target().location()).block();
                String pos = "%d, %d, %d".formatted(b.x(), b.y(), b.z());
                sendListEntry(context, d, Component.translatable("command.codon.breakpoint.list.block", pos));
            });
        active.stream().filter(d -> d.target().location() instanceof SourceLocation.Function)
            .sorted(FUNCTION_ORDER).forEach(d -> {
                FunctionLocation f = ((SourceLocation.Function) d.target().location()).location();
                sendListEntry(context, d, Component.translatable("command.codon.breakpoint.list.function",
                    f.function().toString(), f.line()));
            });
        return 1;
    }

    private static void sendListEntry(CommandContext<CommandSourceStack> context, BreakpointDefinition definition,
                                      MutableComponent entry) {
        BreakpointTarget target = definition.target();
        if (!target.wholeCommand()) {
            entry.append(Component.translatable("command.codon.breakpoint.list.stage", target.stageIndex() + 1));
        }
        if (definition.condition().isResultCondition()) {
            entry.append(Component.translatable("command.codon.breakpoint.list.condition",
                BreakpointEditCommands.conditionText(definition.condition())));
        }
        context.getSource().sendSuccess(() -> entry.withStyle(s -> s.withColor(BREAKPOINT_COLOR)), false);
    }

    private static int clearBreakpoints(CommandContext<CommandSourceStack> context, DebuggerEngine engine) {
        int count = engine.breakpointCount();
        engine.clearBreakpoints();
        context.getSource().sendSuccess(() -> Component.translatable("command.codon.breakpoint.clear.success", count), false);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> control(String name, DebuggerEngine engine, StepMode action) {
        return Commands.literal(name)
            // A deliberate manual/console command targets the pause present when it executes.
            .executes(c -> control(c, engine, action, 0))
            .then(Commands.argument("pauseId", LongArgumentType.longArg(1))
                .executes(c -> control(c, engine, action, LongArgumentType.getLong(c, "pauseId"))));
    }

    private static int control(CommandContext<CommandSourceStack> context, DebuggerEngine engine,
                               StepMode action, long expectedPauseId) {
        var snapshot = engine.currentSnapshot();
        if (!engine.isPaused() || snapshot == null) {
            context.getSource().sendFailure(Component.translatable("command.codon.error.not_paused"));
            return 0;
        }
        if (!engine.control(action, expectedPauseId == 0 ? snapshot.pauseId() : expectedPauseId)) {
            context.getSource().sendFailure(Component.translatable("command.codon.error.stale_pause"));
            return 0;
        }
        return 1;
    }
}
