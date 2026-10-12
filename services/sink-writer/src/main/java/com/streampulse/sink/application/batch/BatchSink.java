/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.application.batch;

import java.util.List;

/** Writes a batch durably. Returning normally means every record in it is stored. */
@FunctionalInterface
public interface BatchSink<T> {

    /** Must be idempotent: the writer retries a failed batch. */
    void write(List<T> batch) throws Exception;
}
