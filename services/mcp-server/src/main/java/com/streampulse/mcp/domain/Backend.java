/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.domain;

public enum Backend {
    QUERY_API("query-api"),
    ALERT_ENGINE("alert-engine");

    private final String label;

    Backend(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
