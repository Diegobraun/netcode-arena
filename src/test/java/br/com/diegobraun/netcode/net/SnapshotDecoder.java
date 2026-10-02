package br.com.diegobraun.netcode.net;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

record SnapshotDecoder(long tick, int ackSeq, int yourId, int tickRate, List<PlayerState> players, List<OrbState> orbs) {

    record PlayerState(int id, float x, float y, int score, int color, boolean bot) {
    }

    record OrbState(int id, float x, float y) {
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
        return new SnapshotDecoder(tick, ack, yourId, tickRate, players, orbs);
    }

    PlayerState player(int id) {
        return players.stream().filter(p -> p.id() == id).findFirst().orElseThrow();
    }
}
