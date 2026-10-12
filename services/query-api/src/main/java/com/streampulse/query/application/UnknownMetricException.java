/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.application;

/** The metric is not one the system produces (maps to a 404). */
public class UnknownMetricException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnknownMetricException(String metric) {
        super("unknown metric: " + metric);
    }
}
