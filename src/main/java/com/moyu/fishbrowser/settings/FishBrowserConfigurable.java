package com.moyu.fishbrowser.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.moyu.fishbrowser.DisplayMode;
import com.moyu.fishbrowser.FishBrowserService;
import com.moyu.fishbrowser.FishBrowserSettings;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.FlowLayout;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Settings page (Settings → Tools → 摸鱼浏览器): display mode, opacity, window behavior and hotkeys.
 */
public final class FishBrowserConfigurable implements Configurable {

    private final JBRadioButton floatingRb = new JBRadioButton("浮窗　— 可拖动/缩放的悬浮窗");
    private final JBRadioButton coverRb = new JBRadioButton("背景　— 盖住当前代码编辑区，变暗，可切鼠标穿透");

    private final JBTextField homeUrlField = new JBTextField();
    private final JComboBox<String> searchEngineCombo = new JComboBox<>(new String[]{"Bing", "Google", "Baidu"});
    private final JSpinner opacitySpinner = spinner(10, 100, 1);
    private final JSpinner codeOpacitySpinner = spinner(10, 100, 1);
    private final JSpinner coverOpacitySpinner = spinner(10, 100, 1);
    private final JSpinner zoomSpinner = spinner(25, 300, 5);
    private final JBCheckBox hideWhenInactiveCb = new JBCheckBox("IDE 不在前台时自动隐藏（切到别的程序/最小化时藏起，回到 IDE 再出现）");
    private final JBCheckBox alwaysOnTopCb = new JBCheckBox("全局置顶（钉在所有窗口最前；关掉则只盖住 IDE）");
    private final JBCheckBox rememberBoundsCb = new JBCheckBox("记住浮窗的位置和大小");

    private final ShortcutField interactKey = new ShortcutField();
    private final ShortcutField backgroundKey = new ShortcutField();
    private final ShortcutField bossKey = new ShortcutField();

    private JPanel panel;

    private static JSpinner spinner(int min, int max, int step) {
        return new JSpinner(new SpinnerNumberModel(min, min, max, step));
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "摸鱼浏览器";
    }

    @Override
    public @Nullable JComponent createComponent() {
        ButtonGroup group = new ButtonGroup();
        group.add(floatingRb);
        group.add(coverRb);

        JButton resetKeys = new JButton("恢复默认快捷键");
        resetKeys.addActionListener(e -> setDefaultShortcuts());
        JPanel resetPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        resetPanel.add(resetKeys);

        panel = FormBuilder.createFormBuilder()
                .addComponent(new JBLabel("显示方式（也可用工具栏「变背景」按钮或快捷键随时切换）："))
                .addComponent(floatingRb)
                .addComponent(coverRb)
                .addSeparator()
                .addLabeledComponent("主页网址", homeUrlField)
                .addLabeledComponent("地址栏搜索引擎", searchEngineCombo)
                .addLabeledComponent("网页模式透明度 %", opacitySpinner)
                .addLabeledComponent("浮窗·代码模式透明度 %（穿透时）", codeOpacitySpinner)
                .addLabeledComponent("背景模式透明度 %", coverOpacitySpinner)
                .addLabeledComponent("页面缩放 %", zoomSpinner)
                .addSeparator()
                .addComponent(hideWhenInactiveCb)
                .addComponent(alwaysOnTopCb)
                .addComponent(rememberBoundsCb)
                .addSeparator()
                .addComponent(new JBLabel("快捷键（点输入框，然后按下你想要的组合键）："))
                .addLabeledComponent("切换交互：网页 / 代码", interactKey)
                .addLabeledComponent("浮窗 ⇄ 背景", backgroundKey)
                .addLabeledComponent("显示 / 隐藏（老板键）", bossKey)
                .addComponent(resetPanel)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        panel.setBorder(JBUI.Borders.empty(10));
        reset();
        return panel;
    }

    private void setDefaultShortcuts() {
        interactKey.setShortcut(KeyEvent.VK_BACK_QUOTE, InputEvent.CTRL_DOWN_MASK);
        backgroundKey.setShortcut(KeyEvent.VK_BACK_QUOTE, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK);
        bossKey.setShortcut(KeyEvent.VK_BACK_QUOTE, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
    }

    private DisplayMode selectedMode() {
        return coverRb.isSelected() ? DisplayMode.COVER : DisplayMode.FLOATING;
    }

    private void setSelectedMode(DisplayMode mode) {
        if (mode == DisplayMode.COVER) {
            coverRb.setSelected(true);
        } else {
            floatingRb.setSelected(true);
        }
    }

    private String engineValue() {
        Object v = searchEngineCombo.getSelectedItem();
        return v == null ? "Bing" : v.toString();
    }

    private static int intValue(JSpinner s) {
        return ((Number) s.getValue()).intValue();
    }

    @Override
    public boolean isModified() {
        FishBrowserSettings s = FishBrowserSettings.getInstance();
        return selectedMode() != s.displayMode()
                || !homeUrlField.getText().trim().equals(s.homeUrl)
                || !engineValue().equals(s.searchEngine)
                || intValue(opacitySpinner) != s.opacity
                || intValue(codeOpacitySpinner) != s.codeModeOpacity
                || intValue(coverOpacitySpinner) != s.coverOpacity
                || intValue(zoomSpinner) != s.zoomPercent
                || hideWhenInactiveCb.isSelected() != s.hideWhenIdeInactive
                || alwaysOnTopCb.isSelected() != s.alwaysOnTop
                || rememberBoundsCb.isSelected() != s.rememberBounds
                || interactKey.getKeyCodeValue() != s.keyInteractCode
                || interactKey.getModifiersValue() != s.keyInteractMods
                || backgroundKey.getKeyCodeValue() != s.keyBackgroundCode
                || backgroundKey.getModifiersValue() != s.keyBackgroundMods
                || bossKey.getKeyCodeValue() != s.keyBossCode
                || bossKey.getModifiersValue() != s.keyBossMods;
    }

    @Override
    public void apply() {
        FishBrowserSettings s = FishBrowserSettings.getInstance();
        s.displayMode = selectedMode().name();
        String url = homeUrlField.getText().trim();
        if (!url.isEmpty()) {
            s.homeUrl = url;
        }
        s.searchEngine = engineValue();
        s.opacity = intValue(opacitySpinner);
        s.codeModeOpacity = intValue(codeOpacitySpinner);
        s.coverOpacity = intValue(coverOpacitySpinner);
        s.zoomPercent = intValue(zoomSpinner);
        s.hideWhenIdeInactive = hideWhenInactiveCb.isSelected();
        s.alwaysOnTop = alwaysOnTopCb.isSelected();
        s.rememberBounds = rememberBoundsCb.isSelected();
        s.keyInteractCode = interactKey.getKeyCodeValue();
        s.keyInteractMods = interactKey.getModifiersValue();
        s.keyBackgroundCode = backgroundKey.getKeyCodeValue();
        s.keyBackgroundMods = backgroundKey.getModifiersValue();
        s.keyBossCode = bossKey.getKeyCodeValue();
        s.keyBossMods = bossKey.getModifiersValue();
        // Re-apply presentation so a mode/opacity change takes effect immediately.
        FishBrowserService.getInstance().applyDisplayMode();
        FishBrowserService.getInstance().refreshZoom();
    }

    @Override
    public void reset() {
        FishBrowserSettings s = FishBrowserSettings.getInstance();
        setSelectedMode(s.displayMode());
        homeUrlField.setText(s.homeUrl);
        searchEngineCombo.setSelectedItem(s.searchEngine);
        opacitySpinner.setValue(s.opacity);
        codeOpacitySpinner.setValue(s.codeModeOpacity);
        coverOpacitySpinner.setValue(s.coverOpacity);
        zoomSpinner.setValue(s.zoomPercent);
        hideWhenInactiveCb.setSelected(s.hideWhenIdeInactive);
        alwaysOnTopCb.setSelected(s.alwaysOnTop);
        rememberBoundsCb.setSelected(s.rememberBounds);
        interactKey.setShortcut(s.keyInteractCode, s.keyInteractMods);
        backgroundKey.setShortcut(s.keyBackgroundCode, s.keyBackgroundMods);
        bossKey.setShortcut(s.keyBossCode, s.keyBossMods);
    }
}
