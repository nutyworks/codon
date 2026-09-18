package works.nuty.codon.client.ui;

import java.util.ArrayList;
import java.util.List;

/** Owns the stack of debugger windows and their z-order. */
public final class WindowManager {
    private final List<Window> windows = new ArrayList<>();
    private float scale = 1;

    public List<Window> getWindows() {
        return windows;
    }

    public void addWindow(Window window) {
        windows.add(window);
    }

    public void removeWindow(Window window) {
        windows.remove(window);
    }

    /** Moves a window to the top of the z-order (rendered last, on top). */
    public void bringToTop(Window window) {
        windows.remove(window);
        windows.add(window);
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = scale;
    }
}
