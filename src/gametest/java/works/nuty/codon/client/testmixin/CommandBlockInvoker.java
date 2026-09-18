package works.nuty.codon.client.testmixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Runs the real command-block body deterministically without depending on scheduled-tick timing. */
@Mixin(CommandBlock.class)
public interface CommandBlockInvoker {
    @Invoker("execute")
    void codon$execute(BlockState state, ServerLevel level, BlockPos pos,
                         BaseCommandBlock commandBlock, boolean commandSet);
}
