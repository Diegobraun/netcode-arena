package br.com.diegobraun.netcode.game;

import static br.com.diegobraun.netcode.game.GameConstants.ARENA_HEIGHT;
import static br.com.diegobraun.netcode.game.GameConstants.ARENA_WIDTH;
import static br.com.diegobraun.netcode.game.GameConstants.DOWN;
import static br.com.diegobraun.netcode.game.GameConstants.INPUT_DT;
import static br.com.diegobraun.netcode.game.GameConstants.LEFT;
import static br.com.diegobraun.netcode.game.GameConstants.PLAYER_RADIUS;
import static br.com.diegobraun.netcode.game.GameConstants.RIGHT;
import static br.com.diegobraun.netcode.game.GameConstants.SPEED;
import static br.com.diegobraun.netcode.game.GameConstants.UP;

public final class Physics {

    private Physics() {
    }

    public static Position step(Position position, int buttons) {
        double dx = ((buttons & RIGHT) != 0 ? 1 : 0) - ((buttons & LEFT) != 0 ? 1 : 0);
        double dy = ((buttons & DOWN) != 0 ? 1 : 0) - ((buttons & UP) != 0 ? 1 : 0);
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length == 0) {
            return position;
        }
        double distance = SPEED * INPUT_DT / length;
        return new Position(
                clamp(position.x() + dx * distance, PLAYER_RADIUS, ARENA_WIDTH - PLAYER_RADIUS),
                clamp(position.y() + dy * distance, PLAYER_RADIUS, ARENA_HEIGHT - PLAYER_RADIUS));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
