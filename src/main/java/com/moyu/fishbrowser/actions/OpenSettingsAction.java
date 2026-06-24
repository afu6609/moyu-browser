package com.moyu.fishbrowser.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.moyu.fishbrowser.FishBrowserService;
import org.jetbrains.annotations.NotNull;

/** Tools menu: open the 摸鱼浏览器 settings page (where you switch display mode + set hotkeys). */
public final class OpenSettingsAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            Project[] open = ProjectManager.getInstance().getOpenProjects();
            if (open.length > 0) {
                project = open[0];
            }
        }
        FishBrowserService.getInstance().openSettings(project);
    }
}
