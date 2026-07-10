# Panel General Dezgre

Panel estático preparado para publicarse mediante GitHub Pages.

## Archivos

- `index.html`
- `styles.css`
- `app.js`
- `CNAME`

## Publicación en GitHub Pages

1. Crea un repositorio llamado `dezgre-panel`.
2. Sube todos los archivos de esta carpeta a la rama `main`.
3. Ve a **Settings → Pages**.
4. En **Build and deployment**, selecciona:
   - Source: `Deploy from a branch`
   - Branch: `main`
   - Folder: `/ (root)`
5. Guarda.
6. En **Custom domain**, escribe `panel.dezgre.com`.
7. Activa **Enforce HTTPS** cuando aparezca disponible.

## DNS

Crea este registro en la zona DNS de dezgre.com:

- Tipo: `CNAME`
- Nombre: `panel`
- Destino: `TU-USUARIO.github.io`
- TTL: `300`

Reemplaza `TU-USUARIO` por tu nombre real de GitHub.

## Enlaces configurados

- https://dokploy.dezgre.com
- https://n8n.dezgre.com
- https://chatwoot.dezgre.com
- https://evo.dezgre.com
- https://api.dezgre.com
- https://data.dezgre.com
- https://ollama.dezgre.com

## Métricas reales del VPS

La interfaz ya está preparada para consumir una API externa.

En `app.js`, cambia:

```js
statusApiUrl: ""
```

por:

```js
statusApiUrl: "https://status.dezgre.com/api/status"
```

La API deberá devolver:

```json
{
  "server": {
    "cpu": 6,
    "memory": 19,
    "diskUsed": 75,
    "diskTotal": 400,
    "diskPercent": 18.75
  },
  "history": {
    "cpu": [5, 6, 8, 7],
    "memory": [18, 19, 19, 20],
    "disk": [18.5, 18.6, 18.7, 18.75]
  }
}
```

Mientras esa API no exista, el panel seguirá funcionando y mostrará los accesos y el estado básico de los servicios.
