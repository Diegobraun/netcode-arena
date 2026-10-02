package br.com.diegobraun.netcode.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static br.com.diegobraun.netcode.game.GameConstants.FIRE;
import static br.com.diegobraun.netcode.game.GameConstants.SHOT_COOLDOWN_INPUTS;
import static br.com.diegobraun.netcode.game.GameConstants.SHOT_SCORE;
import static org.assertj.core.api.Assertions.assertThat;

class LagCompensationTest {

    private static final int TICK_RATE = 20;

    private GameWorld world;
    private Player shooter;
    private Player target;

    @BeforeEach
    void setUp() {
        world = new GameWorld(new Random(5));
        world.clearOrbs();
        shooter = world.addPlayer(false);
        target = world.addPlayer(false);
        shooter.respawn(new Position(100, 300));
    }

    @Test
    void rewindsTargetsToTheTickTheShooterWasSeeing() {
        long seenTick = placeTargetAndTick(new Position(500, 300));
        placeTargetAndTick(new Position(500, 360));
        placeTargetAndTick(new Position(500, 420));

        ShotEvent shot = fireAt(1, 500, 300, seenTick);

        assertThat(shot.hit()).isTrue();
        assertThat(shot.hitId()).isEqualTo(target.id());
        assertThat(shot.compensated()).isTrue();
        assertThat(shot.targetAtShot()).isEqualTo(new Position(500, 300));
        assertThat(shot.rewindMs()).isEqualTo(150);
        assertThat(shooter.score()).isEqualTo(SHOT_SCORE);
    }

    @Test
    void withoutCompensationTheSameShotMissesBecauseTheTargetMovedOn() {
        world.setLagCompensation(shooter.id(), false);
        long seenTick = placeTargetAndTick(new Position(500, 300));
        placeTargetAndTick(new Position(500, 360));
        placeTargetAndTick(new Position(500, 420));

        ShotEvent shot = fireAt(1, 500, 300, seenTick);

        assertThat(shot.hit()).isFalse();
        assertThat(shot.compensated()).isFalse();
        assertThat(shooter.score()).isZero();
    }

    @Test
    void interpolatesBetweenTicksLikeTheClientDoes() {
        long first = placeTargetAndTick(new Position(500, 280));
        placeTargetAndTick(new Position(500, 320));

        ShotEvent shot = fireAt(1, 500, 300, first + 0.5);

        assertThat(shot.hit()).isTrue();
        assertThat(shot.targetAtShot()).isEqualTo(new Position(500, 300));
    }

    @Test
    void rewindIsLimitedToOneSecond() {
        long ancient = placeTargetAndTick(new Position(500, 300));
        target.respawn(new Position(500, 500));
        for (int i = 0; i < TICK_RATE * 2; i++) {
            world.tick(TICK_RATE);
        }

        ShotEvent shot = fireAt(1, 500, 300, ancient);

        assertThat(shot.hit()).isFalse();
        assertThat(shot.rewindMs()).isLessThanOrEqualTo(1000);
    }

    @Test
    void hitTargetRespawnsElsewhere() {
        long seen = placeTargetAndTick(new Position(500, 300));
        fireAt(1, 500, 300, seen);
        assertThat(target.position().distanceTo(new Position(500, 300))).isGreaterThan(0);
    }

    @Test
    void cooldownIsCountedInInputsAndRedundantCopiesFireOnlyOnce() {
        long seen = placeTargetAndTick(new Position(500, 300));
        InputCommand shotInput = new InputCommand(1, FIRE, new InputCommand.Shot(500, 300, seen));

        world.receiveInputs(shooter.id(), List.of(shotInput));
        world.receiveInputs(shooter.id(), List.of(shotInput));
        world.tick(TICK_RATE);
        assertThat(world.shotsThisTick()).hasSize(1);

        world.receiveInputs(shooter.id(), List.of(new InputCommand(SHOT_COOLDOWN_INPUTS - 1, FIRE, new InputCommand.Shot(500, 300, seen))));
        world.tick(TICK_RATE);
        assertThat(world.shotsThisTick()).isEmpty();

        world.receiveInputs(shooter.id(), List.of(new InputCommand(SHOT_COOLDOWN_INPUTS + 1, FIRE, new InputCommand.Shot(500, 300, seen))));
        world.tick(TICK_RATE);
        assertThat(world.shotsThisTick()).hasSize(1);
    }

    @Test
    void shotsStopAtTheNearestTarget() {
        Player blocker = world.addPlayer(false);
        blocker.respawn(new Position(300, 300));
        long seen = placeTargetAndTick(new Position(500, 300));
        blocker.respawn(new Position(300, 300));

        ShotEvent shot = fireAt(1, 500, 300, seen);

        assertThat(shot.hitId()).isEqualTo(blocker.id());
        assertThat(shot.end().x()).isEqualTo(300.0);
    }

    private long placeTargetAndTick(Position position) {
        target.respawn(position);
        world.tick(TICK_RATE);
        return world.currentTick();
    }

    private ShotEvent fireAt(int seq, double x, double y, double viewTick) {
        world.receiveInputs(shooter.id(), List.of(new InputCommand(seq, FIRE, new InputCommand.Shot(x, y, viewTick))));
        target.respawn(target.position());
        world.tick(TICK_RATE);
        assertThat(world.shotsThisTick()).hasSize(1);
        return world.shotsThisTick().getFirst();
    }
}
