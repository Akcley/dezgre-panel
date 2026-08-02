/**
 * Pega estas funciones en el Worker dezgre-status y llama a
 * handleDokployServices(request, env) desde su handler fetch para /api/services.
 * DOKPLOY_API_KEY debe ser un secret de Cloudflare, nunca una variable pública.
 */
export async function handleDokployServices(request, env) {
  const corsHeaders = {
    "Access-Control-Allow-Origin": "https://panel.dezgre.com",
    "Access-Control-Allow-Methods": "GET, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    "Content-Type": "application/json; charset=UTF-8",
    "Cache-Control": "no-store"
  };

  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders });
  if (request.method !== "GET") return json({ error: "Method not allowed" }, 405, corsHeaders);
  if (!env.DOKPLOY_URL || !env.DOKPLOY_API_KEY) {
    return json({ error: "Dokploy is not configured" }, 503, corsHeaders);
  }

  try {
    const response = await fetch(`${env.DOKPLOY_URL.replace(/\/$/, "")}/api/project.all`, {
      headers: { "x-api-key": env.DOKPLOY_API_KEY }
    });
    if (!response.ok) throw new Error(`Dokploy responded with ${response.status}`);
    const projects = await response.json();
    return json({ services: extractServices(projects), checkedAt: new Date().toISOString() }, 200, corsHeaders);
  } catch (error) {
    console.error("Unable to load Dokploy services", error.message);
    return json({ error: "Unable to load Dokploy services" }, 502, corsHeaders);
  }
}

export function extractServices(projects) {
  const services = [];
  walk(projects, services);
  return services.filter(service => !`${service.id} ${service.name} ${service.url}`.toLowerCase().includes("ollama"));
}

function walk(value, services, collectionName = "") {
  if (Array.isArray(value)) {
    value.forEach(item => {
      if (["applications", "compose", "composes"].includes(collectionName) && item && typeof item === "object") {
        services.push(toService(item, collectionName));
      }
      walk(item, services);
    });
    return;
  }
  if (!value || typeof value !== "object") return;
  Object.entries(value).forEach(([key, child]) => walk(child, services, key));
}

function toService(item, type) {
  const domain = Array.isArray(item.domains) ? item.domains[0] : null;
  const host = domain?.host || domain?.domain;
  const protocol = domain?.https === false ? "http" : "https";
  const status = String(item.applicationStatus || item.composeStatus || item.status || "").toLowerCase();
  return {
    id: item.applicationId || item.composeId || item.serviceId || item.id,
    name: item.name || item.appName || item.applicationName || item.composeName || "Servicio",
    description: type === "compose" || type === "composes" ? "Compose administrado por Dokploy" : "Aplicación administrada por Dokploy",
    type,
    url: host ? `${protocol}://${host}` : "",
    online: ["done", "running", "online", "healthy"].includes(status)
  };
}

function json(body, status, headers) {
  return new Response(JSON.stringify(body), { status, headers });
}
