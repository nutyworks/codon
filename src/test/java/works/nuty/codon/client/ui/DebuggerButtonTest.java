package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DebuggerButtonTest {
    @Test
    void changedContextRetainsItsNameAndNarratesStatusWithoutMouseHover() {
        DebuggerButton button = new DebuggerButton();
        Component name = Component.literal("#3 Changed");
        Component status = Component.literal("Context changed at this stage");
        button.configure(0, 0, 80, 17, name, true, false, true, false, () -> { });
        button.withChangedDot(status);
        assertEquals(name, button.getMessage());
        assertTrue(button.hasChangedDot());
        NarrationElementOutput output = mock(NarrationElementOutput.class);
        button.updateWidgetNarration(output);
        verify(output).add(NarratedElementType.HINT, status);
    }

    @Test
    void narrationHintIsSpokenInPlaceOfTheTooltipAndClearedOnReuse() {
        DebuggerButton button = new DebuggerButton();
        Component hint = Component.literal("Fixed · pin-b");
        button.configure(0, 0, 80, 17, Component.literal("Inspect watch"), true, false, true, false, () -> { });
        button.withNarrationHint(hint);
        // Whichever order the owner sets them in, the tooltip must not take the hint slot.
        Component tooltip = Component.literal("Full value");
        button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(tooltip));
        NarrationElementOutput output = mock(NarrationElementOutput.class);
        button.updateNarration(output);
        verify(output).add(NarratedElementType.HINT, hint);
        verify(output, never()).add(NarratedElementType.HINT, tooltip);
        button.configure(0, 0, 80, 17, Component.literal("Inspect watch"), true, false, true, false, () -> { });
        NarrationElementOutput reused = mock(NarrationElementOutput.class);
        button.updateWidgetNarration(reused);
        verify(reused, never()).add(eq(NarratedElementType.HINT), any(Component.class));
    }

    @Test
    void reusedButtonClearsChangedIndicatorAndNarration() {
        DebuggerButton button = new DebuggerButton();
        button.withChangedDot(Component.literal("Changed"));
        button.configure(0, 0, 80, 17, Component.literal("#1 Unchanged"), true, false, true, false, () -> { });
        assertFalse(button.hasChangedDot());
        NarrationElementOutput output = mock(NarrationElementOutput.class);
        button.updateWidgetNarration(output);
        verify(output, never()).add(eq(NarratedElementType.HINT), any(Component.class));
    }

    @Test
    void pendingInputBlockKeepsAppearanceAndFocusButStopsBothClickActionsAndKeyboardActivation() {
        AtomicInteger presses = new AtomicInteger();
        DebuggerButton button = new DebuggerButton();
        button.configure(0, 0, 40, 20, Component.literal("compound"), true, true, true, false, presses::incrementAndGet);
        button.withSecondaryAction(presses::incrementAndGet).withInputBlocked(true);
        button.setFocused(true);
        assertTrue(button.active);
        assertTrue(button.isFocused());
        button.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        assertTrue(button.mouseClicked(new MouseButtonEvent(10, 10,
            new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false));
        assertTrue(button.mouseClicked(new MouseButtonEvent(10, 10,
            new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0)), false));
        assertEquals(0, presses.get());

        button.withInputBlocked(false);
        button.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        assertEquals(1, presses.get());

        button.withInputBlocked(true);
        button.configure(0, 0, 40, 20, Component.literal("fresh"), true, false, true, false, presses::incrementAndGet);
        assertFalse(button.inputBlocked(), "reused buttons must not keep an old input block");
        button.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        assertEquals(2, presses.get());
    }
}
