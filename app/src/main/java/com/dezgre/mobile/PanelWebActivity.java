package com.dezgre.mobile;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

public final class PanelWebActivity extends Activity {
    public static final String EXTRA_ACCESS_URL = "dezgre_panel_access_url";
    private static final int FILE_CHOOSER_REQUEST = 6201;
    private static final int PRELOAD_BG = Color.rgb(247, 247, 248);
    private static final int PANEL_LIGHT_BG = Color.rgb(245, 245, 246);
    private static final int PANEL_DARK_BG = Color.rgb(21, 21, 22);

    private FrameLayout root;
    private WebView webView;
    private DezgrePreloadView preload;
    private ValueCallback<Uri[]> fileChooserCallback;
    private boolean mainFrameCommitted = false;
    private boolean preloadDismissed = false;
    private int currentPanelColor = PRELOAD_BG;
    private int themeResolveAttempts = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(PRELOAD_BG);
        window.setNavigationBarColor(PRELOAD_BG);
        setLightSystemBars(true);

        String accessUrl = getIntent().getStringExtra(EXTRA_ACCESS_URL);
        if (accessUrl == null || !isHttpsUrl(accessUrl)) {
            Toast.makeText(this, "No se pudo abrir DEZGRE de forma segura.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(PRELOAD_BG);

        webView = new WebView(this);
        webView.setBackgroundColor(PRELOAD_BG);
        webView.setAlpha(0f);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setNestedScrollingEnabled(true);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 26) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        }
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        preload = new DezgrePreloadView(this);
        root.addView(preload, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);
        configureWebView();

        if (savedInstanceState != null && webView.restoreState(savedInstanceState) != null) {
            mainFrameCommitted = true;
            webView.setAlpha(1f);
            synchronizePanelTheme(webView, false);
            dismissPreload(false);
            return;
        }
        webView.loadUrl(accessUrl);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    private void configureWebView() {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setTextZoom(100);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= 23) settings.setOffscreenPreRaster(true);
        if (Build.VERSION.SDK_INT >= 21) settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        // DEZGRE's own data-panel-theme is authoritative. Disable manufacturer/WebView
        // algorithmic recoloring so the same DOM paints consistently across devices.
        if (Build.VERSION.SDK_INT >= 33) {
            settings.setAlgorithmicDarkeningAllowed(false);
        } else if (Build.VERSION.SDK_INT >= 29) {
            settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        }

        String userAgent = settings.getUserAgentString();
        if (userAgent == null) userAgent = "Android WebView";
        final String dezgreMobileMarker = "DEZGRE-Mobile/Android";
        if (!userAgent.contains(dezgreMobileMarker)) {
            settings.setUserAgentString(userAgent + " " + dezgreMobileMarker);
        }

        // Theme-only bridge: no tokens, URLs, routing or privileged operations are exposed.
        webView.addJavascriptInterface(new ThemeBridge(), "DEZGRENativeTheme");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request == null ? null : request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(url == null ? null : Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                mainFrameCommitted = false;
                themeResolveAttempts = 0;
                // Keep the last resolved native color while the new document starts, then
                // seed the document as soon as JavaScript becomes available.
                applyNativeThemeColor(currentPanelColor);
                view.postDelayed(() -> synchronizePanelTheme(view, false), 40L);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                mainFrameCommitted = true;
                // Resolve the real panel theme before exposing the WebView, avoiding a
                // device-dependent light frame when React has not mounted .panelShell yet.
                synchronizePanelTheme(view, true);
                scheduleThemeConfirmations(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                mainFrameCommitted = true;
                CookieManager.getInstance().flush();
                synchronizePanelTheme(view, true);
                scheduleThemeConfirmations(view);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    Toast.makeText(PanelWebActivity.this,
                            "No se pudo cargar DEZGRE. Revisa tu conexión e inténtalo nuevamente.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = filePathCallback;
                Intent chooserIntent;
                try {
                    chooserIntent = fileChooserParams.createIntent();
                } catch (Exception ignored) {
                    chooserIntent = new Intent(Intent.ACTION_GET_CONTENT);
                    chooserIntent.addCategory(Intent.CATEGORY_OPENABLE);
                    chooserIntent.setType("*/*");
                }
                try {
                    startActivityForResult(Intent.createChooser(chooserIntent, "Seleccionar archivo"), FILE_CHOOSER_REQUEST);
                    return true;
                } catch (ActivityNotFoundException error) {
                    fileChooserCallback = null;
                    Toast.makeText(PanelWebActivity.this, "No hay un selector de archivos disponible.", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                download(url, userAgent, contentDisposition, mimeType);
            }
        });
    }

    private void dismissPreload(boolean animate) {
        if (preloadDismissed) return;
        preloadDismissed = true;
        if (webView != null) {
            if (animate) {
                webView.animate().alpha(1f).setDuration(180L).start();
            } else {
                webView.setAlpha(1f);
            }
        }
        if (preload == null) return;
        if (!animate) {
            root.removeView(preload);
            preload = null;
            return;
        }
        final DezgrePreloadView current = preload;
        current.animate().alpha(0f).setDuration(180L).withEndAction(() -> {
            if (root != null) root.removeView(current);
            if (preload == current) preload = null;
        }).start();
    }

    private void scheduleThemeConfirmations(final WebView view) {
        if (view == null) return;
        view.postDelayed(() -> synchronizePanelTheme(view, false), 120L);
        view.postDelayed(() -> synchronizePanelTheme(view, false), 420L);
    }

    private void synchronizePanelTheme(final WebView view, final boolean dismissWhenResolved) {
        if (view == null || Build.VERSION.SDK_INT < 19) {
            if (dismissWhenResolved) dismissPreload(true);
            return;
        }

        String javascript = "(function(){"
                + "var id='dezgre-native-app-shell-v0125';"
                + "var root=document.documentElement,body=document.body;"
                + "if(!root)return 'pending';"
                + "if(!document.getElementById(id)){"
                + "var s=document.createElement('style');s.id=id;"
                + "s.textContent='html{scroll-behavior:auto!important}html,body,body>div:first-child,#__next,.panelShell,.panelContent,.panelRouteTransition{background-color:var(--dezgre-app-bg,#f5f5f6)!important}.panelMobileTopbar{-webkit-backdrop-filter:none!important;backdrop-filter:none!important}.panelRouteTransition{animation:none!important}.panelSidebar{will-change:transform}.panelDrawerBackdrop{will-change:opacity}.panelDashboard .internalToolsGrid>a::before{display:none!important}@media(max-width:1023px){html,body{margin:0!important;width:100%!important;height:100%!important;min-height:0!important;overflow:hidden!important;overscroll-behavior:none!important;touch-action:auto!important}body>div:first-child,#__next{height:100%!important;min-height:0!important;overflow:hidden!important}.panelShell{display:flex!important;flex-direction:column!important;width:100%!important;height:100dvh!important;min-height:0!important;overflow:hidden!important;touch-action:auto!important}.panelMobileTopbar{position:relative!important;top:auto!important;flex:0 0 auto!important}.panelContent{position:relative!important;flex:1 1 auto!important;width:100%!important;height:auto!important;min-height:0!important;max-height:none!important;overflow-y:auto!important;overflow-x:hidden!important;overscroll-behavior:none!important;scroll-behavior:auto!important;touch-action:pan-y!important;-webkit-overflow-scrolling:touch!important;will-change:scroll-position!important;scrollbar-width:none!important}.panelContent::-webkit-scrollbar{display:none!important;width:0!important;height:0!important}.panelContent>*{touch-action:auto}.panelDrawerBackdrop{touch-action:none}.panelSidebar{touch-action:pan-y!important}}';"
                + "(document.head||root).appendChild(s);"
                + "}"
                + "function bg(el,c){if(el&&el.style)el.style.setProperty('background-color',c,'important');}"
                + "function notify(mode){try{if(window.DEZGRENativeTheme&&window.DEZGRENativeTheme.onThemeChanged)window.DEZGRENativeTheme.onThemeChanged(mode);}catch(e){}}"
                + "function applyTheme(){"
                + "var shell=document.querySelector('.panelShell');"
                + "if(!shell)return 'pending';"
                + "var mode=shell.getAttribute('data-panel-theme');"
                + "if(mode!=='dark'&&mode!=='light')return 'pending';"
                + "var c=mode==='dark'?'#151516':'#f5f5f6';"
                + "root.style.setProperty('--dezgre-app-bg',c);"
                + "bg(root,c);bg(body,c);"
                + "if(body)bg(body.firstElementChild,c);"
                + "bg(document.getElementById('__next'),c);"
                + "var nodes=document.querySelectorAll('.panelShell,.panelContent,.panelRouteTransition');"
                + "for(var i=0;i<nodes.length;i++)bg(nodes[i],c);"
                + "notify(mode);"
                + "return mode;"
                + "}"
                + "function attachThemeObserver(){"
                + "var shell=document.querySelector('.panelShell');"
                + "if(!shell)return false;"
                + "if(window.__dezgreObservedShell!==shell){"
                + "if(window.__dezgreThemeObserver)window.__dezgreThemeObserver.disconnect();"
                + "var mo=new MutationObserver(function(){applyTheme();});"
                + "mo.observe(shell,{attributes:true,attributeFilter:['data-panel-theme']});"
                + "window.__dezgreThemeObserver=mo;"
                + "window.__dezgreObservedShell=shell;"
                + "}"
                + "return true;"
                + "}"
                + "var mode=applyTheme();"
                + "if(attachThemeObserver()){"
                + "mode=applyTheme();"
                + "if(window.__dezgreThemeBootObserver){window.__dezgreThemeBootObserver.disconnect();window.__dezgreThemeBootObserver=null;}"
                + "}else if(!window.__dezgreThemeBootObserver){"
                + "var boot=new MutationObserver(function(){"
                + "if(attachThemeObserver()){applyTheme();boot.disconnect();window.__dezgreThemeBootObserver=null;}"
                + "});"
                + "boot.observe(root,{childList:true,subtree:true});"
                + "window.__dezgreThemeBootObserver=boot;"
                + "}"
                + "return mode;"
                + "})();";

        view.evaluateJavascript(javascript, value -> {
            String mode = value == null ? "" : value.replace("\"", "").trim();
            if ("dark".equals(mode)) {
                applyNativeThemeColor(PANEL_DARK_BG);
                if (dismissWhenResolved) dismissPreload(true);
            } else if ("light".equals(mode)) {
                applyNativeThemeColor(PANEL_LIGHT_BG);
                if (dismissWhenResolved) dismissPreload(true);
            } else if (dismissWhenResolved) {
                // The boot observer resolves slower WebViews. A bounded probe is only a
                // compatibility fallback so a malformed page can never trap the preload.
                if (themeResolveAttempts < 10) {
                    themeResolveAttempts++;
                    view.postDelayed(() -> synchronizePanelTheme(view, true), 80L);
                } else {
                    dismissPreload(true);
                }
            }
        });
    }

    private final class ThemeBridge {
        @JavascriptInterface
        public void onThemeChanged(String mode) {
            final int color;
            if ("dark".equalsIgnoreCase(mode)) color = PANEL_DARK_BG;
            else if ("light".equalsIgnoreCase(mode)) color = PANEL_LIGHT_BG;
            else return;

            runOnUiThread(() -> {
                applyNativeThemeColor(color);
                if (mainFrameCommitted) dismissPreload(true);
            });
        }
    }

    private void applyNativeThemeColor(int color) {
        currentPanelColor = color;
        if (root != null) root.setBackgroundColor(color);
        if (webView != null) webView.setBackgroundColor(color);

        Window window = getWindow();
        window.setStatusBarColor(color);
        window.setNavigationBarColor(color);

        // Keep the navigation area opaque and theme-matched. No edge-to-edge/Insets
        // startup configuration is used, preserving the stable 0.1.24 safe area.
        if (Build.VERSION.SDK_INT >= 28) {
            window.setNavigationBarDividerColor(color);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
        }
        setLightSystemBars(isLightColor(color));
    }

    private boolean handleNavigation(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            if ("wa.me".equals(host) || host.endsWith(".whatsapp.com") || host.endsWith("maps.google.com")) {
                return openExternal(uri);
            }
            return false;
        }
        if ("tel".equals(scheme) || "mailto".equals(scheme) || "geo".equals(scheme)
                || "whatsapp".equals(scheme) || "intent".equals(scheme)) {
            return openExternal(uri);
        }
        return false;
    }

    private boolean openExternal(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void download(String url, String userAgent, String contentDisposition, String mimeType) {
        if (url == null || !isHttpsUrl(url)) {
            Toast.makeText(this, "La descarga no usa una conexión segura.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            String fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(fileName);
            request.setDescription("Descarga de DEZGRE");
            request.setMimeType(mimeType);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.isEmpty()) request.addRequestHeader("Cookie", cookies);
            if (userAgent != null && !userAgent.isEmpty()) request.addRequestHeader("User-Agent", userAgent);
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("Download manager unavailable");
            manager.enqueue(request);
            Toast.makeText(this, "Descargando " + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "No se pudo iniciar la descarga.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isHttpsUrl(String value) {
        try {
            Uri uri = Uri.parse(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && !uri.getHost().trim().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isLightColor(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255d;
        return luminance > 0.62d;
    }

    private void setLightSystemBars(boolean light) {
        if (Build.VERSION.SDK_INT < 23) return;
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        if (light) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            if (light) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || fileChooserCallback == null) return;
        Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        fileChooserCallback.onReceiveValue(result);
        fileChooserCallback = null;
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.resumeTimers();
            webView.onResume();
            synchronizePanelTheme(webView, false);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("DEZGRENativeTheme");
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
