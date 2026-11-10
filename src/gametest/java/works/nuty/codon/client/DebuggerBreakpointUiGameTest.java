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
import works.nuty.codon.client.ui.BreakpointUi;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerOverlay;
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
            context.waitFor(client -> ConditionStripGameTestUi.open(client.gui.screen()), 100);
            context.runOnClient(client -> require(client.gui.screen() == parent && commandBox(parent) == originalEditor,
                "opening the condition strip retains the active screen and its input widget"));
            context.waitTicks(1);
            context.takeScreenshot("codon-breakpoint-condition-strip");
            // Interact with the strip through Minecraft's real input dispatch. While a strip
            // control has focus, neither the click nor typed text reaches the editor.
            nativeButton(context, parent, text("codon.breakpoint.strip.mode.event"));
            context.getInput().typeChars("9");
            context.runOnClient(client -> {
                require(CodonClientMod.state().breakpoints().get(first).enabled(), "the strip owns clicks inside its bounds");
                require(COMMAND.equals(originalEditor.getValue()), "a focused strip owns typed text");
                require(selected(parent, text("codon.breakpoint.strip.event.created")), "Event defaults to created");
            });
            nativeButton(context, parent, text("codon.breakpoint.save"));
            context.waitFor(client -> client.gui.screen() instanceof CommandBlockEditScreen && !ConditionStripGameTestUi.open(client.gui.screen())
                && CodonClientMod.state().breakpoints().get(first).condition().equals(BreakpointCondition.event(BreakpointCondition.Kind.CREATED)), 200);
            context.waitTicks(1);
            context.runOnClient(client -> {
                Screen screen = require(client.gui.screen(), "command block editor restored");
                EditBox command = screen.children().stream().filter(EditBox.class::isInstance)
                    .map(EditBox.class::cast).filter(box -> box.getY() == 50).findFirst().orElseThrow();
                require(COMMAND.equals(command.getValue()), "saving retains the loaded command text");
                require(client.gui.screen() == parent && command == originalEditor
                    && command.getCursorPosition() == originalCursor, "saving closes only the strip and retains the cursor");
                require(button(screen, "Done").active,
                    "closing restores vanilla controls without another block packet");
                openFirstCondition(screen);
            });
            context.waitFor(client -> ConditionStripGameTestUi.open(client.gui.screen()), 100);
            nativeClick(context, parent, 0, 0, InputConstants.MOUSE_BUTTON_LEFT);
            context.runOnClient(client -> require(client.gui.screen() == parent && ConditionStripGameTestUi.open(parent)
                && ConditionStripGameTestUi.strip(parent).getFocused() == null,
                "an outside click leaves the inline strip open and only moves focus"));
            nativeButton(context, parent, text("codon.breakpoint.cancel"));
            context.runOnClient(client -> require(!ConditionStripGameTestUi.open(parent), "Cancel closes a clean strip"));
            // The parent refreshes its marker layout during render after the strip closes.
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
                    "stage marker is ready to reopen the condition strip");
                return new int[] { point.x(), point.y() };
            });
            nativeClick(context, parent, reopenedMarker[0], reopenedMarker[1], InputConstants.MOUSE_BUTTON_RIGHT);
            context.waitFor(client -> ConditionStripGameTestUi.open(client.gui.screen()), 100);
            context.getInput().resizeWindow(960, 720);
            context.runOnClient(client -> {
                client.options.guiScale().set(3);
                client.resizeGui();
            });
            nativeHover(context, parent, 0, 0);
            context.waitTicks(3);
            context.takeScreenshot("codon-breakpoint-condition-320x240");
            context.runOnClient(client -> {
                Screen screen = client.gui.screen();
                require(screen.width == 320 && screen.height == 240, "the editor uses a 320x240 GUI viewport");
                var strip = ConditionStripGameTestUi.strip(screen);
                require(strip.bounds().height() == works.nuty.codon.client.ui.BreakpointConditionStrip.HEIGHT,
                    "the strip keeps its two rows");
                requireInViewport(screen, "compact condition strip");
            });
            verifyDraftDismissal(context, world, parent, position, first);
            // Count: metric, comparison and number sit on the rule row.
            nativeButton(context, parent, text("codon.breakpoint.strip.mode.count"));
            nativeButton(context, parent, text("codon.breakpoint.strip.metric.output_count"));
            context.runOnClient(client -> {
                Screen screen = client.gui.screen();
                var number = ConditionStripGameTestUi.widget(screen, text("codon.breakpoint.strip.count"));
                require(number.getY() - 2 == ConditionStripGameTestUi.widget(screen, text("codon.breakpoint.strip.metric.output_count")).getY()
                    && number.getY() - 2 == ConditionStripGameTestUi.widget(screen, "=").getY()
                    && number.getY() - 2 == ConditionStripGameTestUi.widget(screen, text("codon.breakpoint.strip.mode.count")).getY(),
                    "mode, metric, comparison and number share one row");
                ConditionStripGameTestUi.number(screen, "-1");
                require(!ConditionStripGameTestUi.widget(screen, text("codon.breakpoint.save")).active,
                    "a negative number disables Save immediately");
                ConditionStripGameTestUi.number(screen, "2");
                requireInViewport(screen, "count controls");
            });
            // Click advances the comparison and right-click goes back.
            for (int step = 0; step < 5; step++) nativeButton(context, parent, context.computeOnClient(client -> current(parent)));
            context.runOnClient(client -> {
                Screen screen = client.gui.screen();
                var comparison = ConditionStripGameTestUi.widget(screen, "≥");
                var back = new MouseButtonEvent(comparison.getX() + 2, comparison.getY() + 2,
                    new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0));
                ConditionStripGameTestUi.strip(screen).mouseClicked(back, false);
                require(ConditionStripGameTestUi.has(screen, ">"), "right-click steps the comparison back");
                ConditionStripGameTestUi.strip(screen).mouseClicked(new MouseButtonEvent(comparison.getX() + 2, comparison.getY() + 2,
                    new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
                // The stepper and its keyboard keys keep a whole number.
                ConditionStripGameTestUi.click(screen, "+");
                ConditionStripGameTestUi.strip(screen).setFocused(ConditionStripGameTestUi.widget(screen, text("codon.breakpoint.strip.count")));
                ConditionStripGameTestUi.key(screen, InputConstants.KEY_DOWN);
                require(((EditBox) ConditionStripGameTestUi.widget(screen, text("codon.breakpoint.strip.count"))).getValue().equals("2"),
                    "the stepper and Up/Down change the number by one");
                require(ConditionStripGameTestUi.widget(screen, text("codon.breakpoint.save")).active,
                    "a valid changed count can be saved");
                require(CodonClientMod.state().breakpoints().get(first).condition().equals(BreakpointCondition.event(BreakpointCondition.Kind.CREATED)),
                    "editing the draft does not change the saved condition");
            });
            context.waitTicks(1);
            context.takeScreenshot("codon-breakpoint-condition-count-320x240");
            nativeButton(context, parent, text("codon.breakpoint.save"));
            context.waitFor(client -> !ConditionStripGameTestUi.open(client.gui.screen())
                && CodonClientMod.state().breakpoints().get(first).condition().equals(
                    BreakpointCondition.count(BreakpointCondition.Kind.OUTPUT_COUNT, BreakpointCondition.Comparison.GE, 2)), 200);
            verifyFeedbackBounds(context, first);
            WrappedCommandEditBox deleteEditor = context.computeOnClient(client -> commandBox(parent));
            int deleteCursor = context.computeOnClient(client -> deleteEditor.getCursorPosition());
            var wholeBeforeDelete = context.computeOnClient(client -> CodonClientMod.state().breakpoints()
                .get(BreakpointTarget.whole(location)));
            context.runOnClient(client -> openFirstCondition(client.gui.screen()));
            context.waitFor(client -> ConditionStripGameTestUi.open(client.gui.screen()), 100);
            context.waitTicks(1);
            context.runOnClient(client -> {
                Screen screen = client.gui.screen();
                ConditionStripGameTestUi.click(screen, text("codon.breakpoint.strip.mode.event"));
                ConditionStripGameTestUi.click(screen, text("codon.breakpoint.strip.event.changed"));
                require(!ConditionStripGameTestUi.has(screen, text("codon.breakpoint.strip.count"))
                    && !ConditionStripGameTestUi.has(screen, "≥"), "Event hides the comparison and number");
            });
            context.waitTicks(1);
            context.takeScreenshot("codon-breakpoint-condition-event-320x240");
            context.runOnClient(client -> ConditionStripGameTestUi.click(client.gui.screen(), text("codon.breakpoint.strip.remove")));
            context.waitFor(client -> {
                var state = CodonClientMod.state();
                return client.gui.screen() == parent && !ConditionStripGameTestUi.open(parent)
                    && state != null && state.breakpoints().get(first) == null
                    && !state.breakpoints().pending(first);
            }, 200);
            context.runOnClient(client -> {
                require(commandBox(parent) == deleteEditor && COMMAND.equals(deleteEditor.getValue())
                    && deleteEditor.getCursorPosition() == deleteCursor,
                    "Remove closes only the strip and preserves the editor and cursor");
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
        context.runOnClient(client -> require(!ConditionStripGameTestUi.open(parent) && client.gui.screen() == parent,
            "Escape closes a clean strip without confirmation"));
        reopenCondition(context, parent);
        nativeButton(context, parent, text("codon.breakpoint.cancel"));
        context.runOnClient(client -> require(!ConditionStripGameTestUi.open(parent),
            "Cancel closes a clean strip without confirmation"));
        reopenCondition(context, parent);
        nativeButton(context, parent, text("codon.breakpoint.strip.mode.always"));
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.runOnClient(client -> {
            require(ConditionStripGameTestUi.has(parent, text("codon.breakpoint.keep_editing"))
                && ConditionStripGameTestUi.has(parent, text("codon.breakpoint.discard")),
                "Escape asks before discarding a changed mode, in place of the buttons");
            require(ConditionStripGameTestUi.strip(parent).getFocused() == ConditionStripGameTestUi.widget(parent, text("codon.breakpoint.keep_editing")),
                "the prompt defaults to keeping the draft");
            require(client.gui.screen() == parent && commandBox(parent) == editor
                && editor.getCursorPosition() == cursor && COMMAND.equals(editor.getValue()),
                "the prompt retains the original screen, command input and cursor");
            require(saved.equals(CodonClientMod.state().breakpoints().get(target))
                && !CodonClientMod.state().breakpoints().pending(target), "the prompt does not send a server edit");
            requireInViewport(parent, "discard prompt");
        });
        context.waitTicks(1);
        context.takeScreenshot("codon-breakpoint-draft-discard-en-320x240");
        String language = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        var korean = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected("ko_kr");
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> korean.isDone() && client.gui.overlay() == null, 200);
        context.runOnClient(client -> require(ConditionStripGameTestUi.has(parent, "계속 편집")
            && ConditionStripGameTestUi.has(parent, "버리기"), "Korean prompt offers the same choices"));
        context.waitTicks(1);
        context.takeScreenshot("codon-breakpoint-draft-discard-ko-320x240");
        context.runOnClient(client -> ConditionStripGameTestUi.key(parent, InputConstants.KEY_ESCAPE));
        context.waitTicks(1);
        context.takeScreenshot("codon-breakpoint-condition-ko-320x240");
        context.runOnClient(client -> ConditionStripGameTestUi.key(parent, InputConstants.KEY_ESCAPE));
        var restored = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected(language);
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> restored.isDone() && client.gui.overlay() == null, 200);
        // Enter activates the safe, initially focused choice.
        context.runOnClient(client -> ConditionStripGameTestUi.strip(parent).setFocused(
            ConditionStripGameTestUi.widget(parent, text("codon.breakpoint.keep_editing"))));
        context.getInput().pressKey(InputConstants.KEY_RETURN);
        context.runOnClient(client -> require(selected(parent, text("codon.breakpoint.strip.mode.always")),
            "Keep editing preserves the changed mode"));
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.runOnClient(client -> require(ConditionStripGameTestUi.has(parent, text("codon.breakpoint.keep_editing")),
            "Escape asks again for a dirty draft"));
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.runOnClient(client -> require(selected(parent, text("codon.breakpoint.strip.mode.always")),
            "Escape in the prompt keeps editing"));
        nativeButton(context, parent, text("codon.breakpoint.strip.mode.event"));
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.runOnClient(client -> require(!ConditionStripGameTestUi.open(parent),
            "returning to the saved rule restores clean dismissal"));
        reopenCondition(context, parent);
        context.runOnClient(client -> {
            ConditionStripGameTestUi.count(parent, BreakpointCondition.count(BreakpointCondition.Kind.INPUT_COUNT,
                BreakpointCondition.Comparison.GT, 0));
            ConditionStripGameTestUi.number(parent, "");
            require(!ConditionStripGameTestUi.widget(parent, text("codon.breakpoint.save")).active, "an empty number cannot be saved");
        });
        nativeButton(context, parent, text("codon.breakpoint.cancel"));
        nativeButton(context, parent, text("codon.breakpoint.keep_editing"));
        context.runOnClient(client -> {
            require(number(parent).isEmpty() && ConditionStripGameTestUi.has(parent, ">"),
                "Keep editing preserves even an invalid number and its comparison");
            ConditionStripGameTestUi.number(parent, "3");
        });
        // A real server rejection exercises the Save/ACK path, while the client
        // still holds the old stage preview. Restore the disposable block afterward.
        world.getServer().runOnServer(server -> ((CommandBlockEntity) server.getPlayerList().getPlayers()
            .getFirst().level().getBlockEntity(position)).getCommandBlock().setCommand(COMMAND + " changed"));
        context.runOnClient(client -> {
            var strip = ConditionStripGameTestUi.strip(parent);
            ConditionStripGameTestUi.click(parent, text("codon.breakpoint.save"));
            require(CodonClientMod.state().breakpoints().pending(target), "Save waits for server acknowledgement");
            ConditionStripGameTestUi.key(parent, InputConstants.KEY_ESCAPE);
            ConditionStripGameTestUi.click(parent, text("codon.breakpoint.cancel"));
            strip.setFocused(ConditionStripGameTestUi.widget(parent, text("codon.breakpoint.strip.count")));
            ConditionStripGameTestUi.type(parent, "9");
            require(ConditionStripGameTestUi.strip(parent) == strip
                && !ConditionStripGameTestUi.widget(parent, text("codon.breakpoint.cancel")).active
                && !ConditionStripGameTestUi.widget(parent, text("codon.breakpoint.save")).active
                && !ConditionStripGameTestUi.widget(parent, text("codon.breakpoint.strip.remove")).active
                && !ConditionStripGameTestUi.widget(parent, text("codon.breakpoint.strip.mode.always")).active
                && number(parent).equals("3"), "a pending request blocks dismissal and every change to the submitted draft");
        });
        context.waitFor(client -> !CodonClientMod.state().breakpoints().pending(target)
            && CodonClientMod.state().breakpoints().error(target) == ClientBreakpointState.Result.STALE_SOURCE, 200);
        context.runOnClient(client -> require(number(parent).equals("3")
            && saved.equals(CodonClientMod.state().breakpoints().get(target)), "a failed Save retains the draft and acknowledged definition"));
        context.waitTicks(1);
        context.takeScreenshot("codon-breakpoint-draft-save-rejected-320x240");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        nativeButton(context, parent, text("codon.breakpoint.keep_editing"));
        context.runOnClient(client -> require(number(parent).equals("3") && ConditionStripGameTestUi.has(parent, ">"),
            "Keep editing after a rejected Save retains the draft"));
        nativeButton(context, parent, text("codon.breakpoint.cancel"));
        nativeButton(context, parent, text("codon.breakpoint.discard"));
        context.runOnClient(client -> require(!ConditionStripGameTestUi.open(parent) && client.gui.screen() == parent
            && saved.equals(CodonClientMod.state().breakpoints().get(target))
            && !CodonClientMod.state().breakpoints().pending(target), "Discard closes without changing the server definition"));
        world.getServer().runOnServer(server -> ((CommandBlockEntity) server.getPlayerList().getPlayers()
            .getFirst().level().getBlockEntity(position)).getCommandBlock().setCommand(COMMAND));
        reopenCondition(context, parent);
        context.runOnClient(client -> require(selected(parent, text("codon.breakpoint.strip.mode.event"))
            && selected(parent, text("codon.breakpoint.strip.event.created"))
            && COMMAND.equals(editor.getValue()) && editor.getCursorPosition() == cursor,
            "reopening after Discard shows the acknowledged rule and the original input"));
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.runOnClient(client -> require(!ConditionStripGameTestUi.open(parent),
            "the reopened acknowledged rule is clean despite the previous failed Save"));
        reopenCondition(context, parent);
    }

    private static void reopenCondition(ClientGameTestContext context, Screen parent) {
        context.runOnClient(client -> openFirstCondition(parent));
        context.waitFor(client -> ConditionStripGameTestUi.open(parent), 100);
        nativeHover(context, parent, 0, 0);
        context.waitTicks(1);
    }

    private static String text(String key) { return ConditionStripGameTestUi.text(key); }

    private static boolean selected(Screen parent, String label) {
        return ((DebuggerButton) ConditionStripGameTestUi.widget(parent, label)).selected();
    }

    private static String number(Screen parent) {
        return ((EditBox) ConditionStripGameTestUi.widget(parent, text("codon.breakpoint.strip.count"))).getValue();
    }

    private static String current(Screen parent) {
        return ConditionStripGameTestUi.strip(parent).widgets().stream().map(widget -> widget.getMessage().getString())
            .filter(label -> label.length() == 1 && "=≠<≤>≥".contains(label)).findFirst().orElseThrow();
    }

    private static void requireInViewport(Screen parent, String name) {
        for (var control : ConditionStripGameTestUi.strip(parent).widgets())
            require(control.getX() >= 0 && control.getY() >= 0 && control.getRight() <= parent.width
                && control.getBottom() <= parent.height, name + " stays inside the viewport: " + control.getMessage().getString());
    }

    private static void nativeButton(ClientGameTestContext context, Screen parent, String label) {
        ConditionStripGameTestUi.nativeClick(context, parent, label);
    }

    private static void verifyFeedbackBounds(ClientGameTestContext context, BreakpointTarget target) {
        context.getInput().resizeWindow(1280, 720);
        context.runOnClient(client -> {
            client.options.guiScale().set(2);
            client.resizeGui();
            openFirstCondition(client.gui.screen());
        });
        context.waitTicks(1);
        // A controlled pending state tests feedback layout; saving above uses a real server acknowledgement.
        var edit = context.computeOnClient(client -> CodonClientMod.state().breakpoints()
            .begin(ClientBreakpointState.Action.SAVE, CodonClientMod.state().breakpoints().get(target)));
        context.waitTicks(1);
        context.runOnClient(client -> {
            Screen parent = client.gui.screen();
            require(!ConditionStripGameTestUi.widget(parent, text("codon.breakpoint.save")).active, "pending feedback disables Save");
            requireInViewport(parent, "pending strip");
        });
        context.takeScreenshot("codon-breakpoint-condition-pending");
        context.runOnClient(client -> CodonClientMod.state().breakpoints().finish(edit.requestId(), ClientBreakpointState.Result.APPLIED));
        context.waitTicks(1);
        context.runOnClient(client -> ConditionStripGameTestUi.strip(client.gui.screen()).cancel());
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
        // The vanilla command-block editor has no docked panels; the Breakpoints panel lives on the Codon screen.
        context.runOnClient(client -> {
            var state = CodonClientMod.state();
            client.setScreenAndShow(new CodonScreen(DebuggerPresentationGameTest.input(client, state), new DebuggerOverlay(state)));
        });
        context.waitTicks(3);
        BreakpointPanelGameTestUi.open(context);
        context.runOnClient(client -> {
            Screen list = client.gui.screen();
            var rows = BreakpointPanelGameTestUi.rows(list);
            require(rows.size() == 2,
                "Navigation-only list has two saved destinations, without edit/delete/overflow actions");
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
            list.setFocused(null);
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
            require(BreakpointPanelGameTestUi.rows(client.gui.screen()).size() == 2,
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
            stage, COMMAND, 3, () -> true));
        context.waitTicks(3);
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            require(ConditionStripGameTestUi.open(screen), "disabled stage opens its condition strip");
            var editor = commandBox(screen);
            editor.updateMarkerHover(-100, -100);
            var marker = new WrappedCommandEditBox.Marker(stage, 8, COMMAND.length(), CodonClientMod.state().breakpoints().get(stage));
            require(editor.markerPosition(marker).visible(), "the exact disabled native-editor stage remains visible while editing");
            var other = new WrappedCommandEditBox.Marker(BreakpointTarget.stage(location, 1, COMMAND), 14, COMMAND.length(), null);
            require(!editor.markerPosition(other).visible(), "unrelated inactive native-editor stages remain hidden");
            require(!CodonClientMod.state().breakpoints().get(stage).enabled(), "opening the editor does not enable the stage");
        });
        context.takeScreenshot("codon-breakpoint-disabled-editing");
        context.runOnClient(client -> ConditionStripGameTestUi.strip(client.gui.screen()).cancel());
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
        context.waitFor(client -> ConditionStripGameTestUi.open(client.gui.screen()), 100);
        context.waitTicks(1);
        context.takeScreenshot("codon-breakpoint-keyboard-condition");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitFor(client -> client.gui.screen() instanceof CommandBlockEditScreen && !ConditionStripGameTestUi.open(client.gui.screen()), 100);
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
