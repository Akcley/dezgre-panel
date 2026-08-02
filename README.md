# Panel General Dezgre

Panel estático de `panel.dezgre.com`. Las métricas del VPS proceden de
`/api/status` y la lista de aplicaciones se obtiene de `/api/services` en el
Worker `dezgre-status`.

La lista ya no está escrita a mano: en cada carga y cada 60 segundos el panel
vuelve a consultar Dokploy. Por eso las altas y bajas se reflejan
automáticamente. Cualquier servicio cuyo id, nombre o URL contenga `ollama` se
descarta tanto en el Worker como en el navegador.

## Publicar el panel

Los archivos `index.html`, `styles.css` y `app.js` pueden publicarse directamente
con GitHub Pages. Si cambia el dominio del Worker, actualiza `statusApiUrl` y
`servicesApiUrl` al inicio de `app.js`.

## Modificación de `dezgre-status`

El archivo [`cloudflare/dokploy-services.js`](cloudflare/dokploy-services.js)
contiene la implementación que debe añadirse al Worker. Se ha dejado separado
porque el código fuente actual de `dezgre-status` no forma parte de este
repositorio y no conviene reemplazar su ruta `/api/status`, que ya entrega las
métricas del VPS.

### 1. Variables de Cloudflare

En **Workers & Pages → dezgre-status → Settings → Variables and Secrets** crea:

- `DOKPLOY_URL`: variable de texto con la URL base, por ejemplo
  `https://dokploy.dezgre.com` (sin `/api/project.all`).
- `DOKPLOY_API_KEY`: **Secret**, con la API key generada en Dokploy.

También se puede configurar con Wrangler:

```bash
npx wrangler secret put DOKPLOY_API_KEY
```

No pongas la clave en `app.js`, GitHub, `wrangler.toml` ni en una respuesta JSON.
El navegador solo llama al Worker y es el Worker quien añade `x-api-key` a la
petición privada hacia Dokploy.

### 2. Código que hay que pegar

Copia las funciones de `cloudflare/dokploy-services.js` dentro del módulo del
Worker. Si se mantiene como archivo independiente, impórtala:

```js
import { handleDokployServices } from "./dokploy-services.js";
```

En el `fetch(request, env)` **existente**, inmediatamente después de construir
`url`, añade esta ruta y conserva intacta la implementación actual de
`/api/status`:

```js
const url = new URL(request.url);

if (url.pathname === "/api/services") {
  return handleDokployServices(request, env);
}
```

Después pulsa **Deploy**. El endpoint público debe responder con este contrato
(nunca incluye la API key):

```json
{
  "services": [
    {
      "id": "application-id",
      "name": "n8n",
      "description": "Aplicación administrada por Dokploy",
      "type": "applications",
      "url": "https://n8n.dezgre.com",
      "online": true
    }
  ],
  "checkedAt": "2026-08-02T12:00:00.000Z"
}
```

### 3. Comprobación antes de publicar el panel

```bash
curl -i https://dezgre-status.nickbrya007.workers.dev/api/services
```

Comprueba que devuelve `200`, que las altas actuales aparecen, que no existe
Ollama y que ni las cabeceras ni el cuerpo contienen `DOKPLOY_API_KEY`. Luego
publica esta rama del panel en GitHub Pages.

## Seguridad y CORS

La ruta permite como origen `https://panel.dezgre.com`; cambia ese valor en el
helper si el panel se publica en otro dominio. Los errores enviados al cliente
son genéricos. El detalle se escribe únicamente en el log del Worker y la API
key nunca se registra.
