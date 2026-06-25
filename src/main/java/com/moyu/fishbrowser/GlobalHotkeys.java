package com.moyu.fishbrowser;

import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * JVM-wide keystroke handler. A {@link KeyEventDispatcher} sees key events before the focused
 * component does, so the hotkeys work no matter what currently has focus inside the IDE process
 * — including the embedded browser. (Only fires while the IDE process has OS focus.)
 *
 * <p>The three shortcuts are user-configurable (see {@link FishBrowserSettings}); this reads them
 * live on every keystroke, so changes in the settings page take effect immediately.</p>
 */
final class GlobalHotkeys {

    private static final int MOD_MASK = InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK
            | InputEvent.SHIFT_DOWN_MASK | InputEvent.META_DOWN_MASK;

    private final FishBrowserService service;
    private KeyEventDispatcher dispatcher;

    GlobalHotkeys(FishBrowserService service) {
        this.service = service;
    }

    void install() {
        if (dispatcher != null) {
            return;
        }
        dispatcher = e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED || isPureModifier(e.getKeyCode())) {
                return false;
            }
            int code = e.getKeyCode();
            int mods = e.getModifiersEx() & MOD_MASK;
            FishBrowserSettings s = FishBrowserSettings.getInstance();
            if (code == s.keyInteractCode && mods == s.keyInteractMods) {
                service.toggleMode();
            } else if (code == s.keyBackgroundCode && mods == s.keyBackgroundMods) {
                service.toggleFloatingBackground();
            } else if (code == s.keyBossCode && mods == s.keyBossMods) {
                service.toggleVisible();
            } else if (code == s.keyZenCode && mods == s.keyZenMods) {
                service.toggleChrome();
            } else {
                return false;
            }
            e.consume();
            return true;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher);
    }

    void uninstall() {
        if (dispatcher != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher);
            dispatcher = null;
        }
    }

    private static boolean isPureModifier(int code) {
        return code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT || code == KeyEvent.VK_SHIFT
                || code == KeyEvent.VK_META || code == KeyEvent.VK_ALT_GRAPH || code == KeyEvent.VK_UNDEFINED;
    }
}
