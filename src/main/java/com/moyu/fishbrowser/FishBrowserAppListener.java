package com.moyu.fishbrowser;

import com.intellij.ide.AppLifecycleListener;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Installs the global hotkeys as soon as the IDE frame is up, so the boss key /
 * mode toggle work even before the overlay has ever been opened.
 */
public final class FishBrowserAppListener implements AppLifecycleListener {

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        FishBrowserService.getInstance().installHotkeys();
    }
}
