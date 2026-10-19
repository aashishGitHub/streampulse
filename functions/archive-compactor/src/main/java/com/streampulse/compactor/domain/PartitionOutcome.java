/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

public record PartitionOutcome(
        PartitionId partition, Status status, long records, int objectsMerged, String targetKey, String detail) {

    public enum Status {
        COMPACTED,
        CLEANED_UP,
        SKIPPED_STILL_BEING_WRITTEN,
        SKIPPED_NOTHING_TO_COMPACT,
        FAILED
    }
}
