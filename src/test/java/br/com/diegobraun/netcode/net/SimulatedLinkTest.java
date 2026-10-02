package br.com.diegobraun.netcode.net;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedLinkTest {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

    @Test
    void perfectLinkDeliversImmediately() {
        SimulatedLink link = new SimulatedLink(new Random(1));
        assertThat(link.plan(0, false)).hasValue(0);
    }

    @Test
    void appliesLatencyAndBoundedJitter() {
        SimulatedLink link = new SimulatedLink(new Random(1));
        link.update(new LinkConditions(50, 20, 0, LinkConditions.Mode.UDP));
        for (int i = 0; i < 1000; i++) {
            long delay = link.plan(i * MS, false).orElseThrow();
            assertThat(delay).isBetween(50 * MS, 70 * MS);
        }
    }

    @Test
    void udpModeDropsRoughlyTheConfiguredFraction() {
        SimulatedLink link = new SimulatedLink(new Random(1));
        link.update(new LinkConditions(10, 0, 20, LinkConditions.Mode.UDP));
        long dropped = 0;
        for (int i = 0; i < 10_000; i++) {
            if (link.plan(i * MS, false).isEmpty()) {
                dropped++;
            }
        }
        assertThat(dropped).isBetween(1_800L, 2_200L);
    }

    @Test
    void reliableMessagesAreNeverDroppedInUdpMode() {
        SimulatedLink link = new SimulatedLink(new Random(1));
        link.update(new LinkConditions(10, 0, 50, LinkConditions.Mode.UDP));
        for (int i = 0; i < 1000; i++) {
            assertThat(link.plan(i * MS, true)).isPresent();
        }
    }

    @Test
    void udpModeCanReorderMessagesWithJitter() {
        SimulatedLink link = new SimulatedLink(new Random(1));
        link.update(new LinkConditions(50, 40, 0, LinkConditions.Mode.UDP));
        boolean reordered = false;
        long previousArrival = Long.MIN_VALUE;
        for (int i = 0; i < 200; i++) {
            long sentAt = i * MS;
            long arrival = sentAt + link.plan(sentAt, false).orElseThrow();
            reordered |= arrival < previousArrival;
            previousArrival = arrival;
        }
        assertThat(reordered).isTrue();
    }

    @Test
    void tcpModeNeverDropsAndKeepsOrderBlockingBehindRetransmissions() {
        SimulatedLink link = new SimulatedLink(new Random(1));
        link.update(new LinkConditions(50, 40, 20, LinkConditions.Mode.TCP));
        long previousArrival = Long.MIN_VALUE;
        long maxDelay = 0;
        for (int i = 0; i < 2000; i++) {
            long sentAt = i * 5 * MS;
            OptionalLong delay = link.plan(sentAt, false);
            assertThat(delay).isPresent();
            long arrival = sentAt + delay.getAsLong();
            assertThat(arrival).isGreaterThanOrEqualTo(previousArrival);
            previousArrival = arrival;
            maxDelay = Math.max(maxDelay, delay.getAsLong());
        }
        assertThat(maxDelay).isGreaterThanOrEqualTo(50 * MS + SimulatedLink.MIN_RETRANSMIT_TIMEOUT_NANOS);
    }

    @Test
    void tcpModeDeliversInSendOrderEvenWhenMessagesShareADeliveryInstant() throws Exception {
        SimulatedLink link = new SimulatedLink(new Random(7));
        link.update(new LinkConditions(5, 0, 30, LinkConditions.Mode.TCP));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<Integer> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(300);
        try {
            for (int i = 0; i < 300; i++) {
                int value = i;
                link.transmit(() -> {
                    received.add(value);
                    done.countDown();
                }, false, scheduler);
            }
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            scheduler.shutdownNow();
        }
        assertThat(received).isSorted().hasSize(300);
    }

    @Test
    void conditionsAreClampedToSaneRanges() {
        LinkConditions conditions = new LinkConditions(-5, 9999, 80, null);
        assertThat(conditions.latencyMs()).isZero();
        assertThat(conditions.jitterMs()).isEqualTo(500);
        assertThat(conditions.lossPercent()).isEqualTo(50);
        assertThat(conditions.mode()).isEqualTo(LinkConditions.Mode.UDP);
    }
}
