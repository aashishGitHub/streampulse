/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.domain;

/**
 * Why an input record went to the dead-letter topic.
 *
 * <p>An enum, not free text, because the name is written to a header and used as a metric tag:
 * it must be stable and low-cardinality. Free text would explode the metric's series count.
 */
public enum RejectionReason {
    UNDECODABLE,
    INVALID_EVENT_ID,
    INVALID_TENANT_ID,
    BLANK_SESSION_ID,
    EVENT_TIME_IN_FUTURE
}
