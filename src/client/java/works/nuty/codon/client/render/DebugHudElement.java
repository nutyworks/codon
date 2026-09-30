package works.nuty.codon.client.render;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.NonNull;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.ui.DebuggerOverlay;

/** Passive HUD; cursor mode renders the same overlay once through CodonScreen. */
public final class DebugHudElement implements HudElement {
    private final DebuggerOverlay overlay;
    private final InputManager input;

    public DebugHudElement(DebuggerOverlay overlay, InputManager input) {
        this.overlay = overlay;
        this.input = input;
    }

    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, @NonNull DeltaTracker tracker) {
        // Avoid competing with chat, inventories, menus, or the interactive debugger screen.
        if (Minecraft.getInstance().gui.screen() == null && !input.isUiHidden()) {
            var scaled = new works.nuty.codon.client.ui.CodonGuiGraphics(graphics,
                works.nuty.codon.client.ui.ScaledCodonScreen.scale(overlay.preferences()), -1, -1);
            overlay.render(scaled, -1, -1, 0, false, input);
            scaled.extractDeferredElements(-1, -1, 0);
        }
    }
}
