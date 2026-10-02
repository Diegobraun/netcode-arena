package br.com.diegobraun.netcode.game;

import java.util.ArrayDeque;
import java.util.Deque;

public final class Player {

    private final int id;
    private final boolean bot;
    private final int color;
    private final Deque<InputCommand> queue = new ArrayDeque<>();
    private Position position;
    private int score;
    private int lastQueuedSeq;
    private int lastProcessedSeq;
    private double botInputBudget;

    Player(int id, boolean bot, int color, Position position) {
        this.id = id;
        this.bot = bot;
        this.color = color;
        this.position = position;
    }

    public int id() {
        return id;
    }

    public boolean bot() {
        return bot;
    }

    public int color() {
        return color;
    }

    public Position position() {
        return position;
    }

    public int score() {
        return score;
    }

    public int lastProcessedSeq() {
        return lastProcessedSeq;
    }

    int queuedInputs() {
        return queue.size();
    }

    void enqueue(InputCommand input, int maxQueued) {
        if (input.seq() <= lastQueuedSeq) {
            return;
        }
        lastQueuedSeq = input.seq();
        queue.addLast(input);
        while (queue.size() > maxQueued) {
            queue.pollFirst();
        }
    }

    void processInputs(int maxInputs) {
        for (int i = 0; i < maxInputs && !queue.isEmpty(); i++) {
            InputCommand input = queue.pollFirst();
            position = Physics.step(position, input.buttons());
            lastProcessedSeq = input.seq();
        }
    }

    void moveAsBot(int buttons, double inputsThisTick) {
        botInputBudget += inputsThisTick;
        while (botInputBudget >= 1) {
            position = Physics.step(position, buttons);
            botInputBudget -= 1;
        }
    }

    void addScore() {
        score++;
    }
}
