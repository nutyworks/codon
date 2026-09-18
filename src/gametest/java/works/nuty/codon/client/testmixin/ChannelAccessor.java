package works.nuty.codon.client.testmixin;

import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.sounds.AudioStream;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Makes a sound-thread observation of the real OpenAL source possible in a client test. */
@Mixin(Channel.class)
public interface ChannelAccessor {
    @Accessor("source")
    int codon$source();

    @Invoker("getState")
    int codon$getState();

    @Accessor("stream")
    @Nullable AudioStream codon$stream();
}
