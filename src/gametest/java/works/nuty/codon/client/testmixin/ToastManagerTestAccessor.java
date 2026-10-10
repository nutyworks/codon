package works.nuty.codon.client.testmixin;

import java.util.BitSet;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Observe real vanilla slot occupancy without modifying the manager. */
@Mixin(ToastManager.class)
public interface ToastManagerTestAccessor {
    @Accessor("occupiedSlots") BitSet codon$occupiedSlots();
}
