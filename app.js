const CONFIG = {
  statusApiUrl: "https://dezgre-status.nickbrya007.workers.dev/api/status",
  services: [
    {
      id: "dokploy",
      name: "Dokploy",
      description: "Despliegue, dominios y administración de aplicaciones.",
      url: "https://dokploy.dezgre.com",
      icon: "🚀",
      button: "Abrir Dokploy"
    },
    {
      id: "n8n",
      name: "n8n",
      description: "Automatizaciones, pedidos y flujos internos.",
      url: "https://n8n.dezgre.com",
      icon: "⚙",
      button: "Abrir automatización"
    },
    {
      id: "chatwoot",
      name: "Chatwoot",
      description: "Atención al cliente y gestión de conversaciones.",
      url: "https://chatwoot.dezgre.com",
      icon: "●",
      button: "Abrir Chatwoot"
    },
    {
      id: "evolution",
      name: "Evolution API",
      description: "Instancias y conexión oficial de WhatsApp.",
      url: "https://evo.dezgre.com",
      icon: "◢",
      button: "Abrir Evolution"
    },
    {
      id: "shalom",
      name: "Shalom API",
      description: "Servicios logísticos, agencias y seguimiento.",
      url: "https://api.dezgre.com",
      icon: "📦",
      button: "Abrir API"
    },
    {
      id: "data",
      name: "Base de datos",
      description: "Panel de pedidos, DNI y seguimiento.",
      url: "https://data.dezgre.com",
      icon: "▰",
      button: "Abrir datos"
    },
    {
      id: "ollama",
      name: "Ollama",
      description: "Modelo local y servicios de inteligencia artificial.",
      url: "https://ollama.dezgre.com",
      icon: "◆",
      button: "Abrir Ollama"
    }
  ]
};

const serviceList = document.getElementById("serviceList");
const overallText = document.getElementById("overallText");
const lastCheck = document.getElementById("lastCheck");
const refreshBtn = document.getElementById("refreshBtn");
const themeToggle = document.getElementById("themeToggle");

function renderServices() {
  serviceList.innerHTML = CONFIG.services.map(service => `
    <article class="service-card" data-service="${service.id}">
      <div class="service-icon" aria-hidden="true">${service.icon}</div>
      <div class="service-info">
        <h3>${service.name}</h3>
        <p>${service.description}</p>
      </div>
      <div class="service-status checking">
        <span class="status-dot checking"></span>
        <span class="label">Verificando</span>
      </div>
      <a class="open-btn" href="${service.url}" target="_blank" rel="noopener noreferrer">
        ${service.button} <span aria-hidden="true">↗</span>
      </a>
    </article>
  `).join("");
}

function setServiceState(id, state) {
  const card = document.querySelector(`[data-service="${id}"]`);
  if (!card) return;

  const status = card.querySelector(".service-status");
  const dot = status.querySelector(".status-dot");
  const label = status.querySelector(".label");

  status.className = `service-status ${state}`;
  dot.className = `status-dot ${state}`;
  label.textContent =
    state === "online" ? "Activo" :
    state === "offline" ? "Sin conexión" :
    "Verificando";
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
  const lineColor = styles.getPropertyValue("--text").trim();
  const muted = styles.getPropertyValue("--border-strong").trim();

  ctx.strokeStyle = muted;
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(0, height - 1);
  ctx.lineTo(width, height - 1);
  ctx.stroke();

  const safeValues = values.length > 1 ? values : [values[0] || 0, values[0] || 0];
  const max = Math.max(...safeValues, 1);
  const min = Math.min(...safeValues, 0);
  const range = max - min || 1;

  ctx.strokeStyle = lineColor;
  ctx.lineWidth = 1.7;
  ctx.beginPath();

  safeValues.forEach((value, index) => {
    const x = (index / (safeValues.length - 1)) * width;
    const y = height - 7 - ((value - min) / range) * (height - 14);
    if (index === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  });

  ctx.stroke();
}

function formatUptime(seconds) {
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  return `${days} días ${hours} h`;
}

async function loadStatus() {
  const badge = document.getElementById("serverBadge");

  overallText.textContent = "Verificando servicios…";
  CONFIG.services.forEach(service => setServiceState(service.id, "checking"));

  try {
    const response = await fetch(`${CONFIG.statusApiUrl}?t=${Date.now()}`, {
      cache: "no-store"
    });

    if (!response.ok) throw new Error("No se pudo consultar el estado");

    const data = await response.json();

    document.getElementById("cpuValue").textContent = `${data.server.cpu}%`;
    document.getElementById("memoryValue").textContent =
      `${data.server.memory}% · ${data.server.memoryUsed}/${data.server.memoryTotal} GB`;
    document.getElementById("diskValue").textContent =
      `${data.server.diskUsed}/${data.server.diskTotal} GB`;

    drawLine("cpuChart", data.history?.cpu || [data.server.cpu]);
    drawLine("memoryChart", data.history?.memory || [data.server.memory]);
    drawLine("diskChart", data.history?.disk || [data.server.diskPercent]);

    badge.innerHTML = '<span class="status-dot online"></span><span>Servidor en línea</span>';

    document.getElementById("metricNote").textContent =
      `Entrada: ${data.server.incomingTrafficMb} MB · ` +
      `Salida: ${data.server.outgoingTrafficMb} MB · ` +
      `Encendido: ${formatUptime(data.server.uptimeSeconds)}`;

    let onlineCount = 0;

    CONFIG.services.forEach(service => {
      const item = data.services?.[service.id];
      const online = item?.online === true;
      setServiceState(service.id, online ? "online" : "offline");
      if (online) onlineCount++;
    });

    overallText.textContent =
      onlineCount === CONFIG.services.length
        ? "Todos los servicios en línea"
        : `${onlineCount} de ${CONFIG.services.length} servicios en línea`;

    lastCheck.textContent = `Última revisión: ${new Date(data.checkedAt).toLocaleTimeString("es-PE", {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit"
    })}`;
  } catch (error) {
    badge.innerHTML = '<span class="status-dot offline"></span><span>Servidor sin conexión</span>';
    document.getElementById("cpuValue").textContent = "No disponible";
    document.getElementById("memoryValue").textContent = "No disponible";
    document.getElementById("diskValue").textContent = "No disponible";
    document.getElementById("metricNote").textContent =
      "No se pudo consultar Hostinger. El panel continúa disponible.";

    CONFIG.services.forEach(service => setServiceState(service.id, "offline"));
    overallText.textContent = "No se pudo verificar el servidor";
    lastCheck.textContent = "Última revisión fallida";
  }
}

refreshBtn.addEventListener("click", loadStatus);

initTheme();
renderServices();
loadStatus();

setInterval(loadStatus, 60000);
