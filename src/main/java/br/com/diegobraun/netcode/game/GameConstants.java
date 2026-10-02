package br.com.diegobraun.netcode.game;

import java.util.Map;

public final class GameConstants {

    public static final double ARENA_WIDTH = 960;
    public static final double ARENA_HEIGHT = 540;
    public static final double PLAYER_RADIUS = 14;
    public static final double ORB_RADIUS = 6;
    public static final double SPEED = 220;
    public static final int INPUT_RATE = 60;
    public static final double INPUT_DT = 1.0 / INPUT_RATE;
    public static final int MAX_ORBS = 12;

    public static final int UP = 1;
    public static final int DOWN = 2;
    public static final int LEFT = 4;
    public static final int RIGHT = 8;

    private GameConstants() {
    }

    public static Map<String, Object> asMap() {
        return Map.of(
                "arenaWidth", ARENA_WIDTH,
                "arenaHeight", ARENA_HEIGHT,
                "playerRadius", PLAYER_RADIUS,
                "orbRadius", ORB_RADIUS,
                "speed", SPEED,
                "inputRate", INPUT_RATE);
    }
}
