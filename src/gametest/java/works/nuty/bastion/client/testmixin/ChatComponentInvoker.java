package works.nuty.bastion.client.testmixin;

import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes vanilla's background-chat extraction so client tests can observe rendered opacity. */
@Mixin(ChatComponent.class)
public interface ChatComponentInvoker {
    @Invoker("extractRenderState")
    void bastion$extractRenderState(ChatComponent.ChatGraphicsAccess graphics, int screenHeight, int ticks,
                                    ChatComponent.DisplayMode displayMode);
}
