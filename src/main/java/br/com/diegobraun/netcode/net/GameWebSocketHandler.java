package br.com.diegobraun.netcode.net;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

@Component
public class GameWebSocketHandler extends AbstractWebSocketHandler {

    private final GameServer server;

    public GameWebSocketHandler(GameServer server) {
        this.server = server;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        server.connect(session);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        server.onInputs(session, message.getPayload());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        server.onControl(session, message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        server.disconnect(session);
    }
}
