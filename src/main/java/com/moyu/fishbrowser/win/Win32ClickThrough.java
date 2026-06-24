package com.moyu.fishbrowser.win;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

import java.awt.Window;

/**
 * Windows-only helper that toggles "mouse click-through" on a Swing window by flipping the
 * WS_EX_TRANSPARENT extended window style via the Win32 API (JNA).
 *
 * <p>It deliberately does NOT touch WS_EX_LAYERED — that bit (and the window's alpha) is owned
 * by Java's {@code Window.setOpacity}; touching it here can blank out the layered window. We only
 * flip TRANSPARENT, then commit with SetWindowPos(SWP_FRAMECHANGED). The caller should re-apply
 * opacity AFTER calling this, to be safe.</p>
 *
 * <p>JNA ships inside the IDE (util-8.jar) → compileOnly. Every call is wrapped so a missing JNA
 * or native failure degrades to a no-op instead of crashing.</p>
 */
public final class Win32ClickThrough {

    private static final Logger LOG = Logger.getInstance(Win32ClickThrough.class);

    private static final int GWL_EXSTYLE = -20;
    private static final int WS_EX_TRANSPARENT = 0x00000020;

    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_FRAMECHANGED = 0x0020;

    private Win32ClickThrough() {
    }

    /** @return true if the OS call succeeded; false on non-Windows or any failure. */
    public static boolean setClickThrough(Window window, boolean enabled) {
        if (!SystemInfo.isWindows || window == null || !window.isDisplayable()) {
            return false;
        }
        try {
            Pointer ptr = Native.getWindowPointer(window);
            if (ptr == null) {
                return false;
            }
            WinDef.HWND hwnd = new WinDef.HWND(ptr);
            int exStyle = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE);
            int newStyle = enabled ? (exStyle | WS_EX_TRANSPARENT) : (exStyle & ~WS_EX_TRANSPARENT);
            if (newStyle != exStyle) {
                User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE, newStyle);
                User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0,
                        SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
            }
            return true;
        } catch (Throwable t) {
            // NoClassDefFoundError (JNA absent) / any native failure -> degrade gracefully.
            LOG.warn("Click-through toggle failed; falling back to no-op", t);
            return false;
        }
    }
}
