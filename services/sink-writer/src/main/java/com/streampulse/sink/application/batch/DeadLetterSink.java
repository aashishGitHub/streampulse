/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.application.batch;

/** Where a record goes when the sink rejects it deterministically. */
@FunctionalInterface
public interface DeadLetterSink<T> {

    /** Returns only once the record is safely parked; a failure here leaves the record unhandled. */
    void deadLetter(T record, String reason) throws Exception;
}
