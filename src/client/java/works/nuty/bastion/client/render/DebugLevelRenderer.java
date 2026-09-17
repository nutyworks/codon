package works.nuty.bastion.client.render;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.NonNull;
import works.nuty.bastion.client.state.ClientDebuggerState;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.ExecutionFlowContext;
import works.nuty.bastion.core.model.ExecutionFlowEdge;
import works.nuty.bastion.core.model.ExecutionFlowStage;
import works.nuty.bastion.core.model.PauseSnapshot;
import works.nuty.bastion.core.model.PauseSource;
import works.nuty.bastion.core.model.SourceLocation;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Draws debugger markers above terrain, preserving their exact execution coordinates. */
public final class DebugLevelRenderer implements LevelRenderEvents.EndMain {
    private static final int BREAKPOINT_RED = ARGB.color(0.9f, 0xFC8C8C);
    private static final int PAUSED_AMBER = ARGB.color(1.0f, 0xF3C171);
    private static final int SOURCE_TEAL = ARGB.color(0.95f, 0x75DFD6);
    private static final int MUTED_TEAL = ARGB.color(0.28f, 0x567C7B);
    private static final int SELECTED_TEAL = ARGB.color(1.0f, 0x75DFD6);
    private static final int DROPPED_RED = ARGB.color(0.95f, 0xFC8C8C);
    private static final int FLOW_TEAL = ARGB.color(0.7f, 0x75DFD6);

    private static final float BREAKPOINT_WIDTH = 1.0f;
    private static final float PAUSED_WIDTH = 3.0f;
    private static final float SOURCE_WIDTH = 1.25f;
    private static final float SELECTED_WIDTH = 2.5f;
    private static final float FACING_LENGTH = 1.0f;
    private static final float RING_RADIUS = 0.38f;
    private static final float SQUARE_RADIUS = 0.32f;
    private static final int RING_SEGMENTS = 20;

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

        BlockLocation pausedBlock = pausedBlock(snapshot, dimension);
        for (BlockLocation breakpoint : state.blockBreakpoints()) {
            if (breakpoint.dimension().equals(dimension) && !breakpoint.equals(pausedBlock)) {
                Gizmos.cuboid(blockPos(breakpoint), GizmoStyle.stroke(BREAKPOINT_RED, BREAKPOINT_WIDTH)).setAlwaysOnTop();
            }
        }
        if (pausedBlock != null) {
            renderPausedBlock(pausedBlock);
        }
        if (paused) {
            renderFlowConnections(state.selectedExecutionFlowStage(), dimension);
            renderPauseSources(state.displayedSources(), dimension);
        }
    }

    private static BlockLocation pausedBlock(PauseSnapshot snapshot, String dimension) {
        if (snapshot != null && snapshot.location() instanceof SourceLocation.Block block
                && block.block().dimension().equals(dimension)) {
            return block.block();
        }
        return null;
    }

    private static void renderPausedBlock(BlockLocation location) {
        BlockPos pos = blockPos(location);
        Gizmos.cuboid(pos, GizmoStyle.stroke(PAUSED_AMBER, PAUSED_WIDTH)).setAlwaysOnTop();
        // A center point distinguishes the active stop from ordinary breakpoint outlines.
        Gizmos.point(Vec3.atCenterOf(pos), PAUSED_AMBER, 7.0f).setAlwaysOnTop();
    }

    private void renderPauseSources(List<PauseSource> sources, String dimension) {
        Map<SourceKey, SourceMarker> markers = new LinkedHashMap<>();
        int selectedIndex = state.selectedSourceIndex();

        for (int index = 0; index < sources.size(); index++) {
            PauseSource source = sources.get(index);
            if (!dimension.equals(source.dimension())) {
                continue;
            }
            Vec3 anchor = new Vec3(source.anchor().x(), source.anchor().y(), source.anchor().z());
            Vec3 facing = Vec3.directionFromRotation(source.pitch(), source.yaw());
            if (!isFinite(anchor) || !isFinite(facing) || facing.lengthSqr() < 1.0e-8) {
                continue;
            }

            boolean dropped = state.isDisplayedSourceDropped(index);
            SourceKey key = new SourceKey(anchor.x, anchor.y, anchor.z, source.pitch(), source.yaw(),
                source.entity() != null, dropped);
            SourceMarker marker = markers.get(key);
            if (marker == null || index == selectedIndex) {
                markers.put(key, new SourceMarker(anchor, facing.normalize(), source.entity() != null,
                    index == selectedIndex, dropped));
            }
        }

        boolean focus = state.gizmoMode() != ClientDebuggerState.GizmoMode.LABELS;
        for (SourceMarker marker : markers.values()) {
            boolean selected = marker.selected();
            int color = selected && !marker.dropped() ? SELECTED_TEAL : marker.dropped() ? DROPPED_RED
                : focus ? MUTED_TEAL : SOURCE_TEAL;
            float width = selected ? SELECTED_WIDTH : SOURCE_WIDTH;
            renderSourceMarker(marker, color, width, selected);
        }
    }

    private void renderFlowConnections(ExecutionFlowStage stage, String dimension) {
        if (stage == null || stage.edges().isEmpty()) return;
        Map<Long, PauseSource> inputs = new HashMap<>();
        Map<Long, PauseSource> outputs = new HashMap<>();
        for (ExecutionFlowContext context : stage.inputs()) inputs.put(context.id(), context.source());
        for (ExecutionFlowContext context : stage.outputs()) outputs.put(context.id(), context.source());
        ExecutionFlowContext selected = state.selectedFlowContext();
        long selectedId = selected == null ? 0 : selected.id();

        for (ExecutionFlowEdge edge : stage.edges()) {
            PauseSource fromSource = inputs.get(edge.inputContextId());
            PauseSource toSource = outputs.get(edge.outputContextId());
            if (fromSource == null || toSource == null
                || !dimension.equals(fromSource.dimension()) || !dimension.equals(toSource.dimension())) continue;
            Vec3 from = vec(fromSource);
            Vec3 to = vec(toSource);
            if (!isFinite(from) || !isFinite(to) || from.distanceToSqr(to) < 1.0e-6) continue;
            boolean selectedEdge = edge.outputContextId() == selectedId;
            Gizmos.arrow(from, to, selectedEdge ? SELECTED_TEAL : FLOW_TEAL,
                selectedEdge ? SELECTED_WIDTH : SOURCE_WIDTH).setAlwaysOnTop();
        }
    }

    private static void renderSourceMarker(SourceMarker marker, int color, float width, boolean selected) {
        Vec3 anchor = marker.anchor();
        if (marker.entityPresent()) {
            renderHorizontalRing(anchor, RING_RADIUS, color, width);
        } else {
            renderHorizontalSquare(anchor, SQUARE_RADIUS, color, width);
        }
        Gizmos.arrow(anchor, anchor.add(marker.facing().scale(FACING_LENGTH)), color, width).setAlwaysOnTop();
        if (selected) {
            Gizmos.point(anchor, color, 8.0f).setAlwaysOnTop();
        }
    }

    private static void renderHorizontalRing(Vec3 center, float radius, int color, float width) {
        for (int segment = 0; segment < RING_SEGMENTS; segment++) {
            double from = Math.TAU * segment / RING_SEGMENTS;
            double to = Math.TAU * (segment + 1) / RING_SEGMENTS;
            Vec3 start = center.add(Math.cos(from) * radius, 0.0, Math.sin(from) * radius);
            Vec3 end = center.add(Math.cos(to) * radius, 0.0, Math.sin(to) * radius);
            Gizmos.line(start, end, color, width).setAlwaysOnTop();
        }
    }

    private static void renderHorizontalSquare(Vec3 center, float radius, int color, float width) {
        Vec3 northwest = center.add(-radius, 0.0, -radius);
        Vec3 northeast = center.add(radius, 0.0, -radius);
        Vec3 southeast = center.add(radius, 0.0, radius);
        Vec3 southwest = center.add(-radius, 0.0, radius);
        Gizmos.line(northwest, northeast, color, width).setAlwaysOnTop();
        Gizmos.line(northeast, southeast, color, width).setAlwaysOnTop();
        Gizmos.line(southeast, southwest, color, width).setAlwaysOnTop();
        Gizmos.line(southwest, northwest, color, width).setAlwaysOnTop();
    }

    private static boolean isFinite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static Vec3 vec(PauseSource source) {
        return new Vec3(source.anchor().x(), source.anchor().y(), source.anchor().z());
    }

    private static BlockPos blockPos(BlockLocation location) {
        return new BlockPos(location.x(), location.y(), location.z());
    }

    private record SourceKey(double x, double y, double z, float pitch, float yaw,
                             boolean entityPresent, boolean dropped) {
    }

    private record SourceMarker(Vec3 anchor, Vec3 facing, boolean entityPresent,
                                boolean selected, boolean dropped) {
    }
}
