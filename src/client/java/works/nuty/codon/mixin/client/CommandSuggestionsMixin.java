package works.nuty.codon.mixin.client;

import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import works.nuty.codon.client.ui.WrappedCommandEditBox;

/** Keep vanilla's immediate completion and usage popups below the expanded input. */
@Mixin(CommandSuggestions.class)
abstract class CommandSuggestionsMixin {
    @Shadow @Final private EditBox input;

    @ModifyConstant(method = {"showSuggestions", "extractUsage"}, constant = @Constant(intValue = 72))
    private int codon$belowCommandInput(int original) {
        return input instanceof WrappedCommandEditBox ? input.getBottom() + 2 : original;
    }
}
