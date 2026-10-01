package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.cursor.CursorType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Vector2f;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import works.nuty.codon.client.ui.layout.UiScale;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.jspecify.annotations.Nullable;

/** Owns a transformed pose/scissor/deferred-tooltip scope in the shared GUI render state. */
public final class CodonGuiGraphics extends GuiGraphicsExtractor {
    private final UiScale scale;
    private final GuiGraphicsExtractor owner;
    private final boolean customScale;

    public CodonGuiGraphics(GuiGraphicsExtractor owner, UiScale scale, int localMouseX, int localMouseY, boolean customScale) {
        super(Minecraft.getInstance(), owner.guiRenderState, localMouseX, localMouseY);
        this.owner = owner;
        this.scale = scale;
        this.customScale = customScale;
        pose().set(owner.pose());
        pose().scale((float) scale.renderFactor());
    }

    public Matrix3x2f textPose(Matrix3x2fc pose) {
        return customScale ? new CodonTextPose(pose, scale.gameScale()) : new Matrix3x2f(pose);
    }

    // Super's constructor initializes the root scissor in game GUI coordinates before scale is set.
    @Override public int guiWidth() { return scale == null ? super.guiWidth() : scale.width(); }
    @Override public int guiHeight() { return scale == null ? super.guiHeight() : scale.height(); }

    @Override public boolean containsPointInScissor(int x, int y) {
        Vector2f point = pose().transformPosition(x, y, new Vector2f());
        return super.containsPointInScissor((int) point.x, (int) point.y);
    }

    @Override public void requestCursor(CursorType cursor) { owner.requestCursor(cursor); }

    @Override public void setTooltipForNextFrame(Font font, List<? extends FormattedCharSequence> lines,
                                                int x, int y, @Nullable Identifier style) {
        super.setTooltipForNextFrame(font, CodonTooltips.fit(font, lines, guiWidth()), Optional.empty(),
            CodonTooltips.withinViewport(DefaultTooltipPositioner.INSTANCE), x, y, false, style);
    }

    @Override public void setComponentTooltipForNextFrame(Font font, List<Component> lines,
                                                         int x, int y, @Nullable Identifier style) {
        setTooltipForNextFrame(font, lines.stream().map(Component::getVisualOrderText).toList(), x, y, style);
    }

    @Override public void setTooltipForNextFrame(Font font, List<FormattedCharSequence> lines,
                                                ClientTooltipPositioner positioner, int x, int y, boolean override) {
        super.setTooltipForNextFrame(font, CodonTooltips.fit(font, lines, guiWidth()),
            CodonTooltips.withinViewport(positioner), x, y, override);
    }

    @Override public void setTooltipForNextFrame(Font font, List<FormattedCharSequence> lines,
                                                Optional<TooltipComponent> component, ClientTooltipPositioner positioner,
                                                int x, int y, boolean override, @Nullable Identifier style) {
        super.setTooltipForNextFrame(font, CodonTooltips.fit(font, lines, guiWidth()), component,
            CodonTooltips.withinViewport(positioner), x, y, override, style);
    }
}
