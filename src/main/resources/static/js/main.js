import { step, UP, DOWN, LEFT, RIGHT } from "./physics.js";
import { encodeInputs, decodeSnapshot } from "./protocol.js";

const MAX_INPUTS_PER_MESSAGE = 30;
const PALETTE = ["#1c7ed6", "#2f9e44", "#ae3ec9", "#e03131", "#0ca678", "#d6336c", "#5c7cfa", "#15aabf"];

const PRESETS = {
  lan: { latencyMs: 0, jitterMs: 0, lossPercent: 0, mode: "udp" },
  continental: { latencyMs: 75, jitterMs: 10, lossPercent: 0, mode: "udp" },
  "bad-udp": { latencyMs: 100, jitterMs: 40, lossPercent: 10, mode: "udp" },
  "bad-tcp": { latencyMs: 100, jitterMs: 40, lossPercent: 10, mode: "tcp" },
};

const canvas = document.getElementById("arena");
const ctx = canvas.getContext("2d");
const $ = (id) => document.getElementById(id);

const settings = {
  prediction: true,
  reconciliation: true,
  interpolation: true,
  ghost: true,
  interpDelayMs: 100,
};

const keys = new Set();
let socket;
let constants;
let myId;
let tickRate = 20;

let me = null;
let pending = [];
let seq = 0;

let latest = null;
let latestReceivedAt = 0;
const history = [];
let renderTick = null;

const stats = { bytesIn: 0, bytesOut: 0, snapshots: 0, outOfOrder: 0, rtt: null, correction: 0, maxCorrection: 0 };
let pingId = 0;

function connect() {
  const protocol = location.protocol === "https:" ? "wss" : "ws";
  socket = new WebSocket(`${protocol}://${location.host}/game`);
  socket.binaryType = "arraybuffer";
  socket.addEventListener("message", (event) => {
    if (typeof event.data === "string") onControl(JSON.parse(event.data));
    else onSnapshot(event.data);
  });
  socket.addEventListener("close", () => {
    $("status").textContent = "desconectado — recarregue a página";
  });
}

function onControl(message) {
  switch (message.type) {
    case "welcome":
      constants = message.constants;
      myId = message.playerId;
      applyServerConfig(message);
      canvas.width = constants.arenaWidth;
      canvas.height = constants.arenaHeight;
      $("status").textContent = `conectado como jogador ${myId}`;
      sendNetSettings();
      requestAnimationFrame(frame);
      break;
    case "config":
      applyServerConfig(message);
      break;
    case "pong": {
      const rtt = performance.now() - message.t;
      stats.rtt = stats.rtt === null ? rtt : stats.rtt * 0.7 + rtt * 0.3;
      break;
    }
  }
}

function applyServerConfig(message) {
  tickRate = message.tickRate;
  $("tick-rate").value = String(message.tickRate);
  $("bots").value = String(message.bots);
  $("bots-value").textContent = message.bots;
}

function onSnapshot(buffer) {
  const snapshot = decodeSnapshot(buffer);
  stats.bytesIn += snapshot.bytes;
  stats.snapshots++;

  if (latest && snapshot.tick <= latest.tick) {
    stats.outOfOrder++;
    return;
  }
  latest = snapshot;
  latestReceivedAt = performance.now();
  tickRate = snapshot.tickRate;
  history.push(snapshot);
  while (history.length > 2 && history[0].tick < snapshot.tick - tickRate * 2) history.shift();

  const server = snapshot.players.find((p) => p.id === myId);
  if (!server) return;

  pending = pending.filter((input) => input.seq > snapshot.ackSeq);

  if (!settings.prediction || !me) {
    me = { x: server.x, y: server.y };
    stats.correction = 0;
    return;
  }

  let corrected = { x: server.x, y: server.y };
  if (settings.reconciliation) {
    for (const input of pending) corrected = step(corrected, input.buttons, constants);
  }
  stats.correction = Math.hypot(me.x - corrected.x, me.y - corrected.y);
  stats.maxCorrection = Math.max(stats.maxCorrection, stats.correction);
  me = corrected;
}

function currentButtons() {
  let buttons = 0;
  if (keys.has("ArrowUp") || keys.has("KeyW")) buttons |= UP;
  if (keys.has("ArrowDown") || keys.has("KeyS")) buttons |= DOWN;
  if (keys.has("ArrowLeft") || keys.has("KeyA")) buttons |= LEFT;
  if (keys.has("ArrowRight") || keys.has("KeyD")) buttons |= RIGHT;
  return buttons;
}

function sampleInput() {
  const input = { seq: ++seq, buttons: currentButtons() };
  pending.push(input);
  if (settings.prediction && me) me = step(me, input.buttons, constants);
  const message = encodeInputs(pending.slice(-MAX_INPUTS_PER_MESSAGE));
  if (socket.readyState === WebSocket.OPEN) {
    socket.send(message);
    stats.bytesOut += message.byteLength;
  }
}

let lastFrame = performance.now();
let accumulator = 0;

function frame(now) {
  const elapsed = Math.min(now - lastFrame, 250);
  lastFrame = now;
  accumulator += elapsed;
  const inputStepMs = 1000 / constants.inputRate;
  while (accumulator >= inputStepMs) {
    sampleInput();
    accumulator -= inputStepMs;
  }
  advanceRenderTick(elapsed);
  draw();
  requestAnimationFrame(frame);
}

function advanceRenderTick(elapsedMs) {
  if (!latest) return;
  const delayTicks = (settings.interpDelayMs / 1000) * tickRate;
  const serverTickNow = latest.tick + ((performance.now() - latestReceivedAt) / 1000) * tickRate;
  const target = serverTickNow - delayTicks;
  if (renderTick === null || Math.abs(target - renderTick) > tickRate) {
    renderTick = target;
    return;
  }
  renderTick += (elapsedMs / 1000) * tickRate;
  renderTick += (target - renderTick) * 0.05;
}

function interpolatedPlayers() {
  if (!settings.interpolation || history.length < 2 || renderTick === null) return latest.players;
  let older = history[0];
  let newer = history[history.length - 1];
  if (renderTick <= older.tick) return older.players;
  if (renderTick >= newer.tick) return newer.players;
  for (let i = 0; i < history.length - 1; i++) {
    if (history[i].tick <= renderTick && renderTick <= history[i + 1].tick) {
      older = history[i];
      newer = history[i + 1];
      break;
    }
  }
  const alpha = (renderTick - older.tick) / (newer.tick - older.tick);
  return newer.players.map((p) => {
    const before = older.players.find((o) => o.id === p.id);
    if (!before) return p;
    return { ...p, x: before.x + (p.x - before.x) * alpha, y: before.y + (p.y - before.y) * alpha };
  });
}

function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function draw() {
  ctx.fillStyle = cssVar("--arena");
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.strokeStyle = cssVar("--grid");
  ctx.lineWidth = 1;
  for (let x = 40; x < canvas.width; x += 40) line(x, 0, x, canvas.height);
  for (let y = 40; y < canvas.height; y += 40) line(0, y, canvas.width, y);

  if (!latest) return;

  ctx.fillStyle = cssVar("--orb");
  for (const orb of latest.orbs) circle(orb.x, orb.y, constants.orbRadius, true);

  const textColor = cssVar("--text");
  for (const player of interpolatedPlayers()) {
    if (player.id === myId) continue;
    ctx.fillStyle = PALETTE[player.color % PALETTE.length];
    circle(player.x, player.y, constants.playerRadius, true);
    label(player.bot ? "bot" : `jogador ${player.id}`, player.x, player.y, textColor);
  }

  const server = latest.players.find((p) => p.id === myId);
  if (server && settings.ghost) {
    ctx.strokeStyle = textColor;
    ctx.setLineDash([4, 4]);
    ctx.lineWidth = 2;
    circle(server.x, server.y, constants.playerRadius, false);
    ctx.setLineDash([]);
  }
  if (me && server) {
    ctx.fillStyle = PALETTE[server.color % PALETTE.length];
    circle(me.x, me.y, constants.playerRadius, true);
    ctx.strokeStyle = textColor;
    ctx.lineWidth = 2;
    circle(me.x, me.y, constants.playerRadius, false);
    label("você", me.x, me.y, textColor);
  }

  drawScores(textColor);
}

function drawScores(color) {
  const ranking = [...latest.players].sort((a, b) => b.score - a.score).slice(0, 5);
  ctx.font = "13px system-ui, sans-serif";
  ctx.textAlign = "left";
  ctx.fillStyle = color;
  ranking.forEach((p, i) => {
    const name = p.id === myId ? "você" : p.bot ? `bot ${p.id}` : `jogador ${p.id}`;
    ctx.fillText(`${i + 1}. ${name} — ${p.score}`, 12, 22 + i * 18);
  });
}

function line(x1, y1, x2, y2) {
  ctx.beginPath();
  ctx.moveTo(x1, y1);
  ctx.lineTo(x2, y2);
  ctx.stroke();
}

function circle(x, y, r, fill) {
  ctx.beginPath();
  ctx.arc(x, y, r, 0, Math.PI * 2);
  if (fill) ctx.fill();
  else ctx.stroke();
}

function label(text, x, y, color) {
  ctx.font = "12px system-ui, sans-serif";
  ctx.textAlign = "center";
  ctx.fillStyle = color;
  ctx.fillText(text, x, y - constants.playerRadius - 6);
}

function sendNetSettings() {
  const message = JSON.stringify({
    type: "net",
    latencyMs: Number($("latency").value),
    jitterMs: Number($("jitter").value),
    lossPercent: Number($("loss").value),
    mode: document.querySelector("input[name=mode]:checked").value,
  });
  socket.send(message);
  $("latency-value").textContent = `${$("latency").value} ms`;
  $("jitter-value").textContent = `${$("jitter").value} ms`;
  $("loss-value").textContent = `${$("loss").value}%`;
}

function sendServerSettings() {
  socket.send(JSON.stringify({ type: "server", tickRate: Number($("tick-rate").value), bots: Number($("bots").value) }));
  $("bots-value").textContent = $("bots").value;
}

function applyPreset(name) {
  const preset = PRESETS[name];
  $("latency").value = preset.latencyMs;
  $("jitter").value = preset.jitterMs;
  $("loss").value = preset.lossPercent;
  document.querySelector(`input[name=mode][value=${preset.mode}]`).checked = true;
  sendNetSettings();
}

function bindControls() {
  ["latency", "jitter", "loss"].forEach((id) => $(id).addEventListener("input", sendNetSettings));
  document.querySelectorAll("input[name=mode]").forEach((el) => el.addEventListener("change", sendNetSettings));
  $("tick-rate").addEventListener("change", sendServerSettings);
  $("bots").addEventListener("change", sendServerSettings);
  $("bots").addEventListener("input", () => ($("bots-value").textContent = $("bots").value));
  $("interp-delay").addEventListener("input", () => {
    settings.interpDelayMs = Number($("interp-delay").value);
    $("interp-delay-value").textContent = `${settings.interpDelayMs} ms`;
  });
  ["prediction", "reconciliation", "interpolation", "ghost"].forEach((id) => {
    $(id).addEventListener("change", () => {
      settings[id] = $(id).checked;
      $("reconciliation").disabled = !settings.prediction;
    });
  });
  document.querySelectorAll("[data-preset]").forEach((button) => {
    button.addEventListener("click", () => applyPreset(button.dataset.preset));
  });
  window.addEventListener("keydown", (event) => {
    if (event.target instanceof HTMLInputElement && event.target.type === "range") return;
    if (event.code.startsWith("Arrow")) event.preventDefault();
    keys.add(event.code);
  });
  window.addEventListener("keyup", (event) => keys.delete(event.code));
  window.addEventListener("blur", () => keys.clear());
}

function updateStats() {
  $("stat-rtt").textContent = stats.rtt === null ? "—" : `${Math.round(stats.rtt)} ms`;
  $("stat-snapshots").textContent = `${stats.snapshots * 2}/s`;
  $("stat-in").textContent = `${((stats.bytesIn * 2) / 1024).toFixed(1)} KB/s`;
  $("stat-out").textContent = `${((stats.bytesOut * 2) / 1024).toFixed(1)} KB/s`;
  $("stat-pending").textContent = pending.length;
  $("stat-correction").textContent = `${stats.correction.toFixed(1)} px (máx ${stats.maxCorrection.toFixed(1)})`;
  $("stat-out-of-order").textContent = stats.outOfOrder;
  $("stat-tick").textContent = latest ? latest.tick : "—";
  stats.bytesIn = 0;
  stats.bytesOut = 0;
  stats.snapshots = 0;
  stats.maxCorrection = 0;
}

function ping() {
  if (socket.readyState !== WebSocket.OPEN) return;
  socket.send(JSON.stringify({ type: "ping", id: ++pingId, t: performance.now() }));
}

bindControls();
connect();
setInterval(updateStats, 500);
setInterval(ping, 500);
