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
    public static final int DEFAULT_WATCH_WIDTH = 280;
    public static final int DEFAULT_INSPECTOR_WIDTH = 190;
    public static final int MIN_WATCH_WIDTH = 220;
    public static final int MIN_INSPECTOR_WIDTH = 160;
    public static final int MAX_PANEL_WIDTH = 640;
    private int watchWidth = DEFAULT_WATCH_WIDTH;
    private int inspectorWidth = DEFAULT_INSPECTOR_WIDTH;
    public enum UiScaleMode { FOLLOW_GAME, CUSTOM }
    public static final int MIN_UI_SCALE = 4;
    public static final int STANDARD_MAX_UI_SCALE = 16;
    public static final int MAX_UI_SCALE = Integer.MAX_VALUE - 3;
    public static final int DEFAULT_UI_SCALE = 8;
    private UiScaleMode uiScaleMode = UiScaleMode.FOLLOW_GAME;
    // Quarter steps: 4 means 1.00x. An absent saved request starts from the actual game scale.
    private int customUiScale = DEFAULT_UI_SCALE;
    private boolean customUiScaleInitialized;
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

    public int watchWidth() { return watchWidth; }
    public int inspectorWidth() { return inspectorWidth; }

    public void setWatchWidth(int width) {
        width = Math.clamp(width, MIN_WATCH_WIDTH, MAX_PANEL_WIDTH);
        if (watchWidth != width) {
            watchWidth = width;
            changed();
        }
    }

    public void setInspectorWidth(int width) {
        width = Math.clamp(width, MIN_INSPECTOR_WIDTH, MAX_PANEL_WIDTH);
        if (inspectorWidth != width) {
            inspectorWidth = width;
            changed();
        }
    }

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
    public boolean customUiScaleInitialized() { return customUiScaleInitialized; }

    public static int gameUiScaleRequest(double appliedGameScale) {
        return (int) Math.max(MIN_UI_SCALE, Math.min(MAX_UI_SCALE, Math.round(appliedGameScale * 4)));
    }

    /** Initialize once from Window's applied scale, including Auto; keep later user requests. */
    public void selectCustomUiScale(double appliedGameScale) {
        if (uiScaleMode != UiScaleMode.CUSTOM || !customUiScaleInitialized) {
            if (!customUiScaleInitialized) customUiScale = gameUiScaleRequest(appliedGameScale);
            customUiScaleInitialized = true;
            uiScaleMode = UiScaleMode.CUSTOM;
            changed();
        }
    }

    public void setUiScaleMode(UiScaleMode mode) {
        mode = Objects.requireNonNull(mode);
        if (uiScaleMode != mode) {
            uiScaleMode = mode;
            changed();
        }
    }

    public void setCustomUiScale(int scale) {
        scale = Math.clamp(scale, MIN_UI_SCALE, MAX_UI_SCALE);
        if (customUiScale != scale || !customUiScaleInitialized) {
            customUiScale = scale;
            customUiScaleInitialized = true;
            changed();
        }
    }

    public void resetUiScale() {
        if (uiScaleMode != UiScaleMode.FOLLOW_GAME || customUiScaleInitialized) {
            uiScaleMode = UiScaleMode.FOLLOW_GAME;
            customUiScale = DEFAULT_UI_SCALE;
            customUiScaleInitialized = false;
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
