/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EntryIdTest {

    @Test
    @DisplayName("ids compare numerically, not as strings")
    void comparesNumerically() {
        assertThat(EntryId.parse("9-0")).isLessThan(EntryId.parse("10-0"));
        assertThat(EntryId.parse("10-1")).isGreaterThan(EntryId.parse("10-0"));
        assertThat(EntryId.parse("10-0")).isEqualByComparingTo(EntryId.parse("10-0"));
    }

    @Test
    @DisplayName("the string form round-trips")
    void roundTrips() {
        assertThat(EntryId.parse("1700000000000-3").toString()).isEqualTo("1700000000000-3");
    }

    @Test
    @DisplayName("malformed ids are rejected")
    void rejectsMalformed() {
        assertThatIllegalArgumentException().isThrownBy(() -> EntryId.parse("abc"));
        assertThatIllegalArgumentException().isThrownBy(() -> EntryId.parse("1-"));
        assertThatIllegalArgumentException().isThrownBy(() -> EntryId.parse("-1-2"));
        assertThatIllegalArgumentException().isThrownBy(() -> EntryId.parse(""));
    }
}
