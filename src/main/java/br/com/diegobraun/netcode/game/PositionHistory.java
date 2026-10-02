package br.com.diegobraun.netcode.game;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static br.com.diegobraun.netcode.game.GameConstants.TELEPORT_DISTANCE;

public final class PositionHistory {

    private record Entry(long tick, Map<Integer, Position> positions) {
    }

    private final Deque<Entry> entries = new ArrayDeque<>();

    public void record(long tick, Collection<Player> players, int maxEntries) {
        Map<Integer, Position> positions = new HashMap<>();
        players.forEach(p -> positions.put(p.id(), p.position()));
        entries.addLast(new Entry(tick, Map.copyOf(positions)));
        while (entries.size() > maxEntries) {
            entries.pollFirst();
        }
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public long oldestTick() {
        return entries.getFirst().tick();
    }

    public long newestTick() {
        return entries.getLast().tick();
    }

    public Map<Integer, Position> positionsAt(double tick) {
        if (entries.isEmpty()) {
            return Map.of();
        }
        Entry older = entries.getFirst();
        Entry newer = entries.getLast();
        if (tick <= older.tick()) {
            return older.positions();
        }
        if (tick >= newer.tick()) {
            return newer.positions();
        }
        Iterator<Entry> iterator = entries.iterator();
        Entry previous = iterator.next();
        while (iterator.hasNext()) {
            Entry next = iterator.next();
            if (previous.tick() <= tick && tick <= next.tick()) {
                older = previous;
                newer = next;
                break;
            }
            previous = next;
        }
        return interpolate(older, newer, (tick - older.tick()) / (newer.tick() - older.tick()));
    }

    static Map<Integer, Position> interpolate(Entry older, Entry newer, double alpha) {
        Map<Integer, Position> result = new HashMap<>();
        newer.positions().forEach((id, to) -> {
            Position from = older.positions().get(id);
            if (from == null || from.distanceTo(to) > TELEPORT_DISTANCE) {
                result.put(id, to);
            } else {
                result.put(id, new Position(from.x() + (to.x() - from.x()) * alpha, from.y() + (to.y() - from.y()) * alpha));
            }
        });
        return result;
    }
}
