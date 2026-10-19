/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.application;

/** A channel could not take an alert. The aggregate that caused it is not acknowledged and is retried. */
public class DeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
