package com.moyu.fishbrowser;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.jcef.JBCefBrowser;
import com.moyu.fishbrowser.win.Win32ClickThrough;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The browser window (off-screen-rendered JCEF so window opacity applies to the web content).
 * It is an undecorated {@link JDialog} <b>owned by the IDE frame</b> — so it floats above its IDE
 * but tucks behind when another window/app is focused, and auto-follows the IDE's minimize. It is
 * focusable (a {@code JWindow} is not), so the address bar can receive keyboard input.
 *
 * <p>Two presentations, driven by the service: <b>floating</b> (movable/resizable) and
 * <b>cover</b> ("背景": fills the editor). Two interaction modes (Ctrl+`): <b>WEB</b> = interactive,
 * <b>CODE</b> = dimmed + click-through.</p>
 */
final class FishBrowserOverlay {

    private static final Logger LOG = Logger.getInstance(FishBrowserOverlay.class);

    enum Mode {WEB, CODE}

    private final FishBrowserService service;
    private final FishBrowserSettings settings = FishBrowserSettings.getInstance();
    private final Window owner;
    private final JDialog window;
    private final JBCefBrowser browser;

    private final JPanel root = new JPanel(new BorderLayout());
    private final JTextField urlField = new JTextField();
    private final JButton modeButton = new JButton();
    private final JButton pinButton = new JButton();
    private final JButton minButton = new JButton();
    private final JSlider opacitySlider;

    private Mode mode;
    private boolean cover;

    FishBrowserOverlay(FishBrowserService parent) {
        this.service = parent;
        this.mode = parseMode();

        // Own the dialog by the active IDE frame: floats above that IDE only, follows its minimize.
        owner = WindowManager.getInstance().findVisibleFrame();
        window = new JDialog(owner);
        window.setUndecorated(true);
        window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        browser = JBCefBrowser.createBuilder()
                .setOffScreenRendering(true)
                .setUrl(startUrl())
                .build();

        opacitySlider = new JSlider(10, 100, FishBrowserSettings.clampOpacity(settings.opacity));

        root.add(buildToolbar(), BorderLayout.NORTH);
        root.add(browser.getComponent(), BorderLayout.CENTER);
        root.add(buildResizeBar(), BorderLayout.SOUTH);
        applyBorder();

        window.setContentPane(root);
        window.setAlwaysOnTop(settings.alwaysOnTop);
        applyInitialBounds();

        browser.getJBCefClient().addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public void onAddressChange(CefBrowser b, CefFrame frame, String url) {
                settings.lastUrl = url;
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!urlField.isFocusOwner()) {
                        urlField.setText(url);
                    }
                });
            }
        }, browser.getCefBrowser());

        // Open target=_blank / window.open links in THIS browser instead of a popup window.
        browser.getJBCefClient().addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override
            public boolean onBeforePopup(CefBrowser b, CefFrame frame, String targetUrl, String targetFrameName) {
                if (targetUrl != null && !targetUrl.isBlank()) {
                    ApplicationManager.getApplication().invokeLater(() -> browser.loadURL(targetUrl));
                }
                return true; // cancel the popup window
            }
        }, browser.getCefBrowser());

        updateModeButton();
        updatePinButton();
        updateMinButton();
        applyOpacity(currentModeOpacity());
    }

    /** The IDE frame this overlay is owned by is still alive (false → the project window was closed). */
    boolean isOwnerAlive() {
        return owner == null || owner.isDisplayable();
    }

    Window getOwnerWindow() {
        return owner;
    }

    // ----- presentation entry points (driven by the service per DisplayMode) -----

    /** Movable, resizable window with its remembered bounds. */
    void showFloating() {
        cover = false;
        applyBorder();
        updateMinButton();
        window.setAlwaysOnTop(settings.alwaysOnTop);
        restoreFloatingBounds();
        if (!window.isVisible()) {
            window.setVisible(true);
        }
        window.toFront();
        setMode(mode);
    }

    /** Borderless window covering {@code bounds} ("背景"); starts as a dimmed, click-through backdrop. */
    void showCover(Rectangle bounds) {
        LOG.warn("[FishBrowser] showCover requested bounds=" + bounds + " wasVisible=" + window.isVisible());
        cover = true;
        applyBorder();
        updateMinButton();
        window.setAlwaysOnTop(settings.alwaysOnTop);
        if (bounds != null && bounds.width > 50 && bounds.height > 50) {
            window.setBounds(bounds);
        }
        if (!window.isVisible()) {
            window.setVisible(true);
        }
        window.toFront();
        // Enter visibly & interactively so you can SEE the background appear; Ctrl+` toggles 穿透.
        setMode(Mode.WEB);
        window.repaint();
    }

    boolean isShowing() {
        return window.isVisible();
    }

    boolean isOurWindow(java.awt.Window w) {
        return w == window;
    }

    void hideOverlay() {
        saveBounds();
        Win32ClickThrough.setClickThrough(window, false);
        window.setVisible(false);
    }

    void toggleMode() {
        setMode(mode == Mode.WEB ? Mode.CODE : Mode.WEB);
    }

    void disposeOverlay() {
        saveBounds();
        try {
            browser.dispose();
        } catch (Throwable t) {
            LOG.warn("Browser dispose failed", t);
        }
        window.dispose();
    }

    void loadUrl(String url) {
        browser.loadURL(url);
    }

    // ----- mode handling -----

    private void setMode(Mode m) {
        this.mode = m;
        settings.lastMode = m.name();
        boolean codeThrough = (m == Mode.CODE);
        int op = currentModeOpacity();
        boolean ct = Win32ClickThrough.setClickThrough(window, codeThrough);
        applyOpacity(op);
        if (m == Mode.WEB) {
            window.toFront();
            window.requestFocus();
        }
        LOG.warn("[FishBrowser] setMode " + m + " cover=" + cover + " opacity=" + op
                + " clickThrough=" + codeThrough + " ctOk=" + ct
                + " bounds=" + window.getBounds() + " visible=" + window.isVisible());
        updateModeButton();
    }

    private int currentModeOpacity() {
        // Background (cover) always uses coverOpacity (WEB = interactive, CODE = click-through).
        if (cover) {
            return settings.coverOpacity;
        }
        return mode == Mode.WEB ? settings.opacity : settings.codeModeOpacity;
    }

    private void applyOpacity(int pct) {
        try {
            window.setOpacity(FishBrowserSettings.clampOpacity(pct) / 100f);
        } catch (Throwable t) {
            LOG.warn("setOpacity not supported", t);
        }
    }

    // ----- UI construction -----

    private JComponent buildToolbar() {
        JLabel grip = new JLabel("⣿"); // braille block = drag handle
        grip.setToolTipText("拖动移动窗口");
        grip.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        grip.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        installDrag(grip);

        JPanel nav = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        nav.setOpaque(false);
        nav.add(grip);
        nav.add(button("◀", "后退", e -> browser.getCefBrowser().goBack()));
        nav.add(button("▶", "前进", e -> browser.getCefBrowser().goForward()));
        nav.add(button("⟳", "刷新", e -> browser.getCefBrowser().reload()));
        nav.add(button("⌂", "主页", e -> browser.loadURL(settings.homeUrl)));

        urlField.setText(startUrl());
        urlField.setMargin(new Insets(4, 8, 4, 8));
        urlField.addActionListener(e -> navigate());
        urlField.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                window.toFront();
                urlField.requestFocusInWindow();
            }
        });

        // Row 1: nav | URL (full width) | Go
        JPanel row1 = new JPanel(new BorderLayout(4, 0));
        row1.setOpaque(false);
        row1.add(nav, BorderLayout.WEST);
        row1.add(urlField, BorderLayout.CENTER);
        row1.add(button("Go", "打开网址", e -> navigate()), BorderLayout.EAST);

        // Row 2: opacity slider | minimize/restore + mode / pin / hide
        JLabel opLabel = new JLabel("透明度");
        opLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        opacitySlider.setPreferredSize(new Dimension(160, 22));
        opacitySlider.setToolTipText("网页模式透明度");
        opacitySlider.setOpaque(false);
        opacitySlider.addChangeListener(e -> {
            int v = opacitySlider.getValue();
            settings.opacity = v;
            if (mode == Mode.WEB) {
                applyOpacity(v);
            }
        });
        JPanel left2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        left2.setOpaque(false);
        left2.add(opLabel);
        left2.add(opacitySlider);

        minButton.setMargin(new Insets(2, 6, 2, 6));
        minButton.setFocusable(false);
        minButton.addActionListener(e -> service.setDisplayMode(cover ? DisplayMode.FLOATING : DisplayMode.COVER));

        modeButton.setMargin(new Insets(2, 6, 2, 6));
        modeButton.setFocusable(false);
        modeButton.addActionListener(e -> toggleMode());

        pinButton.setMargin(new Insets(2, 6, 2, 6));
        pinButton.setFocusable(false);
        pinButton.addActionListener(e -> {
            settings.alwaysOnTop = !settings.alwaysOnTop;
            window.setAlwaysOnTop(settings.alwaysOnTop);
            updatePinButton();
        });

        JPanel right2 = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        right2.setOpaque(false);
        right2.add(minButton);
        right2.add(modeButton);
        right2.add(pinButton);
        right2.add(button("✕", "隐藏 (老板键)", e -> hideOverlay()));

        JPanel row2 = new JPanel(new BorderLayout(4, 0));
        row2.setOpaque(false);
        row2.add(left2, BorderLayout.WEST);
        row2.add(right2, BorderLayout.EAST);

        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        bar.add(row1);
        bar.add(row2);
        installDrag(bar);
        return bar;
    }

    private JComponent buildResizeBar() {
        JLabel resize = new JLabel("◢", SwingConstants.RIGHT); // ◢
        resize.setToolTipText("拖动调整大小");
        resize.setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
        installResize(resize);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        south.add(resize);
        return south;
    }

    private JButton button(String text, String tip, java.awt.event.ActionListener al) {
        JButton b = new JButton(text);
        b.setMargin(new Insets(2, 6, 2, 6));
        b.setFocusable(false);
        b.setToolTipText(tip);
        if (al != null) {
            b.addActionListener(al);
        }
        return b;
    }

    private void applyBorder() {
        root.setBorder(cover ? BorderFactory.createEmptyBorder()
                : BorderFactory.createLineBorder(new Color(70, 70, 70), 1));
    }

    private void updateModeButton() {
        if (mode == Mode.WEB) {
            modeButton.setText("模式:网页");
            modeButton.setToolTipText("当前：网页交互。点击或按 Ctrl+` 切到代码模式（鼠标穿透）");
        } else {
            modeButton.setText("模式:代码");
            modeButton.setToolTipText("当前：代码交互（鼠标穿透）。点击或按 Ctrl+` 切回网页");
        }
    }

    private void updatePinButton() {
        pinButton.setText(settings.alwaysOnTop ? "置顶:开" : "置顶:关");
        pinButton.setToolTipText("开=钉在所有窗口最前；关=只盖住 IDE，切到别的窗口时让位");
    }

    private void updateMinButton() {
        if (cover) {
            minButton.setText("还原");
            minButton.setToolTipText("还原为浮窗 (Ctrl+Alt+`)");
        } else {
            minButton.setText("变背景");
            minButton.setToolTipText("最小化为背景：铺满 IDE、变暗、可切鼠标穿透 (Ctrl+Alt+`)");
        }
    }

    // ----- drag / resize -----

    private void installDrag(JComponent handle) {
        MouseAdapter ma = new MouseAdapter() {
            private Point pressScreen;
            private Point winLoc;

            @Override
            public void mousePressed(MouseEvent e) {
                pressScreen = e.getLocationOnScreen();
                winLoc = window.getLocation();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (pressScreen == null || cover) {
                    return;
                }
                Point now = e.getLocationOnScreen();
                window.setLocation(winLoc.x + (now.x - pressScreen.x),
                        winLoc.y + (now.y - pressScreen.y));
            }
        };
        handle.addMouseListener(ma);
        handle.addMouseMotionListener(ma);
    }

    private void installResize(JComponent handle) {
        MouseAdapter ma = new MouseAdapter() {
            private Point pressScreen;
            private Dimension startSize;

            @Override
            public void mousePressed(MouseEvent e) {
                pressScreen = e.getLocationOnScreen();
                startSize = window.getSize();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (pressScreen == null || cover) {
                    return;
                }
                Point now = e.getLocationOnScreen();
                int w = Math.max(320, startSize.width + (now.x - pressScreen.x));
                int h = Math.max(220, startSize.height + (now.y - pressScreen.y));
                window.setSize(w, h);
                window.validate();
            }
        };
        handle.addMouseListener(ma);
        handle.addMouseMotionListener(ma);
    }

    // ----- helpers -----

    private void navigate() {
        String text = urlField.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        if (!text.contains("://")) {
            if (text.contains(".") && !text.contains(" ")) {
                text = "https://" + text;
            } else {
                text = "https://www.bing.com/search?q="
                        + java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        browser.loadURL(text);
    }

    private String startUrl() {
        if (settings.lastUrl != null && !settings.lastUrl.isBlank()) {
            return settings.lastUrl;
        }
        return settings.homeUrl;
    }

    private Mode parseMode() {
        try {
            return Mode.valueOf(settings.lastMode);
        } catch (Exception e) {
            return Mode.WEB;
        }
    }

    private Rectangle savedBounds() {
        return new Rectangle(settings.winX, settings.winY, settings.winW, settings.winH);
    }

    private Rectangle defaultBounds() {
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        int w = 880;
        int h = 560;
        return new Rectangle(Math.max(0, scr.width - w - 60), 90, w, h);
    }

    /** Used for both first show and restore; rejects off-screen/garbage saved bounds. */
    private void restoreFloatingBounds() {
        Rectangle b = (settings.rememberBounds && isReasonableOnScreen(savedBounds()))
                ? savedBounds() : defaultBounds();
        window.setBounds(b);
    }

    private void applyInitialBounds() {
        restoreFloatingBounds();
    }

    private void saveBounds() {
        if (!settings.rememberBounds || cover) {
            return;
        }
        Rectangle r = window.getBounds();
        if (isReasonableOnScreen(r)) {
            settings.winX = r.x;
            settings.winY = r.y;
            settings.winW = r.width;
            settings.winH = r.height;
        }
    }

    /** True if the rectangle is a sane size and actually overlaps a real screen. */
    private static boolean isReasonableOnScreen(Rectangle r) {
        if (r == null || r.width < 200 || r.height < 150) {
            return false;
        }
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle inter = gd.getDefaultConfiguration().getBounds().intersection(r);
            if (inter.width > 100 && inter.height > 100) {
                return true;
            }
        }
        return false;
    }
}
