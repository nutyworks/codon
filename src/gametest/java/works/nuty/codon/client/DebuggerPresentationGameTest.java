package works.nuty.codon.client;

import io.netty.buffer.Unpooled;
import com.mojang.blaze3d.platform.InputConstants;
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
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.render.DebugLevelRenderer;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerButton;
import works.nuty.codon.client.ui.DebuggerIcon;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.DebuggerTheme;
import works.nuty.codon.core.model.*;
import works.nuty.codon.network.PauseSyncPayload;
import works.nuty.codon.network.BreakpointSyncPayload;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
            world.getConnection().waitForChunksRender();
            ClientDebuggerState state = new ClientDebuggerState();
            CodonScreen screen = context.computeOnClient(client -> {
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
                checkBreakpointCodec();
                state.applyPause(fixture);
                BlockLocation active = ((SourceLocation.Block) fixture.location()).block();
                state.applyBreakpoints(List.of(active, new BlockLocation(active.x() + 2, active.y(), active.z(), active.dimension())));
                LevelRenderEvents.BEFORE_GIZMOS.register(new DebugLevelRenderer(state, input));
                CodonScreen result = new CodonScreen(input, new DebuggerOverlay(state));
                client.setScreenAndShow(result);
                return result;
            });
            context.waitTicks(3);
            context.takeScreenshot("codon-command-integrated");
            checkContinuationWidth(context, screen, state);
            checkIconToolbar(context, screen, state);
            checkCommandPanel(context, screen, state);
            checkStableCommandActions(context, screen, state);
            checkVisibleClauseSelection(context, screen, state);
            checkHorizontalCallPath(context, screen, state);
            checkSourceColors(context, screen, state);
            checkContextStatusLabels(context, screen, state);
            checkUuidCopyFeedback(context, screen, state);
            context.runOnClient(client -> {
                DebuggerButton view = button(screen, value -> value.equals("View"));
                click(screen, view);
                require(screen.getFocused() == view, "Mouse click retains widget focus for keyboard navigation");
            });
            context.waitTicks(2);
            context.takeScreenshot("codon-gizmo-mouse-focus");
            context.runOnClient(client -> {
                // Direct screen events bypass the handlers, so supply fixture input metadata explicitly.
                client.setLastInputType(InputType.KEYBOARD_TAB);
                var previousFocus = screen.getFocused();
                screen.keyPressed(new KeyEvent(InputConstants.KEY_TAB, InputConstants.KEYCODE_TAB, 0));
                require(screen.getFocused() != null && screen.getFocused() != previousFocus,
                    "Tab moves focus after a mouse click");
            });
            context.waitTicks(2);
            context.takeScreenshot("codon-gizmo-keyboard-focus");
            context.runOnClient(client -> state.setGizmoMode(ClientDebuggerState.GizmoMode.GROUPED));
            context.waitTicks(2);
            context.runOnClient(client -> {
                DebuggerButton group = button(screen, value -> value.equals("#1 Zombie 1  +8"));
                click(screen, group);
            });
            context.waitTicks(2);
            context.runOnClient(client -> {
                boolean secondVisible = screen.children().stream().filter(DebuggerButton.class::isInstance)
                    .map(DebuggerButton.class::cast).anyMatch(value -> value.getMessage().getString().equals("#2 Zombie 2"));
                if (!secondVisible) {
                    var first = button(screen, value -> value.equals("#1 Zombie 1"));
                    require(screen.mouseScrolled(first.getX() + 2, first.getY() + 2, 0, -1),
                        "The compact Contexts viewport scrolls to the next group member");
                    require(state.selectedSourceIndex() == 0, "Revealing a group member does not select it");
                }
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
            context.takeScreenshot("codon-grouped-second-selected");
            // Constrain the viewport so the source list still overflows with the shorter command panel.
            context.getInput().resizeWindow(1280, 600);
            context.waitTicks(2);
            context.runOnClient(client -> {
                DebuggerButton second = button(screen, value -> value.equals("#2 Zombie 2"));
                screen.setFocused(second);
                for (int i = 0; i < 6; i++) screen.mouseScrolled(second.getX() + 2, second.getY() + 2, 0, -1);
            });
            context.waitTicks(2);
            context.runOnClient(client -> {
                require(screen.getFocused() == null, "Scrolled-out button loses focus");
                screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0));
                require(state.selectedSourceIndex() == 1, "Enter cannot activate a hidden source");
            });
            context.runOnClient(client -> state.setGizmoMode(ClientDebuggerState.GizmoMode.LABELS));
            context.getInput().resizeWindow(1280, 800);
            context.waitTicks(2);
            context.takeScreenshot("codon-labels");
            checkLabelSlotsStayFixedAfterSourceSelection(context, screen, state);
            context.getInput().resizeWindow(640, 480);
            context.waitTicks(2);
            context.takeScreenshot("codon-command-compact-stack");
            context.runOnClient(client -> {
                require(state.selectedFlowStageIndex() == 2, "The selected condition survives a resize");
                button(screen, value -> value.contains("if entity"));
                for (String label : List.of("Current")) {
                    DebuggerButton control = button(screen, value -> value.equals(label));
                    require(control.getWidth() >= client.font.width(control.getMessage()) + 10,
                        "Command actions keep their complete label at compact width");
                }
                for (var child : screen.children()) {
                    if (child instanceof DebuggerButton button) {
                        require(button.getX() >= 0 && button.getY() >= 0
                            && button.getRight() <= screen.width && button.getBottom() <= screen.height,
                            "Compact controls remain in the screen");
                    }
                }
            });
            context.waitTicks(2);
            context.runOnClient(client -> button(screen, value -> value.contains("if entity")));
            context.takeScreenshot("codon-command-compact");
            context.runOnClient(client -> {
                state.applyResume();
            });
            context.waitTicks(2);
            context.runOnClient(client -> {
                require(screen.children().stream().noneMatch(child -> child instanceof DebuggerButton button
                    && button.icon() == DebuggerIcon.PAUSE),
                    "Resumed command history does not display an active stop marker");
                for (InputManager.Control action : InputManager.Control.values()) {
                    DebuggerButton control = button(screen, value -> value.startsWith(
                        Component.translatable(action.translationKey()).getString() + " "));
                    require(!control.active, "Every execution icon disables on resume");
                }
            });
            context.takeScreenshot("codon-running");
            context.getInput().resizeWindow(1280, 800);
            context.runOnClient(client -> {
                state.setGizmoMode(ClientDebuggerState.GizmoMode.GROUPED);
                state.applyPause(fixture(client));
            });
            context.waitTicks(3);
            checkSolidBlockMarkers(context, world, state);
            context.runOnClient(client -> {
                state.reset();
                client.setScreenAndShow(null);
            });
        }
    }

    private static void checkUuidCopyFeedback(ClientGameTestContext context, CodonScreen screen,
                                               ClientDebuggerState state) {
        String clipboard = context.computeOnClient(client -> client.keyboardHandler.getClipboard());
        try {
            String uuid = context.computeOnClient(client -> state.selectedSource().entity().uuid().toString());
            context.runOnClient(client -> click(screen, button(screen, label -> label.startsWith("UUID: "))));
            context.getInput().setCursorPos(0, 0);
            context.waitTicks(2);
            context.runOnClient(client -> {
                DebuggerButton copied = button(screen, label -> label.startsWith("UUID: "));
                require(client.keyboardHandler.getClipboard().equals(uuid), "Copy UUID writes the selected context UUID");
                require(copied.icon() == DebuggerIcon.CONFIRM
                    && copied.getMessage().getString().contains(Component.translatable("codon.ui.copied").getString()),
                    "Copy UUID exposes a success icon and copied accessible name without hover");
            });
            context.takeScreenshot("codon-copy-uuid-confirmed");
            context.runOnClient(client -> state.selectSource(1));
            context.waitTicks(2);
            context.runOnClient(client -> require(button(screen, label -> label.startsWith("UUID: ")).icon() == DebuggerIcon.COPY_UUID,
                "Copied confirmation does not transfer to another context UUID"));
            context.runOnClient(client -> state.applyPause(fixture(client)));
            context.waitTicks(2);
            context.runOnClient(client -> require(button(screen, label -> label.startsWith("UUID: ")).icon() == DebuggerIcon.COPY_UUID,
                "A new pause clears copied confirmation even for the same UUID"));
        } finally {
            context.runOnClient(client -> client.keyboardHandler.setClipboard(clipboard));
        }
    }

    private static void checkIconToolbar(ClientGameTestContext context, CodonScreen screen, ClientDebuggerState state) {
        context.runOnClient(client -> {
            int toolbarY = works.nuty.codon.client.ui.layout.DebuggerLayout
                .create(screen.width, screen.height, true).controls().y() + 2;
            List<DebuggerButton> icons = screen.children().stream().filter(DebuggerButton.class::isInstance)
                .map(DebuggerButton.class::cast)
                .filter(button -> button.icon() != null && button.getY() == toolbarY).toList();
            require(icons.size() == 8,
                "Main toolbar includes execution, freecam, Information, Source, and breakpoints icons");
            require(icons.stream().filter(button -> button.getWidth() == 20 && button.getHeight() == 20).count() == 7,
                "Execution and utility icons keep compact square hit targets");
            require(icons.stream().anyMatch(button -> button.getMessage().getString().startsWith("BP ")
                && button.getWidth() >= 60), "Breakpoint count is visible in the toolbar");
            require(icons.stream().map(DebuggerButton::getY).distinct().count() == 1, "All icons share one row");
        });
        context.runOnClient(client -> {
            DebuggerButton info = button(screen, value -> value.equals("Information"));
            DebuggerButton keep = button(screen, value -> value.startsWith("Keep freecam"));
            require(info.getX() == keep.getX() + keep.getWidth() + 3,
                "Information sits immediately beside Freecam");
            click(screen, keep);
            require(state.preferences().keepFreecam(), "Toolbar click enables retention");
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-keep-freecam-enabled");
        context.runOnClient(client -> {
            click(screen, button(screen, value -> value.startsWith("Keep freecam")));
            require(!state.preferences().keepFreecam(), "Second click disables retention");
            require(screen.keyPressed(new KeyEvent(InputConstants.KEY_G, 0, 0)),
                "Freecam shortcut is handled in the debugger screen");
            require(state.preferences().keepFreecam(), "G enables retention in cursor mode");
            screen.keyPressed(new KeyEvent(InputConstants.KEY_G, 0, 0));
            require(!state.preferences().keepFreecam(), "G disables retention in cursor mode");

        });
        context.waitTicks(2);
        for (InputManager.Control action : InputManager.Control.values()) {
            double[] cursor = context.computeOnClient(client -> {
                DebuggerButton control = button(screen, value -> value.startsWith(
                    Component.translatable(action.translationKey()).getString() + " "));
                return new double[] {
                    (control.getX() + control.getWidth() / 2.0) * client.getWindow().getScreenWidth() / screen.width,
                    (control.getY() + control.getHeight() / 2.0) * client.getWindow().getScreenHeight() / screen.height
                };
            });
            context.getInput().setCursorPos(cursor[0], cursor[1]);
            context.waitTicks(3);
            context.takeScreenshot("codon-icon-hover-" + action.name().toLowerCase(Locale.ROOT));
        }
        context.getInput().setCursorPos(1100, 400);
        context.runOnClient(client -> {
            client.setLastInputType(InputType.KEYBOARD_TAB);
            screen.setFocused(button(screen, value -> value.startsWith("Continue ")));
        });
        context.waitTicks(3);
        context.takeScreenshot("codon-icon-keyboard-focus-no-shortcut");
        context.runOnClient(client -> {
            click(screen, button(screen, value -> value.equals("View")));
        });
        context.waitTicks(2);
        context.runOnClient(client -> click(screen, button(screen, value -> value.contains("Details"))));
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(Boolean.FALSE.equals(state.preferences().inspectorVisible()), "View menu closes Details");
            click(screen, button(screen, value -> value.equals("View")));
        });
        context.takeScreenshot("codon-icon-details-closed");
        context.waitTicks(2);
        context.runOnClient(client -> click(screen, button(screen, value -> value.contains("Details"))));
        context.waitTicks(2);
    }

    private static void checkHorizontalCallPath(ClientGameTestContext context, CodonScreen screen,
                                                ClientDebuggerState state) {
        context.runOnClient(client -> {
            PauseSnapshot original = fixture(client);
            List<CallFrame> frames = new ArrayList<>();
            frames.add(original.callStack().getFirst());
            for (int i = 1; i <= 9; i++) frames.add(new CallFrame(9 - i,
                new SourceLocation.Function(new FunctionLocation(new FunctionId("demo", "caller_" + i), i)),
                CommandSnippet.plain("function demo:caller_" + (i - 1)), 9000 + i, 0));
            state.applyPause(new PauseSnapshot(original.location(), original.command(), original.depth(), frames,
                original.pauseSources(), original.executionFlows(), original.reason(), original.pauseId()));
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            DebuggerButton leaf = button(screen, value -> value.equals("demo:spawn_wave:12"));
            require(screen.children().stream().noneMatch(child -> child instanceof DebuggerButton control
                && control.getMessage().getString().equals("demo:caller_9:9")), "Off-screen frames are not active widgets");
            require(screen.mouseScrolled(leaf.getX() + 3, leaf.getY() + 3, 100, 0),
                "Horizontal trackpad input scrolls the call path");
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            DebuggerButton root = button(screen, value -> value.equals("demo:caller_9:9"));
            click(screen, root);
            require(state.selectedCallFrameIndex() == 9, "Horizontal scrolling makes the deepest caller selectable");
            require(state.selectedCommand().text().equals("function demo:caller_8"), "Caller selection preserves its command");
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-horizontal-stack-root");
        context.runOnClient(client -> {
            DebuggerButton root = button(screen, value -> value.equals("demo:caller_9:9"));
            require(screen.mouseScrolled(root.getX() + 3, root.getY() + 3, 0, -100),
                "A vertical mouse wheel also scrolls the horizontal path");
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            click(screen, button(screen, value -> value.equals("demo:spawn_wave:12")));
            require(state.selectedCallFrameIndex() == 0 && state.isViewingCurrentCommand(), "The scrolled leaf returns to the actual stop");
            for (var child : screen.children()) if (child instanceof DebuggerButton control)
                require(control.getX() >= 0 && control.getRight() <= screen.width,
                    "Clipped frame hit boxes stay inside the screen");
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-horizontal-stack-leaf");
        context.runOnClient(client -> state.applyPause(fixture(client)));
        context.waitTicks(2);
    }

    private static void checkCommandPanel(ClientGameTestContext context, CodonScreen screen,
                                          ClientDebuggerState state) {
        context.runOnClient(client -> {
            require(screen.children().stream().noneMatch(child -> child instanceof DebuggerButton control
                && control.getMessage().getString().startsWith("Call stack")), "Call path has no expand/collapse control");
            require(button(screen, value -> value.equals("demo:spawn_wave:12")).icon() == DebuggerIcon.PAUSE,
                "The paused frame uses a drawn pause icon");
            button(screen, value -> value.equals("demo:tick:4"));
            DebuggerButton condition = button(screen, value -> value.contains("if entity"));
            require(condition.getY() == button(screen, value -> value.equals("demo:spawn_wave:12")).getY() + 19,
                "Command clauses follow the call path directly without a duplicate location caption row");
            click(screen, condition);
            require(state.selectedExecutionFlowStage().index() == 2 && state.displayedSources().size() == 10,
                "A command clause selects its recorded condition stage and sources");
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-command-condition");
        context.runOnClient(client -> {
            require(button(screen, value -> value.equals("demo:spawn_wave:12")).icon() != DebuggerIcon.PAUSE,
                "An earlier selected clause shows its recorded frame without the current stop icon");
            DebuggerButton parent = button(screen, value -> value.equals("demo:tick:4"));
            click(screen, parent);
            require(state.selectedFrameIndex() == 1 && state.selectedExecutionFlow().invocationId() == 76,
                "Horizontal call path selects the matching parent invocation");
            require(state.selectedPauseSourceIndex() == -1 && state.nbt().executor() == null,
                "A same-valued parent context never becomes the live Watch or NBT executor");
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-command-stack");
        context.runOnClient(client -> {
            DebuggerButton previousClause = button(screen, value -> value.equals("function demo:spawn_wave"));
            click(screen, button(screen, value -> value.equals("Current")));
            click(screen, previousClause);
            require(state.isViewingCurrentCommand(),
                "A clause from the previous flow cannot select a different stage after Current and before the next render");
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(state.selectedFrameIndex() == 0 && state.selectedExecutionFlow().invocationId() == 77
                    && state.isViewingCurrentCommand(), "Current returns to the authoritative stopped frame and stage");
            click(screen, button(screen, value -> value.equals("Expand command panel")));
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            button(screen, value -> value.equals("Collapse command panel"));
            click(screen, button(screen, value -> value.equals("Collapse command panel")));
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            click(screen, button(screen, value -> value.equals("Previous recorded command")));
            require(state.selectedExecutionFlow().invocationId() == 76, "Flow previous selects the parent trace");
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            click(screen, button(screen, value -> value.equals("Current")));
            state.selectSource(0);
        });
    }

    private static void checkStableCommandActions(ClientGameTestContext context, CodonScreen screen,
                                                   ClientDebuggerState state) {
        context.waitTicks(2);
        Map<String, WidgetBounds> original = context.computeOnClient(client -> {
            require(!button(screen, value -> value.equals("Current")).active,
                "Current keeps its slot while the live command is selected");
            require(screen.children().stream().noneMatch(child -> child instanceof DebuggerButton control
                && control.getMessage().getString().equals("Watch")), "Command actions omit Watch");
            Map<String, WidgetBounds> result = new HashMap<>();
            for (String label : List.of("Previous recorded command", "Next recorded command", "Current", "Expand command panel"))
                result.put(label, WidgetBounds.of(button(screen, value -> value.equals(label))));
            clickAt(screen, result.get("Previous recorded command"));
            return result;
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(state.selectedExecutionFlow().invocationId() == 76, "Previous enters recorded history");
            for (String label : List.of("Previous recorded command", "Next recorded command", "Current"))
                require(original.get(label).equals(WidgetBounds.of(button(screen, value -> value.equals(label)))),
                    "History keeps the action slot fixed: " + label);
            // Reuse the actual pointer position, including at the disabled history boundary.
            clickAt(screen, original.get("Previous recorded command"));
            require(!state.isViewingCurrentCommand(), "A repeated Previous click never activates Current");
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-stable-history-actions");
        context.runOnClient(client -> clickAt(screen, original.get("Next recorded command")));
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(state.selectedExecutionFlow().invocationId() == 77 && state.selectedFlowStageIndex() == 0,
                "Next selects the first stage of the next recorded command visit");
            require(original.get("Next recorded command").equals(WidgetBounds.of(button(screen, value -> value.equals("Next recorded command")))),
                "Navigating forward does not move Next");
            require(clickAt(screen, original.get("Current")), "The fixed Current slot accepts its click");
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(state.isViewingCurrentCommand(), "Current returns to the authoritative stopped stage");
            require(original.get("Current").equals(WidgetBounds.of(button(screen, value -> value.equals("Current")))),
                "Returning to current does not move Current");
            clickAt(screen, original.get("Expand command panel"));
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(original.get("Expand command panel").equals(WidgetBounds.of(
                button(screen, value -> value.equals("Collapse command panel")))),
                "Expansion keeps its own toggle under the pointer");
            for (String label : List.of("Previous recorded command", "Next recorded command", "Current"))
                require(original.get(label).equals(WidgetBounds.of(button(screen, value -> value.equals(label)))),
                    "Expansion keeps the action row fixed: " + label);
        });
        context.takeScreenshot("codon-stable-expanded-actions");
        context.runOnClient(client -> clickAt(screen, original.get("Expand command panel")));
        context.waitTicks(2);
        context.runOnClient(client -> require(original.get("Expand command panel").equals(WidgetBounds.of(
            button(screen, value -> value.equals("Expand command panel")))),
            "Two clicks at one coordinate expand and collapse the panel"));
        context.takeScreenshot("codon-stable-collapsed-actions");
    }

    private static void checkVisibleClauseSelection(ClientGameTestContext context, CodonScreen screen,
                                                     ClientDebuggerState state) {
        context.runOnClient(client -> state.applyPause(longCommandFixture(client)));
        context.waitTicks(2);
        context.runOnClient(client -> click(screen, button(screen, value -> value.equals("Expand command panel"))));
        context.waitTicks(2);
        String label = context.computeOnClient(client -> screen.children().stream()
            .filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.getMessage().getString().contains("positioned"))
            .min(java.util.Comparator.comparingInt(DebuggerButton::getY).thenComparingInt(DebuggerButton::getX))
            .orElseThrow().getMessage().getString());
        int expectedStage = context.computeOnClient(client -> state.selectedExecutionFlow().stages().stream()
            .filter(stage -> stage.command().text().substring(stage.command().highlightStart(),
                stage.command().highlightEnd()).strip().equals(label.strip()))
            .findFirst().orElseThrow().index());
        WidgetBounds original = context.computeOnClient(client -> {
            require(!screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
                .anyMatch(button -> button.getMessage().getString().startsWith("execute positioned")),
                "The long command is scrolled past its first row before the click");
            var clause = button(screen, value -> value.equals(label));
            WidgetBounds bounds = WidgetBounds.of(clause);
            require(state.selectedFlowStageIndex() != expectedStage, "The visible target is not already selected");
            require(clickAt(screen, bounds), "The visible clause accepts the fixed-coordinate click");
            require(state.selectedFlowStageIndex() == expectedStage, "Click selects the intended earlier clause");
            return bounds;
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(original.equals(WidgetBounds.of(button(screen, value -> value.equals(label)))),
                "Selecting an already visible clause preserves its screen position");
            require(clickAt(screen, original), "The same coordinate still reaches the selected clause");
            require(state.selectedFlowStageIndex() == expectedStage, "A repeated click keeps the same clause selected");
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-visible-clause-stays-put");
        context.runOnClient(client -> {
            click(screen, button(screen, value -> value.equals("Collapse command panel")));
            state.applyPause(fixture(client));
        });
        context.waitTicks(2);
    }

    private static void checkContinuationWidth(ClientGameTestContext context, CodonScreen screen,
                                               ClientDebuggerState state) {
        context.runOnClient(client -> {
            PauseSnapshot base = fixture(client);
            CommandSnippet command = CommandSnippet.plain("execute as @e[tag=" + "continuation_".repeat(40) + "] run say wrapped");
            var frame = new CallFrame(0, base.location(), command, 77, 0);
            var source = new ExecutionFlowContext(1, base.pauseSources().getFirst());
            var stage = new ExecutionFlowStage(0, command, List.of(source), List.of(source), List.of(), List.of(),
                1, 1, 0, false, 0, 0, true, true, false, 0, List.of(frame));
            state.applyPause(new PauseSnapshot(base.location(), command, 0, List.of(frame), base.pauseSources(),
                List.of(new ExecutionFlowTrace(77, base.location(), List.of(stage), false)), PauseReason.STEP, base.pauseId()));
        });
        context.waitTicks(2);
        context.runOnClient(client -> click(screen, button(screen, value -> value.equals("Expand command panel"))));
        context.waitTicks(2);
        context.runOnClient(client -> {
            var fragments = screen.children().stream().filter(DebuggerButton.class::isInstance)
                .map(DebuggerButton.class::cast).filter(value -> value.getMessage().getString().contains("continuation_"))
                .sorted(java.util.Comparator.comparingInt(DebuggerButton::getY)).toList();
            require(fragments.size() > 1, "long stopped stage has visible continuation rows");
            require(fragments.stream().allMatch(value -> value.icon() == null),
                "Stopped clause fragments do not reserve the removed pause icon");
            require(fragments.stream().allMatch(value -> value.foregroundColor() == works.nuty.codon.client.ui.DebuggerTheme.AMBER),
                "Every fragment retains the actual stopped stage's amber emphasis");
            require(fragments.get(1).getX() < fragments.getFirst().getX(),
                "continuation reclaims the first fragment's breakpoint slot");
        });
        context.takeScreenshot("codon-command-continuation-width");
        context.runOnClient(client -> {
            click(screen, button(screen, value -> value.equals("Collapse command panel")));
            state.applyPause(fixture(client));
        });
        context.waitTicks(2);
    }

    private static PauseSnapshot longCommandFixture(Minecraft client) {
        PauseSnapshot base = fixture(client);
        StringBuilder text = new StringBuilder();
        List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            int start = text.length();
            text.append(i == 0 ? "execute positioned " : " positioned ")
                .append(10000 + i).append(" 64 ").append(20000 + i);
            ranges.add(new int[]{start, text.length()});
        }
        SourceLocation location = base.callStack().getFirst().location();
        ExecutionFlowContext source = new ExecutionFlowContext(1, base.pauseSources().getFirst());
        List<ExecutionFlowStage> stages = new ArrayList<>();
        for (int i = 0; i < ranges.size(); i++) {
            CommandSnippet command = new CommandSnippet(text.toString(), ranges.get(i)[0], ranges.get(i)[1]);
            stages.add(new ExecutionFlowStage(i, command, List.of(source), List.of(source), List.of(), List.of(),
                1, 1, 0, false, 0, 0, true, true, false, i,
                List.of(new CallFrame(0, location, command, 77, i))));
        }
        ExecutionFlowStage last = stages.getLast();
        return new PauseSnapshot(location, last.command(), 0, last.callStack(), List.of(source.source()),
            List.of(new ExecutionFlowTrace(77, location, stages, false)), PauseReason.STEP, base.pauseId());
    }

    private static void checkSourceColors(ClientGameTestContext context, CodonScreen screen,
                                           ClientDebuggerState state) {
        context.getInput().resizeWindow(1280, 1600);
        context.runOnClient(client -> {
            state.applyPause(beforeStage(fixture(client, true), 1));
            state.setGizmoMode(ClientDebuggerState.GizmoMode.LABELS);
        });
        context.waitTicks(3);
        context.runOnClient(client -> {
            require(button(screen, value -> value.equals("+ #1 Zombie 1")).foregroundColor() == DebuggerTheme.GREEN,
                "A genuinely changed executor source is green");
            require(state.selectedFlowStageIndex() == 1 && !state.selectedExecutionFlowStage().complete(),
                "Created world markers are visible without selecting the preceding as stage");
        });
        context.takeScreenshot("codon-default-after-as-created");
        context.runOnClient(client -> state.applyPause(beforeStage(fixture(client, true), 3)));
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(button(screen, value -> value.equals("#1 Zombie 1")).foregroundColor() == DebuggerTheme.TEXT,
                "An unchanged condition output keeps its normal color despite its new occurrence ID");
            DebuggerButton removed = button(screen, value -> value.equals("× Removed context"));
            require(removed.foregroundColor() == DebuggerTheme.RED, "Removed context is red even before selection");
            require(state.selectedFlowStageIndex() == 3 && state.displayedSources().size() == 10
                && state.worldSources().size() == 10,
                "The command panel and viewport expose the same removed-input context");
        });
        context.takeScreenshot("codon-default-after-if-removed");
        context.runOnClient(client -> {
            DebuggerButton removed = button(screen, value -> value.equals("× Removed context"));
            click(screen, removed);
            require(state.selectedFlowStageIndex() == 2 && state.selectedSourceDropped(),
                "Clicking the removed world marker opens its recorded stage");
        });
        context.waitTicks(2);
        context.runOnClient(client -> require(button(screen, value -> value.equals("× Removed context")).foregroundColor()
            == DebuggerTheme.RED, "Selecting a removed source preserves its red status"));
        context.takeScreenshot("codon-unchanged-neutral-removed-red");
        context.runOnClient(client -> state.selectExecutionFlowStage(3));
        context.waitTicks(2);
        context.runOnClient(client -> {
            require(button(screen, value -> value.equals("#1 Zombie 1")).foregroundColor() == DebuggerTheme.TEXT,
                "Reused source buttons clear green status on final-command inputs");
            state.applyPause(fixture(client));
            state.setGizmoMode(ClientDebuggerState.GizmoMode.GROUPED);
        });
        context.getInput().resizeWindow(1280, 800);
        context.waitTicks(2);
    }

    /** All statuses must be readable in both scan surfaces without interpreting their colors. */
    private static void checkContextStatusLabels(ClientGameTestContext context, CodonScreen screen,
                                                 ClientDebuggerState state) {
        context.getInput().resizeWindow(1280, 1600);
        context.runOnClient(client -> {
            state.applyPause(contextStatusFixture(client));
            state.setGizmoMode(ClientDebuggerState.GizmoMode.LABELS);
        });
        context.waitTicks(3);
        List<String> titles = List.of("#1 Unchanged", "+ #2 Created", "#3 Changed",
            "+ [4] Position context", "× Removed");
        Map<String, WidgetBounds> slots = context.computeOnClient(client -> {
            require(state.isDisplayedSourceCreated(1) && state.isDisplayedSourceChanged(2)
                && state.isDisplayedSourceCreated(3) && state.isDisplayedSourceDropped(4),
                "The fixture records distinct unchanged, changed, branched and removed contexts");
            Map<String, WidgetBounds> result = new HashMap<>();
            for (String title : titles) {
                DebuggerButton label = worldLabelButton(screen, title);
                require(label.hasChangedDot() == title.equals("#3 Changed"), "Only changed world labels show the dot");
                require(label.getWidth() <= 150, "Status labels retain the world width budget");
                result.put(title, WidgetBounds.of(label));
            }
            return Map.copyOf(result);
        });
        for (int index = 0; index < titles.size(); index++) {
            int sourceIndex = index;
            String title = titles.get(index);
            context.runOnClient(client -> {
                click(screen, worldLabelButton(screen, title));
                require(state.selectedSourceIndex() == sourceIndex,
                    "The status glyph preserves the exact source selection: " + title);
            });
            context.waitTicks(2);
            context.runOnClient(client -> {
                var inspector = works.nuty.codon.client.ui.layout.DebuggerLayout
                    .create(screen.width, screen.height, true).inspector();
                require(screen.children().stream().anyMatch(child -> child instanceof DebuggerButton button
                    && button.getMessage().getString().equals(title) && button.getX() >= inspector.x()
                    && button.getRight() <= inspector.x() + inspector.width()
                    && button.hasChangedDot() == (sourceIndex == 2)),
                    "The Contexts viewport reveals the selected status and source identity: " + title);
                for (String member : titles) require(slots.get(member).equals(WidgetBounds.of(worldLabelButton(screen, member))),
                    "Selecting a status label preserves every world slot: " + member);
            });
        }
        // The existing three-row viewport now shows Changed, Created (position), Removed together.
        context.runOnClient(client -> {
            for (String title : titles.subList(2, 5)) button(screen, value -> value.equals(title));
        });
        context.takeScreenshot("codon-context-status-labels");
        checkChangedDotsAtScales(context, screen, state);
        context.runOnClient(client -> {
            state.applyPause(fixture(client));
            state.setGizmoMode(ClientDebuggerState.GizmoMode.GROUPED);
        });
        context.getInput().resizeWindow(1280, 800);
        context.waitTicks(2);
    }

    private static void checkChangedDotsAtScales(ClientGameTestContext context, CodonScreen screen,
                                                  ClientDebuggerState state) {
        for (int[] settings : List.of(new int[]{1280, 800, 4}, new int[]{1280, 800, 6},
                new int[]{1280, 800, 9}, new int[]{640, 480, 6})) {
            context.getInput().resizeWindow(settings[0], settings[1]);
            context.runOnClient(client -> {
                state.preferences().setCustomUiScale(settings[2]);
                state.preferences().setUiScaleMode(DebuggerPreferences.UiScaleMode.CUSTOM);
                state.selectSource(2);
                client.setLastInputType(InputType.MOUSE);
                screen.setFocused(null);
            });
            context.waitTicks(3);
            String name = "codon-context-dot-" + settings[0] + "x" + settings[1] + "-scale-" + settings[2];
            context.runOnClient(client -> {
                List<DebuggerButton> labels = screen.children().stream().filter(DebuggerButton.class::isInstance)
                    .map(DebuggerButton.class::cast).filter(value -> value.getMessage().getString().equals("#3 Changed")).toList();
                require(labels.size() == 2 && labels.stream().allMatch(DebuggerButton::hasChangedDot),
                    "Changed list and world labels retain their drawn dot at " + name);
                double scale = screen.uiScale().effective();
                require(scale == settings[2] / 4.0, "The requested physical scale is applied at " + name);
                for (DebuggerButton label : labels) System.out.println("CONTEXT_DOT " + name + " scale=" + scale
                    + " widget=" + label.getX() + "," + label.getY() + "," + label.getWidth() + "," + label.getHeight());
            });
            context.takeScreenshot(name);
        }
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(client -> state.preferences().setUiScaleMode(DebuggerPreferences.UiScaleMode.FOLLOW_GAME));
        context.waitTicks(3);
        context.runOnClient(client -> {
            DebuggerButton label = worldLabelButton(screen, "#3 Changed");
            client.setLastInputType(InputType.KEYBOARD_TAB);
            screen.setFocused(label);
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-context-dot-keyboard-tooltip-en");
        context.runOnClient(client -> {
            state.setGizmoMode(ClientDebuggerState.GizmoMode.GROUPED);
            screen.setFocused(null);
            client.setLastInputType(InputType.MOUSE);
        });
        context.waitTicks(2);
        context.runOnClient(client -> require(button(screen, title -> title.startsWith("#3 Changed")
            && title.contains("  +")).hasChangedDot(), "A mixed group retains its named changed member's dot"));
        context.takeScreenshot("codon-context-dot-grouped");
        context.runOnClient(client -> state.setGizmoMode(ClientDebuggerState.GizmoMode.LABELS));
        String oldLanguage = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        var reload = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected("ko_kr");
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 400);
        context.runOnClient(client -> {
            client.setLastInputType(InputType.KEYBOARD_TAB);
            screen.setFocused(worldLabelButton(screen, "#3 Changed"));
        });
        context.waitTicks(2);
        context.takeScreenshot("codon-context-dot-keyboard-tooltip-ko");
        context.runOnClient(client -> client.setLastInputType(InputType.MOUSE));
        context.getInput().setCursorPos(0, 0);
        context.runOnClient(client -> screen.setFocused(null));
        double[] point = context.computeOnClient(client -> {
            DebuggerButton label = worldLabelButton(screen, "#3 Changed");
            double scale = screen.uiScale().effective();
            return new double[]{(label.getX() + label.getWidth() / 2.0) * scale,
                (label.getY() + label.getHeight() / 2.0) * scale};
        });
        context.getInput().setCursorPos(point[0], point[1]);
        context.waitTicks(12);
        context.takeScreenshot("codon-context-dot-hover-tooltip-ko");
        var restored = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected(oldLanguage);
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> restored.isDone() && client.gui.overlay() == null, 400);
        context.runOnClient(client -> screen.setFocused(null));
        context.getInput().setCursorPos(0, 0);
    }

    private static PauseSnapshot contextStatusFixture(Minecraft client) {
        PauseSnapshot base = fixture(client, true);
        PauseSource anchor = base.pauseSources().getFirst();
        var position = anchor.anchor();
        PauseSource unchanged = new PauseSource(position, 0, 0,
            new EntityRef(new UUID(0, 1), "Unchanged"), anchor.dimension());
        PauseSource changedBefore = new PauseSource(position, 0, 0,
            new EntityRef(new UUID(0, 2), "Changed"), anchor.dimension());
        PauseSource changed = new PauseSource(new Vec3d(position.x() + 0.5, position.y(), position.z()),
            0, 45, changedBefore.entity(), anchor.dimension());
        PauseSource created = new PauseSource(new Vec3d(position.x() + 1, position.y(), position.z()),
            0, 0, new EntityRef(new UUID(0, 3), "Created"), anchor.dimension());
        PauseSource createdPosition = new PauseSource(new Vec3d(position.x() + 1.5, position.y(), position.z()),
            0, 0, null, anchor.dimension());
        PauseSource removed = new PauseSource(new Vec3d(position.x() + 2, position.y(), position.z()),
            0, 0, new EntityRef(new UUID(0, 4), "Removed"), anchor.dimension());
        List<ExecutionFlowContext> inputs = List.of(new ExecutionFlowContext(1, unchanged),
            new ExecutionFlowContext(2, changedBefore), new ExecutionFlowContext(3, anchor),
            new ExecutionFlowContext(4, removed));
        List<ExecutionFlowContext> outputs = List.of(inputs.getFirst(), new ExecutionFlowContext(6, created),
            new ExecutionFlowContext(5, changed), new ExecutionFlowContext(7, createdPosition));
        CommandSnippet command = CommandSnippet.plain("execute as @e at @s");
        List<CallFrame> stack = List.of(new CallFrame(0, base.location(), command, 77, 0));
        ExecutionFlowStage stage = new ExecutionFlowStage(0, command, inputs, outputs,
            List.of(new ExecutionFlowEdge(1, 1), new ExecutionFlowEdge(2, 5),
                new ExecutionFlowEdge(3, 6), new ExecutionFlowEdge(3, 7)),
            List.of(4L), 4, 4, 1, false, 0, 0, true, true, false, 0, stack);
        return new PauseSnapshot(base.location(), command, 0, stack,
            inputs.stream().map(ExecutionFlowContext::source).toList(),
            List.of(new ExecutionFlowTrace(77, base.location(), List.of(stage), false)), PauseReason.STEP);
    }

    /** LABELS mode keeps each source's numbered screen slot stable when the selected source changes. */
    private static void checkLabelSlotsStayFixedAfterSourceSelection(ClientGameTestContext context, CodonScreen screen,
                                                                      ClientDebuggerState state) {
        Map<String, WidgetBounds> before = context.computeOnClient(client -> worldLabelBounds(screen));
        context.runOnClient(client -> {
            DebuggerButton fourth = worldLabelButton(screen, "#4 Zombie 4");
            click(screen, fourth);
            require(state.selectedSourceIndex() == 3, "Clicking a label selects its numbered source");
        });
        context.waitTicks(2);
        Map<String, WidgetBounds> after = context.computeOnClient(client -> worldLabelBounds(screen));
        require(after.equals(before), "Selecting a source preserves every LABELS-mode source label slot and membership");
        context.takeScreenshot("codon-labels-fourth-selected");
    }

    private static Map<String, WidgetBounds> worldLabelBounds(CodonScreen screen) {
        var world = works.nuty.codon.client.ui.layout.DebuggerLayout.create(screen.width, screen.height, true).world();
        Map<String, WidgetBounds> result = new HashMap<>();
        for (var child : screen.children()) {
            if (!(child instanceof DebuggerButton button)
                    || !button.getMessage().getString().matches("(?:#\\d+|\\[\\d+\\]) .*")
                    || button.getX() < world.x() || button.getY() < world.y()
                    || button.getRight() > world.x() + world.width() || button.getBottom() > world.y() + world.height()) {
                continue;
            }
            WidgetBounds previous = result.put(button.getMessage().getString(), WidgetBounds.of(button));
            require(previous == null, "Every LABELS-mode source has one world widget");
        }
        require(result.size() == 9, "The fixture exposes all nine same-dimension source labels in LABELS mode");
        require(result.containsKey("#1 Zombie 1") && result.containsKey("#8 Zombie 8") && result.containsKey("[9] Position context"),
            "LABELS mode retains numbered entity and position sources as separate widgets");
        return Map.copyOf(result);
    }

    private static DebuggerButton worldLabelButton(CodonScreen screen, String title) {
        var world = works.nuty.codon.client.ui.layout.DebuggerLayout.create(screen.width, screen.height, true).world();
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> button.getMessage().getString().equals(title)
                && button.getX() >= world.x() && button.getY() >= world.y()
                && button.getRight() <= world.x() + world.width() && button.getBottom() <= world.y() + world.height())
            .findFirst().orElseThrow(() -> new AssertionError("World label missing: " + title));
    }

    private record WidgetBounds(int x, int y, int width, int height) {
        private static WidgetBounds of(DebuggerButton button) {
            return new WidgetBounds(button.getX(), button.getY(), button.getWidth(), button.getHeight());
        }
    }

    /** Like a real pause packet: prior stages are complete, the selected next stage has not run. */
    private static PauseSnapshot beforeStage(PauseSnapshot fixture, int index) {
        ExecutionFlowTrace original = fixture.executionFlows().stream()
            .filter(flow -> flow.invocationId() == 77).findFirst().orElseThrow();
        ExecutionFlowStage stage = original.stages().get(index);
        List<ExecutionFlowStage> stages = new ArrayList<>(original.stages().subList(0, index));
        stages.add(new ExecutionFlowStage(stage.index(), stage.command(), stage.inputs(),
            stage.terminal() ? stage.inputs() : List.of(), List.of(), List.of(), stage.inputCount(),
            stage.terminal() ? stage.inputCount() : 0, 0, stage.terminal(), 0, 0, false, true, false,
            stage.observationOrder(), stage.callStack()));
        List<CallFrame> stack = new ArrayList<>(fixture.callStack());
        CallFrame top = stack.getFirst();
        stack.set(0, new CallFrame(top.depth(), top.location(), stage.command(), top.invocationId(), index));
        List<ExecutionFlowTrace> flows = fixture.executionFlows().stream().map(flow -> flow.invocationId() == 77
            ? new ExecutionFlowTrace(original.invocationId(), original.location(), stages, false) : flow).toList();
        return new PauseSnapshot(fixture.location(), stage.command(), fixture.depth(), stack,
            stage.inputs().stream().map(ExecutionFlowContext::source).toList(),
            flows,
            PauseReason.STEP, fixture.pauseId());
    }

    private static void checkSolidBlockMarkers(ClientGameTestContext context, TestSingleplayerContext world,
                                               ClientDebuggerState state) {
        List<BlockPos> positions = context.computeOnClient(client -> {
            PauseSnapshot snapshot = state.snapshot();
            PauseSource positionSource = snapshot.pauseSources().get(8);
            BlockLocation active = ((SourceLocation.Block) snapshot.location()).block();
            state.selectSource(8);
            state.setGizmoMode(ClientDebuggerState.GizmoMode.GROUPED);
            return List.of(BlockPos.containing(positionSource.anchor().x(), positionSource.anchor().y(), positionSource.anchor().z()),
                new BlockPos(active.x(), active.y(), active.z()), new BlockPos(active.x() + 2, active.y(), active.z()));
        });
        // Use real server blocks: air-only fixtures cannot expose marker depth conflicts.
        world.getServer().runOnServer(server -> positions.forEach(pos ->
            server.overworld().setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState())));
        context.waitFor(client -> positions.stream().allMatch(pos -> client.level.getBlockState(pos).is(Blocks.STONE)));
        world.getConnection().waitForChunksRender();
        context.waitTicks(3);
        context.takeScreenshot("codon-solid-block-markers");
        context.getInput().lookAt(15, 30);
        context.waitTicks(3);
        context.takeScreenshot("codon-angled-block-markers");
    }

    static InputManager input(Minecraft client, ClientDebuggerState state) {
        InputManager result = new InputManager(state, ignored -> {});
        result.keepFreecamKey = key(client, "key.codon.keep_freecam");
        result.hideUiKey = key(client, "key.codon.hide_ui");
        result.menuKey = key(client, "key.codon.open_menu");
        result.breakpointKey = key(client, "key.codon.breakpoint");
        result.resumeKey = key(client, "key.codon.resume");
        result.stepOverKey = key(client, "key.codon.step_over");
        result.stepIntoKey = key(client, "key.codon.step_into");
        return result;
    }

    private static KeyMapping key(Minecraft client, String name) {
        return Arrays.stream(client.options.keyMappings).filter(key -> key.getName().equals(name)).findFirst().orElseThrow();
    }

    static PauseSnapshot fixture(Minecraft client) {
        return fixture(client, false);
    }

    private static PauseSnapshot fixture(Minecraft client, boolean visibleDropped) {
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
        sources.add(new PauseSource(new Vec3d(visibleDropped ? x + 2 : x, y, visibleDropped ? z + 2 : z), 0, 0,
            new EntityRef(new UUID(0, 10), visibleDropped ? "Removed context" : "Nether context"),
            visibleDropped ? "minecraft:overworld" : "minecraft:the_nether"));
        SourceLocation location = new SourceLocation.Function(new FunctionLocation(new FunctionId("demo", "spawn_wave"), 12));
        String commandText = "execute as @e[type=zombie] at @s if entity @s[tag=keep] run function demo:move";
        CommandSnippet command = new CommandSnippet(commandText, 60, commandText.length());
        List<CallFrame> stack = List.of(new CallFrame(1, location, command, 77, 3),
            new CallFrame(0, new SourceLocation.Function(new FunctionLocation(new FunctionId("demo", "tick"), 4)),
                CommandSnippet.plain("function demo:spawn_wave"), 76, 0));
        SourceLocation pausedBlock = new SourceLocation.Block(new BlockLocation(
            (int) Math.floor(x) + 2, (int) Math.floor(y), (int) Math.floor(z) + 5, "minecraft:overworld"));
        List<PauseSource> finalSources = List.copyOf(sources.subList(0, 9));
        return new PauseSnapshot(pausedBlock, command, 1, stack, finalSources,
            List.of(parentFlowFixture(stack.get(1).location(), sources), flowFixture(pausedBlock, command, sources, stack)),
            PauseReason.BREAKPOINT);
    }

    private static ExecutionFlowTrace parentFlowFixture(SourceLocation location, List<PauseSource> sources) {
        ExecutionFlowContext sameSource = new ExecutionFlowContext(90, sources.getFirst());
        ExecutionFlowStage terminal = new ExecutionFlowStage(0, CommandSnippet.plain("function demo:spawn_wave"),
            List.of(sameSource), List.of(sameSource), List.of(), List.of(), 1, 1, 0, true,
            1, 1, true, true, false, 0, List.of(new CallFrame(0, location,
                CommandSnippet.plain("function demo:spawn_wave"), 76, 0)));
        return new ExecutionFlowTrace(76, location, List.of(terminal), false);
    }

    private static ExecutionFlowTrace flowFixture(SourceLocation location, CommandSnippet command,
                                                   List<PauseSource> sources, List<CallFrame> stack) {
        ExecutionFlowContext root = new ExecutionFlowContext(1, sources.get(8));
        List<ExecutionFlowContext> afterAs = contexts(2, sources);
        List<ExecutionFlowContext> afterAt = contexts(12, sources);
        List<ExecutionFlowContext> afterIf = contexts(22, sources.subList(0, 9));
        ExecutionFlowStage as = new ExecutionFlowStage(0,
            new CommandSnippet(command.text(), 8, 26), List.of(root), afterAs,
            afterAs.stream().map(output -> new ExecutionFlowEdge(root.id(), output.id())).toList(),
            List.of(), 1, 10, 0, false, 0, 0, true, true, false, 1);
        List<ExecutionFlowEdge> atEdges = new ArrayList<>();
        for (int i = 0; i < afterAs.size(); i++) {
            atEdges.add(new ExecutionFlowEdge(afterAs.get(i).id(), afterAt.get(i).id()));
        }
        ExecutionFlowStage at = new ExecutionFlowStage(1,
            new CommandSnippet(command.text(), 27, 32), afterAs, afterAt, atEdges,
            List.of(), 10, 10, 0, false, 0, 0, true, true, false, 2);
        List<ExecutionFlowEdge> ifEdges = new ArrayList<>();
        for (int i = 0; i < afterIf.size(); i++) {
            ifEdges.add(new ExecutionFlowEdge(afterAt.get(i).id(), afterIf.get(i).id()));
        }
        ExecutionFlowStage condition = new ExecutionFlowStage(2,
            new CommandSnippet(command.text(), 33, 55), afterAt, afterIf, ifEdges,
            List.of(afterAt.getLast().id()), 10, 9, 1, false, 0, 0, true, true, false, 3);
        ExecutionFlowStage terminal = new ExecutionFlowStage(3,
            new CommandSnippet(command.text(), 60, command.text().length()), afterIf, afterIf,
            List.of(), List.of(), 9, 9, 0, true, 9, 8, true, true, false, 4);
        return new ExecutionFlowTrace(77, location, List.of(as, at, condition, terminal).stream()
            .map(stage -> withStack(stage, List.of(new CallFrame(1, stack.getFirst().location(),
                stage.command(), 77, stage.index()), stack.get(1)))).toList(), false);
    }

    private static ExecutionFlowStage withStack(ExecutionFlowStage stage, List<CallFrame> stack) {
        return new ExecutionFlowStage(stage.index(), stage.command(), stage.inputs(), stage.outputs(), stage.edges(),
            stage.droppedContextIds(), stage.inputCount(), stage.outputCount(), stage.droppedCount(), stage.terminal(),
            stage.executionCount(), stage.successCount(), stage.complete(), stage.lineageComplete(), stage.truncated(),
            stage.observationOrder(), stack);
    }

    private static List<ExecutionFlowContext> contexts(long firstId, List<PauseSource> sources) {
        List<ExecutionFlowContext> result = new ArrayList<>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            result.add(new ExecutionFlowContext(firstId + i, sources.get(i)));
        }
        return List.copyOf(result);
    }

    private static DebuggerButton button(CodonScreen screen, Predicate<String> label) {
        return screen.children().stream().filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
            .filter(button -> label.test(button.getMessage().getString())).findFirst()
            .orElseThrow(() -> new AssertionError("Button missing; visible: " + screen.children().stream()
                .filter(DebuggerButton.class::isInstance).map(DebuggerButton.class::cast)
                .map(button -> button.getMessage().getString()).toList()));
    }

    private static void click(CodonScreen screen, DebuggerButton button) {
        require(clickAt(screen, WidgetBounds.of(button)), "Widget accepts click");
    }

    private static boolean clickAt(CodonScreen screen, WidgetBounds bounds) {
        // This fixture calls the screen directly, so mirror MouseHandler's input classification.
        Minecraft.getInstance().setLastInputType(InputType.MOUSE);
        MouseButtonEvent event = new MouseButtonEvent(bounds.x() + bounds.width() / 2.0,
            bounds.y() + bounds.height() / 2.0, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        boolean accepted = screen.mouseClicked(event, false);
        screen.mouseReleased(event);
        return accepted;
    }

    private static void checkBreakpointCodec() {
        checkBreakpointRoundTrip(List.of());
        checkBreakpointRoundTrip(List.of(
            new BlockLocation(-16, 64, 32, "minecraft:overworld"),
            new BlockLocation(8, -12, -4, "minecraft:the_nether")
        ));
    }

    private static void checkBreakpointRoundTrip(List<BlockLocation> expected) {
        FriendlyByteBuf roundTripBuffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            BreakpointSyncPayload payload = new BreakpointSyncPayload(expected);
            BreakpointSyncPayload.CODEC.encode(roundTripBuffer, payload);
            require(BreakpointSyncPayload.CODEC.decode(roundTripBuffer).blocks().equals(expected),
                "Breakpoint codec round trip preserves list contents and order");
            require(roundTripBuffer.readableBytes() == 0, "Breakpoint codec consumes its complete payload");
        } finally {
            roundTripBuffer.release();
        }

        FriendlyByteBuf wireBuffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            BreakpointSyncPayload.CODEC.encode(wireBuffer, new BreakpointSyncPayload(expected));
            require(wireBuffer.readVarInt() == expected.size(), "Breakpoint list uses its expected VarInt count");
            for (BlockLocation block : expected) {
                require(wireBuffer.readInt() == block.x() && wireBuffer.readInt() == block.y()
                        && wireBuffer.readInt() == block.z() && wireBuffer.readUtf().equals(block.dimension()),
                    "Breakpoint list keeps block order and block fields on the wire");
            }
            require(wireBuffer.readableBytes() == 0, "Breakpoint wire format has no trailing bytes");
        } finally {
            wireBuffer.release();
        }
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
