/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.domain;

/**
 * A consumed record: the decoded window, plus the original key and bytes so a rejected record
 * can be dead-lettered untouched. The array is not copied; nothing mutates it.
 *
 * @param window null only for a record that could not be decoded
 */
public record AggregateRecord(String key, byte[] value, MetricWindow window) {

    public static AggregateRecord undecodable(String key, byte[] value) {
        return new AggregateRecord(key, value, null);
    }
}
