package br.com.diegobraun.netcode.net;

import org.springframework.web.socket.WebSocketSession;

import java.util.Random;

final class ClientConnection {

    private final WebSocketSession session;
    private final int playerId;
    private final SimulatedLink uplink;
    private final SimulatedLink downlink;

    ClientConnection(WebSocketSession session, int playerId, Random random) {
        this.session = session;
        this.playerId = playerId;
        this.uplink = new SimulatedLink(new Random(random.nextLong()));
        this.downlink = new SimulatedLink(new Random(random.nextLong()));
    }

    WebSocketSession session() {
        return session;
    }

    int playerId() {
        return playerId;
    }

    SimulatedLink uplink() {
        return uplink;
    }

    SimulatedLink downlink() {
        return downlink;
    }

    void updateConditions(LinkConditions conditions) {
        uplink.update(conditions);
        downlink.update(conditions);
    }
}
