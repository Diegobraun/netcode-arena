package br.com.diegobraun.netcode.game;

public record InputCommand(int seq, int buttons, Shot shot) {

    public record Shot(double aimX, double aimY, double viewTick) {
    }

    public InputCommand(int seq, int buttons) {
        this(seq, buttons, null);
    }

    public boolean fires() {
        return (buttons & GameConstants.FIRE) != 0 && shot != null;
    }
}
