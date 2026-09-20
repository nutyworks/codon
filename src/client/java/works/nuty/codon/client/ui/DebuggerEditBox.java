package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2fStack;

/**
 * A vanilla edit box whose complete field chrome participates in Codon's panel-opacity setting.
 * Input, layout, scrolling, narration, and IME positioning remain owned by {@link EditBox}.
 */
public final class DebuggerEditBox extends EditBox {
    public DebuggerEditBox(Font font, int x, int y, int width, int height, Component label) {
        super(font, x, y, width, height, label);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractWidgetRenderState(new TintedGraphics(graphics, mouseX, mouseY), mouseX, mouseY, partialTick);
    }

    /**
     * EditBox only uses this narrow portion of GuiGraphicsExtractor. Delegating it keeps the
     * existing pose, scissor stack, and deferred-render state intact while fading every visual.
     */
    private static final class TintedGraphics extends GuiGraphicsExtractor {
        private final GuiGraphicsExtractor delegate;

        TintedGraphics(GuiGraphicsExtractor delegate, int mouseX, int mouseY) {
            super(Minecraft.getInstance(), delegate.guiRenderState, mouseX, mouseY);
            this.delegate = delegate;
        }

        private static int tint(int color) {
            return DebuggerTheme.color(color);
        }

        private static boolean visible(int color) {
            return (color >>> 24) != 0;
        }

        private static float opacity() {
            return (tint(0xFFFFFFFF) >>> 24) / 255.0F;
        }

        @Override
        public int guiWidth() {
            // The superclass queries dimensions before this wrapper has assigned its delegate.
            return delegate == null ? super.guiWidth() : delegate.guiWidth();
        }

        @Override
        public int guiHeight() {
            return delegate == null ? super.guiHeight() : delegate.guiHeight();
        }

        @Override
        public Matrix3x2fStack pose() {
            return delegate.pose();
        }

        @Override
        public void enableScissor(int x1, int y1, int x2, int y2) {
            delegate.enableScissor(x1, y1, x2, y2);
        }

        @Override
        public void disableScissor() {
            delegate.disableScissor();
        }

        @Override
        public boolean containsPointInScissor(int x, int y) {
            return delegate.containsPointInScissor(x, y);
        }

        @Override
        public void requestCursor(CursorType cursorType) {
            delegate.requestCursor(cursorType);
        }

        @Override
        public void fill(int x1, int y1, int x2, int y2, int color) {
            int faded = tint(color);
            if (visible(faded)) delegate.fill(x1, y1, x2, y2, faded);
        }

        @Override
        public void fill(RenderPipeline pipeline, int x1, int y1, int x2, int y2, int color) {
            int faded = tint(color);
            if (visible(faded)) delegate.fill(pipeline, x1, y1, x2, y2, faded);
        }

        @Override
        public void textHighlight(int x1, int y1, int x2, int y2, boolean invert) {
            // Partially inverting the framebuffer erases glyph contrast at 50% opacity.
            // A translucent selection overlay preserves legibility throughout the fade.
            int highlight = tint(0x6075DFD6);
            if (visible(highlight)) delegate.fill(x1, y1, x2, y2, highlight);
        }

        @Override
        public void text(Font font, String text, int x, int y, int color, boolean shadow) {
            int faded = tint(color);
            if (visible(faded)) delegate.text(font, text, x, y, faded, shadow);
        }

        @Override
        public void text(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
            int faded = tint(color);
            if (visible(faded)) delegate.text(font, text, x, y, faded, shadow);
        }

        @Override
        public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
            int faded = tint(color);
            if (visible(faded)) delegate.text(font, text, x, y, faded, shadow);
        }

        @Override
        public void blitSprite(RenderPipeline pipeline, Identifier sprite, int x, int y, int width, int height) {
            float opacity = opacity();
            if (opacity > 0.0F) delegate.blitSprite(pipeline, sprite, x, y, width, height, opacity);
        }

        @Override
        public void setPreeditOverlay(Renderable overlay) {
            delegate.setPreeditOverlay((graphics, mouseX, mouseY, partialTick) ->
                overlay.extractRenderState(new TintedGraphics(graphics, mouseX, mouseY), mouseX, mouseY, partialTick));
        }
    }
}
