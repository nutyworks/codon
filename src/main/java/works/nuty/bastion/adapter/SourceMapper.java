package works.nuty.bastion.adapter;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.execution.tasks.BuildContexts;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import works.nuty.bastion.action.MacroLineAction;
import works.nuty.bastion.action.PlainLineAction;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.EntityRef;
import works.nuty.bastion.core.model.FunctionId;
import works.nuty.bastion.core.model.FunctionLocation;
import works.nuty.bastion.core.model.PauseSource;
import works.nuty.bastion.core.model.SourceLocation;
import works.nuty.bastion.core.model.Vec3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Translates Minecraft command-execution internals into the core's Minecraft-free value types.
 * This is the only place the engine's notion of "where am I executing" is tied to MC classes.
 */
public final class SourceMapper {
    private SourceMapper() {
    }

    /** Maps a Brigadier function/command id to the core's {@link FunctionId}. */
    public static FunctionId toFunctionId(Identifier id) {
        return new FunctionId(id.getNamespace(), id.getPath());
    }

    /** Maps a block position + dimension id to the core's {@link BlockLocation}. */
    public static BlockLocation toBlockLocation(BlockPos pos, String dimension) {
        return new BlockLocation(pos.getX(), pos.getY(), pos.getZ(), dimension);
    }

    /**
     * Derives the {@link SourceLocation} for a command stage, mirroring the four execution shapes
     * the debugger understands: top-level invocation, continuation, and plain/macro function lines.
     * Returns {@code null} for shapes introduced by other mods so the debugger skips them instead
     * of failing mid-command.
     */
    public static @Nullable SourceLocation toSourceLocation(BuildContexts<?> contexts) {
        if (contexts instanceof BuildContexts.TopLevel<?> topLevel
            && topLevel.source instanceof CommandSourceStack source) {
            return fromCommandSource(source);
        } else if (contexts instanceof BuildContexts.Continuation<?> continuation
            && continuation.originalSource instanceof CommandSourceStack source) {
            return fromCommandSource(source);
        } else if (contexts instanceof PlainLineAction<?> plain) {
            return functionLocation(plain.functionId, plain.lineNumber);
        } else if (contexts instanceof MacroLineAction<?> macro) {
            return functionLocation(macro.functionId, macro.lineNumber);
        }
        return null;
    }

    private static SourceLocation functionLocation(Identifier id, int line) {
        return new SourceLocation.Function(new FunctionLocation(toFunctionId(id), line));
    }

    private static SourceLocation fromCommandSource(CommandSourceStack source) {
        if (source.isPlayer()) {
            var player = source.getPlayer();
            return new SourceLocation.Player(player.getUUID(), player.getName().getString());
        }
        String dimension = source.getLevel().dimension().identifier().toString();
        return new SourceLocation.Block(toBlockLocation(BlockPos.containing(source.getPosition()), dimension));
    }

    /**
     * Captures the active command sources at a pause as serializable {@link PauseSource}s for the
     * in-world visualization (position, facing, and optional attached entity).
     */
    public static List<PauseSource> toPauseSources(Collection<? extends CommandSourceStack> sources) {
        List<PauseSource> result = new ArrayList<>(sources.size());
        for (CommandSourceStack source : sources) {
            result.add(toPauseSource(source));
        }
        return result;
    }

    public static PauseSource toPauseSource(CommandSourceStack source) {
        Vec3 anchored = source.getAnchor().apply(source);
        Vec2 rotation = source.getRotation();
        Entity entity = source.getEntity();
        EntityRef entityRef = entity == null
            ? null
            : new EntityRef(entity.getUUID(), entity.getPlainTextName());
        return new PauseSource(
            new Vec3d(anchored.x, anchored.y, anchored.z),
            rotation.x,
            rotation.y,
            entityRef,
            source.getLevel().dimension().identifier().toString()
        );
    }
}
