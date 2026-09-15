# DEZGRE Mobile — contrato de notificaciones nativas V1

Estado: **API V1 de dispositivos publicada; Android preparado para FCM real en cuanto exista la configuración Firebase del proyecto (`google-services.json`).**

La app usa exclusivamente `https://api.dezgre.com/v1`, no usa cookies web, no contiene `API_AUTH_SECRET`, no consulta la base de datos y no crea endpoints alternativos fuera de API Bubble.

## Eventos canónicos

El cliente acepta únicamente estos `eventType`:

- `WEB_ORDER_CREATED`
- `AI_ORDER_CREATED`
- `WHATSAPP_CONNECTION_DISCONNECTED`
- `WHATSAPP_RECONNECT_STARTED`

`NEW_WEB_ORDER` y `NEW_AI_ORDER` quedan retirados y no forman parte del flujo móvil.

## Payload push requerido

El transporte FCM debe enviar los eventos como **data messages** para que el mismo `FirebaseMessagingService` aplique deduplicación y routing tanto en foreground como cuando el proceso de la app debe ser levantado por FCM.

```json
{
  "schemaVersion": 1,
  "eventId": "evt_01J...",
  "eventType": "WEB_ORDER_CREATED",
  "storeId": "42",
  "title": "Nueva venta web",
  "body": "Pedido de S/159 en NATIVA",
  "resourceId": "order_uuid_or_id",
  "occurredAt": "2026-09-15T08:00:00Z"
}
```

Reglas:

- `eventId` es obligatorio, único e inmutable. El cliente lo usa para deduplicación persistente.
- `eventType` debe ser uno de los cuatro valores canónicos.
- `storeId` es obligatorio para validar que el recurso pertenece a la tienda activa antes de navegar.
- `title` y `body` son texto de presentación generado por backend. La app no reconstruye montos ni datos comerciales.
- `resourceId` es opcional. En ventas, si viene el ID real del pedido, Mobile abre el detalle existente `/orders/{id}`.
- La app no acepta URLs arbitrarias como deep-link. Deriva la ruta localmente:
  - `WEB_ORDER_CREATED` / `AI_ORDER_CREATED` → Pedidos.
  - eventos de conexión → Conexiones.

## Registro de dispositivo LIVE

### Upsert

```http
PUT https://api.dezgre.com/v1/notifications/devices
Authorization: Bearer <token scoped de la tienda actual>
Content-Type: application/json
```

Body Android:

```json
{
  "platform": "android",
  "provider": "fcm",
  "pushToken": "<FCM_REAL>",
  "deviceId": "<UUID estable de la app>",
  "appVersion": "0.1.16-preview"
}
```

Mobile NO envía `userId`, `storeId`, `email`, `rol`, permisos, `API_AUTH_SECRET` ni credenciales servidor FCM. Usuario y tienda salen exclusivamente del Bearer.

El mismo `deviceId` se conserva entre tiendas. Cada Bearer scoped hace su propio `PUT`, por ejemplo NATIVA y AITANA, sin mezclar contexto entre tiendas.

Se ejecuta `PUT` cuando:

1. una sesión scoped se valida/restaura;
2. después de seleccionar tienda y recibir el Bearer scoped;
3. Firebase entrega/rota un FCM registration token;
4. el usuario usa la acción manual de diagnóstico `Sincronizar FCM ahora`.

No existe polling.

### Logout / unregister

```http
DELETE https://api.dezgre.com/v1/notifications/devices/<deviceId>
Authorization: Bearer <Bearer scoped actual>
```

La app ejecuta el DELETE antes de borrar localmente el Bearer. Si el DELETE falla, conserva la sesión para poder reintentar y no dejar un registro remoto huérfano por un logout local silencioso.

## Firebase Android

El código móvil incluye:

- Firebase Android BoM `34.19.0`;
- `firebase-messaging`;
- Google Services Gradle plugin `4.5.0`;
- `FirebaseMessagingService` real;
- lectura del FCM registration token requerido por el contrato de backend;
- `onNewToken(...)` para registrar rotaciones;
- almacenamiento del token cifrado con Android Keystore AES/GCM;
- UUID propio estable, sin IMEI ni Android ID;
- permiso `POST_NOTIFICATIONS`;
- Notification Channels nativos;
- deduplicación persistente por `eventId`;
- deep-link seguro a Pedidos/Conexiones.

### Configuración Firebase aún necesaria

Actualmente no existe `app/google-services.json` en `dezgre-panel`.

Para la APK preview hay que registrar en Firebase Console una app Android con package name:

`com.dezgre.mobile.preview`

Después descargar el archivo oficial `google-services.json` desde:

Firebase Console → Project settings → General → Your apps → Android app `com.dezgre.mobile.preview` → Download `google-services.json`.

El archivo debe colocarse exactamente en:

`app/google-services.json`

No se debe escribir manualmente ni inventar `project_number`, `mobilesdk_app_id`, API key ni sender ID. El plugin Google Services procesa ese archivo durante build.

Para una futura release sin `.preview`, registrar también `com.dezgre.mobile` en el mismo proyecto Firebase (o en el proyecto de producción definido por infraestructura) y usar el JSON que contenga un cliente compatible con ese package.

## Entrega de mensajes

Para que Mobile controle foreground/background/cerrada con deduplicación y deep-link propios, NEXT debe enviar data payload FCM con los campos canónicos. Si se usa el bloque `notification`, Android puede mostrarlo directamente desde FCM en background sin pasar por `onMessageReceived`, lo que evita la lógica móvil de deduplicación/routing.

La app cerrada significa proceso no residente, no `force-stop` manual por el usuario; Android no garantiza entrega a apps que el usuario haya forzado a detener.

## Certificación en teléfono

Cuando `google-services.json` esté incorporado en el build, certificar:

1. permiso Android → PASS;
2. FCM registration token real → PASS;
3. `PUT /notifications/devices` → PASS;
4. repetir `PUT` → backend idempotente / sin duplicar;
5. NATIVA con Bearer NATIVA → PASS;
6. AITANA con Bearer AITANA y mismo `deviceId` → PASS;
7. foreground → notificación nativa visible;
8. background → visible;
9. proceso cerrado (no force-stop) → visible;
10. tap → Pedidos o Conexiones correcto;
11. mismo `eventId` → una sola notificación;
12. logout → DELETE PASS antes de limpiar sesión;
13. sesión nueva/restaurada → vuelve a asegurar registro.

## iOS

El scaffold `ios-scaffold/DEZGRENativeNotifications.swift` también usa los cuatro nombres canónicos. iOS sigue pendiente de target real, capability Push Notifications y APNs provisioning; no afecta el cierre Android actual.
