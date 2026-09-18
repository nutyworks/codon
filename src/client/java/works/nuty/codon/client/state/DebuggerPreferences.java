package works.nuty.codon.client.state;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/** Client preferences that are retained across worlds and client restarts. */
public final class DebuggerPreferences {
    public enum InspectorTab {
        SOURCES, FLOW, DETAILS, STACK
    }

    private ClientDebuggerState.GizmoMode gizmoMode = ClientDebuggerState.GizmoMode.GROUPED;
    private @Nullable Boolean inspectorVisible;
    private InspectorTab inspectorTab = InspectorTab.SOURCES;
    private boolean nbtExpanded = true;
    private Runnable changeListener = () -> { };

    public ClientDebuggerState.GizmoMode gizmoMode() {
        return gizmoMode;
    }

    public void setGizmoMode(ClientDebuggerState.GizmoMode mode) {
        mode = Objects.requireNonNull(mode);
        if (gizmoMode != mode) {
            gizmoMode = mode;
            changed();
        }
    }

    public @Nullable Boolean inspectorVisible() {
        return inspectorVisible;
    }

    public void setInspectorVisible(@Nullable Boolean visible) {
        if (!Objects.equals(inspectorVisible, visible)) {
            inspectorVisible = visible;
            changed();
        }
    }

    public InspectorTab inspectorTab() {
        return inspectorTab;
    }

    public void setInspectorTab(InspectorTab tab) {
        tab = Objects.requireNonNull(tab);
        if (inspectorTab != tab) {
            inspectorTab = tab;
            changed();
        }
    }

    public boolean nbtExpanded() { return nbtExpanded; }

    public void setNbtExpanded(boolean expanded) {
        if (nbtExpanded != expanded) {
            nbtExpanded = expanded;
            changed();
        }
    }

    public void setChangeListener(Runnable changeListener) {
        this.changeListener = Objects.requireNonNull(changeListener);
    }

    private void changed() {
        changeListener.run();
    }
}
