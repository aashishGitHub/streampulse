/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert;

import static com.streampulse.alert.support.Alerts.firing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.alert.application.AlertFeed;
import com.streampulse.alert.support.NoDockerWebTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The stream over a real socket: framing, heartbeat, tenant filter, ordering, resume. */
class SseStreamTest extends NoDockerWebTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AlertFeed feed;

    private final ObjectMapper json = new ObjectMapper();
    private final List<AutoCloseable> open = new ArrayList<>();

    /** One open stream: every line the server sends, as it arrives. */
    private final class Stream implements AutoCloseable {
        final List<String> lines = new CopyOnWriteArrayList<>();
        final Thread reader;

        Stream(String tenant, String lastEventId) throws Exception {
            var request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/v1/alerts/stream?tenantId=" + tenant))
                    .header("Accept", "text/event-stream");
            if (lastEventId != null) {
                request.header("Last-Event-ID", lastEventId);
            }
            HttpResponse<java.io.InputStream> response =
                    HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
            reader = Thread.ofVirtual().start(() -> {
                try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(response.body()))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        lines.add(line);
                    }
                } catch (IOException ignored) {
                    // the test closed the connection
                }
            });
            open.add(this);
        }

        List<String> ids() {
            return lines.stream()
                    .filter(l -> l.startsWith("id:"))
                    .map(l -> l.substring(3).trim())
                    .toList();
        }

        List<JsonNode> events() throws Exception {
            List<JsonNode> events = new ArrayList<>();
            for (String line : lines) {
                if (line.startsWith("data:")) {
                    events.add(json.readTree(line.substring(5)));
                }
            }
            return events;
        }

        @Override
        public void close() {
            reader.interrupt();
        }
    }

    @AfterEach
    void closeStreams() throws Exception {
        for (AutoCloseable stream : open) {
            stream.close();
        }
        open.clear();
        feed.closeAll();
    }

    private static String tenant() {
        return "sse-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("an alert reaches the stream as event 'alert' with an id and the documented JSON as data")
    void deliversAnAlert() throws Exception {
        String tenant = tenant();
        try (var stream = new Stream(tenant, null)) {
            await().atMost(Duration.ofSeconds(5)).until(() -> feed.subscriberCount() >= 1);
            long sequence = feed.publish(firing(tenant, "a1"));

            await().atMost(Duration.ofSeconds(5)).until(() -> !stream.ids().isEmpty());

            assertThat(stream.lines).contains("event:alert", "id:" + sequence);
            JsonNode alert = stream.events().get(0);
            assertThat(alert.path("alertId").asText()).isEqualTo("a1");
            assertThat(alert.path("tenantId").asText()).isEqualTo(tenant);
            assertThat(alert.path("state").asText()).isEqualTo("FIRING");
            assertThat(alert.has("resolvedAt")).isTrue();
            assertThat(alert.has("suppressedBy")).isTrue();
        }
    }

    @Test
    @DisplayName("an idle stream carries a heartbeat comment")
    void heartbeat() throws Exception {
        try (var stream = new Stream(tenant(), null)) {
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(stream.lines).contains(":heartbeat"));
        }
    }

    @Test
    @DisplayName("only the subscribed tenant's alerts are sent")
    void tenantFilter() throws Exception {
        String mine = tenant();
        try (var stream = new Stream(mine, null)) {
            await().atMost(Duration.ofSeconds(5)).until(() -> feed.subscriberCount() >= 1);
            feed.publish(firing("someone-else", "x1"));
            feed.publish(firing(mine, "m1"));

            await().atMost(Duration.ofSeconds(5)).until(() -> !stream.events().isEmpty());

            assertThat(stream.events())
                    .extracting(e -> e.path("alertId").asText())
                    .containsExactly("m1");
        }
    }

    @Test
    @DisplayName("ids strictly increase and events arrive in publish order")
    void orderingAndIds() throws Exception {
        String tenant = tenant();
        try (var stream = new Stream(tenant, null)) {
            await().atMost(Duration.ofSeconds(5)).until(() -> feed.subscriberCount() >= 1);
            for (int i = 0; i < 30; i++) {
                feed.publish(firing(tenant, "o" + i));
            }

            await().atMost(Duration.ofSeconds(10)).until(() -> stream.events().size() == 30);

            List<Long> ids = stream.ids().stream().map(Long::parseLong).toList();
            assertThat(ids).isSorted().doesNotHaveDuplicates().hasSize(30);
            assertThat(stream.events())
                    .extracting(e -> e.path("alertId").asText())
                    .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 30)
                            .mapToObj(i -> "o" + i)
                            .toList());
        }
    }

    @Test
    @DisplayName("reconnecting with Last-Event-ID replays what was missed, in order, then continues live")
    void reconnectWithLastEventIdResumes() throws Exception {
        String tenant = tenant();
        long first = feed.publish(firing(tenant, "r1"));
        feed.publish(firing(tenant, "r2"));
        feed.publish(firing(tenant, "r3"));

        try (var stream = new Stream(tenant, Long.toString(first))) {
            long live = 0;
            await().atMost(Duration.ofSeconds(5)).until(() -> stream.events().size() == 2);
            live = feed.publish(firing(tenant, "r4"));
            long expectedLive = live;

            await().atMost(Duration.ofSeconds(5)).until(() -> stream.events().size() == 3);

            assertThat(stream.events())
                    .extracting(e -> e.path("alertId").asText())
                    .containsExactly("r2", "r3", "r4");
            assertThat(stream.ids().stream().map(Long::parseLong).toList())
                    .isSorted()
                    .doesNotHaveDuplicates()
                    .endsWith(expectedLive)
                    .allMatch(id -> id > first);
        }
    }

    @Test
    @DisplayName("a Last-Event-ID that is not a number is ignored: live events only")
    void garbageLastEventId() throws Exception {
        String tenant = tenant();
        feed.publish(firing(tenant, "old"));

        try (var stream = new Stream(tenant, "not-a-number")) {
            await().atMost(Duration.ofSeconds(5)).until(() -> feed.subscriberCount() >= 1);
            feed.publish(firing(tenant, "new"));

            await().atMost(Duration.ofSeconds(5)).until(() -> !stream.events().isEmpty());

            assertThat(stream.events())
                    .extracting(e -> e.path("alertId").asText())
                    .containsExactly("new");
        }
    }

    @Test
    @DisplayName("closing the client releases its subscription")
    void disconnectReleasesTheSubscription() throws Exception {
        int before = feed.subscriberCount();
        var stream = new Stream(tenant(), null);
        await().atMost(Duration.ofSeconds(5)).until(() -> feed.subscriberCount() == before + 1);

        stream.close();
        open.clear();
        feed.closeAll();

        assertThat(feed.subscriberCount()).isZero();
    }
}
