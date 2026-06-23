package works.nuty.bastion.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.bastion.BastionMod;
import works.nuty.bastion.core.service.DebuggerEngine;

@Mixin(Commands.class)
public abstract class CommandsMixin {

    @Shadow
    public abstract CommandDispatcher<CommandSourceStack> getDispatcher();

    @Inject(method = "performPrefixedCommand", at = @At("HEAD"), cancellable = true)
    private void bastion$executeImmediatelyWhenPaused(CommandSourceStack source, String command, CallbackInfo ci) {
        DebuggerEngine engine = BastionMod.engine();
        if (engine != null && engine.isPaused() && source.getEntity() instanceof ServerPlayer) {
            try {
                ParseResults<CommandSourceStack> parseResults = this.getDispatcher().parse(command, source);
                this.getDispatcher().execute(parseResults);
            } catch (Exception e) {
                source.sendFailure(Component.literal("Error: " + e.getMessage()));
            }
            ci.cancel();
        }
    }
}
