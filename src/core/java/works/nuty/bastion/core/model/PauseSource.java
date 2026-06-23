package works.nuty.bastion.core.model;

import org.jspecify.annotations.Nullable;

/**
 * One command source that was active at the moment execution paused, expressed as serializable
 * data so the in-world visualization can be reconstructed on the client (including on a
 * dedicated server). When {@link #entity()} is null the source is treated as a block/position
 * source and labelled at the block containing {@link #anchor()}.
 */
public record PauseSource(Vec3d anchor, float pitch, float yaw, @Nullable EntityRef entity) {
}
