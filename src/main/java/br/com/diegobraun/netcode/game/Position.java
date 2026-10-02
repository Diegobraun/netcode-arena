package br.com.diegobraun.netcode.game;

public record Position(double x, double y) {

    public double distanceTo(Position other) {
        return Math.hypot(x - other.x, y - other.y);
    }
}
