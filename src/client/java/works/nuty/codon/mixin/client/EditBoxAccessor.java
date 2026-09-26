package works.nuty.codon.mixin.client;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(EditBox.class)
public interface EditBoxAccessor {
    @Accessor("highlightPos") int codon$highlightPos();
    @Accessor("preeditOverlay") @Nullable IMEPreeditOverlay codon$preeditOverlay();
    @Invoker("applyFormat") FormattedCharSequence codon$applyFormat(String text, int offset);
}
