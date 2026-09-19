package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.gui.screens.Screen;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.WatchGrouping;
import works.nuty.codon.client.ui.*;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.Vec3d;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

@SuppressWarnings("UnstableApiUsage")
public final class DebuggerWatchGroupingGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1280, 800);
            var state = new ClientDebuggerState();
            var definitions = List.of(
                new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health", new UUID(0, 1)),
                new WatchSpec(WatchSpec.Kind.SCORE, "points", "", new UUID(0, 1)),
                new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health", new UUID(0, 2)),
                new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:state", "count"),
                new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:state", "nested.value"));
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
                state.applyPause(DebuggerPresentationGameTest.fixture(client));
                state.watches().addAll(definitions);
                var overlay = new DebuggerOverlay(state);
                client.setScreenAndShow(new CodonScreen(DebuggerPresentationGameTest.input(client, state), overlay));
            });
            context.waitTicks(3);
            context.takeScreenshot("codon-group-context");
            context.runOnClient(client -> assertExecutorLayout(client.gui.screen(), definitions, WatchGrouping.Mode.CONTEXT));
            String current = "Context";
            for (String label : List.of("Path", "No group", "Context")) {
                String previous = current;
                context.runOnClient(client -> {
                    click(client.gui.screen(), previous);
                    require(client.gui.screen() instanceof WatchGroupingScreen, "header opens explicit grouping choices");
                    click(client.gui.screen(), label);
                    require(client.gui.screen() instanceof CodonScreen, "selection returns to Watches");
                    require(state.watches().definitions().equals(definitions), "grouping does not change definitions or bindings");
                });
                context.waitTicks(3);
                context.runOnClient(client -> require(state.watches().grouping() == switch(label) {
                    case "Path" -> WatchGrouping.Mode.PATH;
                    case "No group" -> WatchGrouping.Mode.NONE;
                    default -> WatchGrouping.Mode.CONTEXT;
                }, "chosen grouping remains active"));
                context.runOnClient(client -> assertExecutorLayout(client.gui.screen(), definitions, state.watches().grouping()));
                context.takeScreenshot("codon-group-" + label.toLowerCase().replace(' ', '-'));
                current = label;
            }
            context.getInput().resizeWindow(640, 480);
            context.waitTicks(3);
            context.runOnClient(client -> click(client.gui.screen(), "Context"));
            context.waitTicks(3);
            context.takeScreenshot("codon-group-chooser-compact");
            context.runOnClient(client -> {
                var screen = client.gui.screen();
                require(screen.width >= 300, "compact GUI fixture");
                screen.keyPressed(new KeyEvent(InputConstants.KEY_TAB, InputConstants.KEYCODE_TAB, 0));
                screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0));
                require(client.gui.screen() instanceof CodonScreen && state.watches().grouping() == WatchGrouping.Mode.PATH,
                    "Tab and Enter select the next grouping by keyboard");
                client.setScreenAndShow(null);
            });

            checkNoExecutorRows(context);
        }
    }

    /** A positional pause has no executor: context-following entity watches must remain explicit and compact. */
    private static void checkNoExecutorRows(ClientGameTestContext context) {
        var entity = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health");
        var score = new WatchSpec(WatchSpec.Kind.SCORE, "points", "");
        var storage = new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:state", "count");
        var missingStorage = new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:quiet", "count");
        var definitions = List.of(entity, score, storage, missingStorage);
        var state = new ClientDebuggerState();
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(client -> {
            state.applyPause(noExecutorPause(client));
            state.watches().addAll(definitions);
            for (var query : state.watches().drainQueries()) {
                WatchResult result = query.spec().kind() == WatchSpec.Kind.STORAGE_NBT
                    ? query.spec().equals(storage)
                        ? new WatchResult(WatchResult.Status.VALUE, "1", "storage:demo:state")
                        : WatchResult.absent(WatchResult.Status.VALUE_MISSING, "storage:demo:quiet")
                    : WatchResult.absent(WatchResult.Status.NO_EXECUTOR, "no-executor");
                state.watches().accept(query.pauseId(), query.requestId(), result);
            }
            require(state.watches().entries().stream().anyMatch(entry -> entry.spec().equals(entity)
                    && entry.result() != null && entry.result().status() == WatchResult.Status.NO_EXECUTOR),
                "the no-executor result is delivered before rendering the context-following Entity NBT row");
            require(state.watches().entries().stream().anyMatch(entry -> entry.spec().equals(score)
                    && entry.result() != null && entry.result().status() == WatchResult.Status.NO_EXECUTOR),
                "the no-executor result is delivered before rendering the context-following Score row");
            require(state.watches().entries().stream().anyMatch(entry -> entry.spec().equals(missingStorage)
                    && entry.result() != null && entry.result().status() == WatchResult.Status.VALUE_MISSING),
                "the unchanged unset result is delivered before rendering the Storage row");
            client.setScreenAndShow(new CodonScreen(DebuggerPresentationGameTest.input(client, state), new DebuggerOverlay(state)));
        });
        context.waitTicks(3);
        context.runOnClient(client -> {
            assertNoExecutorLayout(client.gui.screen(), definitions);
            requireBefore(client.gui.screen(), entity, storage, "an inactive real context group retains its position");
            requireBefore(client.gui.screen(), score, storage, "the inactive context sibling stays with its real group");
            requireBefore(client.gui.screen(), storage, missingStorage, "the unchanged unset Storage singleton moves to the bottom");
        });
        context.takeScreenshot("codon-group-no-executor-context");
        context.runOnClient(client -> {
            click(client.gui.screen(), "Context");
            click(client.gui.screen(), "No group");
            require(state.watches().grouping() == WatchGrouping.Mode.NONE, "No group remains selected for no-executor watches");
        });
        context.waitTicks(3);
        context.runOnClient(client -> {
            assertNoExecutorLayout(client.gui.screen(), definitions);
            requireBefore(client.gui.screen(), storage, entity, "the active Storage row precedes inactive rows without grouping");
            requireBefore(client.gui.screen(), storage, score, "the active Storage row precedes an inactive Score");
            requireBefore(client.gui.screen(), storage, missingStorage, "the active Storage row precedes an unchanged unset Storage");
        });
        context.takeScreenshot("codon-group-no-executor-no-group");
        context.runOnClient(client -> client.setScreenAndShow(null));
    }

    private static PauseSnapshot noExecutorPause(net.minecraft.client.Minecraft client) {
        PauseSnapshot base = DebuggerPresentationGameTest.fixture(client);
        PauseSource positionOnly = new PauseSource(new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ()),
            0, 0, null, "minecraft:overworld");
        return new PauseSnapshot(base.location(), base.command(), base.depth(), List.of(), List.of(positionOnly),
            List.of(), base.reason(), 500);
    }

    private static void assertExecutorLayout(Screen screen, List<WatchSpec> definitions, WatchGrouping.Mode mode) {
        switch (mode) {
            case CONTEXT -> {
                requireHeights(screen, definitions.get(0), 17, 27);
                requireHeights(screen, definitions.get(1), 17);
            }
            case PATH -> {
                requireHeights(screen, definitions.get(0), 17, 17);
                requireHeights(screen, definitions.get(1), 27);
            }
            case NONE -> {
                requireHeights(screen, definitions.get(0), 27, 27);
                requireHeights(screen, definitions.get(1), 27);
            }
        }
        requireHeights(screen, definitions.get(3), 17);
        requireHeights(screen, definitions.get(4), 17);
    }

    private static void assertNoExecutorLayout(Screen screen, List<WatchSpec> definitions) {
        requireHeights(screen, definitions.get(0), 17);
        requireHeights(screen, definitions.get(1), 17);
        requireHeights(screen, definitions.get(2), 17);
        requireHeights(screen, definitions.get(3), 17);
    }

    private static void requireHeights(Screen screen, WatchSpec spec, int... expected) {
        String inspected = "Inspect watch: " + WatchFormatting.specification(spec).getString();
        List<DebuggerButton> rows = screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.getMessage().getString().equals(inspected))
            .sorted(Comparator.comparingInt(DebuggerButton::getY)).toList();
        require(rows.size() == expected.length, "exactly " + expected.length + " inspect row(s) for " + spec);
        for (int index = 0; index < expected.length; index++)
            require(rows.get(index).getHeight() == expected[index], "inspect row height for " + spec + " is " + expected[index]);
    }
    private static void requireBefore(Screen screen, WatchSpec first, WatchSpec second, String message) {
        require(watchRows(screen, first).getFirst().getY() < watchRows(screen, second).getFirst().getY(), message);
    }
    private static List<DebuggerButton> watchRows(Screen screen, WatchSpec spec) {
        String inspected = "Inspect watch: " + WatchFormatting.specification(spec).getString();
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.getMessage().getString().equals(inspected))
            .sorted(Comparator.comparingInt(DebuggerButton::getY)).toList();
    }
    private static void click(Screen screen, String label) {
        var button = screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(value -> value.getMessage().getString().equals(label)).findFirst().orElseThrow();
        var event = new MouseButtonEvent(button.getX() + 2, button.getY() + 2, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        require(screen.mouseClicked(event, false), "click " + label);
        screen.mouseReleased(event);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
