package works.nuty.codon.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
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
import net.minecraft.core.BlockPos;
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
import net.minecraft.server.rcon.RconConsoleSource;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import works.nuty.codon.core.model.*;
import works.nuty.codon.core.port.DebuggerEventSink;
import works.nuty.codon.core.port.ExecutionController;
import works.nuty.codon.core.service.BreakpointRegistry;
import works.nuty.codon.core.service.CallStack;
import works.nuty.codon.core.service.DebuggerEngine;
import works.nuty.codon.core.service.StepController;

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

    private static final String STAGED = "execute as a run say hi";
    private static final String STAGED_TEXT = "say first\n" + STAGED + "\n$execute as a run say $(m)\n";
    private static final SourceLocation STAGED_LINE = new SourceLocation.Function(new FunctionLocation(FUNCTION, 2));
    private static final BreakpointTarget STAGE_TWO = BreakpointTarget.stage(STAGED_LINE, 1, STAGED);

    @Test void stageBreakpointTargetsTheNumberedStageOfTheSavedCommandAndTogglesIt() throws Exception {
        var fixture = new Fixture(STAGED_TEXT).withParsing();
        assertEquals(1, fixture.run("function test:main 2 stage 2"));
        verify(fixture.engine).saveBreakpoint(BreakpointDefinition.plain(STAGE_TWO));
        assertEquals(List.of("command.codon.breakpoint.stage.success.set"), fixture.successKeys());

        var enabled = BreakpointDefinition.plain(STAGE_TWO);
        when(fixture.engine.breakpointDefinitions()).thenReturn(List.of(enabled));
        assertEquals(1, fixture.run("function test:main 2 stage 2"));
        verify(fixture.engine).saveBreakpoint(enabled.withEnabled(false));
    }

    @Test void conditionIsSetUpdatedAndClearedOnTheIntendedTarget() throws Exception {
        var fixture = new Fixture(STAGED_TEXT).withParsing();
        var outputZero = BreakpointCondition.count(BreakpointCondition.Kind.OUTPUT_COUNT, BreakpointCondition.Comparison.EQ, 0);
        assertEquals(1, fixture.run("function test:main 2 stage 1 condition output_count eq 0"));
        verify(fixture.engine).saveBreakpoint(new BreakpointDefinition(
            BreakpointTarget.stage(STAGED_LINE, 0, STAGED), true, outputZero));

        var disabled = new BreakpointDefinition(STAGE_TWO, false, outputZero);
        when(fixture.engine.breakpointDefinitions()).thenReturn(List.of(disabled));
        assertEquals(1, fixture.run("function test:main 2 stage 2 condition changed"));
        var changed = BreakpointCondition.event(BreakpointCondition.Kind.CHANGED);
        verify(fixture.engine).saveBreakpoint(new BreakpointDefinition(STAGE_TWO, true, changed));

        assertEquals(1, fixture.run("function test:main 2 stage 2 condition clear"));
        verify(fixture.engine).saveBreakpoint(BreakpointDefinition.plain(STAGE_TWO));

        assertEquals(1, fixture.run("function test:main 2 condition removed"));
        verify(fixture.engine).saveBreakpoint(new BreakpointDefinition(BreakpointTarget.whole(STAGED_LINE), true,
            BreakpointCondition.event(BreakpointCondition.Kind.REMOVED)));
        assertEquals(List.of("command.codon.breakpoint.condition.success.set", "command.codon.breakpoint.condition.success.set",
            "command.codon.breakpoint.condition.success.cleared", "command.codon.breakpoint.condition.success.set"),
            fixture.successKeys());
    }

    @Test void rejectedStagesAndConditionsReportTheReasonAndChangeNothing() throws Exception {
        var fixture = new Fixture(STAGED_TEXT).withParsing();
        for (var rejected : List.of(
            new String[] {"function test:main 2 stage 4", "command.codon.breakpoint.invalid.stage"},
            new String[] {"function test:main 1 stage 1", "command.codon.breakpoint.invalid.single_stage"},
            new String[] {"function test:main 3 stage 1", "command.codon.breakpoint.invalid.macro"},
            new String[] {"function test:main 3 condition changed", "command.codon.breakpoint.invalid.macro"},
            new String[] {"function test:main 2 stage 3 condition changed", "command.codon.breakpoint.invalid.final_stage"},
            new String[] {"function test:main 1 condition changed", "command.codon.breakpoint.invalid.final_stage"},
            new String[] {"function test:main 9 stage 1", "command.codon.breakpoint.invalid.line"},
            new String[] {"function test:main 2 stage 2 condition clear", "command.codon.breakpoint.condition.missing"})) {
            fixture.failure = null;
            assertEquals(0, fixture.run(rejected[0]), rejected[0]);
            assertEquals(rejected[1], fixture.failureKey(), rejected[0]);
        }
        for (String malformed : List.of("function test:main 2 stage 0", "function test:main 2 stage 2 condition output_count eq -1",
            "function test:main 2 stage 2 condition output_count", "function test:main 2 stage 2 condition changed eq 1",
            "function test:main 2 stage 2 condition always")) {
            assertThrows(CommandSyntaxException.class, () -> fixture.run(malformed), malformed);
        }
        verify(fixture.engine, never()).saveBreakpoint(any());
    }

    @Test void stageAndConditionCommandsKeepTheOwnerPermissionRequirement() throws Exception {
        var fixture = new Fixture(STAGED_TEXT).withParsing();
        when(fixture.source.permissions()).thenReturn(LevelBasedPermissionSet.GAMEMASTER);
        assertThrows(CommandSyntaxException.class, () -> fixture.run("function test:main 2 stage 2"));
        assertThrows(CommandSyntaxException.class, () -> fixture.run("function test:main 2 condition changed"));
        verify(fixture.engine, never()).saveBreakpoint(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "function test:main 2 stage 2",
        "function test:main 2 condition changed",
        "function test:main 2 stage 2 condition changed",
        "block 1 2 3 stage 2",
        "block 1 2 3 condition changed",
        "block 1 2 3 stage 2 condition changed"
    })
    void capacityFailureKeepsTheTranslationKeyAndReturnsReadableRconText(String command) throws Exception {
        var registry = new BreakpointRegistry();
        for (int line = 1; line <= BreakpointRegistry.MAX_DEFINITIONS; line++) {
            registry.put(BreakpointDefinition.plain(BreakpointTarget.whole(new SourceLocation.Function(
                new FunctionLocation(new FunctionId("test", "capacity"), line)))));
        }
        var before = registry.definitions();
        var sink = mock(DebuggerEventSink.class);
        var engine = new DebuggerEngine(registry, new StepController(), new CallStack(),
            mock(ExecutionController.class), sink);
        var fixture = new Fixture(STAGED_TEXT, engine).withParsing();
        var level = mock(ServerLevel.class);
        var entity = mock(CommandBlockEntity.class);
        var block = mock(BaseCommandBlock.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(fixture.server.getAllLevels()).thenReturn(List.of(level));
        when(level.isLoaded(any(BlockPos.class))).thenReturn(true);
        when(level.getBlockEntity(any(BlockPos.class))).thenReturn(entity);
        when(entity.getCommandBlock()).thenReturn(block);
        when(block.getCommand()).thenReturn(STAGED);
        var rcon = new RconConsoleSource(fixture.server);
        var source = spy(new CommandSourceStack(rcon, net.minecraft.world.phys.Vec3.ZERO,
            net.minecraft.world.phys.Vec2.ZERO, level, LevelBasedPermissionSet.OWNER,
            Component.literal("Rcon"), fixture.server));

        assertEquals(0, fixture.dispatcher.execute("codon breakpoint " + command, source));
        var message = ArgumentCaptor.forClass(Component.class);
        verify(source).sendFailure(message.capture());
        var contents = assertInstanceOf(TranslatableContents.class, message.getValue().getContents());
        assertAll(
            () -> assertEquals("command.codon.breakpoint.invalid.limit", contents.getKey()),
            () -> assertEquals("Breakpoint limit reached.", contents.getFallback()),
            () -> assertEquals("Breakpoint limit reached.", rcon.getCommandResponse(),
                "The real command source and RCON formatter must produce readable text without client assets"),
            () -> assertEquals(before, registry.definitions(),
                "Capacity rejection must leave every saved definition intact"));
        verifyNoInteractions(sink);
    }

    @Test void blockStageUsesTheLoadedCommandBlockSavedCommand() throws Exception {
        var fixture = new Fixture(STAGED_TEXT).withParsing();
        var level = mock(ServerLevel.class);
        var entity = mock(CommandBlockEntity.class);
        var commandBlock = mock(BaseCommandBlock.class);
        when(fixture.source.getLevel()).thenReturn(level);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(fixture.server.getAllLevels()).thenReturn(List.of(level));
        when(level.isLoaded(any(BlockPos.class))).thenReturn(true);
        when(level.getBlockEntity(any(BlockPos.class))).thenReturn(entity);
        when(entity.getCommandBlock()).thenReturn(commandBlock);
        when(commandBlock.getCommand()).thenReturn(STAGED);
        assertEquals(1, fixture.run("block 1 2 3 stage 2 condition created"));
        verify(fixture.engine).saveBreakpoint(new BreakpointDefinition(BreakpointTarget.stage(
            new SourceLocation.Block(new BlockLocation(1, 2, 3, "minecraft:overworld")), 1, STAGED), true,
            BreakpointCondition.event(BreakpointCondition.Kind.CREATED)));

        when(level.isLoaded(any(BlockPos.class))).thenReturn(false);
        assertEquals(0, fixture.run("block 1 2 3 stage 2"));
        assertEquals("command.codon.breakpoint.invalid.block", fixture.failureKey());
        verify(fixture.engine, times(1)).saveBreakpoint(any());
    }

    @Test void listShowsStageAndConditionAndHelpDocumentsTheSyntax() throws Exception {
        var fixture = new Fixture(STAGED_TEXT).withParsing();
        var conditional = new BreakpointDefinition(STAGE_TWO, true,
            BreakpointCondition.event(BreakpointCondition.Kind.CHANGED));
        var plain = BreakpointDefinition.plain(BreakpointTarget.whole(new SourceLocation.Function(new FunctionLocation(FUNCTION, 1))));
        var disabled = BreakpointDefinition.plain(BreakpointTarget.whole(STAGED_LINE)).withEnabled(false);
        when(fixture.engine.hasBreakpoints()).thenReturn(true);
        when(fixture.engine.breakpointDefinitions()).thenReturn(List.of(conditional, disabled, plain));
        assertEquals(1, fixture.run("list"));
        assertEquals(3, fixture.successes.size(), "header plus the two enabled definitions in line order");
        var first = fixture.successes.get(1);
        var second = fixture.successes.get(2);
        assertTrue(first.getSiblings().isEmpty());
        assertEquals(List.of("command.codon.breakpoint.list.stage", "command.codon.breakpoint.list.condition"),
            second.getSiblings().stream().map(sibling -> ((TranslatableContents) sibling.getContents()).getKey()).toList());

        // /help prints Brigadier's smart usage for the node it is asked about.
        assertTrue(fixture.usage("codon", "breakpoint").contains("function <function> <line> [condition|stage]"));
        assertTrue(fixture.usage("codon", "breakpoint").contains("block <pos> [condition|stage]"));
        assertTrue(fixture.usage("codon", "breakpoint", "function", "function", "line", "stage", "stage")
            .contains("(clear|created|removed|changed|input_count|output_count|created_count|removed_count|changed_count)"));
        assertEquals("eq <value>\nne <value>\nlt <value>\nle <value>\ngt <value>\nge <value>",
            fixture.usage("codon", "breakpoint", "block", "pos", "condition", "output_count"));
    }

    private static final class Fixture {
        final CommandSourceStack source = mock(CommandSourceStack.class);
        final MinecraftServer server = mock(MinecraftServer.class);
        final ResourceManager resources = mock(ResourceManager.class);
        final ServerFunctionManager functions = mock(ServerFunctionManager.class);
        final DebuggerEngine engine;
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        final List<Component> successes = new ArrayList<>();
        Component failure;

        Fixture(String text) throws IOException {
            this(text, mock(DebuggerEngine.class));
            when(engine.breakpointDefinitions()).thenReturn(List.of());
            when(engine.toggleFunctionBreakpoint(any())).thenReturn(true);
        }

        Fixture(String text, DebuggerEngine engine) throws IOException {
            this.engine = engine;
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
            doAnswer(call -> { failure = call.getArgument(0); return null; }).when(source).sendFailure(any());
            doAnswer(call -> {
                Supplier<Component> feedback = call.getArgument(0);
                successes.add(feedback.get());
                return null;
            }).when(source).sendSuccess(any(), eq(false));
            CodonCommand.register(dispatcher, engine);
        }

        Fixture withParsing() {
            var commands = mock(Commands.class);
            when(server.getCommands()).thenReturn(commands);
            when(commands.getDispatcher()).thenReturn(dispatcher);
            when(server.createCommandSourceStack()).thenReturn(source);
            dispatcher.register(Commands.literal("say").then(Commands.argument("message", StringArgumentType.greedyString())
                .executes(context -> 1)));
            var execute = dispatcher.register(Commands.literal("execute")
                .then(Commands.literal("run").redirect(dispatcher.getRoot())));
            execute.addChild(Commands.literal("as").then(Commands.argument("target", StringArgumentType.word())
                .redirect(execute)).build());
            return this;
        }

        String usage(String... path) {
            return String.join("\n", dispatcher.getSmartUsage(dispatcher.findNode(List.of(path)), source).values());
        }

        int run(String arguments) throws Exception { return dispatcher.execute("codon breakpoint " + arguments, source); }
        int function(int line) throws Exception { return dispatcher.execute("codon breakpoint function test:main " + line, source); }
        String failureKey() { return ((TranslatableContents) failure.getContents()).getKey(); }
        List<String> successKeys() { return successes.stream().map(message -> ((TranslatableContents) message.getContents()).getKey()).toList(); }
    }
}
