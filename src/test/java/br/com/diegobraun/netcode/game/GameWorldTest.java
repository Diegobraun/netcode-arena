package br.com.diegobraun.netcode.game;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static br.com.diegobraun.netcode.game.GameConstants.INPUT_RATE;
import static br.com.diegobraun.netcode.game.GameConstants.MAX_ORBS;
import static br.com.diegobraun.netcode.game.GameConstants.RIGHT;
import static br.com.diegobraun.netcode.game.GameConstants.SPEED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GameWorldTest {

    private final GameWorld world = new GameWorld(new Random(1));

    @Test
    void processesQueuedInputsAndAcknowledgesTheLastOne() {
        Player player = world.addPlayer(false);
        world.clearOrbs();
        Position start = player.position();

        world.receiveInputs(player.id(), inputs(1, 3, 0));
        world.tick(20);

        assertThat(player.lastProcessedSeq()).isEqualTo(3);
        assertThat(player.position()).isEqualTo(start);
    }

    @Test
    void ignoresRedundantInputsAlreadyReceived() {
        Player player = world.addPlayer(false);
        world.clearOrbs();
        Position start = player.position();

        world.receiveInputs(player.id(), inputs(1, 3, RIGHT));
        world.receiveInputs(player.id(), inputs(1, 6, RIGHT));
        world.tick(10);

        assertThat(player.lastProcessedSeq()).isEqualTo(6);
        assertThat(player.position().x()).isCloseTo(
                Math.min(start.x() + 6 * SPEED / INPUT_RATE, GameConstants.ARENA_WIDTH - GameConstants.PLAYER_RADIUS),
                within(1e-9));
    }

    @Test
    void acceptsGapsWhenInputsAreLost() {
        Player player = world.addPlayer(false);
        world.receiveInputs(player.id(), inputs(10, 12, 0));
        world.tick(20);
        assertThat(player.lastProcessedSeq()).isEqualTo(12);
    }

    @Test
    void limitsHowManyInputsAreSimulatedPerTick() {
        Player player = world.addPlayer(false);
        world.receiveInputs(player.id(), inputs(1, 50, 0));
        world.tick(20);
        assertThat(player.lastProcessedSeq()).isEqualTo(9);
        world.tick(20);
        assertThat(player.lastProcessedSeq()).isEqualTo(18);
    }

    @Test
    void collectingAnOrbScoresAndRespawnsIt() {
        Player player = world.addPlayer(false);
        world.clearOrbs();
        world.placeOrb(new Orb(999, player.position()));

        world.tick(20);

        assertThat(player.score()).isEqualTo(1);
        assertThat(world.orbs()).hasSize(MAX_ORBS).noneMatch(orb -> orb.id() == 999);
    }

    @Test
    void botsAreAddedAndRemovedToMatchTheRequestedCount() {
        world.addPlayer(false);
        world.setBotCount(5);
        assertThat(world.players()).filteredOn(Player::bot).hasSize(5);
        world.setBotCount(2);
        assertThat(world.players()).filteredOn(Player::bot).hasSize(2);
        assertThat(world.players()).filteredOn(p -> !p.bot()).hasSize(1);
    }

    @Test
    void botsMoveTowardsOrbs() {
        world.setBotCount(1);
        Player bot = world.players().iterator().next();
        double before = world.orbs().stream().mapToDouble(o -> o.position().distanceTo(bot.position())).min().orElseThrow();
        world.tick(20);
        double after = world.orbs().stream().mapToDouble(o -> o.position().distanceTo(bot.position())).min().orElseThrow();
        assertThat(after).isLessThan(before);
    }

    @Test
    void botsSpreadOutInsteadOfChasingTheSameOrb() {
        world.setBotCount(4);
        for (int i = 0; i < 200; i++) {
            world.tick(20);
        }
        List<Player> bots = world.players().stream().filter(Player::bot).toList();
        double minDistance = Double.MAX_VALUE;
        for (int i = 0; i < bots.size(); i++) {
            for (int j = i + 1; j < bots.size(); j++) {
                minDistance = Math.min(minDistance, bots.get(i).position().distanceTo(bots.get(j).position()));
            }
        }
        assertThat(minDistance).isGreaterThan(GameConstants.PLAYER_RADIUS);
    }

    private static List<InputCommand> inputs(int fromSeq, int toSeq, int buttons) {
        return IntStream.rangeClosed(fromSeq, toSeq).mapToObj(seq -> new InputCommand(seq, buttons)).toList();
    }
}
