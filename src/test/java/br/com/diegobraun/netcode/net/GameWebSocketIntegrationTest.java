package br.com.diegobraun.netcode.net;

import br.com.diegobraun.netcode.game.GameConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"game.tick-rate=60", "game.bots=0"})
class GameWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    private final ObjectMapper mapper = new ObjectMapper();
    private final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
    private final BlockingQueue<ByteBuffer> snapshots = new LinkedBlockingQueue<>();
    private WebSocket socket;

    @AfterEach
    void close() {
        if (socket != null) {
            socket.abort();
        }
    }

    @Test
    void authoritativeServerAppliesInputsOnceAndAcknowledgesThem() throws Exception {
        connect();
        JsonNode welcome = mapper.readTree(texts.poll(5, TimeUnit.SECONDS));
        assertThat(welcome.path("type").asText()).isEqualTo("welcome");
        int myId = welcome.path("playerId").asInt();
        assertThat(welcome.path("constants").path("inputRate").asInt()).isEqualTo(GameConstants.INPUT_RATE);

        SnapshotDecoder first = nextSnapshot(s -> true);
        float startX = first.player(myId).x();

        ByteBuffer batch = inputBatch(1, 12, GameConstants.RIGHT);
        socket.sendBinary(batch.duplicate(), true).join();
        SnapshotDecoder moved = nextSnapshot(s -> s.ackSeq() == 12);

        double expected = Math.min(startX + 12 * GameConstants.SPEED / GameConstants.INPUT_RATE,
                GameConstants.ARENA_WIDTH - GameConstants.PLAYER_RADIUS);
        assertThat((double) moved.player(myId).x()).isCloseTo(expected, within(0.01));

        socket.sendBinary(batch.duplicate(), true).join();
        Thread.sleep(100);
        snapshots.clear();
        SnapshotDecoder afterResend = nextSnapshot(s -> true);
        assertThat(afterResend.ackSeq()).isEqualTo(12);
        assertThat(afterResend.player(myId).x()).isEqualTo(moved.player(myId).x());
    }

    @Test
    void simulatedLatencyDelaysPong() throws Exception {
        connect();
        texts.poll(5, TimeUnit.SECONDS);
        socket.sendText("{\"type\":\"net\",\"latencyMs\":100,\"jitterMs\":0,\"lossPercent\":0,\"mode\":\"udp\"}", true).join();

        long start = System.nanoTime();
        socket.sendText("{\"type\":\"ping\",\"id\":1,\"t\":0}", true).join();
        JsonNode pong = mapper.readTree(texts.poll(5, TimeUnit.SECONDS));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(pong.path("type").asText()).isEqualTo("pong");
        assertThat(elapsedMs).isGreaterThanOrEqualTo(200);
    }

    private void connect() {
        socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + port + "/game"), new Listener())
                .join();
    }

    private SnapshotDecoder nextSnapshot(Predicate<SnapshotDecoder> condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            ByteBuffer buffer = snapshots.poll(100, TimeUnit.MILLISECONDS);
            if (buffer != null) {
                SnapshotDecoder snapshot = SnapshotDecoder.decode(buffer);
                if (condition.test(snapshot)) {
                    return snapshot;
                }
            }
        }
        throw new AssertionError("No matching snapshot received");
    }

    private static ByteBuffer inputBatch(int fromSeq, int toSeq, int buttons) {
        int count = toSeq - fromSeq + 1;
        ByteBuffer buffer = ByteBuffer.allocate(2 + count * 5).put(Protocol.INPUTS).put((byte) count);
        for (int seq = fromSeq; seq <= toSeq; seq++) {
            buffer.putInt(seq).put((byte) buttons);
        }
        return buffer.flip();
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                texts.add(text.toString());
                text.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            binary.writeBytes(bytes);
            if (last) {
                snapshots.add(ByteBuffer.wrap(binary.toByteArray()));
                binary.reset();
            }
            webSocket.request(1);
            return null;
        }
    }
}
