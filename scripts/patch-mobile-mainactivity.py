from pathlib import Path

main_path = Path("app/src/main/java/com/dezgre/mobile/MainActivity.java")
source = main_path.read_text(encoding="utf-8")
changed = False


def replace_once(old: str, new: str, label: str):
    global source, changed
    if new in source:
        return
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Unexpected {label} state; old={count}")
    source = source.replace(old, new, 1)
    changed = True


def replace_method(start_signature: str, next_signature: str, replacement: str, label: str):
    global source, changed
    start_count = source.count(start_signature)
    next_count = source.count(next_signature)
    if start_count != 1 or next_count != 1:
        raise SystemExit(f"Unexpected {label} markers; start={start_count}, next={next_count}")
    start = source.index(start_signature)
    end = source.index(next_signature, start)
    existing = source[start:end]
    if existing == replacement:
        return
    source = source[:start] + replacement + source[end:]
    changed = True


replace_once(
    '''        if ("products".equals(tab)) loadProducts();
        else if ("profile".equals(tab)) loadProfile();
        else if ("home".equals(tab)) loadHome();
        else showUnavailableSection(tab);''',
    '''        if ("products".equals(tab)) loadProducts();
        else if ("profile".equals(tab)) loadProfile();
        else if ("home".equals(tab)) loadHome();
        else if ("panel".equals(tab)) loadDashboard();
        else if ("orders".equals(tab)) loadOrders();
        else showUnavailableSection(tab);''',
    "fallback routes",
)

replace_once(
    '''        window.setStatusBarColor(PANEL_DARK);
        window.setNavigationBarColor(PANEL_DARK);''',
    '''        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            int flags = getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (android.os.Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }''',
    "preload system bars",
)

replace_once(
    '''        bearer = tokenStore.load();
        if (bearer == null) {
            showLogin(null);
        } else {
            validateSession();
        }''',
    '''        setContentView(new DezgrePreloadView(this));
        SecureTokenStore.Session savedSession = tokenStore.loadSession();
        bearer = savedSession.hasAccess() ? savedSession.accessToken : null;
        if (!savedSession.hasAny()) {
            showLogin(null);
        } else {
            openRealPanel();
        }''',
    "saved-session startup",
)

field_marker = "    private boolean drawerOpen = false;"
push_field = "    private boolean notificationPermissionResolvedThisLaunch = false;"
if push_field not in source:
    if source.count(field_marker) != 1:
        raise SystemExit("MainActivity drawer field marker missing")
    source = source.replace(field_marker, field_marker + "\n" + push_field, 1)
    changed = True

login_method = '''    private void showLogin(String initialError) {
        bearer = null;
        currentUserName = "Usuario";
        currentUserRole = "";
        currentUserAvatar = "";
        currentStoreName = "Tienda";

        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.setPadding(dp(24), dp(54), dp(24), dp(34));
        scroll.addView(wrap);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        wrap.addView(logo, new LinearLayout.LayoutParams(dp(86), dp(86)));
        wrap.addView(gap(18));

        TextView brand = text("DEZGRE MOBILE", 14, TEXT, true);
        brand.setGravity(Gravity.CENTER);
        brand.setLetterSpacing(.12f);
        wrap.addView(brand);
        wrap.addView(gap(9));
        TextView heading = text("Accede a tu operación", 25, TEXT, true);
        heading.setGravity(Gravity.CENTER);
        wrap.addView(heading);
        wrap.addView(gap(34));

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(2), 0, dp(2), 0);
        wrap.addView(form, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText email = new EditText(this);
        email.setHint("Correo electrónico");
        email.setTextColor(TEXT);
        email.setTextSize(16);
        email.setHintTextColor(MUTED);
        email.setSingleLine(true);
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setPadding(dp(16), 0, dp(16), 0);
        email.setBackground(rounded(Color.WHITE, BORDER, 13));
        form.addView(email, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        form.addView(gap(12));

        final FrameLayout passwordWrap = new FrameLayout(this);
        passwordWrap.setBackground(rounded(Color.WHITE, BORDER, 13));
        final EditText password = new EditText(this);
        password.setHint("Contraseña");
        password.setTextColor(TEXT);
        password.setTextSize(16);
        password.setHintTextColor(MUTED);
        password.setSingleLine(true);
        password.setPadding(dp(16), 0, dp(86), 0);
        password.setBackgroundColor(Color.TRANSPARENT);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        passwordWrap.addView(password, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        final TextView togglePassword = text("Mostrar", 12, ACCENT, true);
        togglePassword.setGravity(Gravity.CENTER);
        togglePassword.setClickable(true);
        FrameLayout.LayoutParams toggleParams = new FrameLayout.LayoutParams(dp(78), dp(58));
        toggleParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        passwordWrap.addView(togglePassword, toggleParams);
        togglePassword.setOnClickListener(new View.OnClickListener() {
            private boolean visible = false;
            @Override public void onClick(View v) {
                visible = !visible;
                password.setTransformationMethod(visible
                        ? null
                        : android.text.method.PasswordTransformationMethod.getInstance());
                password.setSelection(password.getText().length());
                togglePassword.setText(visible ? "Ocultar" : "Mostrar");
            }
        });
        form.addView(passwordWrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        form.addView(gap(14));

        final TextView errorText = text(initialError == null ? "" : initialError, 13, DANGER, false);
        errorText.setGravity(Gravity.CENTER);
        errorText.setVisibility(initialError == null ? View.GONE : View.VISIBLE);
        form.addView(errorText);
        if (initialError != null) form.addView(gap(10));

        final Button submit = button("Entrar", true);
        submit.setTextSize(16);
        form.addView(submit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        submit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String emailValue = email.getText().toString().trim();
                String passwordValue = password.getText().toString();
                if (emailValue.isEmpty() || passwordValue.isEmpty()) {
                    errorText.setText("Completa tu correo y contraseña.");
                    errorText.setVisibility(View.VISIBLE);
                    return;
                }
                hideKeyboard();
                submit.setEnabled(false);
                submit.setText("Entrando…");
                errorText.setVisibility(View.GONE);
                JSONObject body = new JSONObject();
                try {
                    body.put("email", emailValue);
                    body.put("password", passwordValue);
                    body.put("deviceId", MobilePushRegistration.deviceId(MainActivity.this));
                } catch (Exception ignored) {}
                api.post("/auth/login", null, body, new ApiClient.Callback() {
                    @Override public void onSuccess(final JSONObject json) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                String access = json.optString("access_token", "").trim();
                                if (!access.isEmpty()) {
                                    persistAndEnter(json);
                                    return;
                                }
                                if (json.optBoolean("needs_store_selection", false)) {
                                    String ticket = json.optString("selection_ticket", "");
                                    JSONArray stores = json.optJSONArray("stores");
                                    if (!ticket.isEmpty() && stores != null && stores.length() > 0) {
                                        showStoreSelection(ticket, stores);
                                        return;
                                    }
                                }
                                submit.setEnabled(true);
                                submit.setText("Entrar");
                                errorText.setText("No se pudo iniciar la sesión. Inténtalo nuevamente.");
                                errorText.setVisibility(View.VISIBLE);
                            }
                        });
                    }

                    @Override public void onError(final ApiClient.ApiException error) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                submit.setEnabled(true);
                                submit.setText("Entrar");
                                errorText.setText(humanLoginError(error));
                                errorText.setVisibility(View.VISIBLE);
                            }
                        });
                    }
                });
            }
        });
        setContentView(scroll);
    }

    private String humanLoginError(ApiClient.ApiException error) {
        if (error == null) return "No se pudo iniciar sesión. Inténtalo nuevamente.";
        if (error.status == 401) return "El correo o la contraseña no son correctos.";
        if (error.status == 403) return "Tu cuenta no tiene acceso activo a una tienda.";
        if (error.status == 429) return "Demasiados intentos. Inténtalo nuevamente más tarde.";
        if (error.status == 0) return "No pudimos conectar. Revisa tu conexión e inténtalo nuevamente.";
        return "No se pudo iniciar sesión. Inténtalo nuevamente.";
    }

'''
replace_method("    private void showLogin(String initialError) {", "    private void persistAndEnter(", login_method, "premium login")

persist_method = '''    private void persistAndEnter(JSONObject authResponse) {
        try {
            bearer = MobileSessionCoordinator.persistAuthResponse(this, tokenStore, authResponse);
            setContentView(new DezgrePreloadView(this));
            openRealPanel();
        } catch (Exception error) {
            tokenStore.clear();
            bearer = null;
            showLogin("No se pudo guardar la sesión de forma segura. Inicia sesión nuevamente.");
        }
    }

'''
replace_method("    private void persistAndEnter(", "    private void showStoreSelection(", persist_method, "session persistence")

replace_once(
    '''                                    String access = json.optString("access_token", "");
                                    if (access.isEmpty()) showLogin("No se recibió el Bearer de tienda.");
                                    else persistAndEnter(access);''',
    '''                                    String access = json.optString("access_token", "").trim();
                                    if (access.isEmpty()) showLogin("No se pudo iniciar la sesión de la tienda.");
                                    else persistAndEnter(json);''',
    "store selection persistence",
)

open_panel_method = '''    private void openRealPanel() {
        SecureTokenStore.Session session = tokenStore.loadSession();
        if (!session.hasAny()) {
            bearer = null;
            showLogin(null);
            return;
        }

        if (android.os.Build.VERSION.SDK_INT >= 33
                && !DezgreNotificationManager.hasPermission(this)
                && !notificationPermissionResolvedThisLaunch) {
            new PushDiagnosticsStore(this).recordPermission(false);
            DezgreNotificationManager.requestPermission(this);
            return;
        }

        setContentView(new DezgrePreloadView(this));
        if (!session.hasAccess()) {
            refreshSessionAndOpenPanel();
            return;
        }
        bearer = session.accessToken;
        requestPanelAccess(false);
    }

    private void requestPanelAccess(final boolean refreshedOnce) {
        if (bearer == null || bearer.trim().isEmpty()) {
            refreshSessionAndOpenPanel();
            return;
        }
        api.get("/mobile/panel-access", bearer, new ApiClient.Callback() {
            @Override public void onSuccess(final JSONObject json) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        PushDiagnosticsStore diagnostics = new PushDiagnosticsStore(MainActivity.this);
                        diagnostics.recordPermission(DezgreNotificationManager.hasPermission(MainActivity.this));
                        diagnostics.recordFirebaseServiceState();
                        PushRegistrationCoordinator.ensureRegistered(MainActivity.this, bearer, null);

                        String accessUrl = json.optString("url", "").trim();
                        JSONObject data = json.optJSONObject("data");
                        if (accessUrl.isEmpty() && data != null) {
                            accessUrl = data.optString("redirect_url", "").trim();
                        }
                        if (accessUrl.isEmpty() || !accessUrl.startsWith("https://")) {
                            showSessionRetry();
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
                        if ((error.status == 401 || error.status == 403) && !refreshedOnce) {
                            refreshSessionAndOpenPanel();
                            return;
                        }
                        if (error.status == 401 || error.status == 403) {
                            tokenStore.clear();
                            bearer = null;
                            currentUserAvatar = "";
                            showLogin("Tu sesión terminó. Inicia sesión nuevamente.");
                            return;
                        }
                        showSessionRetry();
                    }
                });
            }
        });
    }

    private void refreshSessionAndOpenPanel() {
        setContentView(new DezgrePreloadView(this));
        MobileSessionCoordinator.refresh(this, api, tokenStore, new MobileSessionCoordinator.RefreshCallback() {
            @Override public void onSuccess(final String accessToken) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        bearer = accessToken;
                        requestPanelAccess(true);
                    }
                });
            }

            @Override public void onAuthRequired() {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        bearer = null;
                        currentUserAvatar = "";
                        showLogin("Tu sesión terminó. Inicia sesión nuevamente.");
                    }
                });
            }

            @Override public void onTemporaryFailure() {
                runOnUiThread(new Runnable() {
                    @Override public void run() { showSessionRetry(); }
                });
            }
        });
    }

    private void showSessionRetry() {
        FrameLayout retrySurface = new FrameLayout(this);
        retrySurface.setBackgroundColor(BG);
        retrySurface.addView(new DezgrePreloadView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        Button retry = button("Reintentar", true);
        FrameLayout.LayoutParams retryParams = new FrameLayout.LayoutParams(dp(180), dp(52));
        retryParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        retryParams.bottomMargin = dp(42);
        retrySurface.addView(retry, retryParams);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openRealPanel(); }
        });
        setContentView(retrySurface);
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
if "    private void openRealPanel() {" in source:
    replace_method("    private void openRealPanel() {", "    private void validateSession() {", open_panel_method, "refresh-aware panel bootstrap")
else:
    marker = "    private void validateSession() {"
    if source.count(marker) != 1:
        raise SystemExit("validateSession marker missing")
    source = source.replace(marker, open_panel_method + marker, 1)
    changed = True

logout_old = '''                final String logoutBearer = bearer;
                if (logoutBearer == null || logoutBearer.trim().isEmpty()) {
                    tokenStore.clear();
                    bearer = null;
                    currentStoreId = "";
                    currentUserAvatar = "";
                    showLogin(null);
                    return;
                }
                logout.setEnabled(false);
                logout.setText("Cerrando sesión…");
                PushRegistrationCoordinator.unregisterCurrent(MainActivity.this, logoutBearer,
                        new PushRegistrationCoordinator.Callback() {
                            @Override public void onComplete(final boolean success, final String code) {
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        if (!success) {
                                            logout.setEnabled(true);
                                            logout.setText("Cerrar sesión en este dispositivo");
                                            android.widget.Toast.makeText(
                                                    MainActivity.this,
                                                    "No se pudo cerrar la sesión en este dispositivo. Inténtalo nuevamente.",
                                                    android.widget.Toast.LENGTH_LONG
                                            ).show();
                                            return;
                                        }
                                        tokenStore.clear();
                                        bearer = null;
                                        currentStoreId = "";
                                        currentUserAvatar = "";
                                        showLogin(null);
                                    }
                                });
                            }
                        });'''
logout_new = '''                final String logoutBearer = bearer;
                logout.setEnabled(false);
                logout.setText("Cerrando sesión…");
                MobileSessionCoordinator.logout(MainActivity.this, api, tokenStore, logoutBearer,
                        new MobileSessionCoordinator.LogoutCallback() {
                            @Override public void onComplete(final boolean success) {
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        if (!success) {
                                            logout.setEnabled(true);
                                            logout.setText("Cerrar sesión en este dispositivo");
                                            android.widget.Toast.makeText(
                                                    MainActivity.this,
                                                    "No se pudo cerrar la sesión. Inténtalo nuevamente.",
                                                    android.widget.Toast.LENGTH_LONG
                                            ).show();
                                            return;
                                        }
                                        bearer = null;
                                        currentStoreId = "";
                                        currentUserAvatar = "";
                                        showLogin(null);
                                    }
                                });
                            }
                        });'''
replace_once(logout_old, logout_new, "mobile logout contract")

# Human-facing cleanup. Internal protocol identifiers remain internal.
replacements = {
    '"Controla el permiso de Android y verifica el estado FCM de este teléfono."':
        '"Controla los avisos de DEZGRE en este teléfono."',
    '''        String fcmLabel = PushRegistrationCoordinator.isFirebaseConfigured(this)
                ? MobilePushRegistration.statusLabel(this, bearer)
                : "Firebase pendiente: falta google-services.json";
        notifCopy.addView(gap(2));
        notifCopy.addView(text("Push: " + fcmLabel, 11,
                fcmLabel.startsWith("FCM registrado") ? SUCCESS : MUTED, false));''':
    '''        String notificationLabel = PushRegistrationCoordinator.isFirebaseConfigured(this)
                ? MobilePushRegistration.statusLabel(this, bearer)
                : "Configuración pendiente";
        notifCopy.addView(gap(2));
        notifCopy.addView(text("Estado: " + notificationLabel, 11,
                "Notificaciones activas".equals(notificationLabel) ? SUCCESS : MUTED, false));''',
    '"La prueba local valida el canal nativo. El registro remoto se realiza automáticamente al validar la sesión."':
        '"Puedes comprobar los avisos del teléfono o sincronizarlos manualmente cuando lo necesites."',
    'button("Sincronizar FCM ahora", false)': 'button("Sincronizar notificaciones", false)',
    'notificationStatus.setText("Sincronizando FCM con API V1…");': 'notificationStatus.setText("Sincronizando notificaciones…");',
    '''notificationStatus.setText(success
                                                ? "FCM registrado correctamente para la tienda actual."
                                                : "FCM no pudo registrarse: " + code);''':
    '''notificationStatus.setText(success
                                                ? "Notificaciones sincronizadas correctamente."
                                                : "No se pudieron sincronizar las notificaciones. Inténtalo nuevamente.");''',
    'TextView version = text("DEZGRE Mobile " + versionName + " · build " + versionCode, 10, MUTED, false);':
        'TextView version = text("DEZGRE Mobile " + versionName, 10, MUTED, false);',
    '"Esta sección ya existe en DEZGRE Web. En Mobile quedará operativa cuando su contrato público esté disponible en API V1; mientras tanto permanece separada para no mezclarla con Inicio ni inventar datos."':
        '"Esta sección ya existe en DEZGRE Web. En Mobile estará disponible próximamente; mientras tanto permanece separada para mantener una experiencia clara."',
    '"Data de producto disponible mediante Bubble API V1."': '"Consulta y revisa la información de tus productos."',
    '"Información disponible mediante Bubble API V1."': '"Información actual de tu producto."',
    '"Datos calculados directamente por API V1 para tu usuario y tienda."': '"Datos actualizados para tu usuario y tienda."',
    '"API V1 no devolvió pedidos dentro de tu alcance actual."': '"No hay pedidos dentro de tu alcance actual."',
    '"Información real obtenida de API V1."': '"Información actual de este pedido."',
}
for old, new in replacements.items():
    if new in source:
        continue
    if old not in source:
        raise SystemExit(f"Expected presentation text not found: {old[:90]}")
    source = source.replace(old, new)
    changed = True

for forbidden in (
    "Sincronizar FCM",
    "Sincronizando FCM",
    "FCM registrado correctamente",
    "FCM no pudo registrarse",
    "Firebase pendiente",
    "Push: ",
    "API V1",
    "Bubble API",
    "Android Keystore",
    "La API no devolvió",
    " · build ",
    "No se recibió el Bearer de tienda",
):
    if forbidden in source:
        raise SystemExit(f"Technical user-facing text remains in MainActivity: {forbidden}")

for required in (
    'body.put("deviceId", MobilePushRegistration.deviceId(MainActivity.this));',
    "MobileSessionCoordinator.persistAuthResponse",
    "MobileSessionCoordinator.refresh",
    "MobileSessionCoordinator.logout",
    "new DezgrePreloadView(this)",
    'togglePassword.setText(visible ? "Ocultar" : "Mostrar")',
):
    if required not in source:
        raise SystemExit(f"Required mobile session/presentation wiring missing: {required}")

if changed:
    main_path.write_text(source, encoding="utf-8")
print("MainActivity persistent-session + premium presentation audit PASS")

panel_path = Path("app/src/main/java/com/dezgre/mobile/PanelWebActivity.java")
panel_source = panel_path.read_text(encoding="utf-8")
if "DEZGRE-Mobile/Android" not in panel_source:
    raise SystemExit("Stable DEZGRE-Mobile/Android UA marker missing")
if "DezgrePreloadView" not in panel_source or "dismissPreload" not in panel_source:
    raise SystemExit("WebView preload/fade wiring missing")
print("PanelWebActivity UA + preload PASS")

notification_path = Path("app/src/main/java/com/dezgre/mobile/DezgreNotificationManager.java")
notification_source = notification_path.read_text(encoding="utf-8")
old_small_icon = "        builder.setSmallIcon(R.drawable.ic_launcher)"
new_small_icon = "        builder.setSmallIcon(R.drawable.ic_notification_dezgre)"
if new_small_icon not in notification_source:
    if notification_source.count(old_small_icon) != 1:
        raise SystemExit("Unexpected notification smallIcon state")
    notification_source = notification_source.replace(old_small_icon, new_small_icon, 1)
    notification_path.write_text(notification_source, encoding="utf-8")
print("Official DEZGRE notification smallIcon PASS")
