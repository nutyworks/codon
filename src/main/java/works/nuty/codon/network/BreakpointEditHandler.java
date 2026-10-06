package works.nuty.codon.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import works.nuty.codon.adapter.FunctionSourceRepository;
import works.nuty.codon.adapter.BreakpointStageParser;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.service.DebuggerEngine;
import works.nuty.codon.core.service.BreakpointRegistry;
import works.nuty.codon.core.service.ExecutionFlowRecorder;

/** Validates targets against the current world/source before applying a client edit. */
final class BreakpointEditHandler {
    private final DebuggerEngine engine;

    BreakpointEditHandler(DebuggerEngine engine) { this.engine = engine; }

    void edit(MinecraftServer server, ServerPlayer player, BreakpointEditPayload request) {
        BreakpointEditResultPayload.Status status;
        if (!player.connection.isAcceptingMessages()
            || !player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_OWNER)) {
            status = BreakpointEditResultPayload.Status.NO_PERMISSION;
        } else {
            try {
                BreakpointTarget target = request.definition().target();
                status = switch (request.action()) {
                    case DELETE -> {
                        engine.deleteBreakpoint(target);
                        yield BreakpointEditResultPayload.Status.APPLIED;
                    }
                    case TOGGLE, SAVE -> {
                        var definitions = engine.breakpointDefinitions();
                        BreakpointDefinition existing = definitions.stream()
                            .filter(definition -> definition.target().equals(target)).findFirst().orElse(null);
                        if (request.action() == BreakpointEditPayload.Action.TOGGLE
                            && existing != null && existing.staleSource())
                            yield BreakpointEditResultPayload.Status.STALE_SOURCE;
                        BreakpointDefinition effective = request.action() == BreakpointEditPayload.Action.TOGGLE
                            ? existing == null ? BreakpointDefinition.plain(target)
                                : existing.withEnabled(!existing.enabled())
                            : request.definition();
                        BreakpointEditResultPayload.Status validity = request.action() == BreakpointEditPayload.Action.TOGGLE
                            && !effective.enabled() ? BreakpointEditResultPayload.Status.APPLIED
                            : validate(server, effective);
                        if (validity != BreakpointEditResultPayload.Status.APPLIED) yield validity;
                        if (definitions.size() >= BreakpointRegistry.MAX_DEFINITIONS && existing == null)
                            yield BreakpointEditResultPayload.Status.INVALID_TARGET;
                        if (request.action() == BreakpointEditPayload.Action.TOGGLE) {
                            engine.toggleBreakpoint(target);
                        } else {
                            BreakpointDefinition definition = request.definition();
                            engine.saveBreakpoint(definition);
                        }
                        yield BreakpointEditResultPayload.Status.APPLIED;
                    }
                };
            } catch (RuntimeException invalid) {
                status = BreakpointEditResultPayload.Status.INVALID_TARGET;
            }
        }
        if (player.connection.isAcceptingMessages()
            && ServerPlayNetworking.canSend(player, BreakpointEditResultPayload.TYPE.id())) {
            ServerPlayNetworking.send(player, new BreakpointEditResultPayload(request.requestId(), status));
        }
    }

    private static BreakpointEditResultPayload.Status validate(MinecraftServer server, BreakpointDefinition definition) {
        BreakpointTarget target = definition.target();
        if (target.stageIndex() >= ExecutionFlowRecorder.MAX_STAGES) return BreakpointEditResultPayload.Status.INVALID_TARGET;
        String savedCommand;
        switch (target.location()) {
            case SourceLocation.Block block -> {
                ServerLevel level = null;
                for (ServerLevel candidate : server.getAllLevels()) {
                    if (candidate.dimension().identifier().toString().equals(block.block().dimension())) {
                        level = candidate;
                        break;
                    }
                }
                if (level == null) return BreakpointEditResultPayload.Status.INVALID_TARGET;
                BlockPos position = new BlockPos(block.block().x(), block.block().y(), block.block().z());
                if (!level.isLoaded(position) || !(level.getBlockEntity(position) instanceof CommandBlockEntity entity))
                    return BreakpointEditResultPayload.Status.INVALID_TARGET;
                savedCommand = entity.getCommandBlock().getCommand();
            }
            case SourceLocation.Function function -> {
                var line = FunctionSourceRepository.commandLine(server, function.location());
                if (line.status() == FunctionSourceRepository.LineStatus.UNAVAILABLE)
                    return BreakpointEditResultPayload.Status.FAILED;
                if (line.status() == FunctionSourceRepository.LineStatus.INVALID)
                    return BreakpointEditResultPayload.Status.INVALID_TARGET;
                savedCommand = line.command();
                if (savedCommand.startsWith("$")
                    && (!target.wholeCommand() || definition.condition().isResultCondition()))
                    return BreakpointEditResultPayload.Status.INVALID_TARGET;
            }
            case SourceLocation.Player ignored -> {
                return BreakpointEditResultPayload.Status.INVALID_TARGET;
            }
        }
        if (!target.wholeCommand() && !target.commandFingerprint().equals(BreakpointTarget.fingerprint(savedCommand)))
            return BreakpointEditResultPayload.Status.STALE_SOURCE;
        if (!target.wholeCommand() || definition.condition().isResultCondition()) {
            var stages = BreakpointStageParser.parse(server, server.createCommandSourceStack(), savedCommand);
            if (stages.isEmpty()) return BreakpointEditResultPayload.Status.INVALID_TARGET;
            if (target.wholeCommand()) {
                if (stages.get().stream().allMatch(span -> span.terminal()))
                    return BreakpointEditResultPayload.Status.INVALID_TARGET;
            } else if (target.stageIndex() >= stages.get().size()
                || (definition.condition().isResultCondition() && stages.get().get(target.stageIndex()).terminal())) {
                return BreakpointEditResultPayload.Status.INVALID_TARGET;
            }
        }
        return BreakpointEditResultPayload.Status.APPLIED;
    }
}
