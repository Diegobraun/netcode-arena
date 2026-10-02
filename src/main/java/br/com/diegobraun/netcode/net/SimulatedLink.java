package br.com.diegobraun.netcode.net;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class SimulatedLink {

    static final long MIN_RETRANSMIT_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

    private record Pending(long deliverAt, long sequence, Runnable delivery) {
    }

    private final Random random;
    private final PriorityQueue<Pending> inFlight = new PriorityQueue<>(
            Comparator.comparingLong(Pending::deliverAt).thenComparingLong(Pending::sequence));
    private volatile LinkConditions conditions = LinkConditions.PERFECT;
    private long lastDeliveryNanos = Long.MIN_VALUE;
    private long sequence;

    public SimulatedLink(Random random) {
        this.random = random;
    }

    public void update(LinkConditions conditions) {
        this.conditions = conditions;
    }

    public LinkConditions conditions() {
        return conditions;
    }

    public boolean transmit(Runnable delivery, boolean reliable, ScheduledExecutorService scheduler) {
        long delay;
        synchronized (this) {
            long now = System.nanoTime();
            OptionalLong planned = plan(now, reliable);
            if (planned.isEmpty()) {
                return false;
            }
            delay = planned.getAsLong();
            inFlight.add(new Pending(now + delay, sequence++, delivery));
        }
        scheduler.schedule(this::deliverDue, delay, TimeUnit.NANOSECONDS);
        return true;
    }

    private void deliverDue() {
        List<Runnable> due = new ArrayList<>();
        synchronized (this) {
            long now = System.nanoTime();
            while (!inFlight.isEmpty() && inFlight.peek().deliverAt() <= now) {
                due.add(inFlight.poll().delivery());
            }
        }
        due.forEach(Runnable::run);
    }

    synchronized OptionalLong plan(long nowNanos, boolean reliable) {
        LinkConditions c = conditions;
        long delay = millis(c.latencyMs());
        if (c.jitterMs() > 0) {
            delay += millis(random.nextInt(c.jitterMs() + 1));
        }
        boolean lost = random.nextDouble() * 100 < c.lossPercent();

        long deliverAt;
        if (c.mode() == LinkConditions.Mode.UDP) {
            if (lost && !reliable) {
                return OptionalLong.empty();
            }
            deliverAt = nowNanos + delay;
        } else {
            if (lost) {
                delay += retransmitPenalty(c);
            }
            deliverAt = lastDeliveryNanos == Long.MIN_VALUE ? nowNanos + delay : Math.max(nowNanos + delay, lastDeliveryNanos);
        }
        lastDeliveryNanos = lastDeliveryNanos == Long.MIN_VALUE ? deliverAt : Math.max(lastDeliveryNanos, deliverAt);
        return OptionalLong.of(deliverAt - nowNanos);
    }

    static long retransmitPenalty(LinkConditions c) {
        return Math.max(MIN_RETRANSMIT_TIMEOUT_NANOS, millis(2L * c.latencyMs()));
    }

    private static long millis(long ms) {
        return TimeUnit.MILLISECONDS.toNanos(ms);
    }
}
