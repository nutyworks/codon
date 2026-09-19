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

/** Logical focus order, including controls outside a scrolling panel's viewport. */
public final class DebuggerNavigation {
    public enum Group { TOOLBAR, SOURCES, SOURCE_DETAILS, NBT, WATCH, WORLD, CALL_PATH, COMMAND, ACTIONS,
        WINDOW_HEADER, EDITOR, WATCH_LIST }

    private record Target(String id, Group group, int row, int column, Runnable reveal) { }
    private static final Comparator<Target> ORDER = Comparator.comparing(Target::group)
        .thenComparingInt(Target::row).thenComparingInt(Target::column).thenComparing(Target::id);
    private final Map<String, Target> targets = new HashMap<>();
    private final Map<String, AbstractWidget> visible = new HashMap<>();
    private final Map<Group, String> groupCursors = new HashMap<>();
    private List<Target> ordered = List.of();
    private @Nullable Target cursor;
    private @Nullable String pending;
    private boolean mouseNavigation;
    private boolean awaitingScrollRender;
    private boolean keyboard;

    public void rememberFocus(@Nullable GuiEventListener focused) {
        if (pending != null || focused == null) return;
        visible.forEach((id, button) -> {
            if (button == focused && targets.containsKey(id)) remember(targets.get(id));
        });
    }

    public void beginFrame(boolean keyboard) {
        this.keyboard = keyboard;
        targets.clear();
        visible.clear();
    }

    /** Apply the current layout's reveal callback after its selection/resize scroll adjustment. */
    public void revealFocus(Group group) {
        if (cursor == null || cursor.group() != group || pending == null && (!keyboard || mouseNavigation)) return;
        Target target = targets.get(cursor.id());
        if (target != null) target.reveal().run();
    }

    public void add(String id, Group group, int row, int column, Runnable reveal) {
        targets.put(id, new Target(id, group, row, column, reveal));
    }

    public void bind(String id, Group group, AbstractWidget button) {
        if (!button.active || !button.visible) {
            targets.remove(id);
            return;
        }
        visible.put(id, button);
        targets.putIfAbsent(id, new Target(id, group, button.getY(), button.getX(), () -> { }));
    }

    public void endFrame() {
        ordered = targets.values().stream().sorted(ORDER).toList();
        for (int i = 0; i < ordered.size(); i++) {
            AbstractWidget button = visible.get(ordered.get(i).id());
            if (button != null) button.setTabOrderGroup(i);
        }
    }

    /** Mouse scrolling must never silently arm a different button for Enter. */
    public void mouseScrolled() {
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
        rememberFocus(focused);
        int key = event.key();
        if (key == InputConstants.KEY_TAB || isArrow(key)) mouseNavigation = false;
        if (key == InputConstants.KEY_TAB) {
            if (ordered.isEmpty()) return false;
            int direction = event.hasShiftDown() ? -1 : 1;
            List<Group> groups = ordered.stream().map(Target::group).distinct().toList();
            Group destination = null;
            for (Group group : direction > 0 ? groups : groups.reversed()) {
                if (cursor == null || Integer.signum(group.compareTo(cursor.group())) == direction) {
                    destination = group;
                    break;
                }
            }
            if (destination == null) destination = direction > 0 ? groups.getFirst() : groups.getLast();
            Target remembered = targets.get(groupCursors.get(destination));
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
            boolean rows = vertical && (current.group() == Group.SOURCES || current.group() == Group.NBT
                || current.group() == Group.COMMAND || current.group() == Group.WATCH_LIST);
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
            || (pending != null || awaitingScrollRender) && (key == InputConstants.KEY_RETURN
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
