from pathlib import Path

main_path = Path("app/src/main/java/com/dezgre/mobile/MainActivity.java")
source = main_path.read_text(encoding="utf-8")
changed = False


def replace_once(old: str, new: str, label: str):
    global source, changed
    if new in source:
        return
    if source.count(old) != 1:
        raise SystemExit(f"Unexpected {label} state; old={source.count(old)}")
    source = source.replace(old, new, 1)
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
    '''        if (bearer == null) {
            showLogin(null);
        } else {
            validateSession();
        }''',
    '''        if (bearer == null) {
            showLogin(null);
        } else {
            openRealPanel();
        }''',
    "saved-session startup",
)

replace_once(
    '''            tokenStore.save(access);
            bearer = access;
            validateSession();''',
    '''            tokenStore.save(access);
            bearer = access;
            openRealPanel();''',
    "fresh-login startup",
)

field_marker = "    private boolean drawerOpen = false;"
push_field = "    private boolean notificationPermissionResolvedThisLaunch = false;"
if push_field not in source:
    if source.count(field_marker) != 1:
        raise SystemExit("MainActivity drawer field marker missing")
    source = source.replace(field_marker, field_marker + "\n" + push_field, 1)
    changed = True

method_signature = "    private void openRealPanel() {"
validate_signature = "    private void validateSession() {"
method = '''    private void openRealPanel() {
        if (bearer == null || bearer.trim().isEmpty()) {
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

        PushDiagnosticsStore diagnostics = new PushDiagnosticsStore(this);
        diagnostics.recordPermission(DezgreNotificationManager.hasPermission(this));
        diagnostics.recordFirebaseServiceState();
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
                                "No se pudo abrir DEZGRE. Inténtalo nuevamente.",
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

if method_signature not in source:
    if source.count(validate_signature) != 1:
        raise SystemExit("validateSession marker missing")
    source = source.replace(validate_signature, method + validate_signature, 1)
    changed = True
elif "PushRegistrationCoordinator.ensureRegistered(this, bearer, null);" not in source[
        source.index(method_signature):source.index(validate_signature, source.index(method_signature))]:
    start = source.index(method_signature)
    end = source.index(validate_signature, start)
    source = source[:start] + method + source[end:]
    changed = True

# Human-facing cleanup. Internal protocol identifiers remain untouched outside rendered copy.
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
    'notificationStatus.setText("Sincronizando FCM con API V1…");':
        'notificationStatus.setText("Sincronizando notificaciones…");',
    '''notificationStatus.setText(success
                                                ? "FCM registrado correctamente para la tienda actual."
                                                : "FCM no pudo registrarse: " + code);''':
    '''notificationStatus.setText(success
                                                ? "Notificaciones sincronizadas correctamente."
                                                : "No se pudieron sincronizar las notificaciones. Inténtalo nuevamente.");''',
    '"No se pudo retirar este dispositivo de API V1 (" + code + "). Reintenta el cierre de sesión."':
        '"No se pudo cerrar la sesión en este dispositivo. Inténtalo nuevamente."',
    'TextView version = text("DEZGRE Mobile " + versionName + " · build " + versionCode, 10, MUTED, false);':
        'TextView version = text("DEZGRE Mobile " + versionName, 10, MUTED, false);',
    'showLogin("No se recibió el Bearer de tienda.");':
        'showLogin("No se pudo iniciar la sesión de la tienda.");',
    '"No se pudo abrir el panel web. Se activó el modo de respaldo."':
        '"No se pudo abrir DEZGRE. Inténtalo nuevamente."',
    '"La misma cuenta de DEZGRE, ahora en una experiencia móvil conectada solo a API V1."':
        '"La misma cuenta de DEZGRE, ahora en una experiencia móvil."',
    '"La contraseña no se guarda. Tu sesión queda protegida con Android Keystore."':
        '"La contraseña no se guarda en este dispositivo."',
    '"La API no devolvió una sesión utilizable."':
        '"No se pudo iniciar la sesión. Inténtalo nuevamente."',
    '"Esta sección ya existe en DEZGRE Web. En Mobile quedará operativa cuando su contrato público esté disponible en API V1; mientras tanto permanece separada para no mezclarla con Inicio ni inventar datos."':
        '"Esta sección ya existe en DEZGRE Web. En Mobile estará disponible próximamente; mientras tanto permanece separada para mantener una experiencia clara."',
    '"Data de producto disponible mediante Bubble API V1."':
        '"Consulta y revisa la información de tus productos."',
    '"Información disponible mediante Bubble API V1."':
        '"Información actual de tu producto."',
    '"Datos calculados directamente por API V1 para tu usuario y tienda."':
        '"Datos actualizados para tu usuario y tienda."',
    '"API V1 no devolvió pedidos dentro de tu alcance actual."':
        '"No hay pedidos dentro de tu alcance actual."',
    '"Información real obtenida de API V1."':
        '"Información actual de este pedido."',
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

if changed:
    main_path.write_text(source, encoding="utf-8")
print("MainActivity production presentation audit PASS")

# Stable native-app identity: preserve stock WebView UA and append one invariant marker.
panel_path = Path("app/src/main/java/com/dezgre/mobile/PanelWebActivity.java")
panel_source = panel_path.read_text(encoding="utf-8")
old_ua = '''        String userAgent = settings.getUserAgentString();
        if (userAgent == null) userAgent = "Android WebView";
        if (!userAgent.contains("DEZGRE-Mobile/")) {
            settings.setUserAgentString(userAgent + " DEZGRE-Mobile/0.1.14");
        }'''
new_ua = '''        String userAgent = settings.getUserAgentString();
        if (userAgent == null) userAgent = "Android WebView";
        final String dezgreMobileMarker = "DEZGRE-Mobile/Android";
        if (!userAgent.contains(dezgreMobileMarker)) {
            settings.setUserAgentString(userAgent + " " + dezgreMobileMarker);
        }'''
if new_ua not in panel_source:
    if panel_source.count(old_ua) != 1:
        raise SystemExit("Unexpected PanelWebActivity UA state")
    panel_source = panel_source.replace(old_ua, new_ua, 1)
    panel_path.write_text(panel_source, encoding="utf-8")
print("Stable DEZGRE-Mobile/Android UA marker PASS")

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
