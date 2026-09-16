package works.nuty.bastion.client.testmixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only particle state used to verify that the real client tick stopped advancing it. */
@Mixin(Particle.class)
public interface ParticleAccessor {
    @Accessor("age")
    int bastion$age();

    @Accessor("x")
    double bastion$x();

    @Accessor("y")
    double bastion$y();

    @Accessor("z")
    double bastion$z();
}
