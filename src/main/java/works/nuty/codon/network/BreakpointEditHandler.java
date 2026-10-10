package works.nuty.codon.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import works.nuty.codon.adapter.BreakpointTargetValidator;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.service.DebuggerEngine;
import works.nuty.codon.core.service.BreakpointRegistry;

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
                            : BreakpointTargetValidator.validate(server, effective).status();
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
}
