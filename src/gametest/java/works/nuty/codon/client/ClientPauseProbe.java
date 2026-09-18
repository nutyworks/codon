package works.nuty.codon.client;

/** Test-only observations from client-loop mixins. */
public final class ClientPauseProbe {
    private static int textureTicks;

    private ClientPauseProbe() { }

    public static void resetTextureTicks() { textureTicks = 0; }
    public static void observeTextureTick() { textureTicks++; }
    public static int textureTicks() { return textureTicks; }
}
