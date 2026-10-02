package br.com.diegobraun.netcode.net;

import br.com.diegobraun.netcode.game.GameConstants;
import br.com.diegobraun.netcode.game.InputCommand;
import br.com.diegobraun.netcode.game.Orb;
import br.com.diegobraun.netcode.game.Player;
import br.com.diegobraun.netcode.game.Position;
import br.com.diegobraun.netcode.game.ShotEvent;

import java.nio.BufferUnderflowException;
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
    private static final int SHOT_BYTES = 2 + 2 + 1 + 3 * 8 + 2;

    private Protocol() {
    }

    public static List<InputCommand> decodeInputs(ByteBuffer buffer) {
        try {
            if (buffer.remaining() < 2 || buffer.get() != INPUTS) {
                throw new IllegalArgumentException("Not an input message");
            }
            int count = Byte.toUnsignedInt(buffer.get());
            if (count > MAX_INPUTS_PER_MESSAGE) {
                throw new IllegalArgumentException("Too many inputs");
            }
            List<InputCommand> inputs = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int seq = buffer.getInt();
                int buttons = buffer.get() & GameConstants.BUTTON_MASK;
                InputCommand.Shot shot = null;
                if ((buttons & GameConstants.FIRE) != 0) {
                    shot = new InputCommand.Shot(buffer.getFloat(), buffer.getFloat(), buffer.getDouble());
                }
                inputs.add(new InputCommand(seq, buttons, shot));
            }
            if (buffer.hasRemaining()) {
                throw new IllegalArgumentException("Trailing bytes in input message");
            }
            return inputs;
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("Truncated input message", e);
        }
    }

    public static ByteBuffer encodeSnapshot(long tick, int ackSeq, int yourId, int tickRate,
                                            Collection<Player> players, List<Orb> orbs, List<ShotEvent> shots) {
        ByteBuffer buffer = ByteBuffer.allocate(SNAPSHOT_HEADER_BYTES
                + 2 + players.size() * PLAYER_BYTES
                + 2 + orbs.size() * ORB_BYTES
                + 2 + shots.size() * SHOT_BYTES);
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
        buffer.putShort((short) shots.size());
        for (ShotEvent shot : shots) {
            buffer.putShort((short) shot.shooterId());
            buffer.putShort((short) shot.hitId());
            buffer.put((byte) (shot.compensated() ? 1 : 0));
            putPosition(buffer, shot.origin());
            putPosition(buffer, shot.end());
            putPosition(buffer, shot.targetAtShot() == null ? new Position(0, 0) : shot.targetAtShot());
            buffer.putShort((short) Math.min(shot.rewindMs(), 0xFFFF));
        }
        return buffer.flip();
    }

    private static void putPosition(ByteBuffer buffer, Position position) {
        buffer.putFloat((float) position.x());
        buffer.putFloat((float) position.y());
    }
}
