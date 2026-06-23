package works.nuty.bastion.client.render;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.NonNull;
import works.nuty.bastion.client.ui.BastionScreen;
import works.nuty.bastion.client.ui.WindowManager;

/**
 * Draws the debugger windows onto the HUD while no {@link BastionScreen} is open (when the editor
 * screen is open it draws the windows itself, so we skip to avoid double-rendering).
 */
public final class DebugHudElement implements HudElement {
    private final WindowManager windowManager;

    public DebugHudElement(WindowManager windowManager) {
        this.windowManager = windowManager;
    }

    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor drawContext, @NonNull DeltaTracker tickCounter) {
        drawContext.pose().pushMatrix();
        drawContext.pose().scale(windowManager.getScale());

        if (!(Minecraft.getInstance().gui.screen() instanceof BastionScreen)) {
            windowManager.getWindows().forEach(window -> window.render(drawContext, -1, -1));
        }

        drawContext.pose().popMatrix();
    }
}
