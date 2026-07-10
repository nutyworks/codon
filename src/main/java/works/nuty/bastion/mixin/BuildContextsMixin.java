package works.nuty.bastion.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.context.StringRange;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.execution.ChainModifiers;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.execution.Frame;
import net.minecraft.commands.execution.tasks.BuildContexts;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.bastion.BastionMod;
import works.nuty.bastion.adapter.SourceMapper;
import works.nuty.bastion.core.model.CommandSnippet;
import works.nuty.bastion.core.model.SourceLocation;
import works.nuty.bastion.core.service.CommandStageEvent;
import works.nuty.bastion.core.service.DebuggerEngine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Mixin(BuildContexts.class)
public class BuildContextsMixin<T extends ExecutionCommandSource<T>> {
    @Unique
    private static final AtomicLong bastion$nextChainId = new AtomicLong();

    /**
     * Continuation tasks create fresh {@code BuildContexts} instances for one forked execute
     * command. Frame + source + input gives those instances one debugger chain identity, while a
     * later invocation of the same command gets a new frame and therefore a new identity.
     */
    @Unique
    private static final Map<Frame, Map<String, Long>> bastion$chainIds = new WeakHashMap<>();

    @Shadow
    @Final
    public String commandInput;

    /** Fires once per modifier stage ({@code as @e}, {@code at @s}, …) with the sources entering it. */
    @Inject(
        method = "execute",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/brigadier/context/ContextChain;getTopContext()Lcom/mojang/brigadier/context/CommandContext;"
        ),
        slice = @Slice(
            to = @At(
                value = "INVOKE",
                target = "Ljava/util/List;isEmpty()Z"
            )
        )
    )
    void bastion$executeBeforeApplyModifier(
        T originalSource, List<T> initialSources, ExecutionContext<T> context, Frame frame, ChainModifiers initialModifiers, CallbackInfo ci,
        @Local(name = "currentSources") List<T> currentSources,
        @Local(name = "currentStage") ContextChain<T> currentStage
    ) {
        pauseIfNeeded(frame, currentStage, currentSources);
    }

    /** Fires exactly once per chain, for the executable stage with the fully resolved sources. */
    @Inject(
        method = "execute",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/List;isEmpty()Z"
        )
    )
    void bastion$executeAfterModifiers(
        T originalSource, List<T> initialSources, ExecutionContext<T> context, Frame frame, ChainModifiers initialModifiers, CallbackInfo ci,
        @Local(name = "currentStage") ContextChain<T> currentStage,
        @Local(name = "currentSources") List<T> currentSources
    ) {
        pauseIfNeeded(frame, currentStage, currentSources);
    }

    @Unique
    @SuppressWarnings("unchecked")
    private void pauseIfNeeded(final Frame frame, final ContextChain<T> currentStage, final List<T> currentSources) {
        final DebuggerEngine engine = BastionMod.engine();
        if (engine == null || !engine.isActive()) return;

        final SourceLocation location = SourceMapper.toSourceLocation((BuildContexts<?>) (Object) this);
        if (location == null) return;

        final StringRange range = currentStage.getTopContext().getRange();
        final CommandSnippet command = new CommandSnippet(this.commandInput, range.getStart(), range.getEnd());

        final List<CommandSourceStack> sources = (List<CommandSourceStack>) currentSources;

        engine.onCommandStage(new CommandStageEvent(
            bastion$chainId(frame, location),
            frame.depth(),
            location,
            command,
            () -> SourceMapper.toPauseSources(sources)
        ));
    }

    @Unique
    private long bastion$chainId(final Frame frame, final SourceLocation location) {
        final String commandKey = location + "\u0000" + commandInput;
        synchronized (bastion$chainIds) {
            final Map<String, Long> idsForFrame = bastion$chainIds.computeIfAbsent(frame, ignored -> new HashMap<>());
            return idsForFrame.computeIfAbsent(commandKey, ignored -> bastion$nextChainId.getAndIncrement());
        }
    }
}
