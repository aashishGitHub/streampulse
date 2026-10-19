/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import java.util.UUID;

/** Port so tests can predict the outbox row id. */
@FunctionalInterface
public interface ChangeIdGenerator {
    UUID next();
}
