package works.nuty.codon.mixin;

import net.minecraft.server.MinecraftServer;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.injection.At;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.McExecutionController;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import works.nuty.codon.adapter.ServerTickScheduleController;

/** Keeps a debugger pause out of Minecraft's overload/late-tick accounting. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin implements ServerTickScheduleController {
    @Shadow private long nextTickTimeNanos;
    @Shadow private long lastOverloadWarningNanos;

    /** Exclude an intentional debugger pause from watchdog elapsed-time accounting. */
    @ModifyReturnValue(method = "getNextTickTime", at = @At("RETURN"))
    private long codon$watchdogDeadline(long original) {
        return McExecutionController.isParked()
            || (CodonMod.engine() != null && CodonMod.engine().isPaused())
            ? Math.max(original, Util.getNanos()) : original;
    }

    @Override
    public void codon$resetTickSchedule() {
        long now = Util.getNanos();
        nextTickTimeNanos = now;
        lastOverloadWarningNanos = now;
    }
}
