package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.AbstractContainerEventHandler;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.navigation.ScreenDirection;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** Logical focus order, including controls outside a scrolling panel's viewport. */
public final class DebuggerNavigation {
    public enum Group { TOOLBAR, VIEW_MENU, SOURCES, SOURCE_DETAILS, NBT, WATCH, WORLD, CALL_PATH, COMMAND, ACTIONS,
        WINDOW_HEADER, EDITOR, WATCH_LIST }

    private record Target(String id, Group group, int row, int column, Runnable reveal,
                          boolean active, boolean retainWhenInactive) { }
    private static final Comparator<Target> ORDER = Comparator.comparing(Target::group)
        .thenComparingInt(Target::row).thenComparingInt(Target::column).thenComparing(Target::id);
    private final Map<String, Target> targets = new HashMap<>();
    private final Map<String, AbstractWidget> visible = new HashMap<>();
    private final Map<Group, String> groupCursors = new HashMap<>();
    private List<Target> ordered = List.of();
    private @Nullable Target cursor;
    private @Nullable String pending;
    private record DeferredFocus(String id, BooleanSupplier current) { }
    private @Nullable DeferredFocus deferred;
    private boolean mouseNavigation;
    private boolean awaitingScrollRender;
    private boolean keyboard;

    public void rememberFocus(@Nullable GuiEventListener focused) {
        if (deferred != null || pending != null || focused == null) return;
        visible.forEach((id, button) -> {
            if (button == focused && targets.containsKey(id)) remember(targets.get(id));
        });
    }

    public void beginFrame(boolean keyboard) {
        this.keyboard = keyboard;
        if (deferred != null && !deferred.current().getAsBoolean()) deferred = null;
        targets.clear();
        visible.clear();
    }

    /** Apply the current layout's reveal callback after its selection/resize scroll adjustment. */
    public void revealFocus(Group group) {
        if (deferred != null) {
            Target destination = targets.get(deferred.id());
            if (destination != null && destination.group() == group) {
                if (deferred.current().getAsBoolean()) requestFocus(destination.id());
                deferred = null;
            }
        }
        if (cursor == null || cursor.group() != group || pending == null && (!keyboard || mouseNavigation)) return;
        Target target = targets.get(cursor.id());
        if (target != null) target.reveal().run();
    }

    public void add(String id, Group group, int row, int column, Runnable reveal) {
        targets.put(id, new Target(id, group, row, column, reveal, true, false));
    }

    /** Keep an existing focus while an asynchronous edit disables the control; traversal skips it. */
    public void addRetained(String id, Group group, int row, int column, boolean active, Runnable reveal) {
        targets.put(id, new Target(id, group, row, column, reveal, active, true));
    }

    /** Select the exact logical row after an explicit Add/Edit action, including off-screen rows. */
    public void requestFocus(String id) {
        Target target = targets.get(id);
        if (target == null) return;
        remember(target);
        pending = id;
    }

    /** One destination render only; ordinary focus requests still require a registered target. */
    public void requestFocusOnNextFrame(String id, BooleanSupplier current) {
        deferred = new DeferredFocus(id, current);
        pending = null;
    }

    public void cancelDeferredFocus() { deferred = null; }

    public void bind(String id, Group group, AbstractWidget button) {
        Target target = targets.get(id);
        if (!button.visible || !button.active && (target == null || !target.retainWhenInactive())) {
            targets.remove(id);
            return;
        }
        visible.put(id, button);
        targets.put(id, target == null
            ? new Target(id, group, button.getY(), button.getX(), () -> { }, button.active, false)
            : new Target(id, group, target.row(), target.column(), target.reveal(), button.active, target.retainWhenInactive()));
    }

    public void endFrame() {
        // A missing destination must not capture focus if it appears in a later context.
        deferred = null;
        ordered = targets.values().stream().filter(Target::active).sorted(ORDER).toList();
        for (int i = 0; i < ordered.size(); i++) {
            AbstractWidget button = visible.get(ordered.get(i).id());
            if (button != null) button.setTabOrderGroup(i);
        }
    }

    /** Mouse scrolling must never silently arm a different button for Enter. */
    public void mouseScrolled() {
        cancelDeferredFocus();
        mouseNavigation = true;
        awaitingScrollRender = true;
    }

    public @Nullable AbstractWidget restoreFocus(@Nullable GuiEventListener previous, boolean keyboard) {
        awaitingScrollRender = false;
        if (pending != null) {
            AbstractWidget result = visible.get(pending);
            pending = null;
            if (result != null) return result;
            return nearestVisible();
        }
        if (cursor != null && (previous != null || keyboard && !mouseNavigation)) {
            AbstractWidget result = visible.get(cursor.id());
            if (result != null) return result;
            if (keyboard && !mouseNavigation) return nearestVisible();
        }
        if (cursor == null && keyboard && !mouseNavigation) return nearestVisible();
        return null;
    }

    private @Nullable AbstractWidget nearestVisible() {
        Target origin = cursor;
        Target nearest = ordered.stream().filter(target -> visible.containsKey(target.id()))
            .filter(target -> origin == null || target.group() == origin.group())
            .min(Comparator.<Target>comparingLong(target -> origin == null ? 0
                    : Math.abs((long) target.row() - origin.row()) * 10000 + Math.abs((long) target.column() - origin.column()))
                .thenComparing(ORDER)).orElse(null);
        if (nearest == null) return null;
        remember(nearest);
        return visible.get(nearest.id());
    }

    public boolean keyPressed(KeyEvent event, @Nullable GuiEventListener focused,
                              Consumer<@Nullable GuiEventListener> focus) {
        if (event.key() == InputConstants.KEY_TAB || isArrow(event.key())) cancelDeferredFocus();
        rememberFocus(focused);
        int key = event.key();
        if (key == InputConstants.KEY_TAB || isArrow(key)) mouseNavigation = false;
        if (key == InputConstants.KEY_TAB) {
            if (ordered.isEmpty()) return false;
            int direction = event.hasShiftDown() ? -1 : 1;
            // Flow exposes each marker immediately before its clause. Traverse those
            // controls before leaving the region, including wrapped/off-screen cells.
            if (cursor != null && cursor.group() == Group.COMMAND) {
                Target current = targets.getOrDefault(cursor.id(), cursor);
                for (Target candidate : direction > 0 ? ordered : ordered.reversed()) {
                    if (candidate.group() == Group.COMMAND
                        && Integer.signum(ORDER.compare(candidate, current)) == direction) {
                        move(candidate, focus);
                        return true;
                    }
                }
            }
            List<Group> groups = ordered.stream().map(Target::group).distinct().toList();
            Group destination = null;
            for (Group group : direction > 0 ? groups : groups.reversed()) {
                if (cursor == null || Integer.signum(group.compareTo(cursor.group())) == direction) {
                    destination = group;
                    break;
                }
            }
            if (destination == null) destination = direction > 0 ? groups.getFirst() : groups.getLast();
            // Enter Flow at its reading-order edge so reversing Tab reverses the
            // same path. Other regions retain their remembered-item navigation.
            Target remembered = destination == Group.COMMAND ? null : targets.get(groupCursors.get(destination));
            Group chosenGroup = destination;
            List<Target> members = ordered.stream().filter(target -> target.group() == chosenGroup).toList();
            move(remembered != null ? remembered : direction > 0 ? members.getFirst() : members.getLast(), focus);
            return true;
        }
        if (isArrow(key)) {
            if (cursor == null) {
                if (!ordered.isEmpty()) move(ordered.getFirst(), focus);
                return true;
            }
            Target current = targets.getOrDefault(cursor.id(), cursor);
            boolean vertical = key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN;
            int direction = key == InputConstants.KEY_UP || key == InputConstants.KEY_LEFT ? -1 : 1;
            boolean linear = current.group() == Group.CALL_PATH && !vertical
                || current.group() == Group.COMMAND && !vertical;
            boolean rows = vertical && (current.group() == Group.SOURCES || current.group() == Group.NBT || current.group() == Group.WATCH
                || current.group() == Group.COMMAND || current.group() == Group.WATCH_LIST
                || current.group() == Group.VIEW_MENU);
            if (linear || rows) {
                List<Target> group = ordered.stream().filter(target -> target.group() == current.group()).toList();
                Target next = null;
                if (linear) {
                    for (Target candidate : direction > 0 ? group : group.reversed()) {
                        if (Integer.signum(ORDER.compare(candidate, current)) == direction) { next = candidate; break; }
                    }
                } else {
                    next = group.stream().filter(target -> Integer.signum(Integer.compare(target.row(), current.row())) == direction)
                        .min(Comparator.<Target>comparingLong(target -> Math.abs((long) target.row() - current.row()))
                            .thenComparingLong(target -> Math.abs((long) target.column() - current.column()))).orElse(null);
                }
                if (next != null) move(next, focus);
                else if (targets.containsKey(current.id())) move(current, focus);
                // Stay in the list at its logical end; Tab moves to the next region.
                return true;
            }
            // Vanilla's geometric fallback also searches diagonally. Give it only this
            // container's children so a missing neighbour never escapes to another panel.
            List<AbstractWidget> peers = ordered.stream().filter(target -> target.group() == current.group())
                .map(target -> visible.get(target.id())).filter(java.util.Objects::nonNull).toList();
            if (pending == null) navigateWithin(event, peers, focused, focus);
            return true;
        }
        // A reveal is completed during rendering. Do not activate the previous, now hidden control.
        return pending != null && isArrow(key)
            || (deferred != null || pending != null || awaitingScrollRender) && (key == InputConstants.KEY_RETURN
                || key == InputConstants.KEY_SPACE || key == InputConstants.KEY_NUMPADENTER);
    }

    private void move(Target target, Consumer<@Nullable GuiEventListener> focus) {
        remember(target);
        target.reveal().run();
        AbstractWidget button = visible.get(target.id());
        pending = button == null ? target.id() : null;
        focus.accept(button);
    }

    private void remember(Target target) {
        cursor = target;
        groupCursors.put(target.group(), target.id());
    }

    private static boolean isArrow(int key) {
        return key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN
            || key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT;
    }

    /** Native directional geometry, scoped to one container. Tab remains the screen's responsibility. */
    public static boolean navigateWithin(KeyEvent event, List<? extends GuiEventListener> children,
                                         @Nullable GuiEventListener focused, Consumer<@Nullable GuiEventListener> focus) {
        ScreenDirection direction = switch (event.key()) {
            case InputConstants.KEY_UP -> ScreenDirection.UP;
            case InputConstants.KEY_DOWN -> ScreenDirection.DOWN;
            case InputConstants.KEY_LEFT -> ScreenDirection.LEFT;
            case InputConstants.KEY_RIGHT -> ScreenDirection.RIGHT;
            default -> null;
        };
        if (direction == null) return false;
        if (focused == null || !children.contains(focused)) return true;
        var container = new AbstractContainerEventHandler() {
            @Override public List<? extends GuiEventListener> children() { return children; }
        };
        container.setFocused(focused);
        var path = container.nextFocusPath(new FocusNavigationEvent.ArrowNavigation(direction));
        if (path != null) {
            path.applyFocus(true);
            focus.accept(container.getFocused());
        }
        return true;
    }
}
