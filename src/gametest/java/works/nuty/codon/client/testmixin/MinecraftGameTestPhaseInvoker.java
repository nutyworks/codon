package works.nuty.codon.client.testmixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Applied after Fabric's mixin, which adds the client test-phase pump. */
@Mixin(value = Minecraft.class, priority = 900)
public interface MinecraftGameTestPhaseInvoker {
    @Invoker(value = "postRunTasks", remap = false)
    void codon$advanceGameTestPhase();
}
