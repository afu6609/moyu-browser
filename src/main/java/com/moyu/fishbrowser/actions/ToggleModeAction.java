package com.moyu.fishbrowser.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.moyu.fishbrowser.FishBrowserService;
import org.jetbrains.annotations.NotNull;

/** Tools menu: toggle WEB / CODE interaction mode (same as Ctrl+`). */
public final class ToggleModeAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        FishBrowserService.getInstance().toggleMode();
    }
}
