/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.config;

/** The schema this service owns. Flyway and Hibernate are pointed at it in application.yml; hand-written SQL names it. */
public final class ControlPlaneSchema {

    public static final String NAME = "control_plane";

    private ControlPlaneSchema() {}
}
