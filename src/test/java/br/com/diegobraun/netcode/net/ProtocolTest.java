package br.com.diegobraun.netcode.net;

import br.com.diegobraun.netcode.game.GameWorld;
import br.com.diegobraun.netcode.game.InputCommand;
import br.com.diegobraun.netcode.game.Player;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolTest {

    @Test
    void decodesInputBatch() {
        ByteBuffer buffer = ByteBuffer.allocate(2 + 2 * 5)
                .put(Protocol.INPUTS).put((byte) 2)
                .putInt(41).put((byte) 0b1001)
                .putInt(42).put((byte) 0xFF)
                .flip();

        assertThat(Protocol.decodeInputs(buffer))
                .containsExactly(new InputCommand(41, 0b1001), new InputCommand(42, 0x0F));
    }

    @Test
    void rejectsMalformedInputs() {
        ByteBuffer truncated = ByteBuffer.allocate(4).put(Protocol.INPUTS).put((byte) 3).flip();
        assertThatThrownBy(() -> Protocol.decodeInputs(truncated)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotRoundTripsAndStaysCompact() {
        GameWorld world = new GameWorld(new Random(3));
        Player me = world.addPlayer(false);
        world.setBotCount(9);
        world.tick(20);

        ByteBuffer encoded = Protocol.encodeSnapshot(world.currentTick(), 77, me.id(), 20, world.players(), world.orbs());
        int size = encoded.remaining();
        SnapshotDecoder snapshot = SnapshotDecoder.decode(encoded);

        assertThat(snapshot.tick()).isEqualTo(1);
        assertThat(snapshot.ackSeq()).isEqualTo(77);
        assertThat(snapshot.yourId()).isEqualTo(me.id());
        assertThat(snapshot.players()).hasSize(10);
        assertThat(snapshot.orbs()).hasSize(world.orbs().size());
        assertThat(snapshot.player(me.id()).x()).isEqualTo((float) me.position().x());
        assertThat(size).isLessThan(300);
    }
}
