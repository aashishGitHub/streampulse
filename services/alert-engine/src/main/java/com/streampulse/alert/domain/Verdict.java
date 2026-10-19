/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain;

/** What a rule says about a series right now. NO_DECISION means "not enough data": state stays as it is. */
public enum Verdict {
    BREACHING,
    CLEAR,
    NO_DECISION
}
