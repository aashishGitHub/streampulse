/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.domain;

/**
 * The outcome of ingesting a batch.
 *
 * @param published how many events were newly published
 * @param duplicates how many were recognised as already seen and skipped
 * @param idempotencyDegraded true when the dedup store was unavailable and we published
 *     without checking (REQ-IG-14). Surfaced so the caller can record a metric — a silent
 *     degradation is one nobody fixes.
 */
public record IngestResult(int published, int duplicates, boolean idempotencyDegraded) {

    public static IngestResult of(int published, int duplicates) {
        return new IngestResult(published, duplicates, false);
    }

    public static IngestResult degraded(int published) {
        return new IngestResult(published, 0, true);
    }

    public int total() {
        return published + duplicates;
    }
}
