package works.nuty.bastion.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import works.nuty.bastion.client.render.DistinctColorGenerator;

/**
 * A draggable, resizable in-game window. The base class draws the chrome (title bar, body, resize
 * handle) and exposes hit-testing + drag/resize hooks; subclasses override {@link #render} to draw
 * their contents.
 */
public class Window {
    private static final DistinctColorGenerator COLOR_GENERATOR = new DistinctColorGenerator();
    protected static final int HEADER_HEIGHT = 12;

    protected final Minecraft client;
    protected double x;
    protected double y;
    protected double width;
    protected double height;
    protected double minWidth = 100;
    protected double minHeight = 50;
    protected double scrollY = 0;
    protected float scale;
    protected boolean isHovered = false;
    protected Component title = Component.translatable("bastion.ui.window.title");

    protected final int headerColor = COLOR_GENERATOR.nextColor();

    public Window(double x, double y, double width, double height) {
        this.client = Minecraft.getInstance();
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.scale = 1;
        init();
    }

    public void init() {
    }

    public void render(final GuiGraphicsExtractor context, final int mouseX, final int mouseY) {
        // Window body
        context.fill((int) x, (int) y, (int) (x + width), (int) (y + height), getBackgroundColor());

        // Title bar
        context.fill((int) x, (int) y, (int) (x + width), (int) (y + HEADER_HEIGHT), headerColor);
        context.text(client.font, title, (int) x + 4, (int) y + 2, 0xFFFFFFFF, true);

        // Resize handle (bottom-right triangle)
        int handleSize = 6;
        context.fill((int) (x + width - handleSize), (int) (y + height - 2), (int) (x + width - 2), (int) (y + height - 1), 0xFFAAAAAA);
        context.fill((int) (x + width - 4), (int) (y + height - 4), (int) (x + width - 2), (int) (y + height - 3), 0xFFAAAAAA);
        context.fill((int) (x + width - 2), (int) (y + height - 6), (int) (x + width - 1), (int) (y + height - 2), 0xFFAAAAAA);

        if (isHovered) {
            context.outline((int) x, (int) y, (int) width, (int) height, getHoveredOutlineColor());
        }
    }

    public boolean isHeaderHovered(double mouseX, double mouseY) {
        return x <= mouseX && mouseX < x + width && y <= mouseY && mouseY < y + HEADER_HEIGHT;
    }

    public boolean isResizeHovered(double mouseX, double mouseY) {
        int handleSize = 10;
        return mouseX >= x + width - handleSize && mouseX <= x + width && mouseY >= y + height - handleSize && mouseY <= y + height;
    }

    public boolean checkHovered(final double mouseX, final double mouseY) {
        return x <= mouseX && mouseX < x + width && y <= mouseY && mouseY < y + height;
    }

    public void setScale(final float scale) {
        this.scale = scale;
    }

    public int getBackgroundColor() {
        return ARGB.color(0.5f, 0);
    }

    public int getHoveredOutlineColor() {
        return ARGB.color(0.5f, 0x00AAAA);
    }

    public void hovered(double x, double y) {
        this.isHovered = true;
    }

    public void unhovered(double x, double y) {
        this.isHovered = false;
    }

    public void addXY(double dx, double dy) {
        this.x += dx;
        this.y += dy;
    }

    public void addWH(double dw, double dh) {
        this.width = Math.max(minWidth, this.width + dw);
        this.height = Math.max(minHeight, this.height + dh);
    }

    public void mouseScrolled(double scrollX, double scrollY) {
        this.scrollY -= scrollY * 10;
        if (this.scrollY < 0) {
            this.scrollY = 0;
        }
    }
}
