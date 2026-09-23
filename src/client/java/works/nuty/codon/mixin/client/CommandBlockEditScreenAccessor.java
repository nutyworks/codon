package works.nuty.codon.mixin.client;

import net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CommandBlockEditScreen.class)
public interface CommandBlockEditScreenAccessor {
    @Accessor("autoCommandBlock")
    CommandBlockEntity codon$commandBlockEntity();
}
