package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.InputType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.ClientFunctionSourceState;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.FunctionSourceScreen;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.SourceLocation;

/** Renders the smallest supported source browser with a wrapped stage command and captures it. */
@SuppressWarnings("UnstableApiUsage")
public final class FunctionSourceScreenGameTest implements FabricClientGameTest {
    private static final FunctionId FUNCTION = new FunctionId("codon_test", "long_stage");
    private static final String COMMAND =
        "execute as @e[type=minecraft:armor_stand,tag=codon_source_preview] at @s positioned ~ ~1 ~ run say a_very_long_stage_preview_value";

    @Override public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            // Minecraft keeps a minimum GUI height of 240, so 320x240 is the
            // smallest native client viewport for this visual check.
            context.getInput().resizeWindow(960, 720);
            context.runOnClient(client -> {
                client.options.guiScale().set(3);
                client.resizeGui();
            });
            world.getConnection().waitForChunksRender();
            FunctionSourceScreen screen = context.computeOnClient(client -> {
                ClientFunctionSourceState sources = loadedSource();
                require(CodonClientMod.state(), "client debugger state is initialized");
                FunctionSourceScreen result = new FunctionSourceScreen(new Screen(Component.empty()) { }, sources);
                client.setScreenAndShow(result);
                return result;
            });
            context.waitTicks(2);
            context.takeScreenshot("codon-function-source-320x240-before-selection");
            context.runOnClient(client -> {
                require(screen.width == 320 && screen.height == 240,
                    "test uses 320x240 GUI coordinates, actual " + screen.width + "x" + screen.height);
                client.setLastInputType(InputType.MOUSE);
                MouseButtonEvent click = new MouseButtonEvent(100, 130,
                    new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
                require(screen.mouseClicked(click, false), "selects the long function source line");
                screen.mouseReleased(click);
            });
            context.waitTicks(2);
            // The fixture function is not installed in the server's datapacks. Let its
            // NOT_FOUND reply settle, then provide the recorded parse spans for this UI test.
            context.runOnClient(client -> {
                var debugger = require(CodonClientMod.state(), "client debugger state is initialized");
                SourceLocation.Function location = new SourceLocation.Function(new FunctionLocation(FUNCTION, 1));
                long request = debugger.stagePreviews().begin(location);
                require(debugger.stagePreviews().accept(request, location, ClientStagePreviewState.Status.READY, COMMAND,
                    List.of(new ClientStagePreviewState.StageSpan(0, 0, 65, false),
                        new ClientStagePreviewState.StageSpan(1, 66, COMMAND.length(), true))),
                    "stage preview is accepted");
            });
            context.waitTicks(1);
            context.takeScreenshot("codon-function-source-320x240-stage-first");
            context.runOnClient(client -> screen.mouseScrolled(100, 130, 0, -1));
            context.waitTicks(2);
            context.takeScreenshot("codon-function-source-320x240-stage-scrolled");
            context.getInput().resizeWindow(960, 540);
            context.waitTicks(2);
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
                require(screen.width == 480 && screen.height == 270,
                    "test uses 480x270 GUI coordinates after resize, actual "
                        + screen.width + "x" + screen.height);
            });
            context.takeScreenshot("codon-function-source-480x270-stage");
            context.getInput().resizeWindow(1280, 720);
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
                var debugger = require(CodonClientMod.state(), "client debugger state is initialized");
                InputManager input = new InputManager(debugger, ignored -> { });
                KeyMapping.Category category = new KeyMapping.Category(InputManager.CATEGORY_ID);
                input.menuKey = key("open_menu", InputConstants.KEY_V, category);
                input.keepFreecamKey = key("keep_freecam", InputConstants.KEY_G, category);
                input.hideUiKey = key("hide_ui", InputConstants.KEY_H, category);
                input.breakpointKey = key("breakpoint", InputConstants.KEY_F10, category);
                input.resumeKey = key("resume", InputConstants.KEY_F7, category);
                input.stepOverKey = key("step_over", InputConstants.KEY_F8, category);
                input.stepIntoKey = key("step_into", InputConstants.KEY_F9, category);
                CodonScreen parent = new CodonScreen(input, new DebuggerOverlay(debugger));
                client.setScreenAndShow(parent);
                ClientFunctionSourceState wideSources = loadedSource();
                wideSources.rememberBrowseView(0, 0, 1, 0, 0);
                client.setScreenAndShow(new FunctionSourceScreen(parent, wideSources));
            });
            context.waitTicks(2);
            context.takeScreenshot("codon-function-source-640x360-docked");
            context.runOnClient(client -> searchBox(client.gui.screen()).setValue("long_stage"));
            context.getInput().resizeWindow(1600, 1000);
            context.waitTicks(2);
            context.runOnClient(client -> require(searchBox(client.gui.screen()).getValue().equals("long_stage"),
                "resizing retains the function search query"));
            context.takeScreenshot("codon-function-source-search-after-resize");
            context.getInput().resizeWindow(960, 540);
            context.waitTicks(2);
            context.runOnClient(client -> {
                Screen current = client.gui.screen();
                AbstractButton functions = current.children().stream().filter(AbstractButton.class::isInstance)
                    .map(AbstractButton.class::cast).filter(button -> button.visible
                        && button.getMessage().getString().equals("Functions")).findFirst().orElseThrow();
                MouseButtonEvent click = new MouseButtonEvent(functions.getX() + 2, functions.getY() + 2,
                    new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
                current.mouseClicked(click, false);
                current.mouseReleased(click);
                require(searchBox(current).getValue().equals("long_stage") && searchBox(current).visible,
                    "opening the compact function drawer retains the same query");
            });
            context.takeScreenshot("codon-function-source-filtered-drawer");
            context.runOnClient(client -> client.setScreenAndShow(null));
        }
    }

    private static KeyMapping key(String name, int code, KeyMapping.Category category) {
        return new KeyMapping("key.codon." + name, InputConstants.Type.KEYBOARD, code, category);
    }

    private static ClientFunctionSourceState loadedSource() {
        ClientFunctionSourceState sources = new ClientFunctionSourceState();
        sources.select(FUNCTION);
        long request = ((ClientFunctionSourceState.Request.ReadFunction) sources.drainRequests().getFirst()).requestId();
        sources.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY, FUNCTION,
            "gametest", "source-preview", false, 0, true, List.of(COMMAND)));
        sources.open();
        long listRequest = ((ClientFunctionSourceState.Request.ListFunctions) sources.drainRequests().getFirst()).requestId();
        sources.accept(new ClientFunctionSourceState.ListPage(listRequest, ClientFunctionSourceState.Status.READY,
            0, true, List.of(FUNCTION, new FunctionId("codon_test", "other_function"))));
        return sources;
    }

    private static EditBox searchBox(Screen screen) {
        return screen.children().stream().filter(EditBox.class::isInstance)
            .map(EditBox.class::cast).findFirst().orElseThrow();
    }

    private static <T> T require(T value, String message) {
        if (value == null) throw new AssertionError(message);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
