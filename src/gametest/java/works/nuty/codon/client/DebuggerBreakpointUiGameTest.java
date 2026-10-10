package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
import works.nuty.codon.client.ui.BreakpointUi;
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
            // Interact inside the compact layer through Minecraft's real input dispatch.
            // Neither the click nor typed text may reach the editor underneath.
            AbstractButton initialKind = context.computeOnClient(client -> button(conditionLayer(parent), "Always ▾"));
            nativeClick(context, parent, initialKind.getX() + 3, initialKind.getY() + 3, InputConstants.MOUSE_BUTTON_LEFT);
            context.getInput().typeChars("9");
            context.runOnClient(client -> {
                require(CodonClientMod.state().breakpoints().get(first).enabled(), "layer blocks underlying marker clicks");
                require(COMMAND.equals(originalEditor.getValue()), "layer blocks typing into the underlying command");
            });
            AbstractButton save = context.computeOnClient(client -> button(conditionLayer(parent), "Save and enable"));
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
            context.runOnClient(client -> require(client.gui.screen() == parent && ScreenLayers.get(parent) == null,
                "outside click dismisses only the layer"));
            // The parent refreshes its marker layout during render after the layer closes.
            context.waitFor(client -> {
                var editor = commandBox(parent);
                var point = editor.markerPosition(new WrappedCommandEditBox.Marker(first, 8, COMMAND.length(),
                    CodonClientMod.state().breakpoints().get(first)));
                var hit = editor.markerAt(point.x(), point.y());
                return point.visible() && hit != null && hit.target().equals(first);
            }, 100);
            int[] reopenedMarker = context.computeOnClient(client -> {
                var editor = commandBox(parent);
                var point = editor.markerPosition(new WrappedCommandEditBox.Marker(first, 8, COMMAND.length(),
                    CodonClientMod.state().breakpoints().get(first)));
                var hit = editor.markerAt(point.x(), point.y());
                require(point.visible() && hit != null && hit.target().equals(first),
                    "stage marker is ready to reopen the condition layer");
                return new int[] { point.x(), point.y() };
            });
            nativeClick(context, parent, reopenedMarker[0], reopenedMarker[1], InputConstants.MOUSE_BUTTON_RIGHT);
            context.waitFor(client -> ScreenLayers.get(client.gui.screen()) instanceof BreakpointConditionScreen, 100);
            context.getInput().resizeWindow(960, 720);
            context.runOnClient(client -> {
                client.options.guiScale().set(3);
                client.resizeGui();
            });
            // Resize can leave the native cursor over the newly positioned trigger.
            // Establish a non-hover baseline and wait beyond the 180 ms hover-open delay.
            nativeHover(context, parent, 0, 0);
            context.waitTicks(6);
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
                require(controls(screen).stream().filter(value -> value.icon() != null
                    && java.util.Arrays.stream(BreakpointCondition.Kind.values()).anyMatch(kind ->
                        value.getMessage().getString().equals(works.nuty.codon.client.ui.BreakpointUi.kindLabel(kind)))).count() == 9,
                    "the dropdown retains all nine condition kinds");
                require(!button(screen, "Context created").visible, "choices stay hidden until the dropdown opens");
                require(button(screen, "Save and enable").getBottom() - button(screen, "Cancel").getY() <= 137,
                    "compact condition panel fits within 150 GUI pixels vertically");
            });
            verifyDraftDismissal(context, world, parent, position, first);
            AbstractButton kindTrigger = context.computeOnClient(client -> button(conditionLayer(parent), "Always ▾"));
            nativeHover(context, parent, kindTrigger.getX() + 3, kindTrigger.getY() + 3);
            context.waitFor(client -> button(conditionLayer(parent), "Context created").visible, 100);
            context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
            context.runOnClient(client -> require(button(conditionLayer(parent), "Context created").visible,
                "clicking the trigger just after hover-open keeps the menu open"));
            context.runOnClient(client -> require(CodonClientMod.state().breakpoints().get(first).condition().equals(BreakpointCondition.ALWAYS),
                "hovering opens the menu without changing the saved condition"));
            context.takeScreenshot("codon-breakpoint-condition-hover-menu-320x240");
            verifyDropdownToggle(context, parent, "Always ▾", "Context created", "kind");
            context.runOnClient(client -> click(conditionLayer(parent), kindTrigger));
            nativeHover(context, parent, 0, 0);
            context.waitFor(client -> !button(conditionLayer(parent), "Context created").visible, 100);
            nativeHover(context, parent, kindTrigger.getX() + 3, kindTrigger.getY() + 3);
            context.waitFor(client -> button(conditionLayer(parent), "Context created").visible, 100);
            AbstractButton visibleOption = context.computeOnClient(client -> button(conditionLayer(parent), "Context created"));
            int bridgeY = visibleOption.getY() < kindTrigger.getY() ? kindTrigger.getY() - 1 : kindTrigger.getBottom() + 1;
            nativeHover(context, parent, kindTrigger.getX() + 3, bridgeY);
            context.waitTicks(6);
            context.runOnClient(client -> require(button(conditionLayer(parent), "Context created").visible,
                "crossing the trigger/menu gap keeps the menu open"));
            nativeHover(context, parent, visibleOption.getX() + 3, visibleOption.getY() + 3);
            context.getInput().scroll(-1);
            context.waitFor(client -> button(conditionLayer(parent), "Output context count").visible, 100);
            AbstractButton outputOption = context.computeOnClient(client -> button(conditionLayer(parent), "Output context count"));
            nativeClick(context, parent, outputOption.getX() + 3, outputOption.getY() + 3, InputConstants.MOUSE_BUTTON_LEFT);
            context.runOnClient(client -> {
                Screen screen = conditionLayer(parent);
                require(!button(screen, "Output context count").visible, "selecting a kind closes the popup");
                EditBox count = screen.children().stream().filter(EditBox.class::isInstance)
                    .map(EditBox.class::cast).filter(value -> value.visible).findFirst().orElseThrow();
                require(count.getY() == button(screen, "Output count ▾").getY()
                    && count.getY() == button(screen, "= ▾").getY(), "kind, comparison and count share one row");
                count.setValue("-1");
                require(!button(screen, "Save and enable").active, "negative count disables save immediately");
                count.setValue("2");
                screen.setFocused(button(screen, "= ▾"));
            });
            AbstractButton comparisonTrigger = context.computeOnClient(client -> button(conditionLayer(parent), "= ▾"));
            nativeHover(context, parent, comparisonTrigger.getX() + 3, comparisonTrigger.getY() + 3);
            context.waitFor(client -> button(conditionLayer(parent), "≠").visible, 100);
            verifyDropdownToggle(context, parent, "= ▾", "≠", "comparison");
            nativeHover(context, parent, comparisonTrigger.getX() + 3, comparisonTrigger.getY() + 3);
            context.runOnClient(client -> {
                Screen screen = conditionLayer(parent);
                click(screen, button(screen, "Output count ▾"));
                click(screen, comparisonTrigger);
                click(screen, comparisonTrigger);
                require(button(screen, "≠").visible && !button(screen, "Output context count").visible,
                    "switching selectors starts a fresh opening guard on the new menu");
            });
            waitForToggleDelay(context);
            nativeClick(context, parent, comparisonTrigger.getX() + 3, comparisonTrigger.getY() + 3,
                InputConstants.MOUSE_BUTTON_LEFT);
            context.runOnClient(client -> {
                Screen screen = conditionLayer(parent);
                require(!button(screen, "≠").visible && button(screen, "= ▾").visible,
                    "closing the switched comparison preserves its selected value");
                require(screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                    .anyMatch(value -> value.visible && value.getValue().equals("2")),
                    "dropdown toggles preserve the draft threshold");
            });
            nativeHover(context, parent, 0, 0);
            context.getInput().pressKey(InputConstants.KEY_RETURN);
            context.runOnClient(client -> require(button(conditionLayer(parent), "≠").visible,
                "Enter opens the focused comparison dropdown"));
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.runOnClient(client -> require(ScreenLayers.get(parent) != null && !button(conditionLayer(parent), "≠").visible,
                "Escape dismisses the dropdown without closing the condition layer"));
            context.getInput().pressKey(InputConstants.KEY_DOWN);
            context.getInput().pressKey(InputConstants.KEY_UP);
            context.getInput().pressKey(InputConstants.KEY_RETURN);
            context.runOnClient(client -> {
                Screen screen = conditionLayer(parent);
                require(button(screen, "≥ ▾").visible, "arrow navigation reaches and selects a scrolled comparison");
                require(button(screen, "Save and enable").active, "valid count and comparison can be saved");
            });
            context.waitTicks(1);
            context.takeScreenshot("codon-breakpoint-condition-count-dropdown-320x240");
            AbstractButton resizedSave = context.computeOnClient(client -> button(conditionLayer(client.gui.screen()), "Save and enable"));
            nativeClick(context, parent, resizedSave.getX() + 3, resizedSave.getY() + 2, InputConstants.MOUSE_BUTTON_LEFT);
            context.waitFor(client -> ScreenLayers.get(client.gui.screen()) == null
                && CodonClientMod.state().breakpoints().get(first).condition().equals(
                    BreakpointCondition.count(BreakpointCondition.Kind.OUTPUT_COUNT, BreakpointCondition.Comparison.GE, 2)), 200);
            verifyFeedbackBounds(context, first);
            WrappedCommandEditBox deleteEditor = context.computeOnClient(client -> commandBox(parent));
            int deleteCursor = context.computeOnClient(client -> deleteEditor.getCursorPosition());
            var wholeBeforeDelete = context.computeOnClient(client -> CodonClientMod.state().breakpoints()
                .get(BreakpointTarget.whole(location)));
            context.runOnClient(client -> openFirstCondition(client.gui.screen()));
            context.waitFor(client -> ScreenLayers.get(client.gui.screen()) instanceof BreakpointConditionScreen, 100);
            context.runOnClient(client -> {
                Screen screen = conditionLayer(client.gui.screen());
                click(screen, button(screen, "Output count ▾"));
                click(screen, button(screen, "Context changed"));
                require(screen.children().stream().filter(EditBox.class::isInstance)
                    .map(EditBox.class::cast).noneMatch(value -> value.visible),
                    "event conditions hide the count field");
                require(controls(screen).stream().noneMatch(value -> value.visible && value.getMessage().getString().equals("≥ ▾")),
                    "event conditions hide the comparison controls");
            });
            context.waitTicks(1);
            context.takeScreenshot("codon-breakpoint-condition-event-dropdown-320x240");
            context.runOnClient(client -> click(conditionLayer(client.gui.screen()),
                button(conditionLayer(client.gui.screen()), "Delete")));
            context.waitFor(client -> {
                var state = CodonClientMod.state();
                return client.gui.screen() == parent && ScreenLayers.get(parent) == null
                    && state != null && state.breakpoints().get(first) == null
                    && !state.breakpoints().pending(first);
            }, 200);
            context.runOnClient(client -> {
                require(commandBox(parent) == deleteEditor && COMMAND.equals(deleteEditor.getValue())
                    && deleteEditor.getCursorPosition() == deleteCursor,
                    "deleting closes only the condition layer and preserves the editor and cursor");
                require(wholeBeforeDelete.equals(CodonClientMod.state().breakpoints().get(BreakpointTarget.whole(location))),
                    "deleting the exact stage preserves the separate whole-command breakpoint");
            });
            focusMarker(context, first);
            context.getInput().pressKey(InputConstants.KEY_SPACE);
            context.waitFor(client -> {
                var state = CodonClientMod.state();
                var recreated = state == null ? null : state.breakpoints().get(first);
                return recreated != null && recreated.enabled() && recreated.condition().equals(BreakpointCondition.ALWAYS)
                    && !state.breakpoints().pending(first) && focusedMarkerReady(parent, first);
            }, 200);
            context.takeScreenshot("codon-breakpoint-recreated");
            verifyKeyboardMarkers(context, position, location, first);
            verifyDisabledMarkersAfterReopen(context, position, location, first);
            context.runOnClient(client -> client.setScreenAndShow(null));
        }
    }

    private static void verifyDraftDismissal(ClientGameTestContext context, TestSingleplayerContext world,
                                             Screen parent, BlockPos position, BreakpointTarget target) {
        var saved = context.computeOnClient(client -> CodonClientMod.state().breakpoints().get(target));
        WrappedCommandEditBox editor = context.computeOnClient(client -> commandBox(parent));
        int cursor = context.computeOnClient(client -> editor.getCursorPosition());
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.runOnClient(client -> require(ScreenLayers.get(parent) == null,
            "Escape closes a clean condition draft without confirmation"));
        reopenCondition(context, parent, target);
        nativeButton(context, parent, "Cancel");
        context.runOnClient(client -> require(ScreenLayers.get(parent) == null,
            "Cancel closes a clean condition draft without confirmation"));
        reopenCondition(context, parent, target);
        context.runOnClient(client -> selectKind(conditionLayer(parent), BreakpointCondition.Kind.CREATED));
        nativeClick(context, parent, 0, 0, InputConstants.MOUSE_BUTTON_LEFT);
        context.runOnClient(client -> {
            Screen layer = conditionLayer(parent);
            require(button(layer, "Keep editing").visible && button(layer, "Discard").visible,
                "outside click requests explicit discard of a changed kind");
            require(layer.getFocused() == button(layer, "Keep editing"), "confirmation defaults to retaining the draft");
            require(client.gui.screen() == parent && commandBox(parent) == editor
                && editor.getCursorPosition() == cursor && COMMAND.equals(editor.getValue()),
                "discard confirmation retains the original screen, command input and cursor");
            require(saved.equals(CodonClientMod.state().breakpoints().get(target))
                && !CodonClientMod.state().breakpoints().pending(target), "dismissal does not send a server edit");
            for (var control : controls(layer)) require(control.getX() >= 0 && control.getY() >= 0
                && control.getRight() <= layer.width && control.getBottom() <= layer.height,
                "discard confirmation controls fit the 320x240 viewport");
        });
        context.takeScreenshot("codon-breakpoint-draft-discard-en-320x240");
        String language = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        var korean = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected("ko_kr");
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> korean.isDone() && client.gui.overlay() == null, 200);
        context.runOnClient(client -> require(button(conditionLayer(parent), "계속 편집").visible
            && button(conditionLayer(parent), "버리기").visible, "Korean confirmation offers the same discard choices"));
        context.takeScreenshot("codon-breakpoint-draft-discard-ko-320x240");
        var restored = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected(language);
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> restored.isDone() && client.gui.overlay() == null, 200);
        nativeClick(context, parent, 0, 0, InputConstants.MOUSE_BUTTON_LEFT);
        context.getInput().typeChars("99");
        context.runOnClient(client -> require(button(conditionLayer(parent), "Keep editing").visible
            && COMMAND.equals(editor.getValue()), "repeated outside click and typing cannot discard or reach the parent"));
        // Enter activates the safe, initially focused choice.
        context.getInput().pressKey(InputConstants.KEY_RETURN);
        context.runOnClient(client -> require(button(conditionLayer(parent), "Context created ▾").visible,
            "cancelling discard preserves the changed kind"));
        nativeHover(context, parent, 0, 0);
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.runOnClient(client -> require(button(conditionLayer(parent), "Keep editing").visible,
            "Escape requests discard of a dirty draft"));
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.runOnClient(client -> {
            require(button(conditionLayer(parent), "Context created ▾").visible,
                "Escape cancels discard and preserves the draft");
            selectKind(conditionLayer(parent), BreakpointCondition.Kind.ALWAYS);
        });
        nativeClick(context, parent, 0, 0, InputConstants.MOUSE_BUTTON_LEFT);
        context.runOnClient(client -> require(ScreenLayers.get(parent) == null,
            "reverting to the original condition restores clean dismissal"));
        reopenCondition(context, parent, target);
        context.runOnClient(client -> {
            Screen layer = conditionLayer(parent);
            selectKind(layer, BreakpointCondition.Kind.INPUT_COUNT);
            layer.setFocused(button(layer, "= ▾"));
            layer.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
            for (int i = 0; i < BreakpointCondition.Comparison.GT.ordinal(); i++)
                layer.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0));
            layer.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
            countBox(layer).setValue("");
            require(!button(layer, "Save and enable").active, "invalid draft count cannot be saved");
        });
        nativeClick(context, parent, 0, 0, InputConstants.MOUSE_BUTTON_LEFT);
        nativeButton(context, parent, "Keep editing");
        context.runOnClient(client -> {
            Screen layer = conditionLayer(parent);
            require(countBox(layer).getValue().isEmpty() && button(layer, "> ▾").visible,
                "cancelling discard preserves even an invalid count and comparison");
            countBox(layer).setValue("3");
        });
        // A real server rejection exercises the Save/ACK path, while the client
        // still holds the old stage preview. Restore the disposable block afterward.
        world.getServer().runOnServer(server -> ((CommandBlockEntity) server.getPlayerList().getPlayers()
            .getFirst().level().getBlockEntity(position)).getCommandBlock().setCommand(COMMAND + " changed"));
        context.runOnClient(client -> {
            Screen layer = conditionLayer(parent);
            click(layer, button(layer, "Save and enable"));
            require(CodonClientMod.state().breakpoints().pending(target), "Save waits for server acknowledgement");
            layer.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
            layer.mouseClicked(new MouseButtonEvent(0, 0,
                new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
            layer.setFocused(countBox(layer));
            layer.charTyped(new net.minecraft.client.input.CharacterEvent('9'));
            require(ScreenLayers.get(parent) == layer && !button(layer, "Cancel").active
                && !button(layer, "Save and enable").active && !button(layer, "Delete").active
                && countBox(layer).getValue().equals("3"), "pending ACK blocks dismissal and changes to the submitted draft");
        });
        context.waitFor(client -> !CodonClientMod.state().breakpoints().pending(target)
            && CodonClientMod.state().breakpoints().error(target) == ClientBreakpointState.Result.STALE_SOURCE, 200);
        context.runOnClient(client -> require(countBox(conditionLayer(parent)).getValue().equals("3")
            && saved.equals(CodonClientMod.state().breakpoints().get(target)), "failed Save retains the draft and acknowledged definition"));
        context.takeScreenshot("codon-breakpoint-draft-save-rejected-320x240");
        nativeHover(context, parent, 0, 0);
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        nativeButton(context, parent, "Keep editing");
        context.runOnClient(client -> require(countBox(conditionLayer(parent)).getValue().equals("3")
            && button(conditionLayer(parent), "> ▾").visible, "cancelling discard after Save rejection retains the draft"));
        nativeButton(context, parent, "Cancel");
        nativeButton(context, parent, "Discard");
        context.runOnClient(client -> require(ScreenLayers.get(parent) == null && client.gui.screen() == parent
            && saved.equals(CodonClientMod.state().breakpoints().get(target))
            && !CodonClientMod.state().breakpoints().pending(target), "confirmed discard closes without mutating the server definition"));
        world.getServer().runOnServer(server -> ((CommandBlockEntity) server.getPlayerList().getPlayers()
            .getFirst().level().getBlockEntity(position)).getCommandBlock().setCommand(COMMAND));
        reopenCondition(context, parent, target);
        context.runOnClient(client -> require(button(conditionLayer(parent), "Always ▾").visible
            && COMMAND.equals(editor.getValue()) && editor.getCursorPosition() == cursor,
            "reopening after discard restores the acknowledged condition and original parent input"));
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.runOnClient(client -> require(ScreenLayers.get(parent) == null,
            "the reopened acknowledged condition is clean despite the previous failed Save"));
        reopenCondition(context, parent, target);
    }

    private static void reopenCondition(ClientGameTestContext context, Screen parent, BreakpointTarget target) {
        context.runOnClient(client -> BreakpointUi.openCondition(parent, CodonClientMod.state(), target, COMMAND, 3, null));
        context.waitFor(client -> ScreenLayers.get(parent) instanceof BreakpointConditionScreen, 100);
        nativeHover(context, parent, 0, 0);
    }

    private static void selectKind(Screen layer, BreakpointCondition.Kind kind) {
        AbstractButton trigger = controls(layer).stream().filter(control -> control.visible
            && control.getMessage().getString().endsWith(" ▾") && control.getWidth() > 40).findFirst().orElseThrow();
        layer.setFocused(trigger);
        layer.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        for (int i = 0; i < BreakpointCondition.Kind.values().length
            && !((AbstractButton) layer.getFocused()).getMessage().getString().equals(BreakpointUi.kindLabel(kind)); i++)
            layer.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0));
        require(((AbstractButton) layer.getFocused()).getMessage().getString().equals(BreakpointUi.kindLabel(kind)),
            "keyboard can reach the requested condition kind");
        layer.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
    }

    private static EditBox countBox(Screen layer) {
        return layer.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
            .filter(box -> box.visible).findFirst().orElseThrow();
    }

    private static void nativeButton(ClientGameTestContext context, Screen parent, String label) {
        AbstractButton control = context.computeOnClient(client -> button(conditionLayer(parent), label));
        nativeClick(context, parent, control.getX() + 3, control.getY() + 2, InputConstants.MOUSE_BUTTON_LEFT);
    }

    /** Uses native dispatch for delayed toggles and same-turn dispatch for the immediate guard. */
    private static void verifyDropdownToggle(ClientGameTestContext context, Screen parent, String triggerLabel,
                                             String optionLabel, String capture) {
        AbstractButton trigger = context.computeOnClient(client -> button(conditionLayer(parent), triggerLabel));
        waitForToggleDelay(context);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
        context.runOnClient(client -> require(button(conditionLayer(parent), optionLabel).visible,
            "secondary click does not toggle the open " + capture + " menu"));
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.runOnClient(client -> {
            Screen screen = conditionLayer(parent);
            require(!button(screen, optionLabel).visible && button(screen, triggerLabel).visible,
                "delayed trigger click closes the " + capture + " menu without selecting an option");
        });
        waitForToggleDelay(context);
        context.runOnClient(client -> require(!button(conditionLayer(parent), optionLabel).visible,
            "remaining over the closed " + capture + " trigger does not reopen it"));
        context.takeScreenshot("codon-breakpoint-condition-" + capture + "-toggle-closed-320x240");
        context.runOnClient(client -> {
            Screen screen = conditionLayer(parent);
            click(screen, trigger);
            MouseButtonEvent repeated = new MouseButtonEvent(trigger.getX() + 3, trigger.getY() + 3,
                new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            require(screen.mouseClicked(repeated, true), "immediate repeated toggle is consumed");
            screen.mouseReleased(repeated);
            require(button(screen, optionLabel).visible && button(screen, triggerLabel).visible,
                "click reopen and immediate repeated click keep the " + capture + " menu and selection");
        });
        waitForToggleDelay(context);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.runOnClient(client -> require(!button(conditionLayer(parent), optionLabel).visible,
            "a repeated delayed click closes the reopened " + capture + " menu"));
        nativeHover(context, parent, 0, 0);
        context.runOnClient(client -> {
            Screen screen = conditionLayer(parent);
            screen.setFocused(trigger);
            require(screen.keyPressed(new KeyEvent(InputConstants.KEY_SPACE, InputConstants.KEYCODE_SPACE, 0)),
                "Space reopens the focused " + capture + " menu");
            click(screen, trigger);
            require(button(screen, optionLabel).visible, "keyboard opening also guards an immediate trigger click");
            screen.keyPressed(new KeyEvent(InputConstants.KEY_TAB, InputConstants.KEYCODE_TAB, 0));
            require(!button(screen, optionLabel).visible && ScreenLayers.get(parent) == screen
                && screen.getFocused() != trigger,
                "Tab closes only the " + capture + " menu and continues focus traversal");
            screen.setFocused(trigger);
        });
    }

    private static void waitForToggleDelay(ClientGameTestContext context) {
        long started = System.nanoTime();
        context.waitFor(client -> System.nanoTime() - started >= 300_000_000L, 100);
    }

    private static void verifyFeedbackBounds(ClientGameTestContext context, BreakpointTarget target) {
        context.getInput().resizeWindow(1280, 720);
        context.runOnClient(client -> {
            client.options.guiScale().set(2);
            client.resizeGui();
            var state = CodonClientMod.state();
            Screen parent = client.gui.screen();
            ScreenLayers.open(parent, new BreakpointConditionScreen(parent, state, state.breakpoints().get(target),
                new BreakpointConditionScreen.Anchor(300, 200, 20, 20)));
        });
        context.waitTicks(1);
        // A controlled pending state tests feedback layout; saving above uses a real server acknowledgement.
        var edit = context.computeOnClient(client -> CodonClientMod.state().breakpoints()
            .begin(ClientBreakpointState.Action.SAVE, CodonClientMod.state().breakpoints().get(target)));
        context.waitTicks(1);
        context.runOnClient(client -> {
            Screen layer = conditionLayer(client.gui.screen());
            require(!button(layer, "Save and enable").active, "pending feedback disables save");
            for (var button : controls(layer)) if (button.visible)
                require(button.getY() >= 0 && button.getBottom() <= layer.height - 6,
                    "feedback expansion keeps the bottom-anchored controls inside the viewport");
        });
        context.takeScreenshot("codon-breakpoint-condition-pending-anchored");
        context.runOnClient(client -> {
            CodonClientMod.state().breakpoints().finish(edit.requestId(), ClientBreakpointState.Result.APPLIED);
            ScreenLayers.close(ScreenLayers.get(client.gui.screen()));
        });
        context.getInput().resizeWindow(960, 720);
        context.runOnClient(client -> { client.options.guiScale().set(3); client.resizeGui(); });
        context.waitTicks(1);
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
        context.runOnClient(client -> {
            Screen list = client.gui.screen();
            var rows = controls(list).stream().filter(control -> !control.getMessage().equals(
                Component.translatable("codon.breakpoint.close"))).toList();
            require(rows.size() == 2 && controls(list).size() == 3,
                "Navigation-only list has two saved destinations and Close, without edit/delete/overflow actions");
            require(rows.stream().noneMatch(control -> control.active),
                "Command-block destinations without a recorded Flow remain listed and unavailable");
            require(list.width == 320 && list.height == 240, "Breakpoint readability fixture uses the narrow viewport");
            for (var row : rows) {
                String headline = (String) FunctionLineBreakpointGameTest.field(row, "headline");
                String detail = (String) FunctionLineBreakpointGameTest.field(row, "detail");
                boolean wholeRow = row.getMessage().getString().contains(BreakpointUi.target(whole))
                    && !row.getMessage().getString().contains(BreakpointUi.target(stage));
                String kind = Component.translatable(wholeRow ? "codon.breakpoint.whole_target" : "codon.breakpoint.stage_target", 1).getString();
                require(headline.contains(kind) && client.font.width(headline) <= row.getWidth() - DebuggerButton.TEXT_ICON_INSET - 10,
                    "Status, whole-command/stage identity and coordinates remain visible at 320x240");
                require(detail.startsWith(BreakpointUi.condition(CodonClientMod.state().breakpoints().get(wholeRow ? whole : stage).condition())),
                    "Condition has its own line before the dimension text");
                // Vanilla wraps the tooltip, so compare the sentence across its line breaks.
                String tooltip = tooltipText(client, row).replace('\n', ' ');
                require(tooltip.contains(Component.translatable("codon.breakpoint.flow_unavailable").getString())
                    && !tooltip.contains("Shift+F10"),
                    "An unavailable row's tooltip gives the reason without the repeated navigation instruction: " + tooltip);
            }
            var before = CodonClientMod.state().breakpoints().definitions();
            var row = rows.getFirst();
            var event = new MouseButtonEvent(row.getX() + 2, row.getY() + 2,
                new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            require(!list.mouseClicked(event, false), "Unavailable row cannot dispatch a hidden mutation");
            require(CodonClientMod.state().breakpoints().definitions().equals(before)
                && !CodonClientMod.state().breakpoints().pending(whole) && !CodonClientMod.state().breakpoints().pending(stage),
                "Browsing the list leaves definitions and pending requests unchanged");
            require(!list.mouseScrolled(0, 0, 0, -1), "Scrolling outside the list is not consumed");
            list.setFocused(button(list, "Close"));
        });
        context.takeScreenshot("codon-breakpoint-navigation-only-list");
        context.getInput().setCursorPos(0, 0);
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
            require(controls(client.gui.screen()).stream().filter(control -> !control.getMessage().equals(
                Component.translatable("codon.breakpoint.close"))).count() == 2,
                "Open navigation list retains both disabled saved destinations after acknowledgement");
            require(CodonClientMod.state().breakpoints().definitions().size() == 2,
                "Navigation retains disabled definitions without rewriting them");
            require(CodonClientMod.state().blockBreakpoints().isEmpty(), "disabled block has no world marker");
        });
        context.takeScreenshot("codon-breakpoint-disabled-list");
        String language = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        var korean = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected("ko_kr");
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> korean.isDone() && client.gui.overlay() == null, 200);
        context.waitTicks(2);
        context.takeScreenshot("codon-breakpoint-disabled-list-ko-320x240");
        var restored = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected(language);
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> restored.isDone() && client.gui.overlay() == null, 200);
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
        context.runOnClient(client -> BreakpointUi.openCondition(client.gui.screen(), CodonClientMod.state(),
            stage, COMMAND, 3, null));
        context.waitTicks(3);
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            require(ScreenLayers.get(screen) instanceof BreakpointConditionScreen, "disabled stage opens its condition editor");
            var editor = commandBox(screen);
            editor.updateMarkerHover(-100, -100);
            var marker = new WrappedCommandEditBox.Marker(stage, 8, COMMAND.length(), CodonClientMod.state().breakpoints().get(stage));
            require(editor.markerPosition(marker).visible(), "the exact disabled native-editor stage remains visible while editing");
            var other = new WrappedCommandEditBox.Marker(BreakpointTarget.stage(location, 1, COMMAND), 14, COMMAND.length(), null);
            require(!editor.markerPosition(other).visible(), "unrelated inactive native-editor stages remain hidden");
            require(!CodonClientMod.state().breakpoints().get(stage).enabled(), "opening the editor does not enable the stage");
        });
        context.takeScreenshot("codon-breakpoint-disabled-editing");
        context.runOnClient(client -> ScreenLayers.get(client.gui.screen()).onClose());
        context.waitTicks(2);
        context.runOnClient(client -> {
            var editor = commandBox(client.gui.screen());
            editor.updateMarkerHover(-100, -100);
            var marker = new WrappedCommandEditBox.Marker(stage, 8, COMMAND.length(), CodonClientMod.state().breakpoints().get(stage));
            require(!editor.markerPosition(marker).visible() && !CodonClientMod.state().breakpoints().get(stage).enabled(),
                "Cancel removes the native-editor pin and preserves the disabled stage");
        });
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

    private static String tooltipText(Minecraft client, DebuggerButton button) {
        try {
            var field = DebuggerButton.class.getDeclaredField("tooltip");
            field.setAccessible(true);
            var tooltip = (Tooltip) field.get(button);
            StringBuilder text = new StringBuilder();
            if (tooltip != null) for (var line : tooltip.toCharSequence(client)) {
                line.accept((index, style, codePoint) -> { text.appendCodePoint(codePoint); return true; });
                text.append('\n');
            }
            return text.toString();
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static List<DebuggerButton> controls(Screen screen) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance)
            .map(DebuggerButton.class::cast).toList();
    }

    private static Screen conditionLayer(Screen parent) {
        return require(ScreenLayers.get(parent), "condition layer open");
    }

    private static void nativeClick(ClientGameTestContext context, Screen parent, int x, int y, int button) {
        nativeHover(context, parent, x, y);
        context.getInput().pressMouse(button);
    }

    private static void nativeHover(ClientGameTestContext context, Screen parent, int x, int y) {
        double[] position = context.computeOnClient(client -> new double[] {
            (double) x * client.getWindow().getScreenWidth() / parent.width,
            (double) y * client.getWindow().getScreenHeight() / parent.height
        });
        context.getInput().setCursorPos(position[0], position[1]);
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
