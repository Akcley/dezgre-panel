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
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

public final class PanelWebActivity extends Activity {
    public static final String EXTRA_ACCESS_URL = "dezgre_panel_access_url";
    private static final int FILE_CHOOSER_REQUEST = 6201;

    private WebView webView;
    private ProgressBar progress;
    private ValueCallback<Uri[]> fileChooserCallback;
    private boolean mainFrameCommitted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(13, 13, 15));
        window.setNavigationBarColor(Color.rgb(13, 13, 15));

        String accessUrl = getIntent().getStringExtra(EXTRA_ACCESS_URL);
        if (accessUrl == null || !isHttpsUrl(accessUrl)) {
            Toast.makeText(this, "No se recibió un acceso seguro al panel.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(245, 245, 246));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(245, 245, 246));
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(5);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(2)
        );
        progressParams.gravity = android.view.Gravity.TOP;
        root.addView(progress, progressParams);

        setContentView(root);
        configureWebView();

        if (savedInstanceState != null && webView.restoreState(savedInstanceState) != null) {
            mainFrameCommitted = true;
            progress.setVisibility(View.GONE);
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
        if (Build.VERSION.SDK_INT >= 21) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        String userAgent = settings.getUserAgentString();
        if (userAgent == null) userAgent = "Android WebView";
        if (!userAgent.contains("DEZGRE-Mobile/")) {
            settings.setUserAgentString(userAgent + " DEZGRE-Mobile/0.1.7");
        }

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
                progress.setVisibility(View.VISIBLE);
                progress.setProgress(8);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                mainFrameCommitted = true;
                progress.setVisibility(View.GONE);
                applyNativePanelFixes(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                mainFrameCommitted = true;
                progress.setProgress(100);
                progress.setVisibility(View.GONE);
                applyNativePanelFixes(view);
                CookieManager.getInstance().flush();
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
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(Math.max(5, newProgress));
                if (mainFrameCommitted || newProgress >= 100) {
                    progress.setVisibility(View.GONE);
                } else {
                    progress.setVisibility(View.VISIBLE);
                }
            }

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
            public void onDownloadStart(
                    String url,
                    String userAgent,
                    String contentDisposition,
                    String mimeType,
                    long contentLength
            ) {
                download(url, userAgent, contentDisposition, mimeType);
            }
        });
    }

    private void applyNativePanelFixes(WebView view) {
        if (view == null || Build.VERSION.SDK_INT < 19) return;
        String javascript = "(function(){"
                + "var id='dezgre-native-app-fixes-v017';"
                + "if(!document.getElementById(id)){"
                + "var s=document.createElement('style');s.id=id;"
                + "s.textContent='html{min-height:100%!important;overscroll-behavior-y:none!important;scroll-behavior:auto!important}body{min-height:100vh!important;min-height:100dvh!important;margin:0!important;overscroll-behavior-y:none!important}.panelShell{min-height:100vh!important;min-height:100dvh!important}.panelShell[data-panel-theme=dark],.panelShell[data-panel-theme=dark] .panelContent{background:#151516!important}.panelRouteTransition{animation:dezgreMobileRouteIn .18s cubic-bezier(.2,.72,.3,1)!important;will-change:opacity,transform}.panelMobileTopbar{backdrop-filter:none!important;-webkit-backdrop-filter:none!important}.natiVisualBubble{animation:dezgreMobileNatiFloat 4.8s ease-in-out infinite!important;will-change:transform!important;transform:translateZ(0);backface-visibility:hidden;box-shadow:0 8px 22px rgba(0,0,0,.16)!important}.natiVisualPanel{animation:dezgreMobileNatiIn .18s cubic-bezier(.2,.8,.2,1)!important;will-change:opacity,transform!important;transform:translateZ(0);backdrop-filter:none!important;-webkit-backdrop-filter:none!important;box-shadow:0 14px 34px rgba(0,0,0,.16)!important}[class*=nati i],[class*=nati i] *{backdrop-filter:none!important;-webkit-backdrop-filter:none!important}.dezgre-mobile-paused .natiVisualBubble{animation-play-state:paused!important}@keyframes dezgreMobileRouteIn{from{opacity:.72;transform:translate3d(0,5px,0)}to{opacity:1;transform:translate3d(0,0,0)}}@keyframes dezgreMobileNatiFloat{0%,100%{transform:translate3d(0,0,0)}50%{transform:translate3d(0,-3px,0)}}@keyframes dezgreMobileNatiIn{from{opacity:0;transform:translate3d(0,8px,0) scale(.99)}to{opacity:1;transform:translate3d(0,0,0) scale(1)}}@media(max-width:1023px){.panelSidebar{position:fixed!important;top:0!important;right:auto!important;bottom:0!important;height:auto!important;min-height:100vh!important;min-height:100dvh!important;max-height:none!important;box-sizing:border-box!important;overscroll-behavior:contain!important;padding-bottom:max(18px,env(safe-area-inset-bottom))!important;transition:transform .22s cubic-bezier(.2,.8,.2,1)!important}.panelContent{min-height:calc(100dvh - 58px)!important}.panelSidebar nav a,.panelShell button{touch-action:manipulation}.panelSidebar nav a:active,.panelShell button:active{transform:scale(.985)!important}}';"
                + "(document.head||document.documentElement).appendChild(s);"
                + "}"
                + "var root=document.documentElement,body=document.body,shell=document.querySelector('.panelShell');"
                + "function syncTheme(){shell=document.querySelector('.panelShell');var dark=shell&&shell.getAttribute('data-panel-theme')==='dark';var c=dark?'#151516':'#f5f5f6';root.style.backgroundColor=c;if(body)body.style.backgroundColor=c;if(shell)shell.style.minHeight='100dvh';}"
                + "syncTheme();"
                + "if(shell&&!shell.__dezgreThemeObserver){var mo=new MutationObserver(syncTheme);mo.observe(shell,{attributes:true,attributeFilter:['data-panel-theme']});shell.__dezgreThemeObserver=mo;}"
                + "if(!document.__dezgreVisibilityHook){document.__dezgreVisibilityHook=true;document.addEventListener('visibilitychange',function(){root.classList.toggle('dezgre-mobile-paused',document.hidden);});}"
                + "})();";
        view.evaluateJavascript(javascript, null);
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
            if (manager == null) throw new IllegalStateException("DownloadManager unavailable");
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
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
