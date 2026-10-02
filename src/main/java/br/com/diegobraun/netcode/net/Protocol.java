package br.com.diegobraun.netcode.net;

import br.com.diegobraun.netcode.game.InputCommand;
import br.com.diegobraun.netcode.game.Orb;
import br.com.diegobraun.netcode.game.Player;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class Protocol {

    public static final byte INPUTS = 1;
    public static final byte SNAPSHOT = 1;
    public static final int MAX_INPUTS_PER_MESSAGE = 64;

    private static final int SNAPSHOT_HEADER_BYTES = 1 + 4 + 4 + 2 + 1;
    private static final int PLAYER_BYTES = 2 + 4 + 4 + 2 + 1 + 1;
    private static final int ORB_BYTES = 2 + 4 + 4;

    private Protocol() {
    }

    public static List<InputCommand> decodeInputs(ByteBuffer buffer) {
        if (buffer.remaining() < 2 || buffer.get() != INPUTS) {
            throw new IllegalArgumentException("Not an input message");
        }
        int count = Byte.toUnsignedInt(buffer.get());
        if (count > MAX_INPUTS_PER_MESSAGE || buffer.remaining() != count * 5) {
            throw new IllegalArgumentException("Malformed input message");
        }
        List<InputCommand> inputs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            inputs.add(new InputCommand(buffer.getInt(), buffer.get() & 0x0F));
        }
        return inputs;
    }

    public static ByteBuffer encodeSnapshot(long tick, int ackSeq, int yourId, int tickRate,
                                            Collection<Player> players, List<Orb> orbs) {
        ByteBuffer buffer = ByteBuffer.allocate(
                SNAPSHOT_HEADER_BYTES + 2 + players.size() * PLAYER_BYTES + 2 + orbs.size() * ORB_BYTES);
        buffer.put(SNAPSHOT);
        buffer.putInt((int) tick);
        buffer.putInt(ackSeq);
        buffer.putShort((short) yourId);
        buffer.put((byte) tickRate);
        buffer.putShort((short) players.size());
        for (Player player : players) {
            buffer.putShort((short) player.id());
            buffer.putFloat((float) player.position().x());
            buffer.putFloat((float) player.position().y());
            buffer.putShort((short) player.score());
            buffer.put((byte) player.color());
            buffer.put((byte) (player.bot() ? 1 : 0));
        }
        buffer.putShort((short) orbs.size());
        for (Orb orb : orbs) {
            buffer.putShort((short) orb.id());
            buffer.putFloat((float) orb.position().x());
            buffer.putFloat((float) orb.position().y());
        }
        return buffer.flip();
    }
}
