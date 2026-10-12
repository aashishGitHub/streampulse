/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AggregateKeyTest {

    @Test
    @DisplayName("the string form is deterministic, so the same events always group together")
    void deterministic() {
        Observation a =
                new Observation("e-1", "streamflix", "s-1", "title-1", "us-east-1", false, ObservationFixtures.NOW);
        Observation b =
                new Observation("e-2", "streamflix", "s-2", "title-1", "us-east-1", true, ObservationFixtures.NOW);

        assertThat(AggregateKey.of(a).asString()).isEqualTo(AggregateKey.of(b).asString());
        assertThat(AggregateKey.of(a).asString()).isEqualTo("streamflix:title-1:us-east-1");
    }

    @Test
    @DisplayName("a missing title is its own group, not merged into a real title")
    void missingTitle() {
        Observation noTitle =
                new Observation("e-1", "streamflix", "s-1", null, "us-east-1", false, ObservationFixtures.NOW);

        assertThat(AggregateKey.of(noTitle).titleId()).isNull();
        assertThat(AggregateKey.of(noTitle).asString()).isEqualTo("streamflix::us-east-1");
    }

    @Test
    @DisplayName("the tenant is normalised the way the platform normalises it")
    void tenantNormalised() {
        Observation o = new Observation("e-1", " StreamFlix ", "s-1", "t", "r", false, ObservationFixtures.NOW);

        assertThat(AggregateKey.of(o).tenantId()).isEqualTo("streamflix");
    }
}
