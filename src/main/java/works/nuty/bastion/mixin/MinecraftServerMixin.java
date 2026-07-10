package works.nuty.bastion.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import works.nuty.bastion.adapter.ServerTickScheduleController;

/** Keeps a debugger pause out of Minecraft's overload/late-tick accounting. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin implements ServerTickScheduleController {
    @Shadow private long nextTickTimeNanos;
    @Shadow private long lastOverloadWarningNanos;

    @Override
    public void bastion$resetTickSchedule() {
        long now = Util.getNanos();
        nextTickTimeNanos = now;
        lastOverloadWarningNanos = now;
    }
}
