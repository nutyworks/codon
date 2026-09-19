package works.nuty.codon.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ContextChain;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.execution.ChainModifiers;
import net.minecraft.commands.execution.ExecutionControl;
import net.minecraft.commands.execution.tasks.IsolatedCall;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.commands.ExecuteCommand;
import net.minecraft.server.commands.InCommandFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import works.nuty.codon.adapter.CommandTrace;
import works.nuty.codon.adapter.SourceMapper;
import works.nuty.codon.core.model.ExecutionFlowWarning.Reason;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntPredicate;

/** Links the real condition callbacks to the exact input occurrences which scheduled them. */
@Mixin(ExecuteCommand.class)
public abstract class FunctionConditionMixin {
    @WrapMethod(method = "scheduleFunctionConditionsAndTest")
    private static <T extends ExecutionCommandSource<T>> void codon$deferCondition(
        T source, List<T> sources, Function<T, T> contextModifier, IntPredicate check,
        ContextChain<T> step, CompoundTag parameters, ExecutionControl<T> control,
        InCommandFunction<CommandContext<T>, Collection<CommandFunction<T>>> functions,
        ChainModifiers modifiers, Operation<Void> original
    ) {
        CommandTrace trace = CommandTrace.current();
        if (trace != null) trace.deferStage();
        try {
            original.call(source, sources, contextModifier, check, step, parameters, control, functions, modifiers);
        } catch (RuntimeException | Error failure) {
            if (trace != null) trace.abandonStage(Reason.EXECUTION_ERROR,
                failure.getClass().getSimpleName() + ": " + failure.getMessage());
            throw failure;
        } finally {
            if (trace != null) trace.finishDeferredScheduling();
        }
    }

    @WrapOperation(method = "scheduleFunctionConditionsAndTest", at = @At(value = "NEW",
        target = "(Ljava/util/function/Consumer;Lnet/minecraft/commands/CommandResultCallback;)Lnet/minecraft/commands/execution/tasks/IsolatedCall;"))
    private static <T extends ExecutionCommandSource<T>> IsolatedCall<T> codon$observeConditionResult(
        Consumer<ExecutionControl<T>> producer, CommandResultCallback callback, Operation<IsolatedCall<T>> original,
        @Local(name = "filteredSources") List<T> filteredSources
    ) {
        CommandTrace trace = CommandTrace.current();
        CommandTrace.DeferredInput input = trace == null ? null : trace.registerDeferredInput();
        if (input == null) return original.call(producer, callback);
        CommandResultCallback observed = (success, result) -> {
            int before = filteredSources.size();
            try {
                // Vanilla evaluates its predicate exactly once. Observe only the outputs it accepted.
                callback.onResult(success, result);
                int accepted = filteredSources.size() - before;
                input.acceptOutputs(accepted, limit -> filteredSources
                    .subList(before, before + Math.min(Math.max(0, accepted), limit)).stream()
                    .map(value -> SourceMapper.toPauseSource((CommandSourceStack) value)).toList());
            } catch (RuntimeException | Error failure) {
                input.failed(failure);
                throw failure;
            }
        };
        return original.call(producer, observed);
    }
}
