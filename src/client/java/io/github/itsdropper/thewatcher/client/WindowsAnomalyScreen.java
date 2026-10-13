package io.github.itsdropper.thewatcher.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * A fictional Windows-style dialog rendered entirely inside Minecraft.
 * It never opens an operating-system window or launches an external process.
 */
public final class WindowsAnomalyScreen extends Screen {
    private final Screen parent;
    private final String title;
    private final String message;
    private final String detail;

    public WindowsAnomalyScreen(Screen parent, String title, String message, String detail) {
        super(Text.literal(title));
        this.parent = parent;
        this.title = title;
        this.message = message;
        this.detail = detail;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(390, width - 24);
        int left = (width - panelWidth) / 2;
        int top = height / 2 - 78;
        addDrawableChild(ButtonWidget.builder(Text.literal("OK"), button -> close())
                .dimensions(left + panelWidth - 94, top + 112, 76, 22).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Keep the world visible behind the dialog; the interruption should feel out of place.
        context.fill(0, 0, width, height, 0x30000000);

        int panelWidth = Math.min(390, width - 24);
        int left = (width - panelWidth) / 2;
        int top = height / 2 - 78;
        int right = left + panelWidth;
        int bottom = top + 146;

        context.fill(left + 3, top + 4, right + 3, bottom + 4, 0x70000000);
        context.fill(left, top, right, bottom, 0xFFF2F2F2);
        context.fill(left, top, right, top + 31, 0xFFFFFFFF);
        context.fill(left, top, right, top + 1, 0xFFD5D5D5);

        // Windows-style application icon and title bar.
        context.fill(left + 12, top + 9, left + 24, top + 21, 0xFF0878D1);
        context.fill(left + 14, top + 11, left + 18, top + 15, 0xFFFFFFFF);
        context.fill(left + 19, top + 11, left + 22, top + 15, 0xFFFFFFFF);
        context.fill(left + 14, top + 16, left + 18, top + 19, 0xFFFFFFFF);
        context.fill(left + 19, top + 16, left + 22, top + 19, 0xFFFFFFFF);
        context.drawText(textRenderer, Text.literal(title), left + 31, top + 11, 0xFF202020, false);
        context.drawText(textRenderer, Text.literal("×"), right - 18, top + 8, 0xFF555555, false);

        // Error glyph.
        context.fill(left + 19, top + 49, left + 47, top + 77, 0xFFB3261E);
        context.drawText(textRenderer, Text.literal("!"), left + 29, top + 53, 0xFFFFFFFF, true);

        context.drawText(textRenderer, Text.literal(message), left + 59, top + 48,
                0xFF202020, false);
        context.drawText(textRenderer, Text.literal(detail), left + 59, top + 66,
                0xFF454545, false);
        context.drawText(textRenderer, Text.literal("Error code: 0x8000" + Integer.toHexString(
                (title.hashCode() ^ message.hashCode()) & 0xFFFF).toUpperCase()),
                left + 59, top + 84, 0xFF686868, false);

        // Draw widgets last so the OK button remains interactive.
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
