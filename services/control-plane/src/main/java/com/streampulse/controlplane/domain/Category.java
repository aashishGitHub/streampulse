/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

/** A node kind in the service dependency graph; the same vocabulary as the alert-rule contract. */
public enum Category {
    CDN,
    PACKAGER,
    PLAYER,
    INGEST,
    PIPELINE
}
