package works.nuty.codon.client.testmixin;

import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes vanilla's background-chat extraction so client tests can observe rendered opacity. */
@Mixin(ChatComponent.class)
public interface ChatComponentInvoker {
    @Invoker("extractRenderState")
    void codon$extractRenderState(ChatComponent.ChatGraphicsAccess graphics, int screenHeight, int ticks,
                                    ChatComponent.DisplayMode displayMode);
}
