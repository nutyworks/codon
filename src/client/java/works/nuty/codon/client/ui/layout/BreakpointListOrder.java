package works.nuty.codon.client.ui.layout;

import java.util.Comparator;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.SourceLocation;

/** Sort source identities and their numeric positions independently of translated labels. */
public final class BreakpointListOrder {
    private BreakpointListOrder() { }

    public static final Comparator<BreakpointTarget> TARGETS = (left, right) -> {
        int location = compareLocations(left.location(), right.location());
        if (location != 0) return location;
        int stage = Integer.compare(left.stageIndex(), right.stageIndex());
        return stage != 0 ? stage : left.commandFingerprint().compareTo(right.commandFingerprint());
    };

    private static int compareLocations(SourceLocation left, SourceLocation right) {
        if (left instanceof SourceLocation.Block a && right instanceof SourceLocation.Block b) {
            int dimension = a.block().dimension().compareTo(b.block().dimension());
            if (dimension != 0) return dimension;
            int x = Integer.compare(a.block().x(), b.block().x());
            if (x != 0) return x;
            int y = Integer.compare(a.block().y(), b.block().y());
            return y != 0 ? y : Integer.compare(a.block().z(), b.block().z());
        }
        if (left instanceof SourceLocation.Function a && right instanceof SourceLocation.Function b) {
            int namespace = a.location().function().namespace().compareTo(b.location().function().namespace());
            if (namespace != 0) return namespace;
            int path = a.location().function().path().compareTo(b.location().function().path());
            return path != 0 ? path : Integer.compare(a.location().line(), b.location().line());
        }
        return left instanceof SourceLocation.Block ? -1 : 1;
    }
}
