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
import static br.com.diegobraun.netcode.game.GameConstants.MAX_REWIND_SECONDS;
import static br.com.diegobraun.netcode.game.GameConstants.ORB_RADIUS;
import static br.com.diegobraun.netcode.game.GameConstants.PLAYER_RADIUS;
import static br.com.diegobraun.netcode.game.GameConstants.RIGHT;
import static br.com.diegobraun.netcode.game.GameConstants.SHOT_SCORE;
import static br.com.diegobraun.netcode.game.GameConstants.UP;

public final class GameWorld {

    public static final int MAX_QUEUED_INPUTS = INPUT_RATE;
    private static final int COLORS = 8;
    private static final double BOT_DEADZONE = 4;

    private final Random random;
    private final Map<Integer, Player> players = new LinkedHashMap<>();
    private final List<Orb> orbs = new ArrayList<>();
    private final PositionHistory history = new PositionHistory();
    private final List<ShotEvent> shots = new ArrayList<>();
    private int tickRate = 20;
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
        this.tickRate = tickRate;
        shots.clear();
        double inputsPerTick = (double) INPUT_RATE / tickRate;
        int maxInputsPerTick = (int) Math.ceil(inputsPerTick) * 3;
        for (Player player : List.copyOf(players.values())) {
            if (player.bot()) {
                player.moveAsBot(botButtons(player), inputsPerTick);
            } else {
                player.processInputs(maxInputsPerTick, input -> fire(player, input));
            }
            collectOrbs(player);
        }
        while (orbs.size() < MAX_ORBS) {
            spawnOrb();
        }
        history.record(tick, players.values(), (int) Math.ceil(MAX_REWIND_SECONDS * tickRate) + 2);
    }

    public void setLagCompensation(int playerId, boolean enabled) {
        Player player = players.get(playerId);
        if (player != null) {
            player.setLagCompensation(enabled);
        }
    }

    public List<ShotEvent> shotsThisTick() {
        return Collections.unmodifiableList(shots);
    }

    private void fire(Player shooter, InputCommand input) {
        if (!shooter.tryFire(input.seq())) {
            return;
        }
        Position origin = shooter.position();
        double dx = input.shot().aimX() - origin.x();
        double dy = input.shot().aimY() - origin.y();
        double length = Math.hypot(dx, dy);
        if (length < 1e-6) {
            return;
        }
        double ux = dx / length;
        double uy = dy / length;

        boolean compensated = shooter.lagCompensation() && !history.isEmpty();
        double viewTick = compensated ? clampViewTick(input.shot().viewTick()) : tick;
        Map<Integer, Position> targets = compensated ? history.positionsAt(viewTick) : currentPositions();

        double hitDistance = distanceToArenaEdge(origin, ux, uy);
        int hitId = 0;
        Position targetAtShot = null;
        for (Map.Entry<Integer, Position> target : targets.entrySet()) {
            if (target.getKey() == shooter.id() || !players.containsKey(target.getKey())) {
                continue;
            }
            double rx = target.getValue().x() - origin.x();
            double ry = target.getValue().y() - origin.y();
            double along = rx * ux + ry * uy;
            double across = Math.abs(rx * uy - ry * ux);
            if (along >= 0 && along <= hitDistance && across <= PLAYER_RADIUS) {
                hitDistance = along;
                hitId = target.getKey();
                targetAtShot = target.getValue();
            }
        }

        if (hitId != 0) {
            shooter.addScore(SHOT_SCORE);
            players.get(hitId).respawn(randomPosition(PLAYER_RADIUS * 2));
        }
        Position end = new Position(origin.x() + ux * hitDistance, origin.y() + uy * hitDistance);
        int rewindMs = compensated ? (int) Math.round((tick - viewTick) * 1000.0 / tickRate) : 0;
        shots.add(new ShotEvent(shooter.id(), hitId, compensated, origin, end, targetAtShot, rewindMs));
    }

    private double clampViewTick(double viewTick) {
        double earliest = Math.max(history.oldestTick(), tick - MAX_REWIND_SECONDS * tickRate);
        return Math.max(earliest, Math.min(viewTick, history.newestTick()));
    }

    private Map<Integer, Position> currentPositions() {
        Map<Integer, Position> positions = new LinkedHashMap<>();
        players.values().forEach(p -> positions.put(p.id(), p.position()));
        return positions;
    }

    private static double distanceToArenaEdge(Position origin, double ux, double uy) {
        double tx = ux > 0 ? (ARENA_WIDTH - origin.x()) / ux : ux < 0 ? -origin.x() / ux : Double.MAX_VALUE;
        double ty = uy > 0 ? (ARENA_HEIGHT - origin.y()) / uy : uy < 0 ? -origin.y() / uy : Double.MAX_VALUE;
        return Math.min(tx, ty);
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
