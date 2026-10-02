package br.com.diegobraun.netcode.net;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

record SnapshotDecoder(long tick, int ackSeq, int yourId, int tickRate, List<PlayerState> players, List<OrbState> orbs,
                       List<ShotState> shots) {

    record PlayerState(int id, float x, float y, int score, int color, boolean bot) {
    }

    record OrbState(int id, float x, float y) {
    }

    record ShotState(int shooterId, int hitId, boolean compensated, float originX, float originY,
                     float endX, float endY, float targetX, float targetY, int rewindMs) {
    }

    static SnapshotDecoder decode(ByteBuffer buffer) {
        if (buffer.get() != Protocol.SNAPSHOT) {
            throw new IllegalArgumentException("not a snapshot");
        }
        long tick = Integer.toUnsignedLong(buffer.getInt());
        int ack = buffer.getInt();
        int yourId = Short.toUnsignedInt(buffer.getShort());
        int tickRate = Byte.toUnsignedInt(buffer.get());
        List<PlayerState> players = new ArrayList<>();
        int playerCount = Short.toUnsignedInt(buffer.getShort());
        for (int i = 0; i < playerCount; i++) {
            players.add(new PlayerState(Short.toUnsignedInt(buffer.getShort()), buffer.getFloat(), buffer.getFloat(),
                    Short.toUnsignedInt(buffer.getShort()), Byte.toUnsignedInt(buffer.get()), buffer.get() == 1));
        }
        List<OrbState> orbs = new ArrayList<>();
        int orbCount = Short.toUnsignedInt(buffer.getShort());
        for (int i = 0; i < orbCount; i++) {
            orbs.add(new OrbState(Short.toUnsignedInt(buffer.getShort()), buffer.getFloat(), buffer.getFloat()));
        }
        List<ShotState> shots = new ArrayList<>();
        int shotCount = Short.toUnsignedInt(buffer.getShort());
        for (int i = 0; i < shotCount; i++) {
            shots.add(new ShotState(Short.toUnsignedInt(buffer.getShort()), Short.toUnsignedInt(buffer.getShort()),
                    buffer.get() == 1, buffer.getFloat(), buffer.getFloat(), buffer.getFloat(), buffer.getFloat(),
                    buffer.getFloat(), buffer.getFloat(), Short.toUnsignedInt(buffer.getShort())));
        }
        return new SnapshotDecoder(tick, ack, yourId, tickRate, players, orbs, shots);
    }

    PlayerState player(int id) {
        return players.stream().filter(p -> p.id() == id).findFirst().orElseThrow();
    }
}
