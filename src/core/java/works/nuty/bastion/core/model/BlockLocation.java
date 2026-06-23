package works.nuty.bastion.core.model;

/**
 * A block position together with the dimension it lives in. Dimension-awareness is an
 * improvement over the original debugger, which keyed block breakpoints on coordinates alone.
 */
public record BlockLocation(int x, int y, int z, String dimension) {
}
