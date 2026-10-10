package works.nuty.codon.network;

import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.permissions.PermissionSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.*;
import works.nuty.codon.core.service.DebuggerEngine;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BreakpointEditHandlerTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void savedLegacyEditActionsStillRequireOwnerPermissionAndAnActiveConnection() {
        var saved = BreakpointDefinition.plain(BreakpointTarget.stage(new SourceLocation.Function(
            new FunctionLocation(new FunctionId("test", "one"), 1)), 0, "say one"));
        for (boolean connected : List.of(false, true)) for (var action : BreakpointEditPayload.Action.values()) {
            var engine = mock(DebuggerEngine.class);
            var player = mock(ServerPlayer.class);
            player.connection = mock(ServerGamePacketListenerImpl.class);
            when(player.connection.isAcceptingMessages()).thenReturn(connected);
            var source = mock(CommandSourceStack.class);
            when(player.createCommandSourceStack()).thenReturn(source);
            when(source.permissions()).thenReturn(PermissionSet.NO_PERMISSIONS);
            try (var network = mockStatic(ServerPlayNetworking.class)) {
                network.when(() -> ServerPlayNetworking.canSend(eq(player), any(net.minecraft.resources.Identifier.class))).thenReturn(true);
                new BreakpointEditHandler(engine).edit(mock(MinecraftServer.class), player, new BreakpointEditPayload(1, action, saved));
                verifyNoInteractions(engine);
                if (connected) network.verify(() -> ServerPlayNetworking.send(eq(player),
                    argThat(payload -> payload instanceof BreakpointEditResultPayload result && result.status() == BreakpointEditResultPayload.Status.NO_PERMISSION)));
            }
        }
    }
}
