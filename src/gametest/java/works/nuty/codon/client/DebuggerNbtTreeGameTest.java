package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.adapter.SourceMapper;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientNbtState;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerIcon;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.WatchScreen;
import works.nuty.codon.client.ui.WatchFormatting;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.core.service.CommandStageEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the lazy NBT tree through the real paused-server query transport and overlay controls. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerNbtTreeGameTest implements FabricClientGameTest {
    private static final BlockLocation BREAKPOINT = new BlockLocation(40, 80, 0, "minecraft:overworld");
    private static final String UUID_LEAF = "\"UUID\"[0]";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            world.getServer().runCommand("summon minecraft:armor_stand 40 80 0 {NoGravity:1b,CustomName:'\"nbt-a\"'}");
            world.getServer().runCommand("summon minecraft:armor_stand 42 80 0 {NoGravity:1b,CustomName:'\"nbt-b\"'}");
            MinecraftServer server = world.getServer().computeOnServer(s -> s);
            AtomicReference<ArmorStand> first = new AtomicReference<>();
            AtomicReference<ArmorStand> second = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean completed = new AtomicBoolean();
            world.getServer().runOnServer(s -> {
                List<ArmorStand> stands = s.overworld().getEntitiesOfClass(ArmorStand.class,
                    new AABB(39, 78, -2, 43, 84, 2)).stream().sorted(java.util.Comparator.comparingDouble(ArmorStand::getX)).toList();
                first.set(stands.getFirst());
                second.set(stands.get(1));
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
                        List<PauseSource> sources = SourceMapper.toPauseSources(List.of(
                            server.createCommandSourceStack(),
                            server.createCommandSourceStack().withEntity(first.get()).withPosition(first.get().position()),
                            server.createCommandSourceStack().withEntity(second.get()).withPosition(second.get().position())));
                        engine.toggleBlockBreakpoint(BREAKPOINT);
                        engine.onCommandStage(new CommandStageEvent(920001, 0, new SourceLocation.Block(BREAKPOINT),
                            CommandSnippet.plain("data get entity @s UUID"), () -> sources));
                        List<PauseSource> reordered = List.of(sources.get(0), sources.get(2), sources.get(1));
                        engine.onCommandStage(new CommandStageEvent(920002, 0, new SourceLocation.Block(BREAKPOINT),
                            CommandSnippet.plain("say reordered sources"), () -> reordered));
                        engine.onCommandStage(new CommandStageEvent(920003, 0, new SourceLocation.Block(BREAKPOINT),
                            CommandSnippet.plain("say no entity sources"), () -> List.of(sources.get(0))));
                        engine.onCommandStage(new CommandStageEvent(920004, 0, new SourceLocation.Block(BREAKPOINT),
                            CommandSnippet.plain("say returning sources"), () -> sources));
                    } catch (Throwable problem) {
                        failure.set(problem);
                    } finally {
                        engine.onExecutionFinished(failure.get() == null);
                        engine.clearBreakpoints();
                        completed.set(true);
                    }
                });

                context.waitFor(client -> rootReady(first.get().getUUID(), second.get().getUUID()), 200);
                assertInitialSource(context);
                openCodonScreen(context);
                context.takeScreenshot("codon-nbt-tree-empty-watch-plus");
                checkWatchEditorRoute(context);
                collapseAndExpandHeader(context);
                expandAndPinUuidLeaf(context, first.get().getUUID(), "A");
                context.runOnClient(client -> require(CodonClientMod.state().watches().definitions().size() == 1,
                    "left-click adds only the field's own source"));
                expandPosBranch(context, first.get().getUUID());

                collapseSource(context, 2);
                expandSource(context, 3);
                expandAndPinUuidLeaf(context, second.get().getUUID(), "B", true);
                context.waitFor(client -> pinsReady(first.get().getUUID(), second.get().getUUID()), 200);
                context.runOnClient(client -> {
                    WatchSpec a = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", UUID_LEAF, first.get().getUUID());
                    WatchSpec b = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", UUID_LEAF, second.get().getUUID());
                    require(CodonClientMod.state().watches().entries().stream().filter(entry -> entry.spec().equals(a) || entry.spec().equals(b)).count() == 2,
                        "the same leaf path pins independently for source A and source B");
                    require(watch(a).result().status() == WatchResult.Status.VALUE && watch(b).result().status() == WatchResult.Status.VALUE,
                        "both UUID-bound leaf watches evaluate while the non-entity source remains selected");
                    require(watch(a).result().targetKey().equals("entity:" + first.get().getUUID())
                            && watch(b).result().targetKey().equals("entity:" + second.get().getUUID()),
                        "each pin keeps the executor it was created for");
                    for (WatchSpec spec : List.of(a, b)) {
                        var entry = watch(spec);
                        String expectedPrefix = entry.result().targetName() + " #"
                            + spec.executor().toString().substring(0, 8) + " · " + UUID_LEAF + ": ";
                        require(WatchFormatting.line(entry, true).getString().equals(expectedPrefix + entry.result().value()),
                            "entity watches show the actual executor, then the field and value without a selector");
                    }
                });
                assertInitialSource(context);
                // Additional pinned rows resize the NBT viewport; reveal the leaf again before clicking it.
                showNode(context, second.get().getUUID(), "  [0]:");
                context.runOnClient(client -> {
                    CodonScreen screen = codonScreen(client.gui.screen());
                    DebuggerButton leaf = button(screen, message -> message.startsWith("  [0]:"));
                    DebuggerButton pin = pinBeside(screen, leaf);
                    click(screen, pin, InputConstants.MOUSE_BUTTON_RIGHT);
                    require(CodonClientMod.state().watches().definitions().isEmpty(),
                        "a second right-click removes the whole current-source group");
                    click(screen, pin, InputConstants.MOUSE_BUTTON_RIGHT);
                    require(CodonClientMod.state().watches().definitions().size() == 2,
                        "another right-click restores the whole current-source group");
                });
                showNode(context, second.get().getUUID(), "  NoGravity:");
                context.runOnClient(client -> {
                    CodonScreen screen = codonScreen(client.gui.screen());
                    click(screen, pinBeside(screen, button(screen, message -> message.startsWith("  NoGravity:"))), InputConstants.MOUSE_BUTTON_RIGHT);
                    require(CodonClientMod.state().watches().definitions().size() == 4,
                        "a second field adds one independent watch per entity source");
                });
                context.getInput().setCursorPos(1275, 5);
                context.waitTicks(3);
                context.takeScreenshot("codon-nbt-tree-all-sources-four-watches");
                context.runOnClient(client -> {
                    CodonScreen screen = codonScreen(client.gui.screen());
                    click(screen, pinBeside(screen, button(screen, message -> message.startsWith("  NoGravity:"))), InputConstants.MOUSE_BUTTON_RIGHT);
                    require(CodonClientMod.state().watches().definitions().size() == 2
                        && CodonClientMod.state().watches().definitions().stream().allMatch(spec -> spec.path().equals(UUID_LEAF)),
                        "group removal preserves other NBT paths");
                });
                toggleUuidLeafPin(context, second.get().getUUID());
                context.runOnClient(client -> {
                    WatchSpec a = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", UUID_LEAF, first.get().getUUID());
                    WatchSpec b = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", UUID_LEAF, second.get().getUUID());
                    require(CodonClientMod.state().watches().entries().stream().anyMatch(entry -> entry.spec().equals(a)),
                        "unpinning B retains A's identical-path binding");
                    require(CodonClientMod.state().watches().entries().stream().noneMatch(entry -> entry.spec().equals(b)),
                        "clicking B's UUID leaf pin again removes only B's binding");
                });
                assertInitialSource(context);
                checkExpansionRetention(context, first.get().getUUID(), second.get().getUUID());
            } finally {
                AtomicBoolean cleaned = new AtomicBoolean();
                DebuggerTaskQueue.execute(server, () -> {
                    CodonMod.engine().clearBreakpoints();
                    CodonMod.engine().resetSession();
                    cleaned.set(true);
                });
                context.waitFor(client -> completed.get() && cleaned.get(), 200);
                context.runOnClient(client -> client.setScreenAndShow(null));
            }
            if (failure.get() != null) throw new AssertionError("Server fixture failed", failure.get());
        }
    }

    private static void checkExpansionRetention(ClientGameTestContext context, UUID a, UUID b) {
        context.runOnClient(client -> {
            require(!CodonClientMod.state().nbt().sourceExpanded(a), "A starts collapsed");
            require(CodonClientMod.state().nbt().sourceExpanded(b), "B starts expanded");
            CodonScreen screen = codonScreen(client.gui.screen());
            click(screen, button(screen, label -> label.startsWith("▾ NBT · ")));
        });
        openCodonScreen(context);
        context.runOnClient(client -> require(findButton(codonScreen(client.gui.screen()),
            label -> label.startsWith("▸ NBT · ")) != null, "a recreated overlay keeps NBT collapsed"));
        advance(context, false);
        context.runOnClient(client -> {
            var state = CodonClientMod.state();
            require(state.snapshot().pauseSources().get(1).entity().uuid().equals(b), "sources actually reordered");
            require(!state.nbt().enabled(), "global NBT collapse survives F9");
            require(!state.nbt().sourceExpanded(a) && state.nbt().sourceExpanded(b), "collapse follows UUID after reorder");
        });
        context.waitTicks(3);
        context.takeScreenshot("codon-nbt-collapsed-after-step");
        context.runOnClient(client -> {
            CodonScreen screen = codonScreen(client.gui.screen());
            click(screen, button(screen, label -> label.startsWith("▸ NBT · ")));
        });
        showNode(context, b, "▾ UUID:");
        advance(context, true);
        context.runOnClient(client -> require(CodonClientMod.state().nbt().entitySources().isEmpty(),
            "Continue reaches an intermediate stop without entity sources"));
        advance(context, false);
        context.runOnClient(client -> {
            var nbt = CodonClientMod.state().nbt();
            require(nbt.enabled(), "global NBT expansion survives Continue and the next F9");
            require(!nbt.sourceExpanded(a) && nbt.sourceExpanded(b), "returning entities retain distinct collapse states");
        });
        openCodonScreen(context);
        context.runOnClient(client -> require(findButton(codonScreen(client.gui.screen()),
            label -> label.startsWith("▸ #2 · ")) != null, "returning A is visibly collapsed"));
        context.takeScreenshot("codon-nbt-expansion-preserved");
        showNode(context, b, "▾ UUID:");
    }

    private static void advance(ClientGameTestContext context, boolean resume) {
        long previous = context.computeOnClient(client -> CodonClientMod.state().snapshot().pauseId());
        if (resume) context.runOnClient(client -> client.player.connection.sendCommand("codon resume"));
        else context.getInput().pressKey(InputConstants.KEY_F9);
        context.waitFor(client -> CodonClientMod.state().isPaused() && CodonClientMod.state().snapshot() != null
            && CodonClientMod.state().snapshot().pauseId() != previous, 200);
        context.waitTicks(3);
    }

    private static boolean rootReady(UUID a, UUID b) {
        return CodonClientMod.state().isPaused()
            && CodonClientMod.state().nbt().rows(a).stream().anyMatch(row -> row.kind() == ClientNbtState.Kind.NODE)
            && CodonClientMod.state().nbt().rows(b).stream().anyMatch(row -> row.kind() == ClientNbtState.Kind.NODE);
    }

    private static void assertInitialSource(ClientGameTestContext context) {
        context.runOnClient(client -> {
            require(CodonClientMod.state().selectedSourceIndex() == 0, "selected source remains the no-entity source");
            require(CodonClientMod.state().nbt().executor() == null, "NBT tree does not depend on selected entity source");
        });
    }

    private static void openCodonScreen(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 900);
        context.runOnClient(client -> {
            InputManager input = input();
            client.setScreenAndShow(new CodonScreen(input, new DebuggerOverlay(CodonClientMod.state())));
        });
        context.waitTicks(3);
    }

    private static void checkWatchEditorRoute(ClientGameTestContext context) {
        context.runOnClient(client -> {
            CodonScreen screen = codonScreen(client.gui.screen());
            click(screen, button(screen, message -> message.equals("+")));
            require(client.gui.screen() instanceof WatchScreen, "CodonScreen watch summary plus opens WatchScreen");
            client.gui.screen().onClose();
        });
        context.waitFor(client -> client.gui.screen() instanceof CodonScreen, 50);
        context.waitTicks(3);
    }

    private static void collapseAndExpandHeader(ClientGameTestContext context) {
        context.runOnClient(client -> {
            CodonScreen screen = codonScreen(client.gui.screen());
            click(screen, button(screen, message -> message.startsWith("▾ NBT · ")));
            require(!CodonClientMod.state().nbt().enabled(), "NBT header collapses the tree");
        });
        context.waitTicks(3);
        context.takeScreenshot("codon-nbt-tree-collapsed");
        context.runOnClient(client -> {
            CodonScreen screen = codonScreen(client.gui.screen());
            click(screen, button(screen, message -> message.startsWith("▸ NBT · ")));
            require(CodonClientMod.state().nbt().enabled(), "collapsed NBT header expands the tree");
        });
        context.waitFor(client -> CodonClientMod.state().nbt().entitySources().stream().allMatch(source ->
            !CodonClientMod.state().nbt().rows(source.executor().uuid()).isEmpty()), 100);
    }

    private static void expandAndPinUuidLeaf(ClientGameTestContext context, UUID executor, String sourceName) {
        expandAndPinUuidLeaf(context, executor, sourceName, false);
    }

    private static void expandAndPinUuidLeaf(ClientGameTestContext context, UUID executor, String sourceName, boolean rightClick) {
        showNode(context, executor, "▸ UUID:");
        context.runOnClient(client -> {
            CodonScreen screen = codonScreen(client.gui.screen());
            click(screen, button(screen, message -> message.startsWith("▸ UUID:")));
        });
        context.waitFor(client -> CodonClientMod.state().nbt().rows(executor).stream()
            .anyMatch(row -> row.kind() == ClientNbtState.Kind.NODE && row.path().equals(UUID_LEAF)), 200);
        showNode(context, executor, "  [0]:");
        context.runOnClient(client -> {
            CodonScreen screen = codonScreen(client.gui.screen());
            DebuggerButton leaf = button(screen, message -> message.startsWith("  [0]:"));
            DebuggerButton pin = screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
                .filter(button -> button.icon() == DebuggerIcon.PIN && button.getY() == leaf.getY()).findFirst()
                .orElseThrow(() -> new AssertionError("UUID leaf pin is visible for source " + sourceName));
            click(screen, pin, rightClick ? InputConstants.MOUSE_BUTTON_RIGHT : InputConstants.MOUSE_BUTTON_LEFT);
            WatchSpec expected = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", UUID_LEAF, executor);
            require(CodonClientMod.state().watches().entries().stream().anyMatch(entry -> entry.spec().equals(expected)),
                "pinning UUID leaf binds it to source " + sourceName);
        });
    }

    private static void collapseSource(ClientGameTestContext context, int number) {
        showSource(context, number);
        context.runOnClient(client -> {
            CodonScreen screen = codonScreen(client.gui.screen());
            click(screen, button(screen, message -> message.startsWith("▾ #" + number)));
        });
        context.waitTicks(2);
    }

    private static void expandSource(ClientGameTestContext context, int number) {
        showSource(context, number);
        context.runOnClient(client -> {
            CodonScreen screen = codonScreen(client.gui.screen());
            DebuggerButton collapsed = findButton(screen, message -> message.startsWith("▸ #" + number + " · "));
            if (collapsed != null) click(screen, collapsed);
        });
        context.waitFor(client -> findButton(codonScreen(client.gui.screen()), message -> message.startsWith("▾ #" + number)) != null, 100);
    }

    private static void showSource(ClientGameTestContext context, int number) {
        for (int attempt = 0; attempt < 80; attempt++) {
            AtomicBoolean found = new AtomicBoolean();
            context.runOnClient(client -> {
                CodonScreen screen = codonScreen(client.gui.screen());
                if (findButton(screen, message -> (message.startsWith("▾ #" + number + " · ") || message.startsWith("▸ #" + number + " · "))) != null) { found.set(true); return; }
                DebuggerButton header = findButton(screen, message -> message.startsWith("▾ NBT · "));
                if (header != null) screen.mouseScrolled(header.getX() + 4, header.getY() + 20, 0, number == 2 ? 1 : -1);
            });
            if (found.get()) return;
            context.waitTicks(1);
        }
        throw new AssertionError("NBT source header never became visible: #" + number);
    }

    private static void expandPosBranch(ClientGameTestContext context, UUID executor) {
        showNode(context, executor, "▸ Pos:");
        context.runOnClient(client -> {
            CodonScreen screen = codonScreen(client.gui.screen());
            click(screen, button(screen, message -> message.startsWith("▸ Pos:")));
        });
        context.waitFor(client -> CodonClientMod.state().nbt().rows(executor).stream()
            .anyMatch(row -> row.kind() == ClientNbtState.Kind.NODE && row.path().equals("\"Pos\"[0]")), 200);
    }

    /** Uses the model to choose direction, but navigates through actual rendered scroll/page controls. */
    private static void showNode(ClientGameTestContext context, UUID executor, String prefix) {
        context.waitTicks(2);
        for (int attempt = 0; attempt < 128; attempt++) {
            AtomicBoolean visible = new AtomicBoolean();
            AtomicBoolean paged = new AtomicBoolean();
            context.runOnClient(client -> {
                CodonScreen screen = codonScreen(client.gui.screen());
                if (findButton(screen, message -> message.startsWith(prefix)) != null) {
                    visible.set(true);
                    return;
                }
                List<ClientNbtState.Row> rows = CodonClientMod.state().nbt().rows(executor);
                int target = -1;
                for (int i = 0; i < rows.size(); i++) if (rowLabel(rows.get(i)).startsWith(prefix)) { target = i; break; }
                if (target < 0) {
                    String requestedName = prefix.startsWith("▸ ") || prefix.startsWith("▾ ") ? prefix.substring(2, prefix.indexOf(':')) : "";
                    String firstName = rows.stream().filter(row -> row.kind() == ClientNbtState.Kind.NODE && row.depth() == 0)
                        .map(row -> row.node().name()).findFirst().orElse("");
                    ClientNbtState.Kind direction = requestedName.compareTo(firstName) < 0
                        ? ClientNbtState.Kind.PREVIOUS : ClientNbtState.Kind.NEXT;
                    for (int i = 0; i < rows.size(); i++) if (rows.get(i).kind() == direction && rows.get(i).path().isEmpty()) { target = i; break; }
                    if (target >= 0) {
                        String label = net.minecraft.network.chat.Component.translatable(direction == ClientNbtState.Kind.PREVIOUS
                            ? "codon.nbt.previous" : "codon.nbt.next").getString();
                        DebuggerButton page = findButton(screen, message -> message.equals(label));
                        if (page != null) { click(screen, page); paged.set(true); return; }
                    }
                }
                int firstVisible = 0;
                for (int i = 0; i < rows.size(); i++) {
                    String label = rowLabel(rows.get(i));
                    if (!label.isEmpty() && findButton(screen, message -> message.equals(label)) != null) { firstVisible = i; break; }
                }
                DebuggerButton header = button(screen, message -> message.startsWith("▾ NBT · "));
                screen.mouseScrolled(header.getX() + 4, header.getY() + 28, 0, target >= 0 && target < firstVisible ? 1 : -1);
            });
            if (visible.get()) return;
            if (paged.get()) context.waitFor(client -> CodonClientMod.state().nbt().rows(executor).stream()
                .anyMatch(row -> row.kind() == ClientNbtState.Kind.NODE), 200);
            context.waitTicks(1);
        }
        throw new AssertionError("NBT node never became visible: " + prefix);
    }

    private static String rowLabel(ClientNbtState.Row row) {
        if (row.kind() != ClientNbtState.Kind.NODE) return "";
        return (row.node().expandable() ? row.expanded() ? "▾ " : "▸ " : "  ")
            + row.node().name() + ": " + row.node().preview();
    }

    private static void toggleUuidLeafPin(ClientGameTestContext context, UUID executor) {
        showNode(context, executor, "▾ UUID:");
        showNode(context, executor, "  [0]:");
        context.runOnClient(client -> {
            CodonScreen screen = codonScreen(client.gui.screen());
            DebuggerButton leaf = button(screen, message -> message.startsWith("  [0]:"));
            DebuggerButton pin = screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
                .filter(button -> button.icon() == DebuggerIcon.PIN && button.getY() == leaf.getY()).findFirst().orElseThrow();
            click(screen, pin);
        });
    }

    private static DebuggerButton pinBeside(CodonScreen screen, DebuggerButton field) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.icon() == DebuggerIcon.PIN && button.getY() == field.getY()).findFirst().orElseThrow();
    }

    private static boolean pinsReady(UUID first, UUID second) {
        WatchSpec a = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", UUID_LEAF, first);
        WatchSpec b = new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", UUID_LEAF, second);
        var pins = CodonClientMod.state().watches().entries().stream().filter(entry -> entry.spec().equals(a) || entry.spec().equals(b)).toList();
        return pins.size() == 2 && pins.stream().allMatch(entry -> entry.result() != null);
    }

    private static works.nuty.codon.client.state.ClientWatchState.Entry watch(WatchSpec spec) {
        return CodonClientMod.state().watches().entries().stream().filter(entry -> entry.spec().equals(spec)).findFirst().orElseThrow();
    }

    private static InputManager input() {
        InputManager input = new InputManager(CodonClientMod.state(), ignored -> {});
        for (var key : net.minecraft.client.Minecraft.getInstance().options.keyMappings) {
            switch (key.getName()) {
                case "key.codon.open_menu" -> input.menuKey = key;
                case "key.codon.breakpoint" -> input.breakpointKey = key;
                case "key.codon.resume" -> input.resumeKey = key;
                case "key.codon.step_over" -> input.stepOverKey = key;
                case "key.codon.step_into" -> input.stepIntoKey = key;
            }
        }
        return input;
    }

    private static CodonScreen codonScreen(Screen screen) {
        if (!(screen instanceof CodonScreen result)) throw new AssertionError("Codon screen is open");
        return result;
    }

    private static DebuggerButton button(CodonScreen screen, java.util.function.Predicate<String> matches) {
        DebuggerButton result = findButton(screen, matches);
        if (result != null) return result;
        throw new AssertionError("Expected control is visible; got " + screen.children().stream()
            .filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast).map(button -> button.getMessage().getString()).toList());
    }

    private static DebuggerButton findButton(CodonScreen screen, java.util.function.Predicate<String> matches) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> matches.test(button.getMessage().getString())).findFirst().orElse(null);
    }

    private static void click(Screen screen, DebuggerButton button) {
        click(screen, button, InputConstants.MOUSE_BUTTON_LEFT);
    }

    private static void click(Screen screen, DebuggerButton button, int mouseButton) {
        var event = new MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0,
            new MouseButtonInfo(mouseButton, 0));
        require(screen.mouseClicked(event, false), "NBT tree control accepts click");
        screen.mouseReleased(event);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
