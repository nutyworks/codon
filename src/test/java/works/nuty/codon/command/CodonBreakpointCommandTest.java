package works.nuty.codon.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.commands.functions.PlainTextFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerFunctionManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.*;
import works.nuty.codon.core.service.DebuggerEngine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodonBreakpointCommandTest {
    private static final FunctionId FUNCTION = new FunctionId("test", "main");
    private static final Identifier ID = Identifier.fromNamespaceAndPath("test", "main");
    private static final Identifier FILE = Identifier.fromNamespaceAndPath("test", "function/main.mcfunction");

    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void rejectsBlankCommentAndAbsentFileLinesWithoutChangingBreakpoints() throws Exception {
        var fixture = new Fixture("say first\n  # comment\n   \n$say $(message)\n");
        for (int line : List.of(2, 3, 5)) {
            assertEquals(0, fixture.function(line));
            assertEquals("command.codon.breakpoint.function.invalid_line", fixture.failureKey());
        }
        verify(fixture.engine, never()).toggleFunctionBreakpoint(any());
        assertEquals(1, fixture.function(1));
        assertEquals(1, fixture.function(4), "whole macro lines remain valid targets");
        verify(fixture.engine).toggleFunctionBreakpoint(new FunctionLocation(FUNCTION, 1));
        verify(fixture.engine).toggleFunctionBreakpoint(new FunctionLocation(FUNCTION, 4));
    }

    @Test void disablingAnExistingEntryDoesNotRequireItsChangedLineToRemainExecutable() throws Exception {
        var fixture = new Fixture("# replaced command\n");
        var target = BreakpointTarget.whole(new SourceLocation.Function(new FunctionLocation(FUNCTION, 1)));
        when(fixture.engine.breakpointDefinitions()).thenReturn(List.of(BreakpointDefinition.plain(target)));
        when(fixture.engine.toggleFunctionBreakpoint(new FunctionLocation(FUNCTION, 1))).thenReturn(false);
        assertEquals(1, fixture.function(1));
        assertEquals(List.of("command.codon.breakpoint.function.success.disabled"), fixture.successKeys());
        verify(fixture.engine).toggleFunctionBreakpoint(new FunctionLocation(FUNCTION, 1));
        verifyNoInteractions(fixture.resources);
    }

    @Test void loadedFunctionBeyondSourceLimitAndWithoutRawResourceKeepsPositionToggleWithWarning() throws Exception {
        String text = "say test\n".repeat(20_001);
        var fixture = new Fixture(text);
        fixture.dispatcher.register(Commands.literal("say").then(Commands.argument("message", StringArgumentType.greedyString())
            .executes(context -> 1)));
        var compiled = CommandFunction.fromLines(ID, fixture.dispatcher, fixture.source, text.lines().toList());
        assertEquals(20_001, ((PlainTextFunction<?>) compiled).entries().size(),
            "Minecraft can parse a function with more entries than the Source response line limit");
        when(fixture.functions.get(ID)).thenReturn(Optional.of(compiled));
        assertEquals(1, fixture.function(20_001));
        assertEquals(List.of("command.codon.breakpoint.function.source_unavailable", "command.codon.breakpoint.function.success.set"),
            fixture.successKeys());
        verify(fixture.engine).toggleFunctionBreakpoint(new FunctionLocation(FUNCTION, 20_001));
        fixture.successes.clear();
        when(fixture.resources.getResource(FILE)).thenReturn(Optional.empty());
        assertEquals(1, fixture.function(1));
        assertEquals(List.of("command.codon.breakpoint.function.source_unavailable", "command.codon.breakpoint.function.success.set"),
            fixture.successKeys());
        verify(fixture.engine).toggleFunctionBreakpoint(new FunctionLocation(FUNCTION, 1));
        assertNull(fixture.failure, "Unknown source must not imply that a loaded line is invalid");
    }

    @Test void coordinateCommandsKeepFutureAndUnloadedLocationsWithoutAcquiringChunks() throws Exception {
        var fixture = new Fixture("say test\n");
        var level = mock(ServerLevel.class);
        when(fixture.source.getLevel()).thenReturn(level);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(fixture.engine.toggleBlockBreakpoint(any())).thenReturn(true);
        assertEquals(1, fixture.dispatcher.execute("codon breakpoint block 10 80 -10", fixture.source));
        verify(fixture.engine).toggleBlockBreakpoint(new BlockLocation(10, 80, -10, "minecraft:overworld"));
        verify(level, never()).getBlockEntity(any());
        verify(level, never()).getChunk(anyInt(), anyInt());
    }

    private static final class Fixture {
        final CommandSourceStack source = mock(CommandSourceStack.class);
        final MinecraftServer server = mock(MinecraftServer.class);
        final ResourceManager resources = mock(ResourceManager.class);
        final ServerFunctionManager functions = mock(ServerFunctionManager.class);
        final DebuggerEngine engine = mock(DebuggerEngine.class);
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        final List<Component> successes = new ArrayList<>();
        Component failure;

        Fixture(String text) throws IOException {
            when(source.permissions()).thenReturn(LevelBasedPermissionSet.OWNER);
            when(source.getPosition()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);
            when(source.getServer()).thenReturn(server);
            when(server.getFunctions()).thenReturn(functions);
            when(server.getResourceManager()).thenReturn(resources);
            @SuppressWarnings("unchecked") CommandFunction<CommandSourceStack> function = mock(CommandFunction.class);
            when(functions.get(ID)).thenReturn(Optional.of(function));
            var bytes = text.getBytes(StandardCharsets.UTF_8);
            var resource = new Resource(mock(PackResources.class), () -> new ByteArrayInputStream(bytes));
            when(resources.getResource(FILE)).thenReturn(Optional.of(resource));
            when(engine.breakpointDefinitions()).thenReturn(List.of());
            when(engine.toggleFunctionBreakpoint(any())).thenReturn(true);
            doAnswer(call -> { failure = call.getArgument(0); return null; }).when(source).sendFailure(any());
            doAnswer(call -> {
                Supplier<Component> feedback = call.getArgument(0);
                successes.add(feedback.get());
                return null;
            }).when(source).sendSuccess(any(), eq(false));
            CodonCommand.register(dispatcher, engine);
        }

        int function(int line) throws Exception { return dispatcher.execute("codon breakpoint function test:main " + line, source); }
        String failureKey() { return ((TranslatableContents) failure.getContents()).getKey(); }
        List<String> successKeys() { return successes.stream().map(message -> ((TranslatableContents) message.getContents()).getKey()).toList(); }
    }
}
