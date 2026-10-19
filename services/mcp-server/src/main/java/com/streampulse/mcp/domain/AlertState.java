/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.domain;

public enum AlertState {
    FIRING,
    SUPPRESSED,
    RESOLVED;

    public boolean active() {
        return this != RESOLVED;
    }
}
