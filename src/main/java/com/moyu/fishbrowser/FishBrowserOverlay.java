package com.moyu.fishbrowser;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.ui.AsyncProcessIcon;
import com.moyu.fishbrowser.win.Win32ClickThrough;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefAuthCallback;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.network.CefRequest;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
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
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

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
    private final JButton zenButton = new JButton();
    private final JLabel zoomLabel = new JLabel();
    private final JPanel bookmarkBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JSlider opacitySlider;

    /** Thin indeterminate bar at the very top + a spinner by the address bar, shown while a page loads. */
    private final JProgressBar progressBar = new JProgressBar();
    private final AsyncProcessIcon pageSpinner = new AsyncProcessIcon("FishBrowserLoading");

    private JComponent toolbar;
    private JComponent resizeBar;
    private boolean chromeHidden;

    private Mode mode;
    private boolean cover;
    private boolean loading;
    private volatile String currentTitle = "";
    private AWTEventListener wheelListener;
    private AWTEventListener mouseFocusListener;
    /** HTTP-auth challenges awaiting Continue/cancel; tracked so teardown can release the native request. */
    private final java.util.Set<CefAuthCallback> pendingAuth = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** host -> "Basic xxx" Authorization header, injected on every request to that host (401 fallback path). */
    private final java.util.Map<String, String> basicAuthHeaders = new java.util.concurrent.ConcurrentHashMap<>();
    /** Hosts currently showing a 401 prompt, so duplicate dialogs don't stack. */
    private final java.util.Set<String> authPromptInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Shared handler that stamps the stored Authorization header onto outgoing requests to known hosts. */
    private final CefResourceRequestHandler authResourceHandler = new CefResourceRequestHandlerAdapter() {
        @Override
        public boolean onBeforeResourceLoad(CefBrowser b, CefFrame frame, CefRequest request) {
            if (request != null) {
                String host = hostOf(request.getURL());
                String header = (host == null) ? null : basicAuthHeaders.get(host);
                if (header != null) {
                    request.setHeaderByName("Authorization", header, true);
                }
            }
            return false;
        }
    };

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

        toolbar = buildToolbar();
        resizeBar = buildResizeBar();
        // Progress bar lives ABOVE the (hideable) toolbar, so the loading strip still shows in Zen mode.
        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(progressBar, BorderLayout.NORTH);
        north.add(toolbar, BorderLayout.CENTER);
        root.add(north, BorderLayout.NORTH);
        root.add(browser.getComponent(), BorderLayout.CENTER);
        root.add(resizeBar, BorderLayout.SOUTH);
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
                    ApplicationManager.getApplication().invokeLater(() -> load(targetUrl));
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
                // Fallback for HTTP auth NOT surfaced via getAuthCredentials: a 401 main-frame page means the
                // server wants Basic credentials — prompt once, then reload with the Authorization header injected.
                // (Only 401/Basic: 407 proxy auth needs Proxy-Authorization, and Digest/NTLM aren't a static header.)
                if (httpStatusCode == 401 && frame != null && frame.isMain()) {
                    String url = frame.getURL();
                    String host = hostOf(url);
                    LOG.info("[FishBrowser] HTTP " + httpStatusCode + " on " + host + " — prompting for credentials");
                    if (host != null && authPromptInFlight.add(host)) {
                        ApplicationManager.getApplication().invokeLater(() -> promptBasicAuth(host, url));
                    }
                }
            }

            @Override
            public void onLoadingStateChange(CefBrowser b, boolean isLoading, boolean canGoBack, boolean canGoForward) {
                ApplicationManager.getApplication().invokeLater(() -> updateNavState(isLoading, canGoBack, canGoForward));
            }
        }, browser.getCefBrowser());

        // HTTP Basic/Digest auth (e.g. SillyTavern login): show our own prompt instead of CEF's
        // hidden OSR dialog, then hand the typed credentials back to Chromium via the callback.
        browser.getJBCefClient().addRequestHandler(new CefRequestHandlerAdapter() {
            @Override
            public boolean getAuthCredentials(CefBrowser b, String originUrl, boolean isProxy,
                                              String host, int port, String realm, String scheme,
                                              CefAuthCallback callback) {
                pendingAuth.add(callback);
                ApplicationManager.getApplication().invokeLater(
                        () -> promptAuth(host, port, realm, isProxy, callback));
                return true; // credentials are delivered asynchronously via the callback
            }

            @Override
            public CefResourceRequestHandler getResourceRequestHandler(CefBrowser b, CefFrame frame,
                    CefRequest request, boolean isNavigation, boolean isDownload, String requestInitiator,
                    BoolRef disableDefaultHandling) {
                String host = hostOf(request != null ? request.getURL() : null);
                return (host != null && basicAuthHeaders.containsKey(host)) ? authResourceHandler : null;
            }
        }, browser.getCefBrowser());

        try {
            updateModeButton();
            updatePinButton();
            updateMinButton();
            updateZoomLabel();
            updateNavState(false, false, false);
            applyOpacity(currentModeOpacity());
            installZoomWheel();
            installFocusGrab();
            rebuildBookmarkBar();
            setChromeHidden(settings.hideChrome);
        } catch (Throwable t) {
            // Never leak the global AWT listeners / CEF browser / dialog if late construction fails.
            removeAwtListeners();
            try {
                pageSpinner.dispose();
            } catch (Throwable ignore) {
                // ignored
            }
            try {
                browser.dispose();
            } catch (Throwable ignore) {
                // ignored
            }
            window.dispose();
            throw (t instanceof RuntimeException) ? (RuntimeException) t : new RuntimeException(t);
        }
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

    /** Zen / immersive: hide the toolbar &amp; resize grip so only the web page shows (or restore them). */
    void toggleChrome() {
        setChromeHidden(!chromeHidden);
    }

    void setChromeHidden(boolean hidden) {
        chromeHidden = hidden;
        settings.hideChrome = hidden;
        if (toolbar != null) {
            toolbar.setVisible(!hidden);
        }
        if (resizeBar != null) {
            resizeBar.setVisible(!hidden);
        }
        updateZenButton();
        root.revalidate();
        root.repaint();
        forceBrowserRerender();
    }

    /**
     * The off-screen-rendered JCEF page only re-renders when its component actually changes size.
     * Toggling the toolbar's visibility grows/shrinks the page area via {@code revalidate()}, which
     * does NOT push a real resize through to Chromium — so the new area shows a stale/blank frame.
     * Nudge the window size by 1px and back (two EDT cycles, so each delivers a resize event) to force
     * a fresh render that fills the page area. Same path the resize grip already uses successfully.
     */
    private void forceBrowserRerender() {
        if (!window.isVisible()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (!window.isVisible()) {
                return;
            }
            final Dimension s = window.getSize();
            window.setSize(s.width, s.height + 1);
            window.validate();
            SwingUtilities.invokeLater(() -> {
                window.setSize(s);
                window.validate();
            });
        });
    }

    void disposeOverlay() {
        saveBounds();
        removeAwtListeners();
        for (CefAuthCallback cb : new java.util.ArrayList<>(pendingAuth)) {
            resolveAuth(cb, false, null, null); // release any HTTP-auth request paused by getAuthCredentials
        }
        try {
            pageSpinner.suspend();
            pageSpinner.dispose();
        } catch (Throwable ignore) {
            // ignored
        }
        try {
            browser.dispose();
        } catch (Throwable t) {
            LOG.warn("Browser dispose failed", t);
        }
        window.dispose();
    }

    private void removeAwtListeners() {
        if (wheelListener != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(wheelListener);
            wheelListener = null;
        }
        if (mouseFocusListener != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(mouseFocusListener);
            mouseFocusListener = null;
        }
    }

    void loadUrl(String url) {
        load(url);
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
        // CODE mode only dims when the user opts in; otherwise keep the WEB opacity (no brightness flash).
        if (mode == Mode.CODE && settings.dimInCodeMode) {
            return settings.codeModeOpacity;
        }
        return settings.opacity;
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
                setLoadingUi(true);
                browser.getCefBrowser().reload();
            }
        });

        JPanel nav = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        nav.setOpaque(false);
        nav.add(grip);
        nav.add(backButton);
        nav.add(forwardButton);
        nav.add(reloadButton);
        nav.add(button("⌂", "主页", e -> load(settings.homeUrl)));

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

        pageSpinner.setVisible(false);
        pageSpinner.suspend();
        pageSpinner.setToolTipText("正在加载…");

        JPanel row1east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        row1east.setOpaque(false);
        row1east.add(pageSpinner);
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

        zenButton.setMargin(new Insets(2, 6, 2, 6));
        zenButton.setFocusable(false);
        zenButton.addActionListener(e -> toggleChrome());

        JPanel right2 = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        right2.setOpaque(false);
        right2.add(minButton);
        right2.add(modeButton);
        right2.add(pinButton);
        right2.add(zenButton);
        right2.add(button("✕", "隐藏 (老板键)", e -> hideOverlay()));

        JPanel row2 = new JPanel(new BorderLayout(4, 0));
        row2.setOpaque(false);
        row2.add(left2, BorderLayout.WEST);
        row2.add(right2, BorderLayout.EAST);

        bookmarkBar.setOpaque(false);

        progressBar.setIndeterminate(false);
        progressBar.setVisible(false);
        progressBar.setBorder(null);
        progressBar.setPreferredSize(new Dimension(0, 0));
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 0));

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

    private void updateZenButton() {
        String hk = shortcutText(settings.keyZenCode, settings.keyZenMods);
        zenButton.setText(chromeHidden ? "显示栏" : "沉浸");
        zenButton.setToolTipText(chromeHidden
                ? "显示工具栏 / 地址栏（" + hk + "）"
                : "沉浸模式：只看网页，隐藏工具栏（" + hk + " / 工具菜单 还原）");
    }

    private static String shortcutText(int code, int mods) {
        String m = java.awt.event.InputEvent.getModifiersExText(mods);
        String k = java.awt.event.KeyEvent.getKeyText(code);
        return (m == null || m.isEmpty()) ? k : m + "+" + k;
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
        load(toUrlOrSearch(text));
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

    /** getAuthCredentials path: prompt then resume (or cancel) the paused CEF request. Runs on the EDT. */
    private void promptAuth(String host, int port, String realm, boolean isProxy, CefAuthCallback callback) {
        if (!window.isDisplayable()) {
            resolveAuth(callback, false, null, null); // torn down between challenge and prompt — release it
            return;
        }
        String[] creds = askCredentials(host, port, realm, isProxy);
        resolveAuth(callback, creds != null, creds != null ? creds[0] : null, creds != null ? creds[1] : null);
    }

    /** 401-fallback path: prompt, store the Authorization header for this host, then reload through it. */
    private void promptBasicAuth(String host, String reloadUrl) {
        try {
            if (host == null || !window.isDisplayable()) {
                return;
            }
            String[] creds = askCredentials(host, 0, null, false);
            if (creds == null) {
                return; // user cancelled — leave the 401 page as-is
            }
            String token = Base64.getEncoder().encodeToString(
                    (creds[0] + ":" + creds[1]).getBytes(StandardCharsets.UTF_8));
            basicAuthHeaders.put(host, "Basic " + token);
            if (reloadUrl != null && !reloadUrl.isBlank()) {
                load(reloadUrl);
            } else {
                browser.getCefBrowser().reload();
            }
        } finally {
            authPromptInFlight.remove(host);
        }
    }

    /** Modal, always-on-top username/password prompt parented to the IDE frame. Returns {user, pass} or null. */
    private String[] askCredentials(String host, int port, String realm, boolean isProxy) {
        JTextField userField = new JTextField(18);
        JPasswordField passField = new JPasswordField(18);
        String where = (host == null ? "" : host) + (port > 0 ? ":" + port : "");
        String realmText = (realm != null && !realm.isBlank()) ? "  (" + realm + ")" : "";
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
        form.add(new JLabel((isProxy ? "代理服务器 " : "") + where + " 需要登录" + realmText));
        form.add(new JLabel("用户名"));
        form.add(userField);
        form.add(new JLabel("密码"));
        form.add(passField);
        JOptionPane pane = new JOptionPane(form, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        Window parent = (owner != null && owner.isShowing()) ? owner : window;
        JDialog dlg = pane.createDialog(parent, "需要身份验证");
        dlg.setAlwaysOnTop(true);
        try {
            dlg.setVisible(true); // modal — blocks until the user closes it
        } finally {
            dlg.dispose();
        }
        Object val = pane.getValue();
        boolean ok = (val instanceof Integer) && ((Integer) val == JOptionPane.OK_OPTION);
        return ok ? new String[]{userField.getText(), new String(passField.getPassword())} : null;
    }

    /** Lowercased host of a URL, or null if unparseable. */
    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String h = java.net.URI.create(url).getHost();
            return (h == null || h.isBlank()) ? null : h.toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Resolve an HTTP-auth challenge at most once: Continue on OK, otherwise cancel; releases the native request. */
    private void resolveAuth(CefAuthCallback callback, boolean proceed, String user, String pass) {
        if (callback == null || !pendingAuth.remove(callback)) {
            return; // already resolved (remove is the atomic once-guard), or not one of ours
        }
        try {
            if (proceed) {
                callback.Continue(user, pass);
            } else {
                callback.cancel();
            }
        } catch (Throwable t) {
            LOG.warn("Auth callback failed", t);
        }
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
        setLoadingUi(isLoading);
        backButton.setEnabled(canGoBack);
        forwardButton.setEnabled(canGoForward);
    }

    /** Show/hide the loading indicators (thin top bar + spinner) and flip reload⇄stop. */
    private void setLoadingUi(boolean isLoading) {
        loading = isLoading;
        reloadButton.setText(isLoading ? "✕" : "⟳");
        reloadButton.setToolTipText(isLoading ? "停止加载" : "刷新");
        int h = isLoading ? 3 : 0;
        progressBar.setPreferredSize(new Dimension(0, h));
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        progressBar.setVisible(isLoading);
        progressBar.setIndeterminate(isLoading);
        pageSpinner.setVisible(isLoading);
        if (isLoading) {
            pageSpinner.resume();
        } else {
            pageSpinner.suspend();
        }
        root.revalidate();
        root.repaint();
    }

    /** All navigations funnel through here so the loading indicator shows the instant you click / press Enter. */
    private void load(String url) {
        setLoadingUi(true);
        browser.loadURL(url);
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

    /**
     * Keep keyboard focus on the page when the floating window overlaps the editor. The owned dialog
     * sometimes loses the active-window race to the IDE frame behind it, so a click on the web area would
     * still type into the code editor. On a mouse-press inside the page, pull our window to the front,
     * take window focus, and give Chromium keyboard focus.
     */
    private void installFocusGrab() {
        mouseFocusListener = event -> {
            if (event.getID() != MouseEvent.MOUSE_PRESSED || !window.isVisible()) {
                return;
            }
            Object src = event.getSource();
            if (!(src instanceof Component)) {
                return;
            }
            Component c = (Component) src;
            if (!SwingUtilities.isDescendingFrom(c, window)) {
                return;
            }
            window.toFront();
            if (SwingUtilities.isDescendingFrom(c, browser.getComponent())) {
                if (!window.isFocused()) {
                    window.requestFocus();
                }
                try {
                    browser.getCefBrowser().setFocus(true);
                } catch (Throwable ignore) {
                    // ignored
                }
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(mouseFocusListener, AWTEvent.MOUSE_EVENT_MASK);
    }

    private void adjustOpacityBy(int delta) {
        if (cover) {
            settings.coverOpacity = FishBrowserSettings.clampOpacity(settings.coverOpacity + delta);
        } else if (mode == Mode.CODE && settings.dimInCodeMode) {
            settings.codeModeOpacity = FishBrowserSettings.clampOpacity(settings.codeModeOpacity + delta);
        } else {
            // WEB, or CODE without dimming — both display settings.opacity (see currentModeOpacity()).
            settings.opacity = FishBrowserSettings.clampOpacity(settings.opacity + delta);
            opacitySlider.setValue(settings.opacity);
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
            b.addActionListener(e -> load(url));
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
