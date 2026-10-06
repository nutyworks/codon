package works.nuty.codon.mixin.client;

import java.util.List;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Read vanilla's wrapped chat rows and spacing to reserve their HUD area. */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
    @Accessor("trimmedMessages") List<GuiMessage.Line> codon$trimmedMessages();
    @Invoker("getLineHeight") int codon$lineHeight();
}
