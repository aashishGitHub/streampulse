/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BatchBuilderTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static BatchBuilder builder(int sessions, double lateFraction) {
        return new BatchBuilder(CLOCK, new Random(7), UUID::randomUUID, sessions, lateFraction);
    }

    @Test
    void producesParseableJsonWithAnEventsArrayOfTheRequestedSize() throws Exception {
        JsonNode root = JSON.readTree(builder(10, 0).buildJson(25));

        assertThat(root.fieldNames()).toIterable().containsExactly("events");
        assertThat(root.get("events").isArray()).isTrue();
        assertThat(root.get("events")).hasSize(25);
    }

    @Test
    void everyEventCarriesTheFieldNamesTheIngestApiExpects() throws Exception {
        JsonNode event =
                JSON.readTree(builder(10, 0).buildJson(1)).get("events").get(0);

        Iterator<String> names = event.fieldNames();
        Set<String> actual = new HashSet<>();
        names.forEachRemaining(actual::add);
        assertThat(actual)
                .containsExactlyInAnyOrder(
                        "eventId", "sessionId", "titleId", "deviceType", "region", "occurredAt", "type");
        assertThat(UUID.fromString(event.get("eventId").asText())).isNotNull();
        assertThat(Instant.parse(event.get("occurredAt").asText())).isNotNull();
    }

    @Test
    void deviceTypeAndEventTypeAreValuesFromTheOpenApiEnums() throws Exception {
        Set<String> deviceTypes = Set.of(
                "DEVICE_UNKNOWN", "WEB", "MOBILE_IOS", "MOBILE_ANDROID", "SMART_TV", "SET_TOP_BOX", "GAME_CONSOLE");
        Set<String> eventTypes = Set.of(
                "EVENT_UNKNOWN",
                "SESSION_START",
                "SESSION_END",
                "HEARTBEAT",
                "BUFFERING_START",
                "BUFFERING_END",
                "BITRATE_CHANGE",
                "ERROR");

        for (JsonNode event : JSON.readTree(builder(10, 0).buildJson(500)).get("events")) {
            assertThat(deviceTypes).contains(event.get("deviceType").asText());
            assertThat(eventTypes).contains(event.get("type").asText());
        }
    }

    @Test
    void eventIdsAreFreshAndUnique() {
        List<LoadEvent> events = builder(10, 0).events(500);

        assertThat(events.stream().map(LoadEvent::eventId).distinct()).hasSize(500);
    }

    @Test
    void sessionIdsAreDrawnFromAPoolOfTheRequestedSize() {
        List<LoadEvent> events = builder(5, 0).events(500);

        Set<String> sessions = new HashSet<>();
        events.forEach(e -> sessions.add(e.sessionId()));
        assertThat(sessions).hasSize(5);
    }

    @Test
    void sessionIdsAreStableAcrossBatches() {
        BatchBuilder b = builder(3, 0);

        Set<String> all = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            b.events(50).forEach(e -> all.add(e.sessionId()));
        }

        assertThat(all).hasSize(3);
    }

    @Test
    void withNoLateFractionEveryEventIsStampedNow() {
        assertThat(builder(10, 0).events(200))
                .allSatisfy(e -> assertThat(Instant.parse(e.occurredAt())).isEqualTo(NOW));
    }

    @Test
    void withLateFractionOneEveryEventIsOneToFourSecondsInThePast() {
        assertThat(builder(10, 1).events(200)).allSatisfy(e -> {
            Duration age = Duration.between(Instant.parse(e.occurredAt()), NOW);
            assertThat(age).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(4));
        });
    }

    @Test
    void lateFractionIsApproximatelyHonoured() {
        BatchBuilder builder = builder(10, 0.3);
        List<LoadEvent> events = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            events.addAll(builder.events(500));
        }

        long late = events.stream()
                .filter(e -> Instant.parse(e.occurredAt()).isBefore(NOW))
                .count();
        assertThat(late).isBetween(2_700L, 3_300L);
    }

    @Test
    void rejectsSizesOutsideTheApiLimits() {
        assertThatThrownBy(() -> builder(10, 0).events(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder(10, 0).events(501)).isInstanceOf(IllegalArgumentException.class);
    }
}
