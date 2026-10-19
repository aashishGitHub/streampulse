/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

import java.util.List;

public record CompactionReport(List<PartitionOutcome> outcomes) {

    public CompactionReport {
        outcomes = List.copyOf(outcomes);
    }

    public long count(PartitionOutcome.Status status) {
        return outcomes.stream().filter(o -> o.status() == status).count();
    }
}
