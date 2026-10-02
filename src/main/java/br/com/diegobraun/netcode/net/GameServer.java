package br.com.diegobraun.netcode.net;

import br.com.diegobraun.netcode.game.GameConstants;
import br.com.diegobraun.netcode.game.GameWorld;
import br.com.diegobraun.netcode.game.InputCommand;
import br.com.diegobraun.netcode.game.Player;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class GameServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GameServer.class);
    private static final List<Integer> ALLOWED_TICK_RATES = List.of(5, 10, 20, 30, 60);
    private static final int MAX_BOTS = 20;

    private final ObjectMapper mapper;
    private final Random random = new Random();
    private final GameWorld world = new GameWorld(new Random());
    private final Map<String, ClientConnection> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService game = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("game-loop").factory());
    private final ScheduledExecutorService network = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("net-sim").factory());

    private volatile int tickRate;
    private volatile int botCount;
    private ScheduledFuture<?> loop;
    private volatile boolean running;

    public GameServer(ObjectMapper mapper,
                      @Value("${game.tick-rate:20}") int tickRate,
                      @Value("${game.bots:3}") int botCount) {
        this.mapper = mapper;
        this.tickRate = ALLOWED_TICK_RATES.contains(tickRate) ? tickRate : 20;
        this.botCount = Math.clamp(botCount, 0, MAX_BOTS);
    }

    @Override
    public void start() {
        game.execute(() -> world.setBotCount(botCount));
        scheduleLoop();
        running = true;
        log.info("Game loop started at {} Hz with {} bots", tickRate, botCount);
    }

    @Override
    public void stop() {
        running = false;
        game.shutdownNow();
        network.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    void connect(WebSocketSession session) {
        game.execute(() -> {
            Player player = world.addPlayer(false);
            ClientConnection connection = new ClientConnection(session, player.id(), random);
            connections.put(session.getId(), connection);
            sendNow(connection, json(Map.of(
                    "type", "welcome",
                    "playerId", player.id(),
                    "tickRate", tickRate,
                    "bots", botCount,
                    "constants", GameConstants.asMap())));
        });
    }

    void disconnect(WebSocketSession session) {
        ClientConnection connection = connections.remove(session.getId());
        if (connection != null) {
            game.execute(() -> world.removePlayer(connection.playerId()));
        }
    }

    void onInputs(WebSocketSession session, ByteBuffer payload) {
        ClientConnection connection = connections.get(session.getId());
        if (connection == null) {
            return;
        }
        List<InputCommand> inputs;
        try {
            inputs = Protocol.decodeInputs(payload);
        } catch (RuntimeException e) {
            return;
        }
        connection.uplink().transmit(
                () -> game.execute(() -> world.receiveInputs(connection.playerId(), inputs)), false, network);
    }

    void onControl(WebSocketSession session, String payload) throws IOException {
        ClientConnection connection = connections.get(session.getId());
        if (connection == null) {
            return;
        }
        JsonNode message = mapper.readTree(payload);
        switch (message.path("type").asText()) {
            case "ping" -> {
                TextMessage pong = json(Map.of("type", "pong", "id", message.path("id").asLong(), "t", message.path("t").asDouble()));
                connection.uplink().transmit(() -> send(connection, pong, false), false, network);
            }
            case "net" -> connection.updateConditions(new LinkConditions(
                    message.path("latencyMs").asInt(),
                    message.path("jitterMs").asInt(),
                    message.path("lossPercent").asDouble(),
                    LinkConditions.Mode.valueOf(message.path("mode").asText("udp").toUpperCase())));
            case "server" -> game.execute(() -> updateServer(message));
            case "shooting" -> {
                boolean enabled = message.path("lagCompensation").asBoolean(true);
                game.execute(() -> world.setLagCompensation(connection.playerId(), enabled));
            }
            default -> {
            }
        }
    }

    private void updateServer(JsonNode message) {
        int requestedRate = message.path("tickRate").asInt(tickRate);
        if (ALLOWED_TICK_RATES.contains(requestedRate) && requestedRate != tickRate) {
            tickRate = requestedRate;
            scheduleLoop();
        }
        int requestedBots = Math.clamp(message.path("bots").asInt(botCount), 0, MAX_BOTS);
        if (requestedBots != botCount) {
            botCount = requestedBots;
            world.setBotCount(botCount);
        }
        TextMessage config = json(Map.of("type", "config", "tickRate", tickRate, "bots", botCount));
        connections.values().forEach(c -> send(c, config, true));
    }

    private void scheduleLoop() {
        if (loop != null) {
            loop.cancel(false);
        }
        long period = TimeUnit.SECONDS.toNanos(1) / tickRate;
        loop = game.scheduleAtFixedRate(this::tick, period, period, TimeUnit.NANOSECONDS);
    }

    private void tick() {
        try {
            world.tick(tickRate);
            for (ClientConnection connection : connections.values()) {
                int ack = world.player(connection.playerId()).map(Player::lastProcessedSeq).orElse(0);
                ByteBuffer snapshot = Protocol.encodeSnapshot(world.currentTick(), ack, connection.playerId(), tickRate,
                        world.players(), world.orbs(), world.shotsThisTick());
                send(connection, new BinaryMessage(snapshot), false);
            }
        } catch (RuntimeException e) {
            log.error("Tick failed", e);
        }
    }

    private void send(ClientConnection connection, WebSocketMessage<?> message, boolean reliable) {
        connection.downlink().transmit(() -> deliver(connection, message), reliable, network);
    }

    private void sendNow(ClientConnection connection, WebSocketMessage<?> message) {
        network.execute(() -> deliver(connection, message));
    }

    private static void deliver(ClientConnection connection, WebSocketMessage<?> message) {
        WebSocketSession session = connection.session();
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(message);
        } catch (IOException | IllegalStateException e) {
            log.debug("Dropping message to closed session {}", session.getId());
        }
    }

    private TextMessage json(Map<String, Object> payload) {
        try {
            return new TextMessage(mapper.writeValueAsString(payload));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
