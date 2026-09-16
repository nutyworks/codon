package works.nuty.bastion.client.testmixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Read-only engine state for client pause regression coverage. */
@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
    @Accessor("tickCount")
    int bastion$tickCount();

    @Accessor("instanceToChannel")
    Map<SoundInstance, ChannelAccess.ChannelHandle> bastion$instanceToChannel();

    @Accessor("queuedSounds")
    Map<SoundInstance, Integer> bastion$queuedSounds();
}
