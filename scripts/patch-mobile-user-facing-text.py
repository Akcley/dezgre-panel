from pathlib import Path

path = Path("app/src/main/java/com/dezgre/mobile/MainActivity.java")
source = path.read_text(encoding="utf-8")

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
    'button("Sincronizar FCM ahora", false)':
        'button("Sincronizar notificaciones", false)',
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
}

changed = False
for old, new in replacements.items():
    if new in source:
        continue
    count = source.count(old)
    if count == 0:
        raise SystemExit(f"Expected production UI text not found: {old[:80]}")
    source = source.replace(old, new)
    changed = True

# Audit only known user-facing push UI strings. Internal protocol identifiers are allowed
# elsewhere in the codebase, but these technical labels must never be rendered to users.
for forbidden in (
    "Sincronizar FCM ahora",
    "Sincronizando FCM",
    "FCM registrado correctamente",
    "FCM no pudo registrarse",
    "Firebase pendiente",
    "Push: ",
    "API V1 (",
    " · build ",
    "No se recibió el Bearer de tienda",
):
    if forbidden in source:
        raise SystemExit(f"Technical user-facing text remains in MainActivity: {forbidden}")

if changed:
    path.write_text(source, encoding="utf-8")
    print("Production-facing Android text sanitized")
else:
    print("Production-facing Android text already sanitized")
