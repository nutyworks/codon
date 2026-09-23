package works.nuty.codon.core.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** A command block or function line, optionally narrowed to one observed Brigadier stage. */
public record BreakpointTarget(SourceLocation location, int stageIndex, String commandFingerprint) {
    public BreakpointTarget {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(commandFingerprint, "commandFingerprint");
        if (location instanceof SourceLocation.Player) {
            throw new IllegalArgumentException("Player commands have no persistent source location");
        }
        if (stageIndex < -1 || (stageIndex == -1 && !commandFingerprint.isEmpty())
            || (stageIndex >= 0 && !commandFingerprint.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("Invalid breakpoint stage identity");
        }
    }

    public static BreakpointTarget whole(SourceLocation location) {
        return new BreakpointTarget(location, -1, "");
    }

    public static BreakpointTarget stage(SourceLocation location, int stageIndex, String command) {
        if (stageIndex < 0) throw new IllegalArgumentException("Stage index must be nonnegative");
        return new BreakpointTarget(location, stageIndex, fingerprint(command));
    }

    public boolean wholeCommand() {
        return stageIndex == -1;
    }

    /** The exact saved command text is part of a stage identity; editing it cannot silently retarget a stop. */
    public static String fingerprint(String command) {
        Objects.requireNonNull(command, "command");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(command.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java has no SHA-256 provider", impossible);
        }
    }
}
