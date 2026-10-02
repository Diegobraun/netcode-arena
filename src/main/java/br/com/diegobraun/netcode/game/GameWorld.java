package br.com.diegobraun.netcode.game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static br.com.diegobraun.netcode.game.GameConstants.ARENA_HEIGHT;
import static br.com.diegobraun.netcode.game.GameConstants.ARENA_WIDTH;
import static br.com.diegobraun.netcode.game.GameConstants.DOWN;
import static br.com.diegobraun.netcode.game.GameConstants.INPUT_RATE;
import static br.com.diegobraun.netcode.game.GameConstants.LEFT;
import static br.com.diegobraun.netcode.game.GameConstants.MAX_ORBS;
import static br.com.diegobraun.netcode.game.GameConstants.ORB_RADIUS;
import static br.com.diegobraun.netcode.game.GameConstants.PLAYER_RADIUS;
import static br.com.diegobraun.netcode.game.GameConstants.RIGHT;
import static br.com.diegobraun.netcode.game.GameConstants.UP;

public final class GameWorld {

    public static final int MAX_QUEUED_INPUTS = INPUT_RATE;
    private static final int COLORS = 8;
    private static final double BOT_DEADZONE = 4;

    private final Random random;
    private final Map<Integer, Player> players = new LinkedHashMap<>();
    private final List<Orb> orbs = new ArrayList<>();
    private int nextPlayerId = 1;
    private int nextOrbId = 1;
    private long tick;

    public GameWorld(Random random) {
        this.random = random;
        while (orbs.size() < MAX_ORBS) {
            spawnOrb();
        }
    }

    public Player addPlayer(boolean bot) {
        int id = nextPlayerId++;
        Player player = new Player(id, bot, id % COLORS, randomPosition(PLAYER_RADIUS * 2));
        players.put(id, player);
        return player;
    }

    public void removePlayer(int id) {
        players.remove(id);
    }

    public void setBotCount(int count) {
        List<Player> bots = players.values().stream().filter(Player::bot).toList();
        for (int i = bots.size(); i < count; i++) {
            addPlayer(true);
        }
        for (int i = count; i < bots.size(); i++) {
            players.remove(bots.get(i).id());
        }
    }

    public void receiveInputs(int playerId, List<InputCommand> inputs) {
        Player player = players.get(playerId);
        if (player == null) {
            return;
        }
        inputs.stream()
                .sorted(Comparator.comparingInt(InputCommand::seq))
                .forEach(input -> player.enqueue(input, MAX_QUEUED_INPUTS));
    }

    public void tick(int tickRate) {
        tick++;
        double inputsPerTick = (double) INPUT_RATE / tickRate;
        int maxInputsPerTick = (int) Math.ceil(inputsPerTick) * 3;
        for (Player player : players.values()) {
            if (player.bot()) {
                player.moveAsBot(botButtons(player), inputsPerTick);
            } else {
                player.processInputs(maxInputsPerTick);
            }
            collectOrbs(player);
        }
        while (orbs.size() < MAX_ORBS) {
            spawnOrb();
        }
    }

    public long currentTick() {
        return tick;
    }

    public Collection<Player> players() {
        return Collections.unmodifiableCollection(players.values());
    }

    public Optional<Player> player(int id) {
        return Optional.ofNullable(players.get(id));
    }

    public List<Orb> orbs() {
        return Collections.unmodifiableList(orbs);
    }

    public void placeOrb(Orb orb) {
        orbs.add(orb);
    }

    public void clearOrbs() {
        orbs.clear();
    }

    private void collectOrbs(Player player) {
        orbs.removeIf(orb -> {
            boolean touching = orb.position().distanceTo(player.position()) <= PLAYER_RADIUS + ORB_RADIUS;
            if (touching) {
                player.addScore();
            }
            return touching;
        });
    }

    private int botButtons(Player bot) {
        Comparator<Orb> nearest = Comparator.comparingDouble(orb -> orb.position().distanceTo(bot.position()));
        return orbs.stream()
                .filter(orb -> players.values().stream().noneMatch(other -> other != bot && other.bot()
                        && other.position().distanceTo(orb.position()) < bot.position().distanceTo(orb.position())))
                .min(nearest)
                .or(() -> orbs.stream().min(nearest))
                .map(orb -> {
                    double dx = orb.position().x() - bot.position().x();
                    double dy = orb.position().y() - bot.position().y();
                    int buttons = 0;
                    if (dx > BOT_DEADZONE) {
                        buttons |= RIGHT;
                    } else if (dx < -BOT_DEADZONE) {
                        buttons |= LEFT;
                    }
                    if (dy > BOT_DEADZONE) {
                        buttons |= DOWN;
                    } else if (dy < -BOT_DEADZONE) {
                        buttons |= UP;
                    }
                    return buttons;
                })
                .orElse(0);
    }

    private void spawnOrb() {
        orbs.add(new Orb(nextOrbId++, randomPosition(ORB_RADIUS * 4)));
    }

    private Position randomPosition(double margin) {
        return new Position(
                margin + random.nextDouble() * (ARENA_WIDTH - 2 * margin),
                margin + random.nextDouble() * (ARENA_HEIGHT - 2 * margin));
    }
}
