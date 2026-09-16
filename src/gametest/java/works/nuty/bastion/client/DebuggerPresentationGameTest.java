package works.nuty.bastion.client;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.InputType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import works.nuty.bastion.client.input.InputManager;
import works.nuty.bastion.client.render.DebugLevelRenderer;
import works.nuty.bastion.client.state.ClientDebuggerState;
import works.nuty.bastion.client.ui.BastionScreen;
import works.nuty.bastion.client.ui.DebuggerButton;
import works.nuty.bastion.client.ui.DebuggerIcon;
import works.nuty.bastion.client.ui.DebuggerOverlay;
import works.nuty.bastion.core.model.*;
import works.nuty.bastion.network.PauseSyncPayload;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/** Real rendering and widget checks using an explicit fixture, not a server pause acceptance test. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerPresentationGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1280, 800);
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
            });
            context.getInput().lookAt(0, 18);
            world.getClientLevel().waitForChunksRender();
            ClientDebuggerState state = new ClientDebuggerState();
            BastionScreen screen = context.computeOnClient(client -> {
                InputManager input = input(client, state);
                PauseSnapshot fixture = fixture(client);
                FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    PauseSyncPayload.CODEC.encode(buffer, new PauseSyncPayload(fixture));
                    require(PauseSyncPayload.CODEC.decode(buffer).snapshot().equals(fixture), "Snapshot codec round trip");
                    require(buffer.readableBytes() == 0, "Snapshot consumes its complete payload");
                } finally {
                    buffer.release();
                }
                state.applyPause(fixture);
                BlockLocation active = ((SourceLocation.Block) fixture.location()).block();
                state.applyBreakpoints(List.of(active, new BlockLocation(active.x() + 2, active.y(), active.z(), active.dimension())));
                LevelRenderEvents.END_MAIN.register(new DebugLevelRenderer(state));
                BastionScreen result = new BastionScreen(input, new DebuggerOverlay(state));
                client.setScreenAndShow(result);
                return result;
            });
            context.waitTicks(3);
            context.takeScreenshot("bastion-grouped");
            checkIconToolbar(context, screen);
            context.runOnClient(client -> {
                DebuggerButton mode = button(screen, value -> value.startsWith("Gizmo: "));
                click(screen, mode);
                require(screen.getFocused() == mode, "Mouse click retains widget focus for keyboard navigation");
            });
            context.waitTicks(2);
            context.takeScreenshot("bastion-gizmo-mouse-focus");
            context.runOnClient(client -> {
                // Direct screen events bypass the handlers, so supply fixture input metadata explicitly.
                client.setLastInputType(InputType.KEYBOARD_TAB);
                var previousFocus = screen.getFocused();
                screen.keyPressed(new KeyEvent(258, 0, 0));
                require(screen.getFocused() != null && screen.getFocused() != previousFocus,
                    "Tab moves focus after a mouse click");
            });
            context.waitTicks(2);
            context.takeScreenshot("bastion-gizmo-keyboard-focus");
            context.runOnClient(client -> state.setGizmoMode(ClientDebuggerState.GizmoMode.GROUPED));
            context.waitTicks(2);
            context.runOnClient(client -> {
                DebuggerButton group = button(screen, value -> value.equals("#1 Zombie 1  +8"));
                click(screen, group);
            });
            context.waitTicks(2);
            context.runOnClient(client -> {
                DebuggerButton second = button(screen, value -> value.equals("#2 Zombie 2"));
                click(screen, second);
                require(state.selectedSourceIndex() == 1, "Group member click selects source");
            });
            context.waitTicks(2);
            context.runOnClient(client -> {
                button(screen, value -> value.equals("#2 Zombie 2  +8"));
            });
            context.takeScreenshot("bastion-grouped-second-selected");
            context.runOnClient(client -> {
                DebuggerButton second = button(screen, value -> value.equals("#2 Zombie 2"));
                screen.setFocused(second);
                for (int i = 0; i < 6; i++) screen.mouseScrolled(second.getX() + 2, second.getY() + 2, 0, -1);
            });
            context.waitTicks(2);
            context.runOnClient(client -> {
                require(screen.getFocused() == null, "Scrolled-out button loses focus");
                screen.keyPressed(new KeyEvent(257, 0, 0));
                require(state.selectedSourceIndex() == 1, "Enter cannot activate a hidden source");
                state.setGizmoMode(ClientDebuggerState.GizmoMode.FOCUS);
            });
            context.waitTicks(2);
            context.takeScreenshot("bastion-focus");
            context.runOnClient(client -> state.setGizmoMode(ClientDebuggerState.GizmoMode.LABELS));
            context.waitTicks(2);
            context.takeScreenshot("bastion-labels");
            context.getInput().resizeWindow(640, 480);
            context.waitTicks(2);
            context.takeScreenshot("bastion-compact");
            context.runOnClient(client -> {
                for (var child : screen.children()) {
                    if (child instanceof DebuggerButton button) {
                        require(button.getX() >= 0 && button.getY() >= 0
                            && button.getRight() <= screen.width && button.getBottom() <= screen.height,
                            "Compact controls remain in the screen");
                    }
                }
                state.applyResume();
            });
            context.waitTicks(2);
            context.runOnClient(client -> {
                for (InputManager.Control action : InputManager.Control.values()) {
                    DebuggerButton control = button(screen, value -> value.equals(
                        Component.translatable(action.translationKey()).getString()));
                    require(!control.active, "Every execution icon disables on resume");
                }
            });
            context.takeScreenshot("bastion-running");
            context.getInput().resizeWindow(1280, 800);
            var reload = context.computeOnClient(client -> {
                state.setGizmoMode(ClientDebuggerState.GizmoMode.GROUPED);
                state.applyPause(fixture(client));
                client.getLanguageManager().setSelected("ko_kr");
                return client.reloadResourcePacks();
            });
            context.waitFor(client -> reload.isDone());
            reload.join();
            context.waitFor(client -> client.gui.overlay() == null);
            context.waitTicks(3);
            context.takeScreenshot("bastion-korean");
            checkSolidBlockMarkers(context, world, state);
            context.runOnClient(client -> {
                state.reset();
                client.setScreenAndShow(null);
            });
        }
    }

    private static void checkIconToolbar(ClientGameTestContext context, BastionScreen screen) {
        context.runOnClient(client -> {
            List<DebuggerButton> icons = screen.children().stream().filter(DebuggerButton.class::isInstance)
                .map(DebuggerButton.class::cast).filter(button -> button.icon() != null).toList();
            require(icons.size() == 6, "Execution, gizmo, and details controls all use icons");
            require(icons.stream().allMatch(button -> button.getWidth() == 20 && button.getHeight() == 20),
                "Toolbar icons keep compact square hit targets");
            require(icons.stream().map(DebuggerButton::getY).distinct().count() == 1, "All icons share one row");
            require(icons.stream().noneMatch(button -> button.getMessage().getString().contains("F7")
                || button.getMessage().getString().contains("F8") || button.getMessage().getString().contains("F9")),
                "Execution button labels do not include key bindings");
        });
        for (InputManager.Control action : InputManager.Control.values()) {
            double[] cursor = context.computeOnClient(client -> {
                DebuggerButton control = button(screen, value -> value.equals(
                    Component.translatable(action.translationKey()).getString()));
                return new double[] {
                    (control.getX() + control.getWidth() / 2.0) * client.getWindow().getScreenWidth() / screen.width,
                    (control.getY() + control.getHeight() / 2.0) * client.getWindow().getScreenHeight() / screen.height
                };
            });
            context.getInput().setCursorPos(cursor[0], cursor[1]);
            context.waitTicks(3);
            context.takeScreenshot("bastion-icon-hover-" + action.name().toLowerCase(Locale.ROOT));
        }
        context.getInput().setCursorPos(1100, 400);
        context.runOnClient(client -> {
            client.setLastInputType(InputType.KEYBOARD_TAB);
            screen.setFocused(button(screen, value -> value.equals("Continue")));
        });
        context.waitTicks(3);
        context.takeScreenshot("bastion-icon-keyboard-focus-no-shortcut");
        context.runOnClient(client -> {
            DebuggerButton details = button(screen, value -> value.equals("Details"));
            require(details.icon() == DebuggerIcon.DETAILS_OPEN, "Details icon reflects open panel");
            click(screen, details);
        });
        context.waitTicks(2);
        context.runOnClient(client -> require(button(screen, value -> value.equals("Details")).icon() == DebuggerIcon.DETAILS_CLOSED,
            "Details icon reflects closed panel"));
        context.takeScreenshot("bastion-icon-details-closed");
        context.runOnClient(client -> click(screen, button(screen, value -> value.equals("Details"))));
        context.waitTicks(2);
    }

    private static void checkSolidBlockMarkers(ClientGameTestContext context, TestSingleplayerContext world,
                                               ClientDebuggerState state) {
        List<BlockPos> positions = context.computeOnClient(client -> {
            PauseSnapshot snapshot = state.snapshot();
            PauseSource positionSource = snapshot.pauseSources().get(8);
            BlockLocation active = ((SourceLocation.Block) snapshot.location()).block();
            state.selectSource(8);
            state.setGizmoMode(ClientDebuggerState.GizmoMode.FOCUS);
            return List.of(BlockPos.containing(positionSource.anchor().x(), positionSource.anchor().y(), positionSource.anchor().z()),
                new BlockPos(active.x(), active.y(), active.z()), new BlockPos(active.x() + 2, active.y(), active.z()));
        });
        // Use real server blocks: air-only fixtures cannot expose marker depth conflicts.
        world.getServer().runOnServer(server -> positions.forEach(pos ->
            server.overworld().setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState())));
        context.waitFor(client -> positions.stream().allMatch(pos -> client.level.getBlockState(pos).is(Blocks.STONE)));
        world.getClientLevel().waitForChunksRender();
        context.waitTicks(3);
        context.takeScreenshot("bastion-solid-block-markers");
        context.getInput().lookAt(15, 30);
        context.waitTicks(3);
        context.takeScreenshot("bastion-angled-block-markers");
    }

    private static InputManager input(Minecraft client, ClientDebuggerState state) {
        InputManager result = new InputManager(state, ignored -> {});
        result.menuKey = key(client, "key.bastion.open_menu");
        result.breakpointKey = key(client, "key.bastion.breakpoint");
        result.resumeKey = key(client, "key.bastion.resume");
        result.stepOverKey = key(client, "key.bastion.step_over");
        result.stepIntoKey = key(client, "key.bastion.step_into");
        return result;
    }

    private static KeyMapping key(Minecraft client, String name) {
        return Arrays.stream(client.options.keyMappings).filter(key -> key.getName().equals(name)).findFirst().orElseThrow();
    }

    private static PauseSnapshot fixture(Minecraft client) {
        var player = client.player;
        // Keep the fixture in the world viewport to the right of the source inspector.
        double x = player.getX() - 2;
        double y = player.getY() + 0.1;
        double z = player.getZ() + 6;
        List<PauseSource> sources = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            sources.add(new PauseSource(new Vec3d(x + i * 0.025, y, z), 0, i * 30,
                new EntityRef(new UUID(0, i + 1), "Zombie " + (i + 1)), "minecraft:overworld"));
        }
        sources.add(new PauseSource(new Vec3d(x + 1, y, z + 2), 0, 60, null, "minecraft:overworld"));
        sources.add(new PauseSource(new Vec3d(x, y, z), 0, 0,
            new EntityRef(new UUID(0, 10), "Nether source"), "minecraft:the_nether"));
        SourceLocation location = new SourceLocation.Function(new FunctionLocation(new FunctionId("demo", "spawn_wave"), 12));
        CommandSnippet command = new CommandSnippet("execute as @e[type=zombie] at @s run function demo:move", 26, 31);
        List<CallFrame> stack = List.of(new CallFrame(1, location, command),
            new CallFrame(0, new SourceLocation.Function(new FunctionLocation(new FunctionId("demo", "tick"), 4)),
                CommandSnippet.plain("function demo:spawn_wave")));
        SourceLocation pausedBlock = new SourceLocation.Block(new BlockLocation(
            (int) Math.floor(x) + 2, (int) Math.floor(y), (int) Math.floor(z) + 5, "minecraft:overworld"));
        return new PauseSnapshot(pausedBlock, command, 1, stack, sources, PauseReason.BREAKPOINT);
    }

    private static DebuggerButton button(BastionScreen screen, Predicate<String> label) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> label.test(button.getMessage().getString())).findFirst()
            .orElseThrow(() -> new AssertionError("Button missing; visible: " + screen.children().stream()
                .filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
                .map(button -> button.getMessage().getString()).toList()));
    }

    private static void click(BastionScreen screen, DebuggerButton button) {
        // This fixture calls the screen directly, so mirror MouseHandler's input classification.
        Minecraft.getInstance().setLastInputType(InputType.MOUSE);
        MouseButtonEvent event = new MouseButtonEvent(button.getX() + button.getWidth() / 2.0,
            button.getY() + button.getHeight() / 2.0, new MouseButtonInfo(0, 0));
        require(screen.mouseClicked(event, false), "Widget accepts click");
        screen.mouseReleased(event);
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
