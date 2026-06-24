package com.moyu.fishbrowser.settings;

import com.intellij.ui.components.JBTextField;

import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

/**
 * A read-only text field that captures a keyboard shortcut: click it, then press the combo you want.
 * Stores the key code + {@code InputEvent} down-mask modifiers.
 */
final class ShortcutField extends JBTextField {

    private static final int MOD_MASK = InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK
            | InputEvent.SHIFT_DOWN_MASK | InputEvent.META_DOWN_MASK;

    private int keyCode;
    private int modifiers;

    ShortcutField() {
        setEditable(false);
        setColumns(16);
        setToolTipText("点这里，然后按下你想要的组合键（建议带 Ctrl/Alt/Shift）");
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int c = e.getKeyCode();
                if (isPureModifier(c)) {
                    return; // wait for the real key
                }
                keyCode = c;
                modifiers = e.getModifiersEx() & MOD_MASK;
                updateText();
                e.consume();
            }

            @Override
            public void keyReleased(KeyEvent e) {
                e.consume();
            }

            @Override
            public void keyTyped(KeyEvent e) {
                e.consume();
            }
        });
    }

    void setShortcut(int code, int mods) {
        this.keyCode = code;
        this.modifiers = mods;
        updateText();
    }

    int getKeyCodeValue() {
        return keyCode;
    }

    int getModifiersValue() {
        return modifiers;
    }

    private void updateText() {
        String m = InputEvent.getModifiersExText(modifiers);
        String k = KeyEvent.getKeyText(keyCode);
        setText((m == null || m.isEmpty()) ? k : (m + "+" + k));
    }

    private static boolean isPureModifier(int code) {
        return code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT || code == KeyEvent.VK_SHIFT
                || code == KeyEvent.VK_META || code == KeyEvent.VK_ALT_GRAPH || code == KeyEvent.VK_UNDEFINED;
    }
}
