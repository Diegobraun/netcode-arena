const INPUTS = 1;
const SNAPSHOT = 1;

const FIRE = 16;

export function encodeInputs(inputs) {
  const size = inputs.reduce((total, input) => total + 5 + (input.shot ? 16 : 0), 2);
  const buffer = new ArrayBuffer(size);
  const view = new DataView(buffer);
  view.setUint8(0, INPUTS);
  view.setUint8(1, inputs.length);
  let offset = 2;
  for (const input of inputs) {
    view.setUint32(offset, input.seq);
    view.setUint8(offset + 4, input.shot ? input.buttons | FIRE : input.buttons);
    offset += 5;
    if (input.shot) {
      view.setFloat32(offset, input.shot.aimX);
      view.setFloat32(offset + 4, input.shot.aimY);
      view.setFloat64(offset + 8, input.shot.viewTick);
      offset += 16;
    }
  }
  return buffer;
}

export function decodeSnapshot(buffer) {
  const view = new DataView(buffer);
  if (view.getUint8(0) !== SNAPSHOT) throw new Error("not a snapshot");
  let offset = 1;
  const tick = view.getUint32(offset); offset += 4;
  const ackSeq = view.getUint32(offset); offset += 4;
  const yourId = view.getUint16(offset); offset += 2;
  const tickRate = view.getUint8(offset); offset += 1;

  const players = [];
  const playerCount = view.getUint16(offset); offset += 2;
  for (let i = 0; i < playerCount; i++) {
    players.push({
      id: view.getUint16(offset),
      x: view.getFloat32(offset + 2),
      y: view.getFloat32(offset + 6),
      score: view.getUint16(offset + 10),
      color: view.getUint8(offset + 12),
      bot: view.getUint8(offset + 13) === 1,
    });
    offset += 14;
  }

  const orbs = [];
  const orbCount = view.getUint16(offset); offset += 2;
  for (let i = 0; i < orbCount; i++) {
    orbs.push({ id: view.getUint16(offset), x: view.getFloat32(offset + 2), y: view.getFloat32(offset + 6) });
    offset += 10;
  }

  const shots = [];
  const shotCount = view.getUint16(offset); offset += 2;
  for (let i = 0; i < shotCount; i++) {
    shots.push({
      shooterId: view.getUint16(offset),
      hitId: view.getUint16(offset + 2),
      compensated: view.getUint8(offset + 4) === 1,
      originX: view.getFloat32(offset + 5),
      originY: view.getFloat32(offset + 9),
      endX: view.getFloat32(offset + 13),
      endY: view.getFloat32(offset + 17),
      targetX: view.getFloat32(offset + 21),
      targetY: view.getFloat32(offset + 25),
      rewindMs: view.getUint16(offset + 29),
    });
    offset += 31;
  }

  return { tick, ackSeq, yourId, tickRate, players, orbs, shots, bytes: buffer.byteLength };
}
