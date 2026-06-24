package com.moyu.fishbrowser;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Persisted plugin settings (application level). Stored in fishBrowser.xml.
 */
@Service(Service.Level.APP)
@State(name = "FishBrowserSettings", storages = @Storage("fishBrowser.xml"))
public final class FishBrowserSettings implements PersistentStateComponent<FishBrowserSettings> {

    /** Which presentation to use: FLOATING / COVER (背景). */
    public String displayMode = DisplayMode.FLOATING.name();

    /** Page opened the very first time, and via the Home button. */
    public String homeUrl = "https://www.bing.com/";

    /** Last visited URL, restored on next open. */
    public String lastUrl = "";

    /** Opacity used in WEB mode (interactive), percent 10..100. */
    public int opacity = 88;

    /** Opacity used in CODE mode (click-through), percent 10..100. */
    public int codeModeOpacity = 35;

    /** Opacity used in 背景(COVER) mode while it sits as a backdrop, percent 10..100. */
    public int coverOpacity = 50;

    /** Global always-on-top (above ALL apps). Off = above the owning IDE only (lets other windows cover it). */
    public boolean alwaysOnTop = false;

    /** Remember and restore floating-window position/size. */
    public boolean rememberBounds = true;

    /** Auto-hide the overlay when the IDE is not the foreground app (switched away / minimized). */
    public boolean hideWhenIdeInactive = true;

    // Floating-window bounds; -1 means "not set yet".
    public int winX = -1;
    public int winY = -1;
    public int winW = -1;
    public int winH = -1;

    /** Last interaction mode name ("WEB" or "CODE"). */
    public String lastMode = "WEB";

    // --- Configurable hotkeys (keyCode + InputEvent *_DOWN_MASK modifiers) ---
    /** 切换交互：网页 / 代码（穿透）. Default Ctrl+` */
    public int keyInteractCode = KeyEvent.VK_BACK_QUOTE;
    public int keyInteractMods = InputEvent.CTRL_DOWN_MASK;
    /** 浮窗 ⇄ 背景. Default Ctrl+Alt+` */
    public int keyBackgroundCode = KeyEvent.VK_BACK_QUOTE;
    public int keyBackgroundMods = InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK;
    /** 老板键 显示/隐藏. Default Ctrl+Shift+` */
    public int keyBossCode = KeyEvent.VK_BACK_QUOTE;
    public int keyBossMods = InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK;

    public static FishBrowserSettings getInstance() {
        return ApplicationManager.getApplication().getService(FishBrowserSettings.class);
    }

    public DisplayMode displayMode() {
        return DisplayMode.parse(displayMode);
    }

    @Override
    public @Nullable FishBrowserSettings getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull FishBrowserSettings state) {
        com.intellij.util.xmlb.XmlSerializerUtil.copyBean(state, this);
    }

    public static int clampOpacity(int pct) {
        return Math.max(10, Math.min(100, pct));
    }
}
