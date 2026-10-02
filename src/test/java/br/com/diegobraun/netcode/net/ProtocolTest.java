package br.com.diegobraun.netcode.net;

import br.com.diegobraun.netcode.game.GameConstants;
import br.com.diegobraun.netcode.game.GameWorld;
import br.com.diegobraun.netcode.game.InputCommand;
import br.com.diegobraun.netcode.game.Player;
import br.com.diegobraun.netcode.game.Position;
import br.com.diegobraun.netcode.game.ShotEvent;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolTest {

    @Test
    void decodesInputBatchWithAndWithoutShots() {
        ByteBuffer buffer = ByteBuffer.allocate(2 + 5 + 5 + 16)
                .put(Protocol.INPUTS).put((byte) 2)
                .putInt(41).put((byte) 0b1001)
                .putInt(42).put((byte) (0xE0 | GameConstants.FIRE | GameConstants.LEFT))
                .putFloat(300.5f).putFloat(120.25f).putDouble(1040.4)
                .flip();

        assertThat(Protocol.decodeInputs(buffer)).containsExactly(
                new InputCommand(41, 0b1001),
                new InputCommand(42, GameConstants.FIRE | GameConstants.LEFT, new InputCommand.Shot(300.5, 120.25, 1040.4)));
    }

    @Test
    void rejectsShotWithoutItsPayload() {
        ByteBuffer buffer = ByteBuffer.allocate(2 + 5)
                .put(Protocol.INPUTS).put((byte) 1)
                .putInt(1).put((byte) GameConstants.FIRE)
                .flip();
        assertThatThrownBy(() -> Protocol.decodeInputs(buffer)).isInstanceOf(IllegalArgumentException.class);
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

        ShotEvent shot = new ShotEvent(me.id(), 3, true, new Position(10, 20), new Position(30, 40), new Position(50, 60), 275);
        ByteBuffer encoded = Protocol.encodeSnapshot(world.currentTick(), 77, me.id(), 20, world.players(), world.orbs(), List.of(shot));
        int size = encoded.remaining();
        SnapshotDecoder snapshot = SnapshotDecoder.decode(encoded);

        assertThat(snapshot.tick()).isEqualTo(1);
        assertThat(snapshot.ackSeq()).isEqualTo(77);
        assertThat(snapshot.yourId()).isEqualTo(me.id());
        assertThat(snapshot.players()).hasSize(10);
        assertThat(snapshot.orbs()).hasSize(world.orbs().size());
        assertThat(snapshot.player(me.id()).x()).isEqualTo((float) me.position().x());
        assertThat(snapshot.shots()).containsExactly(new SnapshotDecoder.ShotState(me.id(), 3, true, 10, 20, 30, 40, 50, 60, 275));
        assertThat(size).isLessThan(300 + 31);
    }
}
