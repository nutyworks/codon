package works.nuty.codon.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.FunctionArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.commands.FunctionCommand;
import net.minecraft.server.permissions.Permissions;
import works.nuty.codon.adapter.SourceMapper;
import works.nuty.codon.adapter.FunctionSourceRepository;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.StepMode;
import works.nuty.codon.core.service.DebuggerEngine;
import works.nuty.codon.network.ControlRejectedPayload;

/**
 * The {@code /codon} command tree: a thin driving adapter that maps Brigadier arguments to core
 * value types and delegates to the {@link DebuggerEngine}. Owner permission is required.
 */
public final class CodonCommand {
    private static final int BREAKPOINT_COLOR = 0xFFFFAA00;

    private CodonCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, DebuggerEngine engine) {
        dispatcher.register(Commands.literal("codon")
            .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_OWNER))
            .then(Commands.literal("breakpoint")
                .then(Commands.literal("list").executes(c -> listBreakpoints(c, engine)))
                .then(Commands.literal("clear").executes(c -> clearBreakpoints(c, engine)))
                .then(Commands.literal("function")
                    .then(Commands.argument("function", FunctionArgument.functions()).suggests(FunctionCommand.SUGGEST_FUNCTION)
                        .then(Commands.argument("line", IntegerArgumentType.integer(1))
                            .executes(c -> toggleFunctionBreakpoint(c, engine)))))
                .then(Commands.literal("block")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(c -> toggleBlockBreakpoint(c, engine)))))
            .then(control("resume", engine, StepMode.NONE))
            .then(control("stepinto", engine, StepMode.INTO))
            .then(control("stepout", engine, StepMode.OUT))
            .then(control("stepover", engine, StepMode.OVER))
        );
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
            context.getSource().sendFailure(Component.literal("Breakpoint limit reached"));
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
            context.getSource().sendFailure(Component.literal("Breakpoint limit reached"));
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
        for (BlockLocation b : engine.blockBreakpoints()) {
            String pos = "%d, %d, %d".formatted(b.x(), b.y(), b.z());
            context.getSource().sendSuccess(() -> Component.translatable("command.codon.breakpoint.list.block", pos)
                .withStyle(s -> s.withColor(BREAKPOINT_COLOR)), false);
        }
        for (FunctionLocation f : engine.functionBreakpoints()) {
            context.getSource().sendSuccess(() -> Component.translatable("command.codon.breakpoint.list.function", f.function().toString(), f.line())
                .withStyle(s -> s.withColor(BREAKPOINT_COLOR)), false);
        }
        return 1;
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
            .executes(c -> control(c, engine, action, 0, 0))
            .then(Commands.argument("pauseId", LongArgumentType.longArg(1))
                .executes(c -> control(c, engine, action, LongArgumentType.getLong(c, "pauseId"), 0))
                .then(Commands.argument("requestId", LongArgumentType.longArg(1))
                    .executes(c -> control(c, engine, action, LongArgumentType.getLong(c, "pauseId"),
                        LongArgumentType.getLong(c, "requestId")))));
    }

    private static int control(CommandContext<CommandSourceStack> context, DebuggerEngine engine,
                               StepMode action, long expectedPauseId, long requestId) {
        var snapshot = engine.currentSnapshot();
        if (!engine.isPaused() || snapshot == null) {
            context.getSource().sendFailure(Component.translatable("command.codon.error.not_paused"));
            reject(context.getSource(), expectedPauseId, requestId, ControlRejectedPayload.Reason.NOT_PAUSED);
            return 0;
        }
        if (!engine.control(action, expectedPauseId == 0 ? snapshot.pauseId() : expectedPauseId)) {
            context.getSource().sendFailure(Component.translatable("command.codon.error.stale_pause"));
            reject(context.getSource(), expectedPauseId, requestId, ControlRejectedPayload.Reason.STALE_PAUSE);
            return 0;
        }
        return 1;
    }

    private static void reject(CommandSourceStack source, long pauseId, long requestId, ControlRejectedPayload.Reason reason) {
        var player = source.getPlayer();
        if (pauseId > 0 && requestId > 0 && player != null && player.connection.isAcceptingMessages()
            && ServerPlayNetworking.canSend(player, ControlRejectedPayload.TYPE.id())) {
            ServerPlayNetworking.send(player, new ControlRejectedPayload(pauseId, requestId, reason));
        }
    }
}
