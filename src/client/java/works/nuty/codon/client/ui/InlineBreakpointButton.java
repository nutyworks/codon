package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.BreakpointCondition;

/** Keyboard and narration surface for a marker drawn by the command editor. */
public final class InlineBreakpointButton extends AbstractWidget {
    private final WrappedCommandEditBox editor;
    private final BreakpointTarget target;
    private final Consumer<Boolean> action;

    public InlineBreakpointButton(WrappedCommandEditBox editor, WrappedCommandEditBox.Marker marker,
                                  Consumer<Boolean> action) {
        super(0, 0, 9, 9, Component.empty());
        this.editor = editor;
        this.target = marker.target();
        this.action = action;
        setTabOrderGroup(1);
        update(marker, false);
    }

    public BreakpointTarget target() { return target; }

    public void update(WrappedCommandEditBox.Marker marker, boolean pending) {
        var point = editor.markerPosition(marker);
        setX(point.x() - 4);
        setY(point.y() - 4);
        active = !pending;
        Component name = target.wholeCommand() ? Component.translatable("codon.breakpoint.block_stop")
            : Component.translatable("codon.breakpoint.stage_target", target.stageIndex() + 1);
        setMessage(name.copy().append(" · ").append(Component.translatable(marker.enabled()
            ? "codon.breakpoint.inline_on" : "codon.breakpoint.inline_off"))
            .append(" · " + BreakpointUi.condition(marker.definition() == null
                ? BreakpointCondition.ALWAYS : marker.definition().condition())));
    }

    @Override public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (focused) editor.focusBreakpoint(target);
        else editor.clearBreakpointFocus(target);
    }

    // Mouse hit testing remains with the editor, including hover-only marker slots.
    @Override public boolean isMouseOver(double x, double y) { return false; }
    @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        return false;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (!isFocused()) return false;
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_SPACE) {
            if (isActive()) action.accept(event.key() == InputConstants.KEY_RETURN && event.hasShiftDown());
            return true;
        }
        return false;
    }

    @Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int x, int y, float tick) { }

    @Override protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
        output.add(NarratedElementType.USAGE, Component.translatable("codon.breakpoint.inline_keyboard_help"));
    }
}
