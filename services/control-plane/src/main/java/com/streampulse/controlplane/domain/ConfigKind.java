/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

/** The kinds of entity whose changes are announced. Names are part of the outbox's wire vocabulary. */
public enum ConfigKind {
    TENANT("tenant"),
    TITLE("title"),
    ALERT_RULE("alert-rule"),
    SERVICE_NODE("service-node");

    private final String keyPrefix;

    ConfigKind(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    /** {@code tenant/acme}, {@code title/acme/t-1}: unique across kinds because the prefix is. */
    public String key(String... parts) {
        return keyPrefix + "/" + String.join("/", parts);
    }
}
