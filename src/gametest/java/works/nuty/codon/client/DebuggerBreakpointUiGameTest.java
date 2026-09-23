package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Comparator;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
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
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.ui.BreakpointConditionScreen;
import works.nuty.codon.client.ui.BreakpointListScreen;
import works.nuty.codon.client.ui.DebuggerButton;

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
            context.takeScreenshot("codon-breakpoint-command-block-480x270");
            context.runOnClient(client -> {
                Screen screen = require(client.gui.screen(), "command block editor open");
                AbstractButton block = button(screen, "Block stop");
                AbstractButton stages = button(screen, "Stages");
                Button condition = blockControl(screen, 2);
                require(block.getBottom() <= stages.getY() + stages.getHeight()
                    && block.getY() >= 135 + 20, "breakpoint controls sit below vanilla previous output");
                require(block.getRight() <= screen.width && condition.getRight() <= screen.width,
                    "breakpoint controls remain inside the screen");
                click(screen, block);
            });
            context.waitFor(client -> {
                var state = CodonClientMod.state();
                return state != null && state.breakpoints().get(BreakpointTarget.whole(location)) != null;
            }, 200);
            context.runOnClient(client -> {
                Screen screen = require(client.gui.screen(), "command block editor open");
                int left = Math.max(4, (screen.width - 300) / 2);
                int stageY = screen.height / 4 + 153 + 33;
                click(screen, left + 4 + client.font.width("execute ") + 2, stageY + 2);
            });
            BreakpointTarget first = BreakpointTarget.stage(location, 0, COMMAND);
            context.waitFor(client -> {
                var state = CodonClientMod.state();
                return state != null && state.breakpoints().get(first) != null;
            }, 200);
            context.runOnClient(client -> click(require(client.gui.screen(), "command block editor open"),
                blockControl(client.gui.screen(), 2)));
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
                require(button(screen, "Done").active && blockControl(screen, 1).active,
                    "return restores vanilla and stage controls without another block packet");
                click(screen, blockControl(screen, 2));
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
            context.runOnClient(client -> client.setScreenAndShow(null));
        }
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

    private static Button blockControl(Screen screen, int index) {
        int expectedY = screen.height / 4 + 153 + 12;
        List<Button> controls = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
            .filter(value -> value.getY() == expectedY).sorted(Comparator.comparingInt(Button::getX)).toList();
        require(controls.size() == 3, "native command block editor has three breakpoint controls");
        return controls.get(index);
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
