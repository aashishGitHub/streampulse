/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.adapter.streams;

/** Header names on a dead-letter record. Part of the contract in api/asyncapi. */
final class DltHeaders {

    static final String REASON = "dlt-reason";
    static final String ORIGINAL_TOPIC = "dlt-original-topic";
    static final String ORIGINAL_PARTITION = "dlt-original-partition";
    static final String ORIGINAL_OFFSET = "dlt-original-offset";

    private DltHeaders() {}
}
