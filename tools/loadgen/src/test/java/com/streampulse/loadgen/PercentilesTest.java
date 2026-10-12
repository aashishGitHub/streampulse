/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PercentilesTest {

    @Test
    void emptyInputHasNoPercentile() {
        assertThat(Percentiles.of(new long[0], 50)).isEmpty();
    }

    @Test
    void singleElementIsEveryPercentile() {
        long[] one = {42};

        assertThat(Percentiles.of(one, 0)).hasValue(42);
        assertThat(Percentiles.of(one, 50)).hasValue(42);
        assertThat(Percentiles.of(one, 99)).hasValue(42);
        assertThat(Percentiles.of(one, 100)).hasValue(42);
    }

    @Test
    void usesNearestRank() {
        long[] ten = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

        assertThat(Percentiles.of(ten, 50)).hasValue(5);
        assertThat(Percentiles.of(ten, 90)).hasValue(9);
        assertThat(Percentiles.of(ten, 95)).hasValue(10);
        assertThat(Percentiles.of(ten, 99)).hasValue(10);
        assertThat(Percentiles.of(ten, 100)).hasValue(10);
        assertThat(Percentiles.of(ten, 10)).hasValue(1);
    }

    @Test
    void zeroPercentileIsTheMinimum() {
        assertThat(Percentiles.of(new long[] {3, 7, 9}, 0)).hasValue(3);
    }

    @Test
    void tiesReturnTheTiedValue() {
        long[] ties = {5, 5, 5, 5, 5, 5, 5, 5, 5, 100};

        assertThat(Percentiles.of(ties, 50)).hasValue(5);
        assertThat(Percentiles.of(ties, 90)).hasValue(5);
        assertThat(Percentiles.of(ties, 99)).hasValue(100);
    }

    @Test
    void p99OfOneHundredIsTheNinetyNinthValue() {
        long[] hundred = new long[100];
        for (int i = 0; i < 100; i++) {
            hundred[i] = i + 1;
        }

        assertThat(Percentiles.of(hundred, 99)).hasValue(99);
        assertThat(Percentiles.of(hundred, 50)).hasValue(50);
    }

    @Test
    void rejectsPercentileOutOfRange() {
        assertThatThrownBy(() -> Percentiles.of(new long[] {1}, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Percentiles.of(new long[] {1}, 100.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Percentiles.of(new long[] {1}, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
