/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.application;

import com.streampulse.alert.domain.Alert;

/** Output port: somewhere an alert change must arrive. At-least-once, so delivering twice must be safe. */
public interface AlertChannel {

    String name();

    /** @throws DeliveryException if the alert was not accepted; the caller retries this channel alone */
    void deliver(Alert alert);
}
