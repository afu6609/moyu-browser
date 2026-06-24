package com.moyu.fishbrowser;

/**
 * How the browser is presented.
 */
public enum DisplayMode {
    /** A movable, resizable floating window over the IDE. */
    FLOATING,
    /** "背景": a dimmed, click-through window confined to the active code-editor pane. */
    COVER;

    public static DisplayMode parse(String s) {
        try {
            return valueOf(s);
        } catch (Exception e) {
            return FLOATING;
        }
    }
}
