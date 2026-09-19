package com.flowingsun.war_project.client.cef;

import org.cef.CefClient;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefRequestContext;
import org.cef.event.CefKeyEvent;
import org.cef.event.CefMouseEvent;
import org.cef.event.CefMouseWheelEvent;

/**
 * An off screen CEF browser with the input plumbing the interface needs.
 *
 * <p>java-cef keeps the event senders {@code protected}, so like MCEF we extend the OSR browser and
 * expose them. The window size lives in {@code browser_rect_}, which the render handler reports back
 * to CEF; {@code wasResized} tells CEF to re-lay out at the new size.
 */
public class WpCefBrowser extends CefBrowserOsr {
    /** java.awt.event.MouseEvent identifiers, which is what CEF expects. */
    private static final int MOUSE_PRESSED = 501;
    private static final int MOUSE_RELEASED = 502;
    private static final int MOUSE_MOVED = 503;
    private static final int MOUSE_DRAGGED = 506;

    private boolean dragging;
    private int dragButton;

    public WpCefBrowser(CefClient client, String url, boolean transparent, CefRequestContext context) {
        super(client, url, transparent, context);
    }

    /** Resizes the off screen surface and asks CEF to repaint. */
    public void resize(int width, int height) {
        browser_rect_.setBounds(0, 0, Math.max(1, width), Math.max(1, height));
        wasResized(Math.max(1, width), Math.max(1, height));
    }

    public void mouseMove(int x, int y, int modifiers) {
        if (dragging) {
            sendMouseEvent(new CefMouseEvent(MOUSE_DRAGGED, x, y, modifiers, 0, cefButton(dragButton)));
            return;
        }
        sendMouseEvent(new CefMouseEvent(MOUSE_MOVED, x, y, modifiers, 0, 0));
    }

    public void mouseDown(int x, int y, int button, int modifiers) {
        dragging = true;
        dragButton = button;
        sendMouseEvent(new CefMouseEvent(MOUSE_PRESSED, x, y, modifiers, 1, cefButton(button)));
    }

    public void mouseUp(int x, int y, int button, int modifiers) {
        dragging = false;
        sendMouseEvent(new CefMouseEvent(MOUSE_RELEASED, x, y, modifiers, 1, cefButton(button)));
    }

    public void mouseWheel(int x, int y, int modifiers, double delta) {
        sendMouseWheelEvent(new CefMouseWheelEvent(x, y, modifiers, delta, CefMouseWheelEvent.WHEEL_UNIT_SCROLL));
    }

    public void keyDown(int keyCode, char keyChar, int modifiers) {
        sendKeyEvent(new CefKeyEvent(CefKeyEvent.KEY_PRESS, keyCode, keyChar, modifiers));
    }

    public void keyUp(int keyCode, char keyChar, int modifiers) {
        sendKeyEvent(new CefKeyEvent(CefKeyEvent.KEY_RELEASE, keyCode, keyChar, modifiers));
    }

    public void keyTyped(char keyChar, int modifiers) {
        sendKeyEvent(new CefKeyEvent(CefKeyEvent.KEY_TYPE, 0, keyChar, modifiers));
    }

    /** Minecraft reports 0=left, 1=right, 2=middle; CEF wants 0=left, 1=middle, 2=right. */
    private static int cefButton(int button) {
        return switch (button) {
            case 0 -> 0;
            case 1 -> 2;
            case 2 -> 1;
            default -> 0;
        };
    }
}
