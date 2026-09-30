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
    private boolean watchesVisible = true;
    private boolean commandVisible = true;
    public enum UiScaleMode { FOLLOW_GAME, CUSTOM }
    public static final int MIN_UI_SCALE = 4;
    public static final int MAX_UI_SCALE = 16;
    public static final int DEFAULT_UI_SCALE = 8;
    private UiScaleMode uiScaleMode = UiScaleMode.FOLLOW_GAME;
    // Quarter steps: 4 means 1.00x, 16 means 4.00x.
    private int customUiScale = DEFAULT_UI_SCALE;
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

    public boolean watchesVisible() { return watchesVisible; }

    public void setWatchesVisible(boolean visible) {
        if (watchesVisible != visible) {
            watchesVisible = visible;
            changed();
        }
    }

    public boolean commandVisible() { return commandVisible; }

    public void setCommandVisible(boolean visible) {
        if (commandVisible != visible) {
            commandVisible = visible;
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

    public UiScaleMode uiScaleMode() { return uiScaleMode; }
    public int customUiScale() { return customUiScale; }

    public void setUiScaleMode(UiScaleMode mode) {
        mode = Objects.requireNonNull(mode);
        if (uiScaleMode != mode) {
            uiScaleMode = mode;
            changed();
        }
    }

    public void setCustomUiScale(int scale) {
        scale = Math.clamp(scale, MIN_UI_SCALE, MAX_UI_SCALE);
        if (customUiScale != scale) {
            customUiScale = scale;
            changed();
        }
    }

    public void resetUiScale() {
        if (uiScaleMode != UiScaleMode.FOLLOW_GAME || customUiScale != DEFAULT_UI_SCALE) {
            uiScaleMode = UiScaleMode.FOLLOW_GAME;
            customUiScale = DEFAULT_UI_SCALE;
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
