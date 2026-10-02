package br.com.diegobraun.netcode.game;

import org.junit.jupiter.api.Test;

import static br.com.diegobraun.netcode.game.GameConstants.ARENA_WIDTH;
import static br.com.diegobraun.netcode.game.GameConstants.DOWN;
import static br.com.diegobraun.netcode.game.GameConstants.INPUT_RATE;
import static br.com.diegobraun.netcode.game.GameConstants.LEFT;
import static br.com.diegobraun.netcode.game.GameConstants.PLAYER_RADIUS;
import static br.com.diegobraun.netcode.game.GameConstants.RIGHT;
import static br.com.diegobraun.netcode.game.GameConstants.SPEED;
import static br.com.diegobraun.netcode.game.GameConstants.UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PhysicsTest {

    private static final Position CENTER = new Position(400, 300);

    @Test
    void movesSpeedDividedByInputRatePerInput() {
        Position moved = Physics.step(CENTER, RIGHT);
        assertThat(moved.x()).isCloseTo(400 + SPEED / INPUT_RATE, within(1e-9));
        assertThat(moved.y()).isEqualTo(300);
    }

    @Test
    void diagonalMovementIsNormalized() {
        Position moved = Physics.step(CENTER, DOWN | RIGHT);
        assertThat(moved.distanceTo(CENTER)).isCloseTo(SPEED / INPUT_RATE, within(1e-9));
    }

    @Test
    void oppositeButtonsCancelOut() {
        assertThat(Physics.step(CENTER, LEFT | RIGHT | UP | DOWN)).isEqualTo(CENTER);
    }

    @Test
    void staysInsideTheArena() {
        Position nearWall = new Position(ARENA_WIDTH - PLAYER_RADIUS - 1, 300);
        assertThat(Physics.step(nearWall, RIGHT).x()).isEqualTo(ARENA_WIDTH - PLAYER_RADIUS);
    }
}
