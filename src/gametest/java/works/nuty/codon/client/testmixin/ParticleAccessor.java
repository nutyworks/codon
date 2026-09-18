package works.nuty.codon.client.testmixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only particle state used to verify that the real client tick stopped advancing it. */
@Mixin(Particle.class)
public interface ParticleAccessor {
    @Accessor("age")
    int codon$age();

    @Accessor("x")
    double codon$x();

    @Accessor("y")
    double codon$y();

    @Accessor("z")
    double codon$z();
}
