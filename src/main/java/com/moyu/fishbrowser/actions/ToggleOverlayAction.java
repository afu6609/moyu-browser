package com.moyu.fishbrowser.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.moyu.fishbrowser.FishBrowserService;
import com.intellij.openapi.actionSystem.AnAction;
import org.jetbrains.annotations.NotNull;

/** Tools menu: show / hide the overlay (same as the boss key). */
public final class ToggleOverlayAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        FishBrowserService.getInstance().toggleVisible();
    }
}
