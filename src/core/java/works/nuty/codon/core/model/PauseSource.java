package works.nuty.codon.core.model;

import org.jspecify.annotations.Nullable;

/**
 * One command source that was active at the moment execution paused, expressed as serializable
 * data so the in-world visualization can be reconstructed on the client (including on a
 * dedicated server). The anchor is the execution reference point, not necessarily the attached
 * entity's position. Its dimension travels with it so execute-in sources cannot appear in the
 * viewer's unrelated world. A null entity means a position source, not necessarily a block.
 */
public record PauseSource(Vec3d anchor, float pitch, float yaw, @Nullable EntityRef entity,
                          String dimension) {
}
