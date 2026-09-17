package works.nuty.bastion.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import works.nuty.bastion.BastionMod;
import works.nuty.bastion.core.service.DebuggerEngine;

/** A connected block chain is one stepping scope, despite draining a queue for each block. */
@Mixin(CommandBlock.class)
abstract class CommandBlockMixin {
    @WrapMethod(method = "execute")
    private void bastion$executeChainScope(BlockState state, ServerLevel level, BlockPos pos,
                                          BaseCommandBlock commandBlock, boolean commandSet,
                                          Operation<Void> original) {
        DebuggerEngine engine = BastionMod.engine();
        if (engine != null) engine.onExecutionStarted();
        boolean completedNormally = false;
        try {
            original.call(state, level, pos, commandBlock, commandSet);
            completedNormally = true;
        } finally {
            if (engine != null) engine.onExecutionFinished(completedNormally);
        }
    }
}
