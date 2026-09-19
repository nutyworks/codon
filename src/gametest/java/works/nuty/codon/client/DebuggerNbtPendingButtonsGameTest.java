package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.InputType;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerIcon;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.core.model.NbtPage;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.WatchResult;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Rendered controls retain their appearance briefly while their NBT page is being replaced. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerNbtPendingButtonsGameTest implements FabricClientGameTest {
    private static final List<NbtPage.Node> ROOT = List.of(
        new NbtPage.Node("compound", "compound", "{}", true),
        new NbtPage.Node("list", "list", "[]", true));
    private static final List<NbtPage.Node> COMPOUND = List.of(
        new NbtPage.Node("nested", "compound.nested", "{}", true));

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext ignored = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1280, 960);
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
            });

            AtomicLong now = new AtomicLong();
            ClientDebuggerState state = new ClientDebuggerState(now::get);
            CodonScreen screen = context.computeOnClient(client -> {
                PauseSnapshot base = DebuggerPresentationGameTest.fixture(client);
                state.applyPause(new PauseSnapshot(base.location(), base.command(), base.depth(), base.callStack(),
                    base.pauseSources(), base.executionFlows(), base.reason(), 701));
                CodonScreen result = new CodonScreen(DebuggerPresentationGameTest.input(client, state), new DebuggerOverlay(state));
                client.setScreenAndShow(result);
                return result;
            });
            context.waitTicks(2);

            loadRoot(context, state);
            context.waitTicks(2);
            expandCompound(context, screen);
            context.waitTicks(1);
            loadCompound(context, state);
            context.waitTicks(2);
            context.takeScreenshot("codon-nbt-buttons-loaded");

            context.runOnClient(client -> {
                click(screen, pinAt(screen, button(screen, "▾ compound: {}")), InputConstants.MOUSE_BUTTON_LEFT);
            });
            context.waitTicks(1);
            Buttons before = context.computeOnClient(client -> capture(screen, state.selectedSource().entity().uuid()));
            ClientNbtQuery pendingRoot = context.computeOnClient(client -> {
                require(before.compoundPin().getMessage().getString().equals(Component.translatable("codon.nbt.unpin").getString()),
                    "The selected NBT pin is visibly retained before refresh");
                client.setLastInputType(InputType.KEYBOARD_TAB);
                screen.setFocused(before.compound());
                require(screen.getFocused() == before.compound(), "Compound toggle owns keyboard focus before refresh");
                state.nbt().refresh(before.executor());
                return rootQuery(state, before.executor());
            });
            context.waitTicks(2);
            now.set(100_000_000L);
            context.waitTicks(2);
            context.takeScreenshot("codon-nbt-buttons-pending");

            context.runOnClient(client -> {
                Buttons pending = capture(screen, before.executor());
                assertRetained(before, pending);
                require(screen.getFocused() == before.compound(), "Refresh does not drop compound keyboard focus");
                require(before.compound().inputBlocked() && before.list().inputBlocked()
                        && before.compoundPin().inputBlocked() && before.listPin().inputBlocked(),
                    "Retained compound/list controls block input while raw rows are pending");
                String expanded = before.compound().getMessage().getString();
                click(screen, before.compound(), InputConstants.MOUSE_BUTTON_LEFT);
                click(screen, before.compoundPin(), InputConstants.MOUSE_BUTTON_RIGHT);
                before.compound().onPress(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0));
                require(before.compound().getMessage().getString().equals(expanded),
                    "Blocked pointer and keyboard activation cannot collapse the retained compound");
                require(before.compoundPin().getMessage().getString().equals(Component.translatable("codon.nbt.unpin").getString()),
                    "Blocked right-click cannot change retained pin selection");
            });

            context.runOnClient(client -> state.nbt().accept(pendingRoot.pauseId(), pendingRoot.requestId(),
                new NbtPage(WatchResult.Status.VALUE, ROOT, 0, ROOT.size())));
            context.waitTicks(2);
            context.runOnClient(client -> {
                Buttons rootRestored = capture(screen, before.executor());
                require(!rootRestored.compound().inputBlocked() && !rootRestored.list().inputBlocked(),
                    "A fresh root reply immediately restores compound/list input");
                require(rootRestored.compoundChild().inputBlocked(),
                    "A retained child remains blocked until its own page reply arrives");
                state.nbt().accept(701, childQuery(state, rootRestored.executor()).requestId(),
                    new NbtPage(WatchResult.Status.VALUE, COMPOUND, 0, COMPOUND.size()));
            });
            context.waitTicks(2);
            context.takeScreenshot("codon-nbt-buttons-restored");

            context.runOnClient(client -> {
                Buttons restored = capture(screen, before.executor());
                require(!restored.compoundChild().inputBlocked(), "Fresh child reply immediately restores child input");
                click(screen, restored.compound(), InputConstants.MOUSE_BUTTON_LEFT);
                require(state.nbt().rows(restored.executor()).stream().noneMatch(row -> row.path().equals("compound.nested")),
                    "Restored compound toggle performs its normal action");
                state.nbt().refresh(restored.executor());
                rootQuery(state, restored.executor());
            });
            now.set(350_000_000L);
            context.waitTicks(2);
            context.takeScreenshot("codon-nbt-buttons-waiting");
            context.runOnClient(client -> {
                require(buttonOrNull(screen, "▸ compound: {}") == null && buttonOrNull(screen, "▸ list: []") == null,
                    "Expired retained rows no longer leave stale compound/list controls onscreen");
                require(screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
                        .noneMatch(button -> button.getMessage().getString().equals(Component.translatable("codon.nbt.refresh").getString()) && !button.active),
                    "Pending NBT status does not replace retained controls with a disabled Refresh button");
                client.setScreenAndShow(null);
            });
        }
    }

    private static void loadRoot(ClientGameTestContext context, ClientDebuggerState state) {
        context.runOnClient(client -> {
            var executor = state.selectedSource().entity().uuid();
            var query = rootQuery(state, executor);
            state.nbt().accept(query.pauseId(), query.requestId(), new NbtPage(WatchResult.Status.VALUE, ROOT, 0, ROOT.size()));
        });
    }

    private static void expandCompound(ClientGameTestContext context, CodonScreen screen) {
        context.runOnClient(client -> click(screen, button(screen, "▸ compound: {}"), InputConstants.MOUSE_BUTTON_LEFT));
    }

    private static void loadCompound(ClientGameTestContext context, ClientDebuggerState state) {
        context.runOnClient(client -> {
            var executor = state.selectedSource().entity().uuid();
            var query = childQuery(state, executor);
            state.nbt().accept(query.pauseId(), query.requestId(), new NbtPage(WatchResult.Status.VALUE, COMPOUND, 0, COMPOUND.size()));
        });
    }

    private static Buttons capture(CodonScreen screen, java.util.UUID executor) {
        DebuggerButton compound = button(screen, "▾ compound: {}");
        DebuggerButton list = button(screen, "▸ list: []");
        return new Buttons(compound, list, pinAt(screen, compound), pinAt(screen, list),
            button(screen, "▸ nested: {}"), executor, bounds(compound), bounds(list),
            bounds(pinAt(screen, compound)), bounds(pinAt(screen, list)));
    }

    private static void assertRetained(Buttons before, Buttons pending) {
        require(before.compound() == pending.compound() && before.list() == pending.list()
                && before.compoundPin() == pending.compoundPin() && before.listPin() == pending.listPin(),
            "Pending render reuses the exact compound/list control instances");
        require(before.compoundBounds().equals(bounds(pending.compound())) && before.listBounds().equals(bounds(pending.list()))
                && before.compoundPinBounds().equals(bounds(pending.compoundPin())) && before.listPinBounds().equals(bounds(pending.listPin())),
            "Pending render keeps compound/list toggle and pin bounds stable");
        require(before.compound().active && before.list().active && before.compoundPin().active && before.listPin().active,
            "Retained controls keep their active visual style");
    }

    private static ClientNbtQuery rootQuery(ClientDebuggerState state, java.util.UUID executor) {
        var query = state.nbt().drainQueries().stream().filter(value -> value.executor().equals(executor) && value.path().isEmpty())
            .findFirst().orElseThrow(() -> new AssertionError("Root NBT query is queued"));
        return new ClientNbtQuery(query.pauseId(), query.requestId());
    }

    private static ClientNbtQuery childQuery(ClientDebuggerState state, java.util.UUID executor) {
        var query = state.nbt().drainQueries().stream().filter(value -> value.executor().equals(executor) && value.path().equals("compound"))
            .findFirst().orElseThrow(() -> new AssertionError("Compound child NBT query is queued"));
        return new ClientNbtQuery(query.pauseId(), query.requestId());
    }

    private static DebuggerButton pinAt(CodonScreen screen, DebuggerButton node) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.icon() == DebuggerIcon.PIN && button.getY() == node.getY()).findFirst()
            .orElseThrow(() -> new AssertionError("NBT node pin button is rendered"));
    }

    private static DebuggerButton button(CodonScreen screen, String label) {
        DebuggerButton result = buttonOrNull(screen, label);
        if (result == null) throw new AssertionError("NBT button missing: " + label);
        return result;
    }

    private static DebuggerButton buttonOrNull(CodonScreen screen, String label) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.getMessage().getString().equals(label)).findFirst().orElse(null);
    }

    private static void click(CodonScreen screen, DebuggerButton button, int mouseButton) {
        net.minecraft.client.Minecraft.getInstance().setLastInputType(InputType.MOUSE);
        MouseButtonEvent event = new MouseButtonEvent(button.getX() + button.getWidth() / 2.0,
            button.getY() + button.getHeight() / 2.0, new MouseButtonInfo(mouseButton, 0));
        screen.mouseClicked(event, false);
        screen.mouseReleased(event);
    }

    private static Bounds bounds(DebuggerButton button) {
        return new Bounds(button.getX(), button.getY(), button.getWidth(), button.getHeight());
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private record ClientNbtQuery(long pauseId, long requestId) { }
    private record Bounds(int x, int y, int width, int height) { }
    private record Buttons(DebuggerButton compound, DebuggerButton list, DebuggerButton compoundPin,
                           DebuggerButton listPin, DebuggerButton compoundChild, java.util.UUID executor,
                           Bounds compoundBounds, Bounds listBounds, Bounds compoundPinBounds, Bounds listPinBounds) { }
}
