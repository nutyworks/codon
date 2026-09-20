package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientDebuggerState;
import net.minecraft.client.gui.screens.Screen;
import works.nuty.codon.client.ui.DebuggerHelpScreen;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.core.model.NbtPage;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.WatchResult;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Native mouse regressions for the rendered debugger scrollbar tracks. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerScrollbarGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext ignored = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1280, 600);
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
            });

            ClientDebuggerState state = new ClientDebuggerState();
            CodonScreen screen = context.computeOnClient(client -> {
                PauseSnapshot base = DebuggerPresentationGameTest.fixture(client);
                state.applyPause(new PauseSnapshot(base.location(), base.command(), base.depth(), base.callStack(),
                    base.pauseSources(), base.executionFlows(), base.reason(), 91));
                InputManager input = DebuggerPresentationGameTest.input(client, state);
                CodonScreen result = new CodonScreen(input, new DebuggerOverlay(state));
                client.setScreenAndShow(result);
                return result;
            });
            context.waitTicks(3);

            populateNbt(context, state);
            context.waitTicks(3);

            clickSourceTrack(context, screen);
            dragSourceTrack(context, screen);
            clickNbtTrack(context, screen);
            dragNbtTrack(context, screen);
            checkInformation(context, state);

            context.runOnClient(client -> client.setScreenAndShow(null));
        }
    }

    private static void checkInformation(ClientGameTestContext context, ClientDebuggerState state) {
        DebuggerHelpScreen help = context.computeOnClient(client -> {
            var result = new DebuggerHelpScreen(null, DebuggerPresentationGameTest.input(client, state));
            client.setScreenAndShow(result);
            return result;
        });
        context.waitTicks(3);
        Track initial = context.computeOnClient(client -> informationTrack(help));
        moveCursor(context, help, initial.x() + 1, initial.y() + initial.length() - 1);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        context.runOnClient(client -> require(integerField(help, "offset") > 0,
            "Native Information track click scrolls the help text"));
        context.takeScreenshot("codon-scrollbar-information-click");
        Track moved = context.computeOnClient(client -> informationTrack(help));
        moveCursor(context, help, moved.x() + 1, thumbCenter(moved));
        context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(1);
        moveCursor(context, help, moved.x() + 1, moved.y());
        context.waitTicks(2);
        context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        context.runOnClient(client -> require(integerField(help, "offset") == 0,
            "Native Information thumb drag returns to the first line after release"));
        context.takeScreenshot("codon-scrollbar-information-drag");
    }

    private static Track informationTrack(DebuggerHelpScreen screen) {
        Map<?, ?> tracks = (Map<?, ?>) field(field(screen, "scrollbars"), "tracks");
        Object value = tracks.get("information");
        require(value != null, "Information has a rendered interactive scrollbar");
        return track(value);
    }

    private static void populateNbt(ClientGameTestContext context, ClientDebuggerState state) {
        context.runOnClient(client -> {
            state.selectSource(0);
            var executor = state.selectedSource().entity().uuid();
            var query = state.nbt().drainQueries().stream().filter(value -> value.executor().equals(executor))
                .findFirst().orElseThrow(() -> new AssertionError("Selected source receives an NBT query"));
            List<NbtPage.Node> nodes = new ArrayList<>();
            for (int index = 0; index < 12; index++)
                nodes.add(new NbtPage.Node("scroll_field_" + index, "\"scroll_field_" + index + "\"", "{}", true));
            state.nbt().accept(query.pauseId(), query.requestId(),
                new NbtPage(WatchResult.Status.VALUE, nodes, 0, nodes.size()));
        });
    }

    private static void clickSourceTrack(ClientGameTestContext context, CodonScreen screen) {
        context.runOnClient(client -> {
            DebuggerButton first = button(screen, "#1 Zombie 1");
            screen.setFocused(first);
            require(screen.getFocused() == first, "Source click starts after a normally focusable inspector widget");
        });
        Track source = context.computeOnClient(client -> track(screen, "sources"));
        moveCursor(context, screen, source.x() + source.thickness() / 2.0, source.y() + source.length() - 1.0);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        context.runOnClient(client -> {
            int offset = integerField(overlay(screen), "sourceOffset");
            require(offset > 0, "Native click below the Sources thumb advances its scroll offset");
            require(sourceButtonOrNull(screen, "#1 Zombie 1") == null,
                "A Sources scrollbar click redraws a later visible source row");
        });
        context.takeScreenshot("codon-scrollbar-sources-click");
    }

    private static void dragSourceTrack(ClientGameTestContext context, CodonScreen screen) {
        Track initial = context.computeOnClient(client -> track(screen, "sources"));
        moveCursor(context, screen, initial.x() + initial.thickness() / 2.0, thumbCenter(initial));
        context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(1);
        moveCursor(context, screen, initial.x() + initial.thickness() / 2.0, initial.y());
        context.waitTicks(2);
        context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        context.runOnClient(client -> {
            Track source = track(screen, "sources");
            int offset = integerField(overlay(screen), "sourceOffset");
            require(offset == 0, "Native Sources drag back to the start persists after release");
            require(source.offset() == offset, "Rendered Sources thumb agrees with its released offset");
            require(sourceButtonOrNull(screen, "#1 Zombie 1") != null,
                "Dragging Sources redraws the first inspector row after release");
        });
        context.takeScreenshot("codon-scrollbar-sources-drag");
    }

    private static void clickNbtTrack(ClientGameTestContext context, CodonScreen screen) {
        Track nbt = context.computeOnClient(client -> trackStartingWith(screen, "nbt-"));
        moveCursor(context, screen, nbt.x() + nbt.thickness() / 2.0, nbt.y() + nbt.length() - 1.0);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        context.runOnClient(client -> {
            int offset = integerField(nbtPanel(overlay(screen)), "offset");
            require(offset > 0, "Native click below the NBT thumb advances its tree offset");
            require(buttonOrNull(screen, "▸ scroll_field_0: {}") == null,
                "An NBT scrollbar click redraws later tree rows");
        });
        context.takeScreenshot("codon-scrollbar-nbt-click");
    }

    private static void dragNbtTrack(ClientGameTestContext context, CodonScreen screen) {
        Track initial = context.computeOnClient(client -> trackStartingWith(screen, "nbt-"));
        moveCursor(context, screen, initial.x() + initial.thickness() / 2.0, thumbCenter(initial));
        context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(1);
        // Keep the pointer inside the 2-pixel track while crossing its full travel distance.
        moveCursor(context, screen, initial.x() + initial.thickness() / 2.0, initial.y());
        context.waitTicks(2);
        context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        context.runOnClient(client -> {
            Track nbt = trackStartingWith(screen, "nbt-");
            int offset = integerField(nbtPanel(overlay(screen)), "offset");
            require(offset == 0, "Native NBT drag back to the start persists after release");
            require(nbt.offset() == offset, "Rendered NBT thumb agrees with its released tree offset");
            require(buttonOrNull(screen, "▸ scroll_field_0: {}") != null,
                "Dragging the NBT thumb redraws the first tree row after release");
        });
        context.takeScreenshot("codon-scrollbar-nbt-drag");
    }

    private static void moveCursor(ClientGameTestContext context, Screen screen, double x, double y) {
        double[] physical = context.computeOnClient(client -> new double[] {
            x * client.getWindow().getScreenWidth() / screen.width,
            y * client.getWindow().getScreenHeight() / screen.height
        });
        context.getInput().setCursorPos(physical[0], physical[1]);
    }

    private static double thumbCenter(Track track) {
        int travel = track.length() - track.thumb();
        int top = travel * track.offset() / track.maximum();
        return track.y() + top + track.thumb() / 2.0;
    }

    private static Track track(CodonScreen screen, String id) {
        return tracks(screen).entrySet().stream().filter(entry -> entry.getKey().equals(id)).map(entry -> track(entry.getValue()))
            .findFirst().orElseThrow(() -> new AssertionError("Rendered scrollbar track missing: " + id));
    }

    private static Track trackStartingWith(CodonScreen screen, String prefix) {
        return tracks(screen).entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix)).map(entry -> track(entry.getValue()))
            .findFirst().orElseThrow(() -> new AssertionError("Rendered scrollbar track missing: " + prefix));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tracks(CodonScreen screen) {
        Object scrollbarInput = field(overlay(screen), "scrollbars");
        return (Map<String, Object>) field(scrollbarInput, "tracks");
    }

    private static Track track(Object value) {
        return new Track((boolean) accessor(value, "horizontal"), (int) accessor(value, "x"), (int) accessor(value, "y"),
            (int) accessor(value, "length"), (int) accessor(value, "thickness"), (int) accessor(value, "thumb"),
            (int) accessor(value, "offset"), (int) accessor(value, "maximum"));
    }

    private static Object accessor(Object target, String name) {
        try {
            Method method = target.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Rendered scrollbar geometry is inspectable", exception);
        }
    }

    private static DebuggerOverlay overlay(CodonScreen screen) { return (DebuggerOverlay) field(screen, "overlay"); }
    private static Object nbtPanel(DebuggerOverlay overlay) { return field(overlay, "nbtPanel"); }

    private static int integerField(Object target, String name) { return (int) field(target, name); }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Debugger scrollbar state is inspectable", exception);
        }
    }

    private static DebuggerButton button(CodonScreen screen, String label) {
        DebuggerButton result = buttonOrNull(screen, label);
        if (result == null) throw new AssertionError("Visible debugger button missing: " + label);
        return result;
    }

    private static DebuggerButton buttonOrNull(CodonScreen screen, String label) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.getMessage().getString().equals(label)).findFirst().orElse(null);
    }

    private static DebuggerButton sourceButtonOrNull(CodonScreen screen, String label) {
        var inspector = works.nuty.codon.client.ui.layout.DebuggerLayout.create(screen.width, screen.height, true).inspector();
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.getMessage().getString().equals(label)
                && button.getX() >= inspector.x() && button.getRight() <= inspector.x() + inspector.width()
                && button.getY() >= inspector.y() && button.getBottom() <= inspector.y() + inspector.height())
            .findFirst().orElse(null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Track(boolean horizontal, int x, int y, int length, int thickness, int thumb, int offset, int maximum) { }
}
