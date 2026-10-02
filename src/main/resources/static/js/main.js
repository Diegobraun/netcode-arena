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

const SHOT_EFFECT_MS = 900;
const LOCAL_TRACER_MS = 120;

const settings = {
  lagCompensation: true,
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
let aim = null;
let fireRequested = false;
let lastShotSeq = -Infinity;
const effects = [];

let latest = null;
let latestReceivedAt = 0;
const history = [];
let renderTick = null;

const stats = { bytesIn: 0, bytesOut: 0, snapshots: 0, outOfOrder: 0, rtt: null, correction: 0, maxCorrection: 0, shots: 0, hits: 0, lastRewindMs: null };
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
      sendShootingSettings();
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

  for (const shot of snapshot.shots) onShotEvent(shot);

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

function onShotEvent(shot) {
  const now = performance.now();
  const mine = shot.shooterId === myId;
  if (mine && shot.hitId) {
    stats.hits++;
  }
  if (mine) stats.lastRewindMs = shot.compensated ? shot.rewindMs : 0;
  effects.push({ kind: "server-shot", shot, mine, until: now + SHOT_EFFECT_MS });
}

function viewTick() {
  if (!latest) return 0;
  if (!settings.interpolation || renderTick === null || history.length < 2) return latest.tick;
  return Math.max(history[0].tick, Math.min(renderTick, history[history.length - 1].tick));
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
  if (fireRequested && aim && seq - lastShotSeq >= constants.shotCooldownInputs) {
    input.shot = { aimX: aim.x, aimY: aim.y, viewTick: viewTick() };
    lastShotSeq = seq;
    stats.shots++;
  }
  fireRequested = false;
  pending.push(input);
  if (settings.prediction && me) me = step(me, input.buttons, constants);
  if (input.shot && me) {
    effects.push({ kind: "local-tracer", from: { ...me }, to: { ...aim }, until: performance.now() + LOCAL_TRACER_MS });
  }
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
    if (!before || Math.hypot(p.x - before.x, p.y - before.y) > constants.teleportDistance) return p;
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

  drawEffects(textColor);
  drawCrosshair(textColor);
  drawScores(textColor);
}

function drawEffects(textColor) {
  const now = performance.now();
  for (let i = effects.length - 1; i >= 0; i--) {
    if (effects[i].until < now) effects.splice(i, 1);
  }
  for (const effect of effects) {
    const life = (effect.until - now) / (effect.kind === "local-tracer" ? LOCAL_TRACER_MS : SHOT_EFFECT_MS);
    ctx.globalAlpha = Math.max(0, Math.min(1, life));
    if (effect.kind === "local-tracer") {
      ctx.strokeStyle = textColor;
      ctx.lineWidth = 1;
      line(effect.from.x, effect.from.y, effect.to.x, effect.to.y);
    } else {
      const { shot } = effect;
      ctx.strokeStyle = shot.hitId ? cssVar("--hit") : cssVar("--muted");
      ctx.lineWidth = shot.hitId ? 2.5 : 1.5;
      line(shot.originX, shot.originY, shot.endX, shot.endY);
      if (shot.hitId) {
        ctx.strokeStyle = cssVar("--hit");
        ctx.setLineDash([3, 3]);
        circle(shot.targetX, shot.targetY, constants.playerRadius, false);
        ctx.setLineDash([]);
        if (effect.mine) {
          ctx.font = "bold 13px system-ui, sans-serif";
          ctx.textAlign = "center";
          ctx.fillStyle = cssVar("--hit");
          const note = shot.compensated ? `+3 · rewind ${shot.rewindMs} ms` : "+3 · sem compensação";
          ctx.fillText(note, shot.targetX, shot.targetY + constants.playerRadius + 16);
        }
      }
    }
  }
  ctx.globalAlpha = 1;
}

function drawCrosshair(color) {
  if (!aim) return;
  ctx.strokeStyle = color;
  ctx.lineWidth = 1.5;
  line(aim.x - 8, aim.y, aim.x - 3, aim.y);
  line(aim.x + 3, aim.y, aim.x + 8, aim.y);
  line(aim.x, aim.y - 8, aim.x, aim.y - 3);
  line(aim.x, aim.y + 3, aim.x, aim.y + 8);
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

function sendShootingSettings() {
  socket.send(JSON.stringify({ type: "shooting", lagCompensation: settings.lagCompensation }));
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
  $("lagCompensation").addEventListener("change", () => {
    settings.lagCompensation = $("lagCompensation").checked;
    sendShootingSettings();
  });
  canvas.addEventListener("mousemove", (event) => (aim = canvasPoint(event)));
  canvas.addEventListener("mouseleave", () => (aim = null));
  canvas.addEventListener("mousedown", (event) => {
    if (event.button !== 0) return;
    aim = canvasPoint(event);
    fireRequested = true;
    event.preventDefault();
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

function canvasPoint(event) {
  const rect = canvas.getBoundingClientRect();
  return {
    x: ((event.clientX - rect.left) / rect.width) * canvas.width,
    y: ((event.clientY - rect.top) / rect.height) * canvas.height,
  };
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
  $("stat-hits").textContent = `${stats.hits}/${stats.shots}`;
  $("stat-rewind").textContent = stats.lastRewindMs === null ? "—" : `${stats.lastRewindMs} ms`;
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
