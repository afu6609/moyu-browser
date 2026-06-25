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
import org.cef.handler.CefLoadHandlerAdapter;

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
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
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
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

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
    private final JButton backButton = new JButton("◀");
    private final JButton forwardButton = new JButton("▶");
    private final JButton reloadButton = new JButton("⟳");
    private final JLabel zoomLabel = new JLabel();
    private final JPanel bookmarkBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JSlider opacitySlider;

    private Mode mode;
    private boolean cover;
    private boolean loading;
    private String currentTitle = "";
    private AWTEventListener wheelListener;

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

            @Override
            public void onTitleChange(CefBrowser b, String title) {
                currentTitle = title == null ? "" : title;
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

        // Re-apply page zoom after each navigation (some pages reset it on load).
        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser b, CefFrame frame, int httpStatusCode) {
                try {
                    b.setZoomLevel(FishBrowserSettings.zoomLevelFor(settings.zoomPercent));
                } catch (Throwable ignore) {
                    // ignored
                }
            }

            @Override
            public void onLoadingStateChange(CefBrowser b, boolean isLoading, boolean canGoBack, boolean canGoForward) {
                ApplicationManager.getApplication().invokeLater(() -> updateNavState(isLoading, canGoBack, canGoForward));
            }
        }, browser.getCefBrowser());

        updateModeButton();
        updatePinButton();
        updateMinButton();
        updateZoomLabel();
        updateNavState(false, false, false);
        applyOpacity(currentModeOpacity());
        installZoomWheel();
        rebuildBookmarkBar();
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

    /** Re-fit the cover to the (possibly resized) editor bounds; called by the service tracker. */
    void refitCoverBounds(Rectangle target) {
        if (!cover || target == null || target.width < 50 || target.height < 50 || !window.isVisible()) {
            return;
        }
        Rectangle cur = window.getBounds();
        if (Math.abs(cur.x - target.x) > 2 || Math.abs(cur.y - target.y) > 2
                || Math.abs(cur.width - target.width) > 2 || Math.abs(cur.height - target.height) > 2) {
            window.setBounds(target);
            window.validate();
        }
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
        if (wheelListener != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(wheelListener);
            wheelListener = null;
        }
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

        configBtn(backButton, "后退", e -> browser.getCefBrowser().goBack());
        configBtn(forwardButton, "前进", e -> browser.getCefBrowser().goForward());
        configBtn(reloadButton, "刷新", e -> {
            if (loading) {
                browser.getCefBrowser().stopLoad();
            } else {
                browser.getCefBrowser().reload();
            }
        });

        JPanel nav = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        nav.setOpaque(false);
        nav.add(grip);
        nav.add(backButton);
        nav.add(forwardButton);
        nav.add(reloadButton);
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

        JPanel row1east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        row1east.setOpaque(false);
        row1east.add(button("☆", "收藏当前页", e -> addCurrentBookmark()));
        row1east.add(button("Go", "打开网址", e -> navigate()));

        // Row 1: nav | URL (full width) | ☆ + Go
        JPanel row1 = new JPanel(new BorderLayout(4, 0));
        row1.setOpaque(false);
        row1.add(nav, BorderLayout.WEST);
        row1.add(urlField, BorderLayout.CENTER);
        row1.add(row1east, BorderLayout.EAST);

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

        JLabel zoomTitle = new JLabel("缩放");
        zoomTitle.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 2));
        zoomLabel.setToolTipText("点击重置为 100%");
        zoomLabel.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 3));
        zoomLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        zoomLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                applyZoom(100);
            }
        });
        left2.add(zoomTitle);
        left2.add(button("－", "缩小", e -> applyZoom(settings.zoomPercent - 10)));
        left2.add(zoomLabel);
        left2.add(button("＋", "放大", e -> applyZoom(settings.zoomPercent + 10)));

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

        bookmarkBar.setOpaque(false);

        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        bar.add(row1);
        bar.add(row2);
        bar.add(bookmarkBar);
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

    /** Set the web page zoom (percent); persists and updates the label. */
    void applyZoom(int pct) {
        int z = FishBrowserSettings.clampZoom(pct);
        settings.zoomPercent = z;
        try {
            browser.getCefBrowser().setZoomLevel(FishBrowserSettings.zoomLevelFor(z));
        } catch (Throwable t) {
            LOG.warn("setZoomLevel failed", t);
        }
        updateZoomLabel();
    }

    private void updateZoomLabel() {
        zoomLabel.setText(settings.zoomPercent + "%");
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
        browser.loadURL(toUrlOrSearch(text));
    }

    /** A full URL is opened as-is; a bare domain gets https://; anything else is a search query. */
    private String toUrlOrSearch(String text) {
        if (text.contains("://")) {
            return text;
        }
        boolean looksLikeUrl = !text.contains(" ")
                && (text.startsWith("localhost") || (text.contains(".") && !text.endsWith(".")));
        return looksLikeUrl ? "https://" + text : FishBrowserSettings.searchUrl(settings.searchEngine, text);
    }

    private JButton configBtn(JButton b, String tip, java.awt.event.ActionListener al) {
        b.setMargin(new Insets(2, 6, 2, 6));
        b.setFocusable(false);
        b.setToolTipText(tip);
        if (al != null) {
            b.addActionListener(al);
        }
        return b;
    }

    /** Update back/forward enabled state and the reload⇄stop button from a load-state change. */
    private void updateNavState(boolean isLoading, boolean canGoBack, boolean canGoForward) {
        loading = isLoading;
        backButton.setEnabled(canGoBack);
        forwardButton.setEnabled(canGoForward);
        reloadButton.setText(isLoading ? "✕" : "⟳");
        reloadButton.setToolTipText(isLoading ? "停止加载" : "刷新");
    }

    /**
     * Ctrl+wheel = zoom; Ctrl+Shift+wheel = opacity. Uses a Toolkit-level listener because the OSR
     * browser component does not deliver wheel events to a Swing MouseWheelListener on getComponent().
     */
    private void installZoomWheel() {
        wheelListener = event -> {
            if (!(event instanceof MouseWheelEvent)) {
                return;
            }
            MouseWheelEvent e = (MouseWheelEvent) event;
            if (!e.isControlDown() || e.isConsumed() || !window.isVisible()) {
                return;
            }
            Component c = e.getComponent();
            if (c == null || !SwingUtilities.isDescendingFrom(c, window)) {
                return;
            }
            int up = e.getWheelRotation() < 0 ? 1 : -1;
            if (e.isShiftDown()) {
                adjustOpacityBy(up * 5);
            } else {
                applyZoom(settings.zoomPercent + up * 10);
            }
            e.consume();
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(wheelListener, AWTEvent.MOUSE_WHEEL_EVENT_MASK);
    }

    private void adjustOpacityBy(int delta) {
        if (cover) {
            settings.coverOpacity = FishBrowserSettings.clampOpacity(settings.coverOpacity + delta);
        } else if (mode == Mode.WEB) {
            settings.opacity = FishBrowserSettings.clampOpacity(settings.opacity + delta);
            opacitySlider.setValue(settings.opacity);
        } else {
            settings.codeModeOpacity = FishBrowserSettings.clampOpacity(settings.codeModeOpacity + delta);
        }
        applyOpacity(currentModeOpacity());
    }

    // ----- bookmarks -----

    private void rebuildBookmarkBar() {
        bookmarkBar.removeAll();
        for (String bm : settings.bookmarks) {
            String[] parts = bm.split("\t", 2);
            String name = parts.length > 0 ? parts[0] : bm;
            String url = parts.length > 1 ? parts[1] : "";
            JButton b = new JButton(ellipsize(name, 18));
            b.setMargin(new Insets(1, 6, 1, 6));
            b.setFocusable(false);
            b.setToolTipText("<html>" + escapeHtml(url) + "<br>左键打开 · 右键删除</html>");
            b.addActionListener(e -> browser.loadURL(url));
            b.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                        removeBookmark(bm);
                    }
                }
            });
            bookmarkBar.add(b);
        }
        bookmarkBar.revalidate();
        bookmarkBar.repaint();
    }

    private void addCurrentBookmark() {
        String url = browser.getCefBrowser().getURL();
        if (url == null || url.isBlank()) {
            url = settings.lastUrl;
        }
        if (url == null || url.isBlank()) {
            return;
        }
        String name = (currentTitle != null && !currentTitle.isBlank()) ? currentTitle : url;
        final String u = url;
        settings.bookmarks.removeIf(s -> s.endsWith("\t" + u));
        settings.bookmarks.add(name.replace("\t", " ") + "\t" + u);
        rebuildBookmarkBar();
    }

    private void removeBookmark(String entry) {
        settings.bookmarks.remove(entry);
        rebuildBookmarkBar();
    }

    private static String ellipsize(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
