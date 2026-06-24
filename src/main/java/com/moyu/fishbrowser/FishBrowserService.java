package com.moyu.fishbrowser;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.jcef.JBCefApp;
import com.moyu.fishbrowser.settings.FishBrowserConfigurable;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.Timer;
import java.awt.Dimension;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.beans.PropertyChangeListener;

/**
 * Application-level owner of the overlay and the global hotkeys. Applies whichever
 * {@link DisplayMode} is configured (浮窗 / 背景). All UI work runs on the EDT.
 */
@Service(Service.Level.APP)
public final class FishBrowserService implements Disposable {

    private static final Logger LOG = Logger.getInstance(FishBrowserService.class);

    private final GlobalHotkeys hotkeys = new GlobalHotkeys(this);
    private boolean hotkeysInstalled;
    private PropertyChangeListener focusWatcher;
    private Timer hideDebounce;
    private boolean hiddenAuto;
    private FishBrowserOverlay overlay;
    private boolean active;

    public static FishBrowserService getInstance() {
        return ApplicationManager.getApplication().getService(FishBrowserService.class);
    }

    public void installHotkeys() {
        if (!hotkeysInstalled) {
            hotkeys.install();
            installForegroundWatcher();
            hotkeysInstalled = true;
        }
    }

    /**
     * Auto-hide the overlay when the IDE is not in the foreground (switched to another app or
     * minimized), and bring it back when an IDE window becomes active again. Debounced to ignore
     * the brief focus gaps that happen while moving focus between the IDE and the overlay itself.
     */
    private void installForegroundWatcher() {
        focusWatcher = evt -> {
            if (evt.getNewValue() == null) {
                scheduleAutoHide();
            } else {
                cancelAutoHide();
                if (hiddenAuto && active) {
                    hiddenAuto = false;
                    applyCurrentMode();
                }
            }
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addPropertyChangeListener("activeWindow", focusWatcher);
    }

    private void scheduleAutoHide() {
        cancelAutoHide();
        hideDebounce = new Timer(250, e -> {
            // Re-check at fire time: only hide if no window of this process is still active.
            boolean ideForeground = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow() != null;
            if (!ideForeground && settings().hideWhenIdeInactive
                    && active && overlay != null && overlay.isShowing()) {
                hiddenAuto = true;
                overlay.hideOverlay();
            }
        });
        hideDebounce.setRepeats(false);
        hideDebounce.start();
    }

    private void cancelAutoHide() {
        if (hideDebounce != null) {
            hideDebounce.stop();
            hideDebounce = null;
        }
    }

    private FishBrowserSettings settings() {
        return FishBrowserSettings.getInstance();
    }

    /** @return the overlay, or null if JCEF is unavailable in this IDE. */
    private FishBrowserOverlay getOrCreateOverlay() {
        // If the IDE window that owned the overlay was closed, drop it and rebuild on the live frame.
        if (overlay != null && !overlay.isOwnerAlive()) {
            overlay.disposeOverlay();
            overlay = null;
        }
        if (overlay == null) {
            if (!JBCefApp.isSupported()) {
                Messages.showWarningDialog(
                        "当前 IDE 的运行时不支持 JCEF（内置 Chromium）。\n" +
                                "请使用 JetBrains Runtime（自带 JCEF）启动 IDEA 后重试。",
                        "摸鱼浏览器无法启动");
                return null;
            }
            try {
                overlay = new FishBrowserOverlay(this);
            } catch (Throwable t) {
                LOG.error("Failed to create Fish Browser overlay", t);
                Messages.showErrorDialog("创建悬浮浏览器失败：" + t.getMessage(), "摸鱼浏览器");
                return null;
            }
        }
        return overlay;
    }

    // ----- public commands (menu actions + hotkeys) -----

    /** Boss key: show if hidden, hide if visible. */
    public void toggleVisible() {
        onEdt(() -> {
            if (active) {
                active = false;
                teardownPresentation();
            } else {
                active = true;
                applyCurrentMode();
            }
        });
    }

    public void show() {
        onEdt(() -> {
            active = true;
            applyCurrentMode();
        });
    }

    public DisplayMode currentDisplayMode() {
        return settings().displayMode();
    }

    /** Switch display mode and show it immediately (used by the quick-switch menu action). */
    public void setDisplayMode(DisplayMode mode) {
        onEdt(() -> {
            teardownPresentation();
            settings().displayMode = mode.name();
            active = true;
            applyCurrentMode();
        });
    }

    /** Ctrl+Alt+` / toolbar button: flip between the floating window and the full-cover background. */
    public void toggleFloatingBackground() {
        DisplayMode next = (currentDisplayMode() == DisplayMode.COVER)
                ? DisplayMode.FLOATING : DisplayMode.COVER;
        setDisplayMode(next);
    }

    /** Ctrl+`: WEB/CODE (穿透) toggle. */
    public void toggleMode() {
        onEdt(() -> {
            FishBrowserOverlay o = getOrCreateOverlay();
            if (o == null) {
                return;
            }
            if (!active) {
                active = true;
                applyCurrentMode();
            } else {
                o.toggleMode();
            }
        });
    }

    /** Open the settings page. Hides the overlay first so the (modal) dialog isn't behind it. */
    public void openSettings(Project project) {
        onEdt(() -> {
            boolean wasActive = active;
            if (overlay != null) {
                overlay.hideOverlay();
            }
            try {
                // Navigate the unified Settings dialog to our page (match by display name; id/class
                // lookups don't reliably select extension-wrapped configurables, so it wouldn't jump).
                ShowSettingsUtil.getInstance().showSettingsDialog(project,
                        c -> c != null && "摸鱼浏览器".equals(c.getDisplayName()),
                        c -> { });
            } catch (Throwable t) {
                LOG.warn("Open settings by name failed; using standalone dialog", t);
                try {
                    ShowSettingsUtil.getInstance().editConfigurable(project, new FishBrowserConfigurable());
                } catch (Throwable t2) {
                    LOG.warn("Open settings fallback failed", t2);
                }
            }
            if (wasActive) {
                active = true;
                applyCurrentMode();
            }
        });
    }

    /** Re-apply presentation after the display mode changed in Settings. */
    public void applyDisplayMode() {
        onEdt(() -> {
            teardownPresentation();
            if (active) {
                applyCurrentMode();
            }
        });
    }

    // ----- internals -----

    private void applyCurrentMode() {
        FishBrowserOverlay o = getOrCreateOverlay();
        if (o == null) {
            active = false;
            return;
        }
        if (settings().displayMode() == DisplayMode.COVER) {
            Rectangle b = coverBounds();
            LOG.warn("[FishBrowser] applyCurrentMode COVER -> bounds=" + b);
            o.showCover(b);
        } else {
            o.showFloating();
        }
    }

    private void teardownPresentation() {
        cancelAutoHide();
        hiddenAuto = false;
        if (overlay != null) {
            overlay.hideOverlay();
        }
    }

    /** Bounds the 背景 should occupy: the active editor pane, falling back to the whole frame. */
    private Rectangle coverBounds() {
        Rectangle r = activeEditorBounds();
        return r != null ? r : activeFrameBounds();
    }

    /** Screen bounds of the currently selected code editor (the box showing xxx.java), or null. */
    private Rectangle activeEditorBounds() {
        for (Project p : ProjectManager.getInstance().getOpenProjects()) {
            if (p.isDisposed()) {
                continue;
            }
            Editor editor = FileEditorManager.getInstance(p).getSelectedTextEditor();
            if (editor == null) {
                continue;
            }
            JComponent comp = editor.getComponent();
            if (comp != null && comp.isShowing()) {
                try {
                    Point loc = comp.getLocationOnScreen();
                    Dimension size = comp.getSize();
                    if (size.width > 50 && size.height > 50) {
                        Rectangle r = new Rectangle(loc, size);
                        LOG.warn("[FishBrowser] active editor bounds (" + p.getName() + ") = " + r);
                        return r;
                    }
                } catch (Throwable ignore) {
                    // component not realized yet
                }
            }
        }
        LOG.warn("[FishBrowser] no showing editor found; falling back to frame bounds");
        return null;
    }

    private Rectangle activeFrameBounds() {
        JFrame f = WindowManager.getInstance().findVisibleFrame();
        if (f != null && f.getWidth() > 0) {
            return f.getBounds();
        }
        return new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
    }

    private static void onEdt(Runnable r) {
        ApplicationManager.getApplication().invokeLater(r);
    }

    @Override
    public void dispose() {
        hotkeys.uninstall();
        cancelAutoHide();
        if (focusWatcher != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .removePropertyChangeListener("activeWindow", focusWatcher);
            focusWatcher = null;
        }
        if (overlay != null) {
            overlay.disposeOverlay();
            overlay = null;
        }
    }
}
