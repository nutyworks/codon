package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import works.nuty.codon.CodonMod;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.BreakpointCondition;
import works.nuty.codon.client.network.ClientNetworking;
import works.nuty.codon.client.state.ClientBreakpointState;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.ui.BreakpointConditionScreen;
import works.nuty.codon.client.ui.BreakpointListScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.WrappedCommandEditBox;

/** Opens the native editor and checks that visible breakpoint controls send real edits. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerBreakpointUiGameTest implements FabricClientGameTest {
    private static final String COMMAND = "execute as @a if entity @e[type=minecraft:armor_stand,tag=codon_ui_none] run say unreachable";

    @Override public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            BlockPos position = world.getServer().computeOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                server.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
                CodonMod.engine().clearBreakpoints();
                BlockPos placed = player.blockPosition().offset(6, 0, 6);
                var level = player.level();
                level.setBlockAndUpdate(placed, Blocks.COMMAND_BLOCK.defaultBlockState());
                CommandBlockEntity entity = require((CommandBlockEntity) level.getBlockEntity(placed), "command block exists");
                entity.getCommandBlock().setCommand(COMMAND);
                level.sendBlockUpdated(placed, level.getBlockState(placed), level.getBlockState(placed), 3);
                return placed;
            });
            context.waitFor(client -> client.level != null
                && client.level.getBlockEntity(position) instanceof CommandBlockEntity, 200);
            SourceLocation.Block location = context.computeOnClient(client -> new SourceLocation.Block(
                new BlockLocation(position.getX(), position.getY(), position.getZ(),
                    client.level.dimension().identifier().toString())));
            context.getInput().resizeWindow(960, 540);
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
                CommandBlockEntity entity = require((CommandBlockEntity) client.level.getBlockEntity(position),
                    "client command block exists");
                entity.getCommandBlock().setCommand(COMMAND);
                CommandBlockEditScreen screen = new CommandBlockEditScreen(entity);
                client.setScreenAndShow(screen);
                screen.updateGui();
            });
            context.waitTicks(3);
            context.takeScreenshot("codon-breakpoint-command-block-before-preview");
            context.waitFor(client -> CodonClientMod.state() != null
                && CodonClientMod.state().breakpoints().ready(), 200);
            context.waitFor(client -> {
                var state = CodonClientMod.state();
                var preview = state == null ? null : state.stagePreviews().get(location);
                return preview != null && preview.status() != ClientStagePreviewState.Status.LOADING;
            }, 200);
            context.runOnClient(client -> require(CodonClientMod.state().stagePreviews().get(location).status()
                == ClientStagePreviewState.Status.READY, "server accepts the saved command stage preview"));
            context.runOnClient(client -> {
                Screen screen = require(client.gui.screen(), "command block editor open");
                WrappedCommandEditBox command = screen.children().stream().filter(WrappedCommandEditBox.class::isInstance)
                    .map(WrappedCommandEditBox.class::cast).findFirst().orElseThrow();
                require(command.getHeight() == 60, "command input shows multiple rows");
                command.moveCursorToStart(false);
                command.onClick(new MouseButtonEvent(command.getX() + 4, command.getY() + 19,
                    new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
                require(command.getCursorPosition() > 0, "clicking the second row maps to the wrapped command");
                require(COMMAND.equals(command.getValue()), "soft wrapping leaves command text unchanged");
                String longCommand = "say " + "wrapped command ".repeat(80);
                command.setValue(longCommand);
                require(command.getValue().equals(longCommand), "long commands retain the vanilla length limit");
                require(command.mouseScrolled(command.getX() + 8, command.getY() + 8, 0, 1),
                    "long commands accept vertical scrolling");
                require(command.markerAt(command.getX() - 10, command.getY() + 8) == null,
                    "dirty command cannot reuse saved breakpoint markers");
                command.setValue(COMMAND);
                command.moveCursorToStart(false);
            });
            context.takeScreenshot("codon-breakpoint-command-block-480x270");
            context.runOnClient(client -> {
                Screen screen = require(client.gui.screen(), "command block editor open");
                require(screen.children().stream().filter(AbstractButton.class::isInstance)
                    .map(AbstractButton.class::cast).noneMatch(button -> button.getMessage().getString().contains("Stages")
                        || button.getMessage().getString().contains("Block stop")),
                    "separate breakpoint controls have been removed");
                WrappedCommandEditBox command = commandBox(screen);
                int textX = command.getScreenX(0);
                command.updateMarkerHover(command.getX() - 10, command.getY() + 8);
                require(command.getScreenX(0) == textX, "block breakpoint sits outside the input without shifting text");
                click(screen, command.getX() - 10, command.getY() + 8);
            });
            context.waitFor(client -> {
                var state = CodonClientMod.state();
                return state != null && state.breakpoints().get(BreakpointTarget.whole(location)) != null;
            }, 200);
            context.runOnClient(client -> {
                Screen screen = require(client.gui.screen(), "command block editor open");
                WrappedCommandEditBox command = commandBox(screen);
                int markerX = command.getScreenX("execute ".length());
                click(screen, markerX + 5, command.getY() + 8);
                require(CodonClientMod.state().breakpoints().get(BreakpointTarget.stage(location, 0, COMMAND)) == null,
                    "editing text does not toggle a breakpoint");
                require(COMMAND.equals(command.getValue()), "text click preserves command");
                command.updateMarkerHover(markerX + 1, command.getY() + 8);
                require(command.getScreenX("execute ".length()) == markerX + 12,
                    "showing a stage breakpoint shifts the following text by its slot width");
                click(screen, command.getScreenX("execute ".length()) - 8, command.getY() + 8);
            });
            BreakpointTarget first = BreakpointTarget.stage(location, 0, COMMAND);
            context.waitFor(client -> {
                var state = CodonClientMod.state();
                return state != null && state.breakpoints().get(first) != null;
            }, 200);
            context.takeScreenshot("codon-breakpoint-inline-active");
            context.runOnClient(client -> openFirstCondition(require(client.gui.screen(), "command block editor open")));
            context.waitFor(client -> client.gui.screen() instanceof BreakpointConditionScreen, 100);
            context.runOnClient(client -> click(require(client.gui.screen(), "condition editor open"),
                button(client.gui.screen(), "Save")));
            context.waitFor(client -> client.gui.screen() instanceof CommandBlockEditScreen, 200);
            context.waitTicks(1);
            context.runOnClient(client -> {
                Screen screen = require(client.gui.screen(), "command block editor restored");
                EditBox command = screen.children().stream().filter(EditBox.class::isInstance)
                    .map(EditBox.class::cast).filter(box -> box.getY() == 50).findFirst().orElseThrow();
                require(COMMAND.equals(command.getValue()), "return retains the loaded command text");
                require(button(screen, "Done").active,
                    "return restores vanilla controls without another block packet");
                openFirstCondition(screen);
            });
            context.waitFor(client -> client.gui.screen() instanceof BreakpointConditionScreen, 100);
            context.getInput().resizeWindow(960, 720);
            context.runOnClient(client -> {
                client.options.guiScale().set(3);
                client.resizeGui();
            });
            context.waitTicks(3);
            context.takeScreenshot("codon-breakpoint-condition-320x240");
            context.runOnClient(client -> {
                Screen screen = require(client.gui.screen(), "condition editor open");
                require(screen.width == 320 && screen.height == 240,
                    "condition editor uses a 320x240 GUI viewport");
                for (DebuggerButton control : controls(screen)) {
                    if (!control.visible) continue;
                    require(control.getX() >= 0 && control.getY() >= 0 && control.getRight() <= screen.width
                        && control.getBottom() <= screen.height, "compact condition control stays in bounds");
                }
                DebuggerButton chooser = controls(screen).stream()
                    .filter(value -> value.getMessage().getString().startsWith("Condition:"))
                    .findFirst().orElseThrow();
                click(screen, chooser);
            });
            context.waitTicks(1);
            context.takeScreenshot("codon-breakpoint-condition-menu-320x240");
            context.runOnClient(client -> {
                Screen screen = require(client.gui.screen(), "condition editor open");
                require(controls(screen).stream().filter(value -> value.visible
                    && value.getMessage().getString().contains("Context")).count() >= 1,
                    "condition options open directly instead of cycling: "
                        + controls(screen).stream().map(value -> value.getMessage().getString()
                            + "=" + value.visible).toList());
            });
            // The first outside click closes the popup; the next activates Delete.
            context.runOnClient(client -> click(require(client.gui.screen(), "condition editor open"),
                button(client.gui.screen(), "Delete")));
            context.runOnClient(client -> click(require(client.gui.screen(), "condition editor open"),
                button(client.gui.screen(), "Delete")));
            context.waitFor(client -> client.gui.screen() instanceof BreakpointListScreen, 100);
            context.waitFor(client -> {
                var state = CodonClientMod.state();
                return state != null && state.breakpoints().get(first) == null;
            }, 200);
            context.runOnClient(client -> {
                Screen screen = require(client.gui.screen(), "breakpoint list open");
                click(screen, button(screen, "Undo"));
            });
            context.waitFor(client -> {
                var state = CodonClientMod.state();
                return state != null && state.breakpoints().get(first) != null;
            }, 200);
            verifyDisabledMarkersAfterReopen(context, position, location, first);
            context.runOnClient(client -> client.setScreenAndShow(null));
        }
    }

    private static void verifyDisabledMarkersAfterReopen(ClientGameTestContext context, BlockPos position,
                                                         SourceLocation.Block location, BreakpointTarget stage) {
        BreakpointTarget whole = BreakpointTarget.whole(location);
        BreakpointCondition condition = BreakpointCondition.event(BreakpointCondition.Kind.CREATED);
        context.runOnClient(client -> {
            var state = CodonClientMod.state();
            ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.SAVE,
                state.breakpoints().get(stage).withCondition(condition));
        });
        context.waitFor(client -> !CodonClientMod.state().breakpoints().pending(stage), 200);
        context.runOnClient(client -> {
            var state = CodonClientMod.state();
            for (var target : List.of(whole, stage))
                ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.TOGGLE, state.breakpoints().get(target));
        });
        context.waitFor(client -> List.of(whole, stage).stream().allMatch(target -> {
            var state = CodonClientMod.state().breakpoints();
            return !state.pending(target) && state.get(target) != null && !state.get(target).enabled();
        }), 200);
        context.runOnClient(client -> {
            client.setScreenAndShow(null);
            var entity = (CommandBlockEntity) client.level.getBlockEntity(position);
            var screen = new CommandBlockEditScreen(entity);
            client.setScreenAndShow(screen);
            screen.updateGui();
        });
        context.waitTicks(3);
        context.runOnClient(client -> {
            WrappedCommandEditBox editor = commandBox(client.gui.screen());
            editor.updateMarkerHover(-100, -100);
            for (var target : List.of(whole, stage)) {
                var definition = CodonClientMod.state().breakpoints().get(target);
                var marker = new WrappedCommandEditBox.Marker(target, target.wholeCommand() ? -1 : 8,
                    COMMAND.length(), definition, false);
                require(editor.markerPosition(marker).visible(), "saved disabled marker remains visible after reopening");
            }
            require(CodonClientMod.state().breakpoints().get(stage).condition().equals(condition),
                "disabled stage retains its condition");
            var absent = new WrappedCommandEditBox.Marker(BreakpointTarget.stage(location, 1, COMMAND), 14,
                COMMAND.length(), null, false);
            require(!editor.markerPosition(absent).visible(), "unsaved marker remains hidden without hover");
        });
        context.takeScreenshot("codon-breakpoint-disabled-reopened");
    }

    private static List<DebuggerButton> controls(Screen screen) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance)
            .map(DebuggerButton.class::cast).toList();
    }

    private static AbstractButton button(Screen screen, String part) {
        return screen.children().stream().filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast)
            .filter(value -> value.getMessage().getString().toLowerCase().contains(part.toLowerCase()))
            .findFirst().orElseThrow(() -> new AssertionError("Required button: " + part));
    }

    private static WrappedCommandEditBox commandBox(Screen screen) {
        return screen.children().stream().filter(WrappedCommandEditBox.class::isInstance)
            .map(WrappedCommandEditBox.class::cast).findFirst().orElseThrow();
    }

    private static void openFirstCondition(Screen screen) {
        WrappedCommandEditBox command = commandBox(screen);
        MouseButtonEvent event = new MouseButtonEvent(command.getScreenX("execute ".length()) - 8, command.getY() + 8,
            new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0));
        require(screen.mouseClicked(event, false), "right-click marker opens condition editor");
        screen.mouseReleased(event);
    }

    private static void click(Screen screen, AbstractButton button) {
        click(screen, button.getX() + Math.min(3, button.getWidth() - 1), button.getY() + 2);
    }

    private static void click(Screen screen, int x, int y) {
        MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        require(screen.mouseClicked(event, false), "control accepts click at " + x + "," + y);
        screen.mouseReleased(event);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static <T> T require(T value, String message) {
        if (value == null) throw new AssertionError(message);
        return value;
    }
}
