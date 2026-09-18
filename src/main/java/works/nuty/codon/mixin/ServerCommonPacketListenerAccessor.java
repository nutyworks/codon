package works.nuty.codon.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the narrow transport operations required while the debugger is parked. */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerCommonPacketListenerAccessor {
    @Invoker("keepConnectionAlive")
    void codon$keepConnectionAlive();

    @Accessor("connection")
    Connection codon$connection();
}
