/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.domain;

import static com.streampulse.processor.domain.ObservationFixtures.error;
import static com.streampulse.processor.domain.ObservationFixtures.event;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WindowAccumulatorTest {

    @Test
    @DisplayName("counts events, errors and distinct sessions")
    void counts() {
        WindowAccumulator acc = WindowAccumulator.empty()
                .add(event("e-1", "s-1"))
                .add(event("e-2", "s-1"))
                .add(error("e-3", "s-2"));

        assertThat(acc.events()).isEqualTo(3);
        assertThat(acc.errors()).isEqualTo(1);
        assertThat(acc.sessions()).containsExactlyInAnyOrder("s-1", "s-2");
    }

    @Test
    @DisplayName("remembers which tenant, title and region it aggregates")
    void remembersItsKey() {
        WindowAccumulator acc = WindowAccumulator.empty().add(event("e-1", "s-1"));

        assertThat(acc.key()).isEqualTo(new AggregateKey("streamflix", "title-1", "us-east-1"));
    }

    @Test
    @DisplayName("add never mutates the accumulator it was called on")
    void addIsImmutable() {
        WindowAccumulator first = WindowAccumulator.empty().add(event("e-1", "s-1"));

        first.add(event("e-2", "s-2"));

        assertThat(first.events()).isEqualTo(1);
        assertThat(first.sessions()).containsExactly("s-1");
    }
}
