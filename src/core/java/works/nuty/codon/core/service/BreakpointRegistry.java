package works.nuty.codon.core.service;

import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.BreakpointCondition;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.SourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** The authoritative breakpoint definitions, including disabled entries and their conditions. */
public final class BreakpointRegistry {
    public static final int MAX_DEFINITIONS = 4096;
    public static final class LimitExceeded extends IllegalStateException {
        public LimitExceeded() { super("Breakpoint definition limit reached"); }
    }
    private final Map<BreakpointTarget, BreakpointDefinition> definitions = new ConcurrentHashMap<>();

    /** Legacy command/F10 toggle: remove an existing breakpoint or add a plain one. */
    public boolean toggleBlock(BlockLocation block) {
        return toggleLegacy(BreakpointTarget.whole(new SourceLocation.Block(block)));
    }

    /** Legacy command toggle: remove an existing breakpoint or add a plain one. */
    public boolean toggleFunction(FunctionLocation location) {
        return toggleLegacy(BreakpointTarget.whole(new SourceLocation.Function(location)));
    }

    private boolean toggleLegacy(BreakpointTarget target) {
        if (definitions.remove(target) != null) return false;
        ensureCapacityFor(target);
        definitions.put(target, BreakpointDefinition.plain(target));
        return true;
    }

    /** Primary UI action. A second click disables without discarding the saved condition. */
    public BreakpointDefinition toggleEnabled(BreakpointTarget target) {
        ensureCapacityFor(target);
        return definitions.compute(target, (ignored, previous) -> previous == null
            ? BreakpointDefinition.plain(target) : previous.withEnabled(!previous.enabled()));
    }

    public void put(BreakpointDefinition definition) {
        ensureCapacityFor(definition.target());
        definitions.put(definition.target(), definition);
    }

    private void ensureCapacityFor(BreakpointTarget target) {
        if (!definitions.containsKey(target) && definitions.size() >= MAX_DEFINITIONS)
            throw new LimitExceeded();
    }

    public @Nullable BreakpointDefinition get(BreakpointTarget target) {
        return definitions.get(target);
    }

    public @Nullable BreakpointDefinition remove(BreakpointTarget target) {
        return definitions.remove(target);
    }

    public List<BreakpointDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    /** Previous whole-command match contract, used by existing command and test paths. */
    public boolean matches(SourceLocation location) {
        if (location instanceof SourceLocation.Player) return false;
        BreakpointDefinition definition = definitions.get(BreakpointTarget.whole(location));
        return definition != null && definition.enabled() && definition.condition().equals(BreakpointCondition.ALWAYS);
    }

    public boolean isEmpty() {
        return definitions.values().stream().noneMatch(BreakpointDefinition::enabled);
    }

    public int size() {
        return definitions.size();
    }

    public void clear() {
        definitions.clear();
    }

    /** Active whole-block breakpoints for the older HUD and wire protocol. */
    public Set<BlockLocation> blocks() {
        return definitions.values().stream()
            .filter(definition -> definition.enabled() && definition.target().wholeCommand())
            .map(BreakpointDefinition::target)
            .map(BreakpointTarget::location)
            .filter(location -> location instanceof SourceLocation.Block)
            .map(location -> ((SourceLocation.Block) location).block())
            .collect(Collectors.toUnmodifiableSet());
    }

    /** Active whole-function-line breakpoints for commands and older protocol. */
    public Set<FunctionLocation> functions() {
        return definitions.values().stream()
            .filter(definition -> definition.enabled() && definition.target().wholeCommand())
            .map(BreakpointDefinition::target)
            .map(BreakpointTarget::location)
            .filter(location -> location instanceof SourceLocation.Function)
            .map(location -> ((SourceLocation.Function) location).location())
            .collect(Collectors.toUnmodifiableSet());
    }
}
