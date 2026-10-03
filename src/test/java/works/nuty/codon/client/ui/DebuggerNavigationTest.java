package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import works.nuty.codon.client.state.ClientBreakpointState;
import works.nuty.codon.core.model.*;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises focus across actual widget replacement, without rendering or starting Minecraft. */
class DebuggerNavigationTest {
    private static final DebuggerNavigation.Group FLOW = DebuggerNavigation.Group.COMMAND;
    private final DebuggerNavigation navigation = new DebuggerNavigation();
    private final AtomicReference<GuiEventListener> focus = new AtomicReference<>();
    private final ClientBreakpointState breakpoints = new ClientBreakpointState();
    private final BreakpointTarget target = BreakpointTarget.stage(
        new SourceLocation.Block(new BlockLocation(0, 64, 0, "minecraft:overworld")), 2, "execute as @s run say test");

    @Test void pendingEditRetainsExactBreakpointThroughWidgetRebuildAndAcknowledgement() {
        var initial = frame();
        focus.set(initial.marker());
        var edit = breakpoints.begin(ClientBreakpointState.Action.TOGGLE, BreakpointDefinition.plain(target));
        var pending = frame();
        assertNotSame(initial.marker(), pending.marker(), "A rebuild replaces native widget instances");
        assertFalse(pending.marker().active);
        assertSame(pending.marker(), focus.get(), "Pending edit must not move focus to its adjacent clause");
        breakpoints.acceptPage(1, 0, true, java.util.List.of(BreakpointDefinition.plain(target)));
        assertSame(frame().marker(), focus.get(), "Definition sync can precede the edit acknowledgement");
        breakpoints.finish(edit.requestId(), ClientBreakpointState.Result.APPLIED);
        var acknowledged = frame();
        assertTrue(acknowledged.marker().active);
        assertSame(acknowledged.marker(), focus.get());
    }

    @Test void explicitTraversalWhilePendingWinsOverALaterAcknowledgement() {
        focus.set(frame().marker());
        var edit = breakpoints.begin(ClientBreakpointState.Action.TOGGLE, BreakpointDefinition.plain(target));
        var pending = frame();
        tab(false);
        assertSame(pending.clause(), focus.get());
        breakpoints.finish(edit.requestId(), ClientBreakpointState.Result.APPLIED);
        assertSame(frame().clause(), focus.get(), "Acknowledgement cannot steal focus back from explicit Tab");
        tab(true);
        assertEquals("marker", ((DebuggerButton) focus.get()).getMessage().getString());
    }

    @Test void clickingAnotherControlWhilePendingWinsOverRejectedEdit() {
        focus.set(frame().marker());
        var edit = breakpoints.begin(ClientBreakpointState.Action.TOGGLE, BreakpointDefinition.plain(target));
        var pending = frame();
        focus.set(pending.clause());
        navigation.rememberFocus(focus.get());
        breakpoints.finish(edit.requestId(), ClientBreakpointState.Result.NO_PERMISSION);
        assertSame(frame().clause(), focus.get());
        assertEquals(ClientBreakpointState.Result.NO_PERMISSION, breakpoints.error(target));
    }

    @Test void pendingMarkerIsSkippedByTraversalFromAnotherControl() {
        var edit = breakpoints.begin(ClientBreakpointState.Action.TOGGLE, BreakpointDefinition.plain(target));
        var widgets = frame();
        focus.set(widgets.clause());
        tab(true);
        assertSame(widgets.previous(), focus.get(), "Shift+Tab skips an unavailable marker instead of trapping focus");
        breakpoints.finish(edit.requestId(), ClientBreakpointState.Result.APPLIED);
        frame();
        assertEquals("previous", ((DebuggerButton) focus.get()).getMessage().getString());
    }

    private record Widgets(DebuggerButton previous, DebuggerButton marker, DebuggerButton clause) { }

    private Widgets frame() {
        var previous = focus.get();
        navigation.rememberFocus(previous);
        navigation.beginFrame(true);
        var widgets = new Widgets(button("previous", true), button("marker", !breakpoints.pending(target)), button("clause", true));
        navigation.add("previous", FLOW, 0, 0, () -> { });
        navigation.addRetained("exact-stage-target", FLOW, 0, 1, widgets.marker().active, () -> { });
        navigation.add("clause", FLOW, 0, 2, () -> { });
        navigation.bind("previous", FLOW, widgets.previous());
        navigation.bind("exact-stage-target", FLOW, widgets.marker());
        navigation.bind("clause", FLOW, widgets.clause());
        navigation.endFrame();
        focus.set(navigation.restoreFocus(previous, true));
        return widgets;
    }

    private static DebuggerButton button(String label, boolean active) {
        var button = new DebuggerButton();
        button.configure(0, 0, 20, 16, Component.literal(label), active, false, false, false, () -> { });
        return button;
    }

    private void tab(boolean reverse) {
        assertTrue(navigation.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0,
            reverse ? InputConstants.MOD_SHIFT : 0), focus.get(), focus::set));
    }
}
