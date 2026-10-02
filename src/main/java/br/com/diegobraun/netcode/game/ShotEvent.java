package br.com.diegobraun.netcode.game;

public record ShotEvent(
        int shooterId,
        int hitId,
        boolean compensated,
        Position origin,
        Position end,
        Position targetAtShot,
        int rewindMs) {

    public boolean hit() {
        return hitId != 0;
    }
}
