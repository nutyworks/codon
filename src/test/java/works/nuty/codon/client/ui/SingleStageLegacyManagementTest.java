package works.nuty.codon.client.ui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import works.nuty.codon.client.CodonClientMod;
import works.nuty.codon.client.network.ClientNetworking;
import works.nuty.codon.client.state.*;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import works.nuty.codon.core.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual menu and destination classes with a headless Minecraft and mocked transport. */
class SingleStageLegacyManagementTest {
    private static final String COMMAND = "say one";
    private static final FunctionId FUNCTION = new FunctionId("test", "one");
    private static final SourceLocation FUNCTION_LOCATION = new SourceLocation.Function(new FunctionLocation(FUNCTION, 1));
    private static final SourceLocation BLOCK_LOCATION = new SourceLocation.Block(new BlockLocation(0, 64, 0, "minecraft:overworld"));

    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void savedActionsUseTheirExactDefinitionAndCannotRecreateARemovedEntry() throws Exception {
        for (SourceLocation location : List.of(FUNCTION_LOCATION, BLOCK_LOCATION)) {
            var state = state(location);
            var whole = BreakpointTarget.whole(location);
            var legacy = state.breakpoints().get(BreakpointTarget.stage(location, 0, COMMAND));
            var parent = mock(CodonScreen.class);
            var opened = new AtomicReference<Screen>();
            try (var minecraft = mockStatic(Minecraft.class); var layers = mockStatic(ScreenLayers.class);
                 var network = mockStatic(ClientNetworking.class)) {
                minecraft.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
                layers.when(() -> ScreenLayers.open(any(), any())).thenAnswer(invocation -> {
                    opened.set(invocation.getArgument(1)); return null;
                });
                BreakpointContextMenu.openEditor(parent, state, whole, COMMAND, 1, new Bounds(0, 0, 1, 1), () -> true, () -> { });
                var menu = assertInstanceOf(DebuggerContextMenu.class, opened.get());
                var choices = items(menu);
                assertEquals(3, choices.size(), "line options plus saved-stage toggle and options");
                choices.get(0).action().run();
                assertEquals(whole, ((BreakpointDefinition) field(opened.get(), "original")).target(), "line action keeps its target");
                choices.get(2).action().run();
                var editor = assertInstanceOf(BreakpointConditionScreen.class, opened.get());
                assertEquals(legacy, field(editor, "original"));
                assertTrue(editor.editsMarker(whole, COMMAND));
                choices.get(1).action().run();
                network.verify(() -> ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.TOGGLE, legacy));
                network.verifyNoMoreInteractions();
                state.breakpoints().begin(ClientBreakpointState.Action.TOGGLE, legacy);
                assertTrue(items(menu).get(0).enabled());
                assertFalse(items(menu).get(1).enabled());
                assertFalse(items(menu).get(2).enabled());
                choices.get(1).action().run();
                network.verifyNoMoreInteractions();
                state.breakpoints().reset();
                state.breakpoints().acceptPage(2, 0, true, List.of(BreakpointDefinition.plain(whole)));
                choices.get(1).action().run();
                choices.get(2).action().run();
                assertEquals(1, items(menu).size());
                network.verifyNoMoreInteractions();
                assertEquals(List.of(BreakpointDefinition.plain(whole)), state.breakpoints().definitions());
            }
        }
    }

    @Test void unknownMultipleStaleAndDifferentCommandsNeverOfferALegacyAlias() throws Exception {
        var state = state(FUNCTION_LOCATION);
        var opened = new AtomicReference<Screen>();
        try (var minecraft = mockStatic(Minecraft.class); var layers = mockStatic(ScreenLayers.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
            layers.when(() -> ScreenLayers.open(any(), any())).thenAnswer(invocation -> {
                opened.set(invocation.getArgument(1)); return null;
            });
            for (int count : List.of(0, 2)) {
                BreakpointContextMenu.openEditor(mock(CodonScreen.class), state, BreakpointTarget.whole(FUNCTION_LOCATION), COMMAND,
                    count, new Bounds(0, 0, 1, 1), () -> true, () -> { });
                assertInstanceOf(BreakpointConditionScreen.class, opened.get());
            }
            var target = BreakpointTarget.stage(FUNCTION_LOCATION, 0, COMMAND);
            int revision = 1;
            for (var saved : List.of(BreakpointDefinition.plain(target).withStaleSource(true),
                BreakpointDefinition.plain(BreakpointTarget.stage(FUNCTION_LOCATION, 0, "say old")))) {
                state.breakpoints().acceptPage(++revision, 0, true, List.of(saved));
                BreakpointContextMenu.openEditor(mock(CodonScreen.class), state, BreakpointTarget.whole(FUNCTION_LOCATION), COMMAND,
                    1, new Bounds(0, 0, 1, 1), () -> true, () -> { });
                assertInstanceOf(BreakpointConditionScreen.class, opened.get());
            }
        }
    }

    @Test void sourceListDestinationRevealsTheLineWithoutCreatingAStageControl() throws Exception {
        var state = state(FUNCTION_LOCATION);
        var sources = new ClientFunctionSourceState();
        sources.select(FUNCTION);
        long request = sources.drainRequests().getFirst().requestId();
        sources.accept(new ClientFunctionSourceState.SourcePage(request, ClientFunctionSourceState.Status.READY,
            FUNCTION, "fixture", "one", false, 0, true, List.of(COMMAND)));
        try (var minecraft = mockStatic(Minecraft.class); var codon = mockStatic(CodonClientMod.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
            codon.when(CodonClientMod::state).thenReturn(state);
            var source = new FunctionSourceScreen(mock(CodonScreen.class), sources);
            var font = mock(Font.class);
            set(source, "font", font);
            set(source, "panelHeight", 440);
            var widths = new HashMap<Integer, Float>(); COMMAND.codePoints().forEach(cp -> widths.put(cp, 6f));
            ((List<SourceCodeLine>) field(source, "codeLines")).add(new SourceCodeLine(COMMAND, font, widths));
            var legacy = BreakpointTarget.stage(FUNCTION_LOCATION, 0, COMMAND);
            source.revealBreakpoint(legacy);
            call(source, "revealBreakpoint", new Class<?>[0]);
            assertEquals(-1, field(source, "selectedStageIndex"));
            assertEquals(BreakpointTarget.whole(FUNCTION_LOCATION), field(source, "focusedBreakpoint"));
            assertTrue(((List<?>) call(source, "stagesForLine", new Class<?>[] {int.class, boolean.class}, 1, false)).isEmpty());
            assertEquals(2, state.breakpoints().definitions().size(), "navigation does not mutate either definition");
        }
    }

    @Test void blockListCanReachOnlyTheExactSavedSoleStage() throws Exception {
        var state = state(BLOCK_LOCATION);
        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
            var list = new BreakpointListScreen(mock(CodonScreen.class), state);
            assertEquals(true, call(list, "canNavigate", new Class<?>[] {BreakpointTarget.class}, BreakpointTarget.stage(BLOCK_LOCATION, 0, COMMAND)));
            assertEquals(false, call(list, "canNavigate", new Class<?>[] {BreakpointTarget.class}, BreakpointTarget.stage(BLOCK_LOCATION, 1, COMMAND)));
            assertEquals(false, call(list, "canNavigate", new Class<?>[] {BreakpointTarget.class}, BreakpointTarget.stage(BLOCK_LOCATION, 0, "say old")));
            var panel = new CommandPanel(state, () -> { });
            assertEquals(BreakpointTarget.whole(BLOCK_LOCATION), call(panel, "selectedBreakpoint", new Class<?>[0]));
        }
    }

    private static ClientDebuggerState state(SourceLocation location) {
        var state = new ClientDebuggerState();
        state.breakpoints().acceptPage(1, 0, true, List.of(BreakpointDefinition.plain(BreakpointTarget.whole(location)),
            BreakpointDefinition.plain(BreakpointTarget.stage(location, 0, COMMAND))));
        long request = state.stagePreviews().begin(location);
        state.stagePreviews().accept(request, location, ClientStagePreviewState.Status.READY, COMMAND,
            List.of(new ClientStagePreviewState.StageSpan(0, 0, COMMAND.length(), true)));
        var stage = new ExecutionFlowStage(0, CommandSnippet.plain(COMMAND), List.of(), List.of(), List.of(), List.of(),
            0, 0, 0, true, 0, 0, true, true, false);
        state.applyPause(new PauseSnapshot(location, CommandSnippet.plain(COMMAND), 0, List.of(), List.of(),
            List.of(new ExecutionFlowTrace(100, location, List.of(stage), false)), PauseReason.BREAKPOINT, 1));
        return state;
    }
    private static List<DebuggerContextMenu.Item> items(Object menu) throws Exception {
        return ((Supplier<List<DebuggerContextMenu.Item>>) field(menu, "source")).get();
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, arguments);
    }
    private static Field findField(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static Object field(Object object, String name) throws Exception { return findField(object, name).get(object); }
    private static void set(Object object, String name, Object value) throws Exception { findField(object, name).set(object, value); }
}
