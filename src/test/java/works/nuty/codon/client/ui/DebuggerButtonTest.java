package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DebuggerButtonTest {
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
