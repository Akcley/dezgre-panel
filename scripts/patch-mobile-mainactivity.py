from pathlib import Path

path = Path("app/src/main/java/com/dezgre/mobile/MainActivity.java")
source = path.read_text(encoding="utf-8")
changed = False

old_routes = '''        if ("products".equals(tab)) loadProducts();
        else if ("profile".equals(tab)) loadProfile();
        else if ("home".equals(tab)) loadHome();
        else showUnavailableSection(tab);'''
new_routes = '''        if ("products".equals(tab)) loadProducts();
        else if ("profile".equals(tab)) loadProfile();
        else if ("home".equals(tab)) loadHome();
        else if ("panel".equals(tab)) loadDashboard();
        else if ("orders".equals(tab)) loadOrders();
        else showUnavailableSection(tab);'''

old_count = source.count(old_routes)
new_count = source.count(new_routes)
if new_count == 1 and old_count == 0:
    print("Mobile fallback routes already wired")
elif old_count == 1 and new_count == 0:
    source = source.replace(old_routes, new_routes, 1)
    changed = True
    print("Mobile fallback routes patched")
else:
    raise SystemExit(
        f"Unexpected mobile route state: old={old_count}, new={new_count}; refusing broad rewrite"
    )

old_startup = '''        if (bearer == null) {
            showLogin(null);
        } else {
            validateSession();
        }'''
new_startup = '''        if (bearer == null) {
            showLogin(null);
        } else {
            openRealPanel();
        }'''

old_startup_count = source.count(old_startup)
new_startup_count = source.count(new_startup)
if new_startup_count == 1 and old_startup_count == 0:
    print("Saved session already enters real panel directly")
elif old_startup_count == 1 and new_startup_count == 0:
    source = source.replace(old_startup, new_startup, 1)
    changed = True
    print("Removed duplicate /me roundtrip from cold start")
else:
    raise SystemExit(
        f"Unexpected startup state: old={old_startup_count}, new={new_startup_count}; refusing broad rewrite"
    )

old_persist = '''            tokenStore.save(access);
            bearer = access;
            validateSession();'''
new_persist = '''            tokenStore.save(access);
            bearer = access;
            openRealPanel();'''

old_persist_count = source.count(old_persist)
new_persist_count = source.count(new_persist)
if new_persist_count == 1 and old_persist_count == 0:
    print("Fresh login already enters real panel directly")
elif old_persist_count == 1 and new_persist_count == 0:
    source = source.replace(old_persist, new_persist, 1)
    changed = True
    print("Removed duplicate /me roundtrip after login/store selection")
else:
    raise SystemExit(
        f"Unexpected persist state: old={old_persist_count}, new={new_persist_count}; refusing broad rewrite"
    )

field_marker = "    private boolean drawerOpen = false;"
push_field = "    private boolean notificationPermissionResolvedThisLaunch = false;"
if push_field not in source:
    if source.count(field_marker) != 1:
        raise SystemExit("MainActivity drawer field marker missing")
    source = source.replace(field_marker, field_marker + "\n" + push_field, 1)
    changed = True
    print("Inserted notification permission launch state")

method_signature = "    private void openRealPanel() {"
validate_signature = "    private void validateSession() {"
method = '''    private void openRealPanel() {
        if (bearer == null || bearer.trim().isEmpty()) {
            showLogin(null);
            return;
        }

        // Android 13+: resolve notification permission while MainActivity is still
        // foreground. Starting the WebView activity first can hide/suppress the prompt.
        if (android.os.Build.VERSION.SDK_INT >= 33
                && !DezgreNotificationManager.hasPermission(this)
                && !notificationPermissionResolvedThisLaunch) {
            new PushDiagnosticsStore(this).recordPermission(false);
            DezgreNotificationManager.requestPermission(this);
            return;
        }

        PushDiagnosticsStore diagnostics = new PushDiagnosticsStore(this);
        diagnostics.recordPermission(DezgreNotificationManager.hasPermission(this));
        diagnostics.recordFirebaseServiceState();

        // This is the real production bootstrap path. Force token acquisition and the
        // scoped idempotent PUT on every session restore/login; backend UPSERT prevents
        // duplicates and derives user/store exclusively from the Bearer.
        PushRegistrationCoordinator.ensureRegistered(this, bearer, null);

        FrameLayout launchSurface = new FrameLayout(this);
        launchSurface.setBackgroundColor(BG);
        setContentView(launchSurface);

        api.get("/mobile/panel-access", bearer, new ApiClient.Callback() {
            @Override public void onSuccess(final JSONObject json) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        String accessUrl = json.optString("url", "").trim();
                        JSONObject data = json.optJSONObject("data");
                        if (accessUrl.isEmpty() && data != null) {
                            accessUrl = data.optString("redirect_url", "").trim();
                        }
                        if (accessUrl.isEmpty() || !accessUrl.startsWith("https://")) {
                            showMain("home");
                            return;
                        }
                        android.content.Intent intent = new android.content.Intent(MainActivity.this, PanelWebActivity.class);
                        intent.putExtra(PanelWebActivity.EXTRA_ACCESS_URL, accessUrl);
                        startActivity(intent);
                        overridePendingTransition(0, 0);
                        finish();
                    }
                });
            }

            @Override public void onError(final ApiClient.ApiException error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (error.status == 401 || error.status == 403) {
                            tokenStore.clear();
                            bearer = null;
                            currentUserAvatar = "";
                            showLogin("Tu sesión expiró o ya no tiene acceso.");
                            return;
                        }
                        showMain("home");
                        android.widget.Toast.makeText(
                                MainActivity.this,
                                "No se pudo abrir el panel web. Se activó el modo de respaldo.",
                                android.widget.Toast.LENGTH_SHORT
                        ).show();
                    }
                });
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != DezgreNotificationManager.REQUEST_NOTIFICATIONS) return;
        notificationPermissionResolvedThisLaunch = true;
        new PushDiagnosticsStore(this).recordPermission(DezgreNotificationManager.hasPermission(this));
        openRealPanel();
    }

'''

method_count = source.count(method_signature)
if method_count == 0:
    marker_count = source.count(validate_signature)
    if marker_count != 1:
        raise SystemExit(f"Expected one validateSession marker, found {marker_count}")
    source = source.replace(validate_signature, method + validate_signature, 1)
    changed = True
    print("Inserted optimized exact web panel bootstrap with push registration")
elif method_count == 1:
    start = source.index(method_signature)
    end = source.index(validate_signature, start)
    existing = source[start:end]
    if "PushRegistrationCoordinator.ensureRegistered(this, bearer, null);" not in existing:
        source = source[:start] + method + source[end:]
        changed = True
        print("Upgraded real panel bootstrap with push registration")
    else:
        print("Real panel bootstrap already includes push registration")
else:
    raise SystemExit(f"Unexpected openRealPanel count: {method_count}")

if changed:
    path.write_text(source, encoding="utf-8")
    print("MainActivity generated source updated safely")
else:
    print("MainActivity generated source already up to date")
