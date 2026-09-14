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

old_entry = '''                        applyIdentity(json);
                        showMain("home");'''
new_entry = '''                        applyIdentity(json);
                        openRealPanel();'''

old_entry_count = source.count(old_entry)
new_entry_count = source.count(new_entry)
if new_entry_count == 1 and old_entry_count == 0:
    print("Session already opens the real DEZGRE panel")
elif old_entry_count == 1 and new_entry_count == 0:
    source = source.replace(old_entry, new_entry, 1)
    changed = True
    print("Session entry patched to real DEZGRE panel")
else:
    raise SystemExit(
        f"Unexpected session entry state: old={old_entry_count}, new={new_entry_count}; refusing broad rewrite"
    )

method_signature = "    private void openRealPanel() {"
validate_signature = "    private void validateSession() {"
method = '''    private void openRealPanel() {
        setContentView(loadingScreen("Abriendo el panel real de DEZGRE…"));
        api.get("/mobile/panel-access", bearer, new ApiClient.Callback() {
            @Override public void onSuccess(final JSONObject json) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        String accessUrl = json.optString("url", "").trim();
                        if (accessUrl.isEmpty() || !accessUrl.startsWith("https://")) {
                            android.widget.Toast.makeText(
                                    MainActivity.this,
                                    "La API no devolvió un acceso web seguro. Se abrirá el modo móvil de respaldo.",
                                    android.widget.Toast.LENGTH_LONG
                            ).show();
                            showMain("home");
                            return;
                        }
                        android.content.Intent intent = new android.content.Intent(MainActivity.this, PanelWebActivity.class);
                        intent.putExtra(PanelWebActivity.EXTRA_ACCESS_URL, accessUrl);
                        startActivity(intent);
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
                        android.widget.Toast.makeText(
                                MainActivity.this,
                                "El panel web todavía no está disponible. Se abrirá el modo móvil de respaldo.",
                                android.widget.Toast.LENGTH_LONG
                        ).show();
                        showMain("home");
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
    print("Inserted exact web panel bootstrap")
elif method_count == 1:
    print("Exact web panel bootstrap already present")
else:
    raise SystemExit(f"Unexpected openRealPanel count: {method_count}")

if changed:
    path.write_text(source, encoding="utf-8")
    print("MainActivity generated source updated safely")
else:
    print("MainActivity generated source already up to date")
