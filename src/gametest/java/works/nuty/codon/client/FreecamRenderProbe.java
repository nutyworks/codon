package works.nuty.codon.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** Test-only observation that the real paused player reaches vanilla render-state extraction. */
public final class FreecamRenderProbe {
    private static volatile float playerPartialTick = Float.NaN;
    private static volatile String visibility = "not observed";
    private static volatile int handSubmissions;
    private static volatile int bodySubmissions;

    public static void reset() { playerPartialTick = Float.NaN; visibility = "not observed"; bodySubmissions = 0; }
    public static int bodySubmissions() { return bodySubmissions; }
    public static void observeBodySubmission(Entity entity) {
        if (entity == Minecraft.getInstance().player) bodySubmissions++;
    }
    public static float playerPartialTick() { return playerPartialTick; }
    public static String visibility() { return visibility; }
    public static void resetHandSubmissions() { handSubmissions = 0; }
    public static int handSubmissions() { return handSubmissions; }
    public static void observeHands() { handSubmissions++; }

    public static void observeVisibility(Entity entity, boolean visible, boolean sectionVisible, boolean frustumVisible) {
        if (entity == Minecraft.getInstance().player) {
            visibility = "visible=" + visible + ", section=" + sectionVisible + ", frustum=" + frustumVisible;
        }
    }

    public static void observe(Entity entity, float partialTick) {
        var freecam = CodonClientMod.freecam();
        if (freecam != null && freecam.isActive() && entity == Minecraft.getInstance().player) {
            playerPartialTick = partialTick;
        }
    }
}
