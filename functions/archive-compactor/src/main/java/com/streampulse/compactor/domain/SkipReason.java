/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

public enum SkipReason {
    STILL_BEING_WRITTEN,
    NOTHING_TO_COMPACT
}
