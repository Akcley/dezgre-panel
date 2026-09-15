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

method_signature = "    private void openRealPanel() {"
validate_signature = "    private void validateSession() {"
method = '''    private void openRealPanel() {
        if (bearer == null || bearer.trim().isEmpty()) {
            showLogin(null);
            return;
        }

        // Keep the launch transition deliberately quiet: no second logo, spinner or
        // loading message. panel-access already validates the Bearer and store access,
        // so calling /me first only added an unnecessary network roundtrip.
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
                        // Network/API outage must not make the installed app unusable.
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

'''

method_count = source.count(method_signature)
if method_count == 0:
    marker_count = source.count(validate_signature)
    if marker_count != 1:
        raise SystemExit(f"Expected one validateSession marker, found {marker_count}")
    source = source.replace(validate_signature, method + validate_signature, 1)
    changed = True
    print("Inserted optimized exact web panel bootstrap")
elif method_count == 1:
    print("Optimized exact web panel bootstrap already present")
else:
    raise SystemExit(f"Unexpected openRealPanel count: {method_count}")

if changed:
    path.write_text(source, encoding="utf-8")
    print("MainActivity generated source updated safely")
else:
    print("MainActivity generated source already up to date")
