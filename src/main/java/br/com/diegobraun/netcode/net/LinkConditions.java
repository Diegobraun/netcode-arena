package br.com.diegobraun.netcode.net;

public record LinkConditions(int latencyMs, int jitterMs, double lossPercent, Mode mode) {

    public enum Mode { UDP, TCP }

    public static final LinkConditions PERFECT = new LinkConditions(0, 0, 0, Mode.UDP);

    public LinkConditions {
        latencyMs = Math.clamp(latencyMs, 0, 1000);
        jitterMs = Math.clamp(jitterMs, 0, 500);
        lossPercent = Math.clamp(lossPercent, 0, 50);
        mode = mode == null ? Mode.UDP : mode;
    }
}
