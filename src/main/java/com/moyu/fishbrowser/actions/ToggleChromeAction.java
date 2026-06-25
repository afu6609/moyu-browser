package com.moyu.fishbrowser.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.moyu.fishbrowser.FishBrowserService;
import org.jetbrains.annotations.NotNull;

/** Tools menu: Zen mode — hide the toolbar / address bar and show only the web page (or restore it). */
public final class ToggleChromeAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        FishBrowserService.getInstance().toggleChrome();
    }
}
