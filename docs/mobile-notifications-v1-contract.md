# DEZGRE Mobile — contrato de notificaciones nativas V1

Estado: **cliente preparado; transporte push y registro remoto pendientes de contrato certificado en `https://api.dezgre.com/v1`.**

Este contrato no usa cookies web, no contiene `API_AUTH_SECRET`, no consulta la base de datos y no crea endpoints alternativos fuera de API Bubble.

## Eventos soportados

El cliente acepta únicamente estos `eventType`:

- `NEW_WEB_ORDER`
- `NEW_AI_ORDER`
- `WHATSAPP_CONNECTION_DISCONNECTED`
- `WHATSAPP_RECONNECT_STARTED`

Cualquier otro tipo se ignora hasta que exista un contrato V1 explícito.

## Payload push requerido

El backend debe entregar el mismo payload semántico a FCM y APNs:

```json
{
  "schemaVersion": 1,
  "eventId": "evt_01J...",
  "eventType": "NEW_WEB_ORDER",
  "storeId": "42",
  "title": "Nueva venta web",
  "body": "Pedido de S/159 en NATIVA",
  "resourceId": "order_uuid_or_id",
  "occurredAt": "2026-09-15T08:00:00Z"
}
```

Reglas:

- `eventId` es obligatorio, único e inmutable. El cliente lo usa para deduplicación persistente.
- `eventType` debe ser uno de los cuatro valores anteriores.
- `storeId` es obligatorio. El cliente evita abrir un recurso si la sesión activa pertenece a otra tienda.
- `title` y `body` son texto de presentación generado por backend. La app no reconstruye montos, nombres de tienda ni datos comerciales.
- `resourceId` es opcional. Para ventas debe ser el ID real del pedido cuando backend lo conozca. Para conexiones puede omitirse mientras no exista un detalle móvil certificado.
- `occurredAt` es opcional para presentación/diagnóstico; nunca reemplaza a `eventId` como llave de deduplicación.

La app **no acepta un URL/deep-link arbitrario enviado por backend**. La ruta se deriva localmente:

- `NEW_WEB_ORDER` / `NEW_AI_ORDER` → `Pedidos`; si existe `resourceId`, abre el detalle real `/orders/{id}` que ya consume Mobile.
- `WHATSAPP_CONNECTION_DISCONNECTED` / `WHATSAPP_RECONNECT_STARTED` → `Conexiones`; mientras ese módulo no tenga contrato V1 propio, se abre la ruta reservada sin inventar datos.

## Registro de dispositivo que NEXT debe entregar

Falta una operación autenticada bajo `/v1` para **upsert de dispositivo push**. El nombre/path exacto lo debe definir NEXT; Mobile no lo inventa.

Semántica requerida de la operación `REGISTER_PUSH_DEVICE`:

- método recomendado: `POST` o `PUT` idempotente;
- autenticación: `Authorization: Bearer <access_token>` actual;
- el backend deriva usuario y tienda desde el Bearer; el cliente no puede registrar un token para otra tienda arbitrariamente;
- upsert lógico por `(usuario, tienda, platform, deviceId)`;
- una rotación de token actualiza el mismo dispositivo en vez de crear duplicados.

Body Android:

```json
{
  "platform": "android",
  "provider": "fcm",
  "pushToken": "FCM_TOKEN",
  "deviceId": "uuid-generado-por-la-app",
  "appVersion": "0.1.15-preview"
}
```

Body iOS:

```json
{
  "platform": "ios",
  "provider": "apns",
  "pushToken": "APNS_DEVICE_TOKEN",
  "deviceId": "uuid-generado-por-la-app",
  "appVersion": "x.y.z"
}
```

Respuesta mínima requerida:

```json
{
  "ok": true
}
```

Mobile solo marcará el token como registrado después de una respuesta 2xx válida.

También hace falta una operación autenticada `UNREGISTER_PUSH_DEVICE` bajo `/v1` para cerrar sesión/revocar el dispositivo. El path lo define NEXT. Body mínimo:

```json
{
  "platform": "android",
  "deviceId": "uuid-generado-por-la-app"
}
```

En iOS el mismo contrato usa `platform: "ios"`.

## Android preparado

Ya existe en el cliente:

- permiso `POST_NOTIFICATIONS` para Android 13+;
- Notification Channels nativos y versionados;
- cuatro nombres de canal para los eventos operativos;
- entrada `DezgreNotificationManager.showPayload(...)` para el transporte FCM futuro;
- parser estricto de `eventId`, `eventType`, `storeId`, `resourceId`;
- deduplicación persistente por `eventId` con ventana de 7 días y máximo 256 eventos;
- push token cifrado con Android Keystore (`AES/GCM`);
- `deviceId` aleatorio UUID estable, sin IMEI ni Android ID;
- payload de registro listo mediante `MobilePushRegistration.buildRegistrationPayload(...)`;
- `MainActivity` en `singleTop` para que tocar una notificación reutilice la tarea actual;
- routing interno seguro a `orders` o `connections`;
- apertura del detalle real del pedido cuando viene `resourceId`;
- validación de `storeId` antes de abrir el recurso.

No se añadió `FirebaseMessagingService` todavía porque faltan el proyecto/configuración FCM certificada y el endpoint V1 de registro. Cuando existan, el servicio solo deberá:

1. entregar `onNewToken(token)` a `MobilePushRegistration.acceptTransportToken(...)`;
2. registrar el token contra la operación V1 entregada por NEXT;
3. entregar cada data payload a `DezgreNotificationManager.showPayload(...)`.

No requiere polling.

## iOS preparado

Como aún no existe target iOS en este repositorio, se dejó un scaffold compilable para integrar cuando nazca el target:

`ios-scaffold/DEZGRENativeNotifications.swift`

Incluye:

- permiso `UNUserNotificationCenter`;
- registro para APNs;
- APNs device token en Keychain;
- UUID de dispositivo estable;
- payload de registro equivalente al Android;
- parser de los cuatro eventos;
- deduplicación por `eventId`;
- presentación nativa en foreground;
- routing a Pedidos/Conexiones al tocar la notificación.

Para recibir con la app cerrada/segundo plano faltan capability/provisioning APNs del target iOS y el envío real desde backend. Las claves privadas APNs permanecen únicamente del lado servidor/infraestructura de build.

## Infraestructura pendiente — bloqueo actual

Mobile se detiene antes de inventar backend. Para cerrar push real faltan exactamente:

1. **Path + método V1 de `REGISTER_PUSH_DEVICE`** con la semántica definida arriba.
2. **Path + método V1 de `UNREGISTER_PUSH_DEVICE`**.
3. **Proyecto/configuración FCM Android certificada** para obtener tokens reales en runtime.
4. **Credenciales FCM servidor** solo en backend/infra; nunca dentro del APK.
5. **Target iOS + Push Notifications capability + APNs entitlement/provisioning**.
6. **Clave/certificado APNs servidor** fuera de la app.
7. Emisión real de los cuatro eventos con `eventId` único y el payload V1 definido aquí.

Hasta que NEXT entregue 1 y 2 y la infraestructura entregue 3–6, el cliente no hace polling, no simula registros remotos y no inventa endpoints.
