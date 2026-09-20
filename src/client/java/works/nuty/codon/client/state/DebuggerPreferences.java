package works.nuty.codon.client.state;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/** Client preferences that are retained across worlds and client restarts. */
public final class DebuggerPreferences {
    public enum InspectorTab {
        // DETAILS remains readable for existing settings; the UI presents it as SOURCES.
        SOURCES, FLOW, DETAILS, STACK
    }

    private ClientDebuggerState.GizmoMode gizmoMode = ClientDebuggerState.GizmoMode.GROUPED;
    private @Nullable Boolean inspectorVisible;
    private InspectorTab inspectorTab = InspectorTab.SOURCES;
    private boolean nbtExpanded = true;
    private boolean keepFreecam;
    private int backgroundOpacity = 100;
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

    public int backgroundOpacity() { return backgroundOpacity; }

    /** Live slider preview; the editing widget commits the final value when the gesture ends. */
    public void previewBackgroundOpacity(int opacity) {
        backgroundOpacity = Math.clamp(opacity, 0, 100);
    }

    public void setBackgroundOpacity(int opacity) {
        opacity = Math.clamp(opacity, 0, 100);
        if (backgroundOpacity != opacity) {
            backgroundOpacity = opacity;
            changed();
        }
    }

    public boolean keepFreecam() { return keepFreecam; }

    public void setKeepFreecam(boolean keep) {
        if (keepFreecam != keep) {
            keepFreecam = keep;
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
