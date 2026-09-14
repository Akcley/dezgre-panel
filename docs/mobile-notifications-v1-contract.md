# DEZGRE Mobile — contrato de notificaciones V1

Estado: **cliente Android preparado; endpoints remotos pendientes de certificación en `api.dezgre.com/v1`.**

La app nunca debe inventar una preferencia de sonido global. La fuente de verdad es la configuración de **cada tienda** en Configuración → Notificaciones de DEZGRE Web.

## 1. Identidad de una preferencia

Cada configuración se resuelve por:

- `store_id`
- `event_key`

Ejemplos iniciales de `event_key`:

- `whatsapp_message` → Nuevo mensaje de WhatsApp
- `support_chat` → Soporte / Chat Web

Nuevos eventos pueden agregarse sin recompilar la app siempre que respeten el contrato.

## 2. Configuración que V1 debe entregar a la app

```json
{
  "event_key": "whatsapp_message",
  "enabled": true,
  "sound_mode": "custom",
  "sound_name": "whatsapp_computer.mp3",
  "sound_url": "https://.../whatsapp_computer.mp3",
  "sound_revision": "sha256-o-version-inmutable"
}
```

Campos:

- `enabled`: permite o bloquea el aviso para ese evento.
- `sound_mode`: `default`, `custom` o `silent`.
- `sound_name`: nombre visible configurado por el administrador.
- `sound_url`: URL autenticada o firmada del MP3 cuando sea `custom`.
- `sound_revision`: cambia cada vez que cambia el audio o su comportamiento. Es obligatorio para invalidar caché/canal Android.

La app guarda esta configuración localmente por `store_id + event_key`. Un cambio en web debe sincronizarse sin publicar un APK nuevo.

## 3. Registro de dispositivo requerido

V1 debe exponer una operación autenticada para registrar/actualizar un dispositivo del usuario actual. El servidor debe poder guardar varios dispositivos por usuario.

Datos mínimos:

```json
{
  "platform": "android",
  "push_token": "FCM_TOKEN",
  "app_version": "0.1.0",
  "device_id": "ID_GENERADO_POR_LA_APP"
}
```

El Bearer actual determina usuario y tienda; el cliente no debe poder registrar tokens en otra tienda arbitraria.

## 4. Payload push

El transporte push debe enviar datos, no decidir la preferencia final de sonido en el servidor:

```json
{
  "schema_version": 1,
  "notification_id": "evt_123",
  "store_id": "42",
  "event_key": "whatsapp_message",
  "title": "Nuevo mensaje de WhatsApp",
  "body": "Tienes un mensaje nuevo",
  "deep_link": "dezgre://whatsapp/conversation/123"
}
```

La app recibe `event_key`, consulta la configuración cacheada de esa tienda y decide si mostrar, silenciar o reproducir el sonido configurado.

## 5. Sonidos personalizados en Android

Android 8+ fija el sonido de un canal una vez creado. Por eso DEZGRE Mobile crea canales versionados usando `store_id + event_key + sound_revision`.

Para MP3 subidos por el administrador, la fase de sincronización deberá:

1. descargar/verificar el MP3 indicado por V1;
2. guardarlo con una URI local accesible de forma segura;
3. guardar esa `local_sound_uri` junto con la configuración cacheada;
4. crear el canal correspondiente a la nueva `sound_revision`;
5. dejar de usar la revisión anterior para eventos futuros.

Nunca se debe incrustar el MP3 elegido por una tienda dentro del APK.

## 6. Estado implementado en Android

Ya existe en la app:

- permiso `POST_NOTIFICATIONS` para Android 13+;
- caché por tienda y evento;
- modelo de configuración dinámico;
- canales versionados por revisión de sonido;
- soporte para `default`, `custom` y `silent`;
- parser de payload por `store_id + event_key`;
- deep-link reservado en el `Intent` de la notificación;
- prueba local desde Perfil.

Aún falta, y no debe simularse hasta que V1 lo exponga:

- credenciales/configuración FCM del proyecto móvil;
- endpoint V1 de registro de dispositivo/token;
- endpoint V1 para leer la configuración de notificaciones de la tienda;
- descarga autenticada/firmada del MP3 personalizado;
- envío FCM desde el backend/eventos reales;
- resolución final de deep links dentro de cada módulo móvil.

## Regla de seguridad

La app solo se comunica con `https://api.dezgre.com/v1`. No debe leer la base de datos, cookies de la web ni endpoints internos de `dezgre-system` para obtener la configuración.
