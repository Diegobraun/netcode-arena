export const UP = 1;
export const DOWN = 2;
export const LEFT = 4;
export const RIGHT = 8;
export const FIRE = 16;

export function step(position, buttons, c) {
  const dx = (buttons & RIGHT ? 1 : 0) - (buttons & LEFT ? 1 : 0);
  const dy = (buttons & DOWN ? 1 : 0) - (buttons & UP ? 1 : 0);
  const length = Math.sqrt(dx * dx + dy * dy);
  if (length === 0) return position;
  const distance = (c.speed * (1 / c.inputRate)) / length;
  return {
    x: clamp(position.x + dx * distance, c.playerRadius, c.arenaWidth - c.playerRadius),
    y: clamp(position.y + dy * distance, c.playerRadius, c.arenaHeight - c.playerRadius),
  };
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}
