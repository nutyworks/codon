package works.nuty.codon.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import works.nuty.codon.CodonMod;

import java.util.ArrayList;
import java.util.List;

/** Observes primitives finalized for each native frame, without changing the shared collector. */
public final class WorldMarkerRenderProbe {
    public static final int SENTINEL = 0xFF00FF;
    private static final List<Frame> frames = new ArrayList<>();
    private static boolean enabled;
    private static boolean collecting;
    private static int markers;
    private static int sentinel;
    private static int conditional;
    private static String pendingScreenshot;

    public static void start() { start(null); }
    public static void start(String screenshot) { frames.clear(); pendingScreenshot = screenshot; enabled = true; }
    public static void stop() { enabled = false; collecting = false; pendingScreenshot = null; }
    public static List<Frame> frames() { return List.copyOf(frames); }

    public static void beginFrame() {
        collecting = enabled;
        markers = 0;
        sentinel = 0;
        conditional = 0;
    }

    public static void primitive(int color) {
        if (!collecting) return;
        switch (color & 0xFFFFFF) {
            case 0xFC8C8C, 0xF3C171, 0x75DFD6, 0x567C7B, 0x83E89D, 0xC7A0FF -> markers++;
            case 0xD3AAFF -> { markers++; conditional++; }
            case SENTINEL -> sentinel++;
            default -> { }
        }
    }

    public static void endFrame() {
        if (collecting) frames.add(new Frame(CodonClientMod.input().isUiHidden(), markers, sentinel, conditional));
        collecting = false;
    }

    /** Capture the first completed native frame, including its HUD, without an extra test render. */
    public static void captureFrame() {
        if (!enabled || frames.isEmpty() || pendingScreenshot == null) return;
        Minecraft client = Minecraft.getInstance();
        String name = pendingScreenshot;
        pendingScreenshot = null;
        Screenshot.grab(client.gameDirectory, name + ".png", client.gameRenderer.mainRenderTarget(), 1,
            message -> CodonMod.LOGGER.info("World marker screenshot: {}", message.getString()));
    }

    public record Frame(boolean hidden, int markers, int sentinel, int conditional) { }
}
