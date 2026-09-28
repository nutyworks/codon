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
import net.minecraft.client.input.KeyEvent;
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
import works.nuty.codon.client.ui.ScreenLayers;
import works.nuty.codon.client.ui.BreakpointListScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.WrappedCommandEditBox;
import works.nuty.codon.client.ui.InlineBreakpointButton;

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
                command.updateMarkerHover(-100, -100);
                require(command.markerAt(command.getX() - 10, command.getY() + 8) != null,
                    "unused whole-command marker is visible without hovering");
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
            Screen parent = context.computeOnClient(client -> client.gui.screen());
            WrappedCommandEditBox originalEditor = context.computeOnClient(client -> commandBox(parent));
            int originalCursor = context.computeOnClient(client -> originalEditor.getCursorPosition());
            int[] marker = context.computeOnClient(client -> new int[] {
                originalEditor.getScreenX("execute ".length()) - 8, originalEditor.getY() + 8
            });
            nativeClick(context, parent, marker[0], marker[1], InputConstants.MOUSE_BUTTON_RIGHT);
            context.waitFor(client -> ScreenLayers.get(client.gui.screen()) instanceof BreakpointConditionScreen, 100);
            context.runOnClient(client -> require(client.gui.screen() == parent && commandBox(parent) == originalEditor,
                "opening condition details retains the active screen and its input widget"));
            context.takeScreenshot("codon-breakpoint-condition-layer");
            // Click the underlying marker and type through Minecraft's real input dispatch.
            // Neither action may reach the editor below the modal layer.
            nativeClick(context, parent, marker[0], marker[1], InputConstants.MOUSE_BUTTON_LEFT);
            context.getInput().typeChars("9");
            context.runOnClient(client -> {
                require(CodonClientMod.state().breakpoints().get(first).enabled(), "layer blocks underlying marker clicks");
                require(COMMAND.equals(originalEditor.getValue()), "layer blocks typing into the underlying command");
            });
            AbstractButton save = context.computeOnClient(client -> button(conditionLayer(parent), "Save"));
            nativeClick(context, parent, save.getX() + 3, save.getY() + 2, InputConstants.MOUSE_BUTTON_LEFT);
            context.waitFor(client -> client.gui.screen() instanceof CommandBlockEditScreen && ScreenLayers.get(client.gui.screen()) == null, 200);
            context.waitTicks(1);
            context.runOnClient(client -> {
                Screen screen = require(client.gui.screen(), "command block editor restored");
                EditBox command = screen.children().stream().filter(EditBox.class::isInstance)
                    .map(EditBox.class::cast).filter(box -> box.getY() == 50).findFirst().orElseThrow();
                require(COMMAND.equals(command.getValue()), "return retains the loaded command text");
                require(client.gui.screen() == parent && command == originalEditor
                    && command.getCursorPosition() == originalCursor, "saving closes only the layer and retains the cursor");
                require(button(screen, "Done").active,
                    "return restores vanilla controls without another block packet");
                openFirstCondition(screen);
            });
            context.waitFor(client -> ScreenLayers.get(client.gui.screen()) instanceof BreakpointConditionScreen, 100);
            nativeClick(context, parent, 0, 0, InputConstants.MOUSE_BUTTON_LEFT);
            context.runOnClient(client -> {
                require(client.gui.screen() == parent && ScreenLayers.get(parent) == null,
                    "outside click dismisses only the layer");
                openFirstCondition(parent);
            });
            context.getInput().resizeWindow(960, 720);
            context.runOnClient(client -> {
                client.options.guiScale().set(3);
                client.resizeGui();
            });
            context.waitTicks(3);
            context.takeScreenshot("codon-breakpoint-condition-320x240");
            context.runOnClient(client -> {
                Screen screen = conditionLayer(client.gui.screen());
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
                Screen screen = conditionLayer(client.gui.screen());
                require(controls(screen).stream().filter(value -> value.visible
                    && value.getMessage().getString().contains("Context")).count() >= 1,
                    "condition options open directly instead of cycling: "
                        + controls(screen).stream().map(value -> value.getMessage().getString()
                            + "=" + value.visible).toList());
            });
            // The first outside click closes the popup; the next activates Delete.
            context.runOnClient(client -> click(conditionLayer(client.gui.screen()),
                button(conditionLayer(client.gui.screen()), "Delete")));
            context.runOnClient(client -> click(conditionLayer(client.gui.screen()),
                button(conditionLayer(client.gui.screen()), "Delete")));
            context.waitFor(client -> client.gui.screen() instanceof BreakpointListScreen, 100);
            context.waitFor(client -> {
                var state = CodonClientMod.state();
                // The snapshot can arrive before the next frame enables Undo.
                return state != null && state.breakpoints().get(first) == null
                    && !state.breakpoints().pending(first) && button(client.gui.screen(), "Undo").isActive();
            }, 200);
            context.runOnClient(client -> {
                Screen screen = require(client.gui.screen(), "breakpoint list open");
                click(screen, button(screen, "Undo"));
            });
            context.waitFor(client -> {
                var state = CodonClientMod.state();
                return state != null && state.breakpoints().get(first) != null;
            }, 200);
            context.takeScreenshot("codon-breakpoint-restored");
            verifyKeyboardMarkers(context, position, location, first);
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
        context.runOnClient(client -> client.setScreenAndShow(
            new BreakpointListScreen(client.gui.screen(), CodonClientMod.state())));
        context.waitTicks(2);
        context.runOnClient(client -> require(controls(client.gui.screen()).stream()
            .filter(control -> control.getTabOrderGroup() < 100).count() == 2,
            "list initially shows both enabled breakpoints"));
        context.runOnClient(client -> {
            Screen list = client.gui.screen();
            DebuggerButton row = controls(list).stream()
                .filter(control -> control.getTabOrderGroup() < 100).findFirst().orElseThrow();
            click(list, row);
            require(button(list, "Delete").active, "selecting a row enables actions for that breakpoint");
            require(!list.mouseScrolled(0, 0, 0, -1), "scrolling outside the list is not consumed");
            DebuggerButton conditionButton = controls(list).stream()
                .filter(control -> control.getMessage().getString().startsWith("Condition")).findFirst().orElseThrow();
            client.setLastInputType(net.minecraft.client.InputType.KEYBOARD_TAB);
            list.setFocused(conditionButton);
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-breakpoint-selected-keyboard-tooltip");
        context.runOnClient(client -> {
            var state = CodonClientMod.state();
            for (var target : List.of(whole, stage))
                ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.TOGGLE, state.breakpoints().get(target));
        });
        context.waitFor(client -> List.of(whole, stage).stream().allMatch(target -> {
            var state = CodonClientMod.state().breakpoints();
            return !state.pending(target) && state.get(target) != null && !state.get(target).enabled();
        }), 200);
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(controls(client.gui.screen()).stream().noneMatch(control -> control.getTabOrderGroup() < 100),
                "open list removes disabled breakpoints after acknowledgement");
            require(CodonClientMod.state().breakpoints().definitions().size() == 2,
                "hiding disabled breakpoints preserves their saved definitions");
            require(CodonClientMod.state().blockBreakpoints().isEmpty(), "disabled block has no world marker");
        });
        context.takeScreenshot("codon-breakpoint-disabled-list");
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
                    COMMAND.length(), definition);
                require(editor.markerPosition(marker).visible() == target.wholeCommand(),
                    "only the whole-command marker stays visible without hovering after reopening");
                var point = editor.markerPosition(marker);
                require(!target.wholeCommand() || point.x() >= 4,
                    "whole-command marker stays fully inside the narrow viewport");
                editor.updateMarkerHover(point.x(), point.y());
                require(editor.markerPosition(marker).visible(), "hover reveals a disabled marker");
                editor.updateMarkerHover(-100, -100);
                editor.focusBreakpoint(target);
                require(editor.markerPosition(marker).visible(), "keyboard focus reveals a disabled marker");
                editor.clearBreakpointFocus(target);
                require(editor.markerPosition(marker).visible() == target.wholeCommand(),
                    "only the whole-command marker stays visible after hover and focus leave");
            }
            require(CodonClientMod.state().breakpoints().get(stage).condition().equals(condition),
                "disabled stage retains its condition");
            var absent = new WrappedCommandEditBox.Marker(BreakpointTarget.stage(location, 1, COMMAND), 14,
                COMMAND.length(), null);
            require(!editor.markerPosition(absent).visible(), "unsaved marker remains hidden without hover");
        });
        context.takeScreenshot("codon-breakpoint-disabled-reopened");
        context.runOnClient(client -> {
            var editor = commandBox(client.gui.screen());
            var marker = new WrappedCommandEditBox.Marker(stage, 8, COMMAND.length(),
                CodonClientMod.state().breakpoints().get(stage));
            var point = editor.markerPosition(marker);
            editor.updateMarkerHover(point.x(), point.y());
            point = editor.markerPosition(marker);
            click(client.gui.screen(), point.x(), point.y());
        });
        context.waitFor(client -> {
            var state = CodonClientMod.state().breakpoints();
            return !state.pending(stage) && state.get(stage).enabled();
        }, 200);
        context.runOnClient(client -> require(CodonClientMod.state().breakpoints().get(stage).condition().equals(condition),
            "reactivating the hidden stage preserves its condition"));
    }

    private static void verifyKeyboardMarkers(ClientGameTestContext context, BlockPos position,
                                              SourceLocation.Block location, BreakpointTarget first) {
        context.runOnClient(client -> {
            var screen = new CommandBlockEditScreen((CommandBlockEntity) client.level.getBlockEntity(position));
            client.setScreenAndShow(screen);
            screen.updateGui();
        });
        context.waitTicks(3);
        var targets = context.computeOnClient(client -> client.gui.screen().children().stream()
            .filter(InlineBreakpointButton.class::isInstance).map(InlineBreakpointButton.class::cast)
            .map(InlineBreakpointButton::target).toList());
        require(targets.size() >= 3, "whole command and parsed stages have keyboard controls");
        context.runOnClient(client -> {
            var unused = client.gui.screen().children().stream().filter(InlineBreakpointButton.class::isInstance)
                .map(InlineBreakpointButton.class::cast)
                .filter(control -> CodonClientMod.state().breakpoints().get(control.target()) == null).toList();
            require(!unused.isEmpty(), "fixture includes unused stage markers");
            String always = works.nuty.codon.client.ui.BreakpointUi.condition(BreakpointCondition.ALWAYS);
            require(unused.stream().allMatch(control -> control.getMessage().getString().endsWith(" · " + always)),
                "unused marker narration includes its default Always condition");
        });
        for (var target : targets) focusMarker(context, target);
        BreakpointTarget whole = BreakpointTarget.whole(location);
        focusMarker(context, whole);
        context.getInput().pressKey(InputConstants.KEY_SPACE);
        context.waitFor(client -> !CodonClientMod.state().breakpoints().pending(whole)
            && !CodonClientMod.state().breakpoints().get(whole).enabled()
            && focusedMarkerReady(client.gui.screen(), whole), 200);
        context.getInput().pressKey(InputConstants.KEY_RETURN);
        context.waitFor(client -> !CodonClientMod.state().breakpoints().pending(whole)
            && CodonClientMod.state().breakpoints().get(whole).enabled()
            && focusedMarkerReady(client.gui.screen(), whole), 200);
        focusMarker(context, first);
        context.runOnClient(client -> {
            client.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_TAB,
                InputConstants.KEYCODE_TAB, InputConstants.MOD_SHIFT));
            require(client.gui.screen().getFocused() instanceof InlineBreakpointButton control
                && control.target().equals(whole), "Shift+Tab returns to the whole-command marker");
        });
        focusMarker(context, first);
        context.takeScreenshot("codon-breakpoint-keyboard-focus");
        context.getInput().pressKey(InputConstants.KEY_SPACE);
        context.waitFor(client -> !CodonClientMod.state().breakpoints().pending(first)
            && !CodonClientMod.state().breakpoints().get(first).enabled()
            && focusedMarkerReady(client.gui.screen(), first), 200);
        context.getInput().pressKey(InputConstants.KEY_RETURN);
        context.waitFor(client -> !CodonClientMod.state().breakpoints().pending(first)
            && CodonClientMod.state().breakpoints().get(first).enabled()
            && focusedMarkerReady(client.gui.screen(), first), 200);
        // Fabric's TestInput supplies modifiers=0 even while Shift is held.
        context.runOnClient(client -> require(client.gui.screen().keyPressed(
            new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, InputConstants.MOD_SHIFT)),
            "focused stage handles Shift+Enter"));
        context.waitFor(client -> ScreenLayers.get(client.gui.screen()) instanceof BreakpointConditionScreen, 100);
        context.takeScreenshot("codon-breakpoint-keyboard-condition");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitFor(client -> client.gui.screen() instanceof CommandBlockEditScreen && ScreenLayers.get(client.gui.screen()) == null, 100);
        focusMarker(context, first);
        context.runOnClient(client -> commandBox(client.gui.screen()).setValue(COMMAND + " changed"));
        context.getInput().pressKey(InputConstants.KEY_SPACE);
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(CodonClientMod.state().breakpoints().get(first).enabled(),
                "dirty command cannot activate stale keyboard marker targets");
            require(client.gui.screen().children().stream().noneMatch(InlineBreakpointButton.class::isInstance),
                "dirty commands remove breakpoint focus controls");
        });
    }

    private static void focusMarker(ClientGameTestContext context, BreakpointTarget target) {
        for (int attempts = 0; attempts < 24; attempts++) {
            if (context.computeOnClient(client -> client.gui.screen().getFocused() instanceof InlineBreakpointButton control
                    && control.target().equals(target))) {
                context.waitFor(client -> focusedMarkerReady(client.gui.screen(), target), 200);
                return;
            }
            context.getInput().pressKey(InputConstants.KEY_TAB);
        }
        throw new AssertionError("Tab can reach marker " + target);
    }

    private static boolean focusedMarkerReady(Screen screen, BreakpointTarget target) {
        return screen.getFocused() instanceof InlineBreakpointButton control
            && control.target().equals(target) && control.isActive();
    }

    private static List<DebuggerButton> controls(Screen screen) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance)
            .map(DebuggerButton.class::cast).toList();
    }

    private static Screen conditionLayer(Screen parent) {
        return require(ScreenLayers.get(parent), "condition layer open");
    }

    private static void nativeClick(ClientGameTestContext context, Screen parent, int x, int y, int button) {
        double[] position = context.computeOnClient(client -> new double[] {
            (double) x * client.getWindow().getScreenWidth() / parent.width,
            (double) y * client.getWindow().getScreenHeight() / parent.height
        });
        context.getInput().setCursorPos(position[0], position[1]);
        context.getInput().pressMouse(button);
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
