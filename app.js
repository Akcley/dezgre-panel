const CONFIG = {
  statusApiUrl: "https://dezgre-status.nickbrya007.workers.dev/api/status",
  servicesApiUrl: "https://dezgre-status.nickbrya007.workers.dev/api/services"
};

let services = [];

const serviceList = document.getElementById("serviceList");
const overallText = document.getElementById("overallText");
const lastCheck = document.getElementById("lastCheck");
const refreshBtn = document.getElementById("refreshBtn");
const themeToggle = document.getElementById("themeToggle");

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>'"]/g, character => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    "'": "&#39;",
    '"': "&quot;"
  })[character]);
}

function isOllama(service) {
  return [service.id, service.name, service.url]
    .filter(Boolean)
    .some(value => String(value).toLowerCase().includes("ollama"));
}

function normalizeServices(payload) {
  const source = Array.isArray(payload?.services)
    ? payload.services
    : Object.entries(payload?.services || {}).map(([id, service]) => ({ id, ...service }));

  return source
    .map((service, index) => ({
      id: String(service.id || service.applicationId || service.composeId || `service-${index}`),
      name: String(service.name || service.appName || "Servicio"),
      description: String(service.description || service.type || "Servicio administrado por Dokploy"),
      url: service.url ? String(service.url) : "",
      online: service.online === true,
      icon: service.icon || "◆"
    }))
    .filter(service => !isOllama(service));
}

function renderServices() {
  if (!services.length) {
    serviceList.innerHTML = '<p class="empty-services">No hay servicios de Dokploy para mostrar.</p>';
    return;
  }

  serviceList.innerHTML = services.map(service => {
    const state = service.online ? "online" : "offline";
    const link = service.url
      ? `<a class="open-btn" href="${escapeHtml(service.url)}" target="_blank" rel="noopener noreferrer">Abrir servicio <span aria-hidden="true">↗</span></a>`
      : '<span class="open-btn disabled" aria-disabled="true">Sin dominio</span>';

    return `
      <article class="service-card" data-service="${escapeHtml(service.id)}">
        <div class="service-icon" aria-hidden="true">${escapeHtml(service.icon)}</div>
        <div class="service-info">
          <h3>${escapeHtml(service.name)}</h3>
          <p>${escapeHtml(service.description)}</p>
        </div>
        <div class="service-status ${state}">
          <span class="status-dot ${state}"></span>
          <span class="label">${service.online ? "Activo" : "Sin conexión"}</span>
        </div>
        ${link}
      </article>`;
  }).join("");
}

function saveTheme(theme) {
  localStorage.setItem("dezgre-theme", theme);
  document.documentElement.dataset.theme = theme;
}

function initTheme() {
  const stored = localStorage.getItem("dezgre-theme");
  saveTheme(stored === "light" ? "light" : "dark");
}

themeToggle.addEventListener("click", () => {
  const current = document.documentElement.dataset.theme;
  saveTheme(current === "dark" ? "light" : "dark");
});

function drawLine(canvasId, values) {
  const canvas = document.getElementById(canvasId);
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  const dpr = window.devicePixelRatio || 1;
  const width = canvas.clientWidth || 260;
  const height = canvas.clientHeight || 54;
  canvas.width = width * dpr;
  canvas.height = height * dpr;
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, width, height);
  const styles = getComputedStyle(document.documentElement);
  ctx.strokeStyle = styles.getPropertyValue("--border-strong").trim();
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(0, height - 1);
  ctx.lineTo(width, height - 1);
  ctx.stroke();
  const safeValues = values.length > 1 ? values : [values[0] || 0, values[0] || 0];
  const max = Math.max(...safeValues, 1);
  const min = Math.min(...safeValues, 0);
  const range = max - min || 1;
  ctx.strokeStyle = styles.getPropertyValue("--text").trim();
  ctx.lineWidth = 1.7;
  ctx.beginPath();
  safeValues.forEach((value, index) => {
    const x = (index / (safeValues.length - 1)) * width;
    const y = height - 7 - ((value - min) / range) * (height - 14);
    index === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
  });
  ctx.stroke();
}

function formatUptime(seconds = 0) {
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  return `${days} días ${hours} h`;
}

async function fetchJson(url) {
  const response = await fetch(`${url}?t=${Date.now()}`, { cache: "no-store" });
  if (!response.ok) throw new Error(`La API respondió ${response.status}`);
  return response.json();
}

async function loadStatus() {
  const badge = document.getElementById("serverBadge");
  overallText.textContent = "Actualizando servicios…";
  refreshBtn.disabled = true;

  const [statusResult, servicesResult] = await Promise.allSettled([
    fetchJson(CONFIG.statusApiUrl),
    fetchJson(CONFIG.servicesApiUrl)
  ]);

  if (servicesResult.status === "fulfilled") {
    services = normalizeServices(servicesResult.value);
    renderServices();
    const onlineCount = services.filter(service => service.online).length;
    overallText.textContent = services.length && onlineCount === services.length
      ? "Todos los servicios en línea"
      : `${onlineCount} de ${services.length} servicios en línea`;
  } else {
    overallText.textContent = "No se pudo actualizar la lista de servicios";
  }

  if (statusResult.status === "fulfilled") {
    const data = statusResult.value;
    const server = data.server || {};
    document.getElementById("cpuValue").textContent = server.cpu == null ? "No disponible" : `${server.cpu}%`;
    document.getElementById("memoryValue").textContent = server.memory == null
      ? "No disponible"
      : `${server.memory}% · ${server.memoryUsed}/${server.memoryTotal} GB`;
    document.getElementById("diskValue").textContent = server.diskUsed == null
      ? "No disponible"
      : `${server.diskUsed}/${server.diskTotal} GB`;
    drawLine("cpuChart", data.history?.cpu || [server.cpu || 0]);
    drawLine("memoryChart", data.history?.memory || [server.memory || 0]);
    drawLine("diskChart", data.history?.disk || [server.diskPercent || 0]);
    badge.innerHTML = '<span class="status-dot online"></span><span>Servidor en línea</span>';
    document.getElementById("metricNote").textContent =
      `Entrada: ${server.incomingTrafficMb ?? 0} MB · Salida: ${server.outgoingTrafficMb ?? 0} MB · Encendido: ${formatUptime(server.uptimeSeconds)}`;
    lastCheck.textContent = `Última revisión: ${new Date(data.checkedAt || Date.now()).toLocaleTimeString("es-PE")}`;
  } else {
    badge.innerHTML = '<span class="status-dot offline"></span><span>Servidor sin conexión</span>';
    ["cpuValue", "memoryValue", "diskValue"].forEach(id => { document.getElementById(id).textContent = "No disponible"; });
    document.getElementById("metricNote").textContent = "No se pudo consultar el estado del VPS. La lista de Dokploy se actualiza por separado.";
    lastCheck.textContent = "Última revisión fallida";
  }

  refreshBtn.disabled = false;
}

refreshBtn.addEventListener("click", loadStatus);
initTheme();
serviceList.innerHTML = '<p class="empty-services">Consultando servicios de Dokploy…</p>';
loadStatus();
setInterval(loadStatus, 60000);
