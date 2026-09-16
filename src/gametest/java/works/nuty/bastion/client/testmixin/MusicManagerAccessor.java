package works.nuty.bastion.client.testmixin;

import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only music scheduler delay for debugger pause regression coverage. */
@Mixin(MusicManager.class)
public interface MusicManagerAccessor {
    @Accessor("nextSongDelay")
    int bastion$nextSongDelay();
}
