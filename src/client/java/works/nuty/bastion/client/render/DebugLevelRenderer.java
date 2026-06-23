package works.nuty.bastion.client.render;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import works.nuty.bastion.client.state.ClientDebuggerState;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.EntityRef;
import works.nuty.bastion.core.model.PauseSnapshot;
import works.nuty.bastion.core.model.PauseSource;
import works.nuty.bastion.core.model.SourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the in-world debugger visualization from synced state: a marker cuboid for every block
 * breakpoint in the current dimension, a highlighted cuboid for the paused block, and a
 * point + facing arrow + label for each command source captured at the pause.
 */
public final class DebugLevelRenderer implements LevelRenderEvents.EndMain {
    private static final int BLOCK_FILL = ARGB.color(0.5f, 0xFF0000);
    private static final int BLOCK_FILL_ACTIVE = ARGB.color(0.5f, 0x00FFFF);
    private static final int BLOCK_STROKE = ARGB.color(1f, 0xFF0000);

    private final ClientDebuggerState state;

    public DebugLevelRenderer(ClientDebuggerState state) {
        this.state = state;
    }

    @Override
    public void endMain(@NonNull LevelRenderContext context) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        String dimension = level.dimension().identifier().toString();

        PauseSnapshot snapshot = state.snapshot();
        boolean paused = state.isPaused() && snapshot != null;

        BlockLocation pausedBlock = null;
        if (paused && snapshot.location() instanceof SourceLocation.Block block) {
            pausedBlock = block.block();
            if (pausedBlock.dimension().equals(dimension)) {
                Gizmos.cuboid(blockPos(pausedBlock), 1 / 128f, GizmoStyle.strokeAndFill(BLOCK_STROKE, 1, BLOCK_FILL_ACTIVE));
            }
        }

        for (BlockLocation breakpoint : state.blockBreakpoints()) {
            if (!breakpoint.dimension().equals(dimension) || breakpoint.equals(pausedBlock)) {
                continue;
            }
            Gizmos.cuboid(blockPos(breakpoint), 1 / 128f, GizmoStyle.strokeAndFill(BLOCK_STROKE, 1, BLOCK_FILL));
        }

        if (paused) {
            renderPauseSources(snapshot.pauseSources());
        }
    }

    private void renderPauseSources(List<PauseSource> sources) {
        DistinctColorGenerator colorGenerator = new DistinctColorGenerator();
        Map<@Nullable EntityRef, Integer> entityCounter = new HashMap<>();
        Map<ArrowKey, Integer> arrowCounter = new HashMap<>();

        for (int i = 0; i < sources.size(); i++) {
            PauseSource source = sources.get(i);
            Vec3 anchor = new Vec3(source.anchor().x(), source.anchor().y(), source.anchor().z());
            int color = colorGenerator.nextColor();

            EntityRef entity = source.entity();
            ArrowKey arrowKey = new ArrowKey(source.anchor().x(), source.anchor().y(), source.anchor().z(), source.pitch(), source.yaw());
            int entityCount = entityCounter.getOrDefault(entity, 0);
            int arrowCount = arrowCounter.getOrDefault(arrowKey, 0);

            Gizmos.point(anchor, color, 30);
            Vec3 facing = Vec3.applyLocalCoordinatesToRotation(new Vec2(source.pitch(), source.yaw()), Vec3.Z_AXIS);
            Gizmos.arrow(anchor, anchor.add(facing.scale(1 + arrowCount / 2f)), color);

            BlockPos labelPos = BlockPos.containing(anchor);
            if (entity != null && entityCount == 0) {
                Gizmos.billboardTextOverBlock(entity.uuid().toString(), labelPos, -1, color, 0.5f);
                Gizmos.billboardTextOverBlock(entity.name(), labelPos, -2, color, 0.5f);
            }
            Gizmos.billboardTextOverBlock("Source #" + i, labelPos, entityCount, color, 0.5f);

            entityCounter.put(entity, entityCount + 1);
            arrowCounter.put(arrowKey, arrowCount + 1);
        }
    }

    private static BlockPos blockPos(BlockLocation location) {
        return new BlockPos(location.x(), location.y(), location.z());
    }

    private record ArrowKey(double x, double y, double z, float pitch, float yaw) {
    }
}
