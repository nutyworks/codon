package works.nuty.codon.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.datafixers.util.Pair;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.FunctionArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.ArgumentCaptor;
import works.nuty.codon.core.service.BreakpointRegistry;
import works.nuty.codon.core.service.DebuggerEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CodonCommandLabelsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @SuppressWarnings("unchecked")
    void blockLimitReturnsATranslatableFailure() throws Exception {
        var source = mock(CommandSourceStack.class, RETURNS_DEEP_STUBS);
        CommandContext<CommandSourceStack> context = mock(CommandContext.class);
        when(context.getSource()).thenReturn(source);
        when(source.getLevel().dimension()).thenReturn(Level.OVERWORLD);
        var engine = mock(DebuggerEngine.class);
        when(engine.toggleBlockBreakpoint(any())).thenThrow(new BreakpointRegistry.LimitExceeded());
        try (var coordinates = mockStatic(BlockPosArgument.class)) {
            coordinates.when(() -> BlockPosArgument.getBlockPos(context, "pos")).thenReturn(new BlockPos(1, 64, 2));
            assertLimit("toggleBlockBreakpoint", context, engine, source);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void functionLimitReturnsATranslatableFailure() throws Exception {
        var source = mock(CommandSourceStack.class);
        CommandContext<CommandSourceStack> context = mock(CommandContext.class);
        when(context.getSource()).thenReturn(source);
        when(context.getArgument("line", int.class)).thenReturn(1);
        var engine = mock(DebuggerEngine.class);
        when(engine.toggleFunctionBreakpoint(any())).thenThrow(new BreakpointRegistry.LimitExceeded());
        try (var functions = mockStatic(FunctionArgument.class)) {
            functions.when(() -> FunctionArgument.getFunctionOrTag(context, "function"))
                .thenReturn(Pair.of(Identifier.fromNamespaceAndPath("demo", "state"), null));
            assertLimit("toggleFunctionBreakpoint", context, engine, source);
        }
    }

    private static void assertLimit(String methodName, CommandContext<CommandSourceStack> context,
                                    DebuggerEngine engine, CommandSourceStack source) throws Exception {
        var method = CodonCommand.class.getDeclaredMethod(methodName, CommandContext.class, DebuggerEngine.class);
        method.setAccessible(true);
        assertEquals(0, method.invoke(null, context, engine));
        var message = ArgumentCaptor.forClass(Component.class);
        verify(source).sendFailure(message.capture());
        var contents = assertInstanceOf(TranslatableContents.class, message.getValue().getContents());
        assertEquals("command.codon.breakpoint.error.limit", contents.getKey());
    }
}
