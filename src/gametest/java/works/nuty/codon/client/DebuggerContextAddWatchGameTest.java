package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.adapter.SourceMapper;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.ScreenLayers;
import works.nuty.codon.client.ui.WatchFormatting;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.core.service.CommandStageEvent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Contexts' Add to Watch against a real paused server: the row action and its menu entry open the Watch row with
 * {@code entity }, path completion lists the selected context's NBT keys, and Enter adds an unbound
 * {@code entity <path>} watch that reads a value. Position-only contexts have no action; a pending control disables it.
 */
@SuppressWarnings({"UnstableApiUsage", "unchecked"})
public final class DebuggerContextAddWatchGameTest implements FabricClientGameTest {
    private static final BlockLocation BREAKPOINT = new BlockLocation(46, 80, 0, "minecraft:overworld");
    private static final WatchSpec HEALTH = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health");

    @Override
    public void runTest(ClientGameTestContext context) {
        int oldScale = context.computeOnClient(client -> client.options.guiScale().get());
        String oldLanguage = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            world.getServer().runCommand("summon minecraft:armor_stand 46 80 0 {NoGravity:1b,CustomName:'\"add-watch\"'}");
            MinecraftServer server = world.getServer().computeOnServer(s -> s);
            AtomicReference<ArmorStand> stand = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean completed = new AtomicBoolean();
            world.getServer().runOnServer(s -> {
                stand.set(s.overworld().getEntitiesOfClass(ArmorStand.class, new AABB(45, 78, -2, 47, 84, 2)).getFirst());
                var player = s.getPlayerList().getPlayers().getFirst();
                s.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
                CodonMod.engine().clearBreakpoints();
            });
            context.runOnClient(client -> CodonClientMod.state().reset());
            try {
                server.execute(() -> {
                    var engine = CodonMod.engine();
                    engine.onExecutionStarted();
                    try {
                        List<PauseSource> sources = SourceMapper.toPauseSources(List.of(server.createCommandSourceStack(),
                            server.createCommandSourceStack().withEntity(stand.get()).withPosition(stand.get().position())));
                        engine.toggleBlockBreakpoint(BREAKPOINT);
                        engine.onCommandStage(new CommandStageEvent(930001, 0, new SourceLocation.Block(BREAKPOINT),
                            CommandSnippet.plain("data get entity @s Health"), () -> sources));
                    } catch (Throwable problem) {
                        failure.set(problem);
                    } finally {
                        engine.onExecutionFinished(failure.get() == null);
                        engine.clearBreakpoints();
                        completed.set(true);
                    }
                });
                context.waitFor(client -> CodonClientMod.state().isPaused()
                    && CodonClientMod.state().snapshot() != null
                    && CodonClientMod.state().snapshot().pauseSources().size() == 2, 200);

                language(context, "en_us");
                open(context, 1280, 900);
                select(context, 0);
                context.runOnClient(client -> require(addWatch(client.gui.screen()) == null,
                    "a position-only context has no Add to Watch action"));
                rightClickRow(context, 0);
                context.runOnClient(client -> {
                    require(WatchGameTestUi.action(client.gui.screen(), "codon.ui.contexts.add_watch") == null,
                        "a position-only context's menu has no Add to Watch entry");
                    ScreenLayers.get(client.gui.screen()).onClose();
                });
                context.waitTicks(2);

                // Row action: the Watch row opens with "entity " and completes the context's own NBT keys.
                select(context, 1);
                DebuggerButton action = context.computeOnClient(client -> addWatch(client.gui.screen()));
                require(action != null && action.active, "the selected entity context shows an enabled Add to Watch action");
                WatchGameTestUi.click(context, action, InputConstants.MOUSE_BUTTON_LEFT);
                context.waitTicks(2);
                context.runOnClient(client -> {
                    EditBox box = box(client);
                    require(box.getValue().equals("entity "), "Add to Watch prefills the entity keyword");
                    require(box.getCursorPosition() == "entity ".length(), "the cursor waits in the path slot");
                    require(client.gui.screen().getFocused() == box, "the Watch row has keyboard focus");
                });
                // The server answers with this context's NBT keys; the row lists the first few.
                context.waitFor(client -> completionReady(), 200);
                context.waitTicks(3);
                context.takeScreenshot("codon-context-add-watch-completion");
                context.runOnClient(client -> box(client).setValue("entity Heal"));
                context.waitFor(client -> hasButton(client, "Health") && !hasButton(client, "NoGravity"), 200);
                context.runOnClient(client -> {
                    key(client, InputConstants.KEY_TAB);
                    require(box(client).getValue().equals("entity Health"), "Tab completes the context's NBT key");
                    key(client, InputConstants.KEY_RETURN);
                    require(CodonClientMod.state().watches().definitions().contains(HEALTH),
                        "Enter adds an unbound entity <path> watch, not one bound to the context's UUID");
                });
                context.waitFor(client -> health() != null && health().result() != null
                    && health().result().status() == WatchResult.Status.VALUE, 200);
                context.waitTicks(3);
                context.runOnClient(client -> {
                    var entry = health();
                    require(entry.result().targetKey().equals("entity:" + stand.get().getUUID()),
                        "the unbound watch reads the selected entity context");
                    require(WatchFormatting.line(entry, true).getString().contains("Health: " + entry.result().value()),
                        "the row shows the path and its value");
                    require(client.gui.screen().children().stream().noneMatch(EditBox.class::isInstance),
                        "Enter closes the Watch row");
                });
                context.takeScreenshot("codon-context-add-watch-row");

                // Menu entry: the same action through right-click; the menu dismisses before the row opens.
                rightClickRow(context, 1);
                context.runOnClient(client -> require(WatchGameTestUi.action(client.gui.screen(),
                    "codon.ui.contexts.add_watch") != null, "the entity context's menu offers Add to Watch"));
                context.takeScreenshot("codon-context-add-watch-menu");
                WatchGameTestUi.choose(context, "codon.ui.contexts.add_watch");
                context.waitTicks(2);
                context.runOnClient(client -> {
                    require(ScreenLayers.get(client.gui.screen()) == null, "the menu closes");
                    require(box(client).getValue().equals("entity "), "the menu entry opens the same entity row");
                    require(client.gui.screen().getFocused() == box(client), "menu restoration does not steal the row's focus");
                    key(client, InputConstants.KEY_ESCAPE);
                });
                context.waitTicks(2);

                // A pending control disables the action and its menu entry; nothing opens.
                context.runOnClient(client -> require(CodonClientMod.state().beginControlRequest(),
                    "fixture marks a control request pending"));
                context.waitTicks(2);
                context.runOnClient(client -> {
                    DebuggerButton pending = addWatch(client.gui.screen());
                    require(pending != null && !pending.active, "a pending control disables Add to Watch");
                });
                rightClickRow(context, 1);
                context.takeScreenshot("codon-context-add-watch-pending-menu");
                context.runOnClient(client -> {
                    var item = menuEntry(client.gui.screen(), "codon.ui.contexts.add_watch");
                    require(item != null, "the entity context's menu keeps Add to Watch while a control is pending");
                    require(!item.active, "a pending control disables the menu entry");
                    ScreenLayers.get(client.gui.screen()).onClose();
                    CodonClientMod.state().failWatchReads();
                });
                context.waitTicks(2);

                // Korean at the 320x240 viewport: Contexts sits in the View drawer, and opening the row shows Watches.
                language(context, "ko_kr");
                open(context, 640, 480);
                openCompactContexts(context);
                context.takeScreenshot("codon-context-add-watch-ko-compact-contexts");
                DebuggerButton korean = context.computeOnClient(client -> addWatch(client.gui.screen()));
                require(korean != null && korean.active, "the compact Korean Contexts row shows Add to Watch");
                WatchGameTestUi.click(context, korean, InputConstants.MOUSE_BUTTON_LEFT);
                context.waitFor(client -> completionReady(), 200);
                context.waitTicks(3);
                context.runOnClient(client -> require(box(client).getValue().equals("entity "),
                    "the compact Korean action opens the entity row"));
                context.takeScreenshot("codon-context-add-watch-ko-compact-completion");
                context.runOnClient(client -> key(client, InputConstants.KEY_ESCAPE));
            } finally {
                AtomicBoolean cleaned = new AtomicBoolean();
                DebuggerTaskQueue.execute(server, () -> {
                    CodonMod.engine().clearBreakpoints();
                    CodonMod.engine().resetSession();
                    cleaned.set(true);
                });
                context.waitFor(client -> completed.get() && cleaned.get(), 200);
                context.runOnClient(client -> {
                    client.setScreenAndShow(null);
                    client.options.guiScale().set(oldScale);
                    client.resizeGui();
                });
                language(context, oldLanguage);
            }
            if (failure.get() != null) throw new AssertionError("Server fixture failed", failure.get());
        }
    }

    private static void open(ClientGameTestContext context, int width, int height) {
        context.getInput().resizeWindow(width, height);
        context.runOnClient(client -> {
            client.options.guiScale().set(2);
            client.resizeGui();
            client.gui.hud.getChat().clearMessages(false);
            CodonClientMod.state().preferences().setInspectorVisible(true);
            client.setScreenAndShow(new CodonScreen(DebuggerPresentationGameTest.input(client, CodonClientMod.state()),
                new DebuggerOverlay(CodonClientMod.state())));
        });
        context.waitTicks(3);
    }

    private static void openCompactContexts(ClientGameTestContext context) {
        context.runOnClient(client -> require(addWatch(client.gui.screen()) == null,
            "the compact layout initially folds the Contexts section"));
        WatchGameTestUi.click(context, context.computeOnClient(client -> control(client.gui.screen(), "codon.ui.view")),
            InputConstants.MOUSE_BUTTON_LEFT);
        WatchGameTestUi.click(context, context.computeOnClient(client -> control(client.gui.screen(), "codon.ui.contexts")),
            InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(3);
    }

    private static void select(ClientGameTestContext context, int index) {
        context.runOnClient(client -> CodonClientMod.state().selectSource(index));
        context.waitTicks(3);
    }

    private static void rightClickRow(ClientGameTestContext context, int index) {
        DebuggerButton row = context.computeOnClient(client -> overlayButton(client.gui.screen(), "source-" + index));
        require(row != null, "context row " + index + " is visible");
        WatchGameTestUi.click(context, row, InputConstants.MOUSE_BUTTON_RIGHT);
        context.runOnClient(client -> require(ScreenLayers.get(client.gui.screen()) != null,
            "right-click opens the context menu"));
    }

    private static DebuggerButton addWatch(Screen screen) { return overlayButton(screen, "add-watch"); }

    /** The overlay's control for a stable id, only while it is drawn on the screen. */
    private static DebuggerButton overlayButton(Screen screen, String id) {
        var overlay = (DebuggerOverlay) FunctionLineBreakpointGameTest.field(screen, "overlay");
        var buttons = (Map<String, DebuggerButton>) FunctionLineBreakpointGameTest.field(overlay, "buttonCache");
        DebuggerButton button = buttons.get(id);
        return button != null && button.visible && screen.children().contains(button) ? button : null;
    }

    /** A menu entry by its label text, enabled or not. */
    private static DebuggerButton menuEntry(Screen parent, String key) {
        String label = Component.translatable(key).getString();
        return ScreenLayers.get(parent).children().stream().filter(DebuggerButton.class::isInstance)
            .map(DebuggerButton.class::cast).filter(button -> button.visible && button.getMessage().getString().equals(label))
            .findFirst().orElse(null);
    }

    private static DebuggerButton control(Screen screen, String key) {
        String label = Component.translatable(key).getString();
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.visible && button.getMessage().getString().endsWith(label)).findFirst()
            .orElseThrow(() -> new AssertionError("Visible control: " + key));
    }

    private static works.nuty.codon.client.state.ClientWatchState.Entry health() {
        return CodonClientMod.state().watches().entries().stream().filter(entry -> entry.spec().equals(HEALTH))
            .findFirst().orElse(null);
    }

    private static boolean completionReady() {
        var page = CodonClientMod.state().watchEditor().page();
        return page != null && page.status() == WatchResult.Status.VALUE && !page.options().isEmpty();
    }

    private static EditBox box(Minecraft client) {
        return client.gui.screen().children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
            .findFirst().orElseThrow(() -> new AssertionError("the Watch expression row is open"));
    }

    private static boolean hasButton(Minecraft client, String label) {
        return client.gui.screen() != null && client.gui.screen().children().stream().filter(DebuggerButton.class::isInstance)
            .map(DebuggerButton.class::cast).anyMatch(button -> button.visible && button.getMessage().getString().startsWith(label));
    }

    private static void key(Minecraft client, int key) {
        client.gui.screen().keyPressed(new KeyEvent(key, 0, 0));
    }

    private static void language(ClientGameTestContext context, String language) {
        if (context.computeOnClient(client -> client.getLanguageManager().getSelected().equals(language))) return;
        var reload = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected(language);
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 200);
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
