package works.nuty.codon.mixin.client;

import net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserve vanilla's loaded editor state when returning from a breakpoint screen. */
@Mixin(CommandBlockEditScreen.class)
abstract class CommandBlockEditScreenMixin {
    @Unique private boolean codon$receivedCommand;

    @Inject(method = "updateGui", at = @At("TAIL"))
    private void codon$markCommandReceived(CallbackInfo ci) {
        codon$receivedCommand = true;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void codon$restoreLoadedEditor(CallbackInfo ci) {
        // Vanilla disables its controls on every init and expects another block-entity packet.
        // Returning to this same screen instance does not request that packet again.
        if (codon$receivedCommand) ((CommandBlockEditScreen) (Object) this).updateGui();
    }
}
