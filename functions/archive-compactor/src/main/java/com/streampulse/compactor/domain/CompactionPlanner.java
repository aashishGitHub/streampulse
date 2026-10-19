/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Pure: decides, from a listing and the clock reading it is given, what to do to each partition. */
public final class CompactionPlanner {

    private final CompactionPolicy policy;

    public CompactionPlanner(CompactionPolicy policy) {
        this.policy = policy;
    }

    /**
     * @param committedSources raw keys that a manifest says are already folded into a combined object
     * @param now one clock reading for the whole run, so every partition is judged at the same instant
     */
    public List<PartitionPlan> plan(Collection<ArchiveObject> listing, Set<String> committedSources, Instant now) {
        Map<String, PartitionId> partitions = new TreeMap<>();
        Map<String, List<ArchiveObject>> rawByPartition = new HashMap<>();

        for (ArchiveObject object : listing) {
            var key = ArchiveKey.parse(object.key());
            if (key.isEmpty() || key.get().kind() == ArchiveKey.Kind.OTHER) {
                continue;
            }
            PartitionId partition = key.get().partition();
            partitions.put(partition.prefix(), partition);
            if (key.get().kind() == ArchiveKey.Kind.RAW) {
                rawByPartition
                        .computeIfAbsent(partition.prefix(), p -> new ArrayList<>())
                        .add(object);
            }
        }

        List<PartitionPlan> plans = new ArrayList<>();
        for (var entry : partitions.entrySet()) {
            List<ArchiveObject> raw = rawByPartition.getOrDefault(entry.getKey(), List.of());
            plans.add(planOne(entry.getValue(), raw, committedSources, now));
        }
        return plans;
    }

    private PartitionPlan planOne(PartitionId partition, List<ArchiveObject> raw, Set<String> committed, Instant now) {
        if (raw.isEmpty()) {
            return new PartitionPlan(partition, List.of(), List.of(), SkipReason.NOTHING_TO_COMPACT);
        }

        // Only raw objects count: a combined object is written by us, and its fresh timestamp must
        // not make the partition look busy.
        Instant newest = raw.stream()
                .map(ArchiveObject::lastModified)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        if (newest.plus(policy.quietPeriod()).isAfter(now)) {
            return new PartitionPlan(partition, List.of(), List.of(), SkipReason.STILL_BEING_WRITTEN);
        }

        List<String> leftovers = raw.stream()
                .map(ArchiveObject::key)
                .filter(committed::contains)
                .sorted()
                .toList();
        List<ArchiveObject> fresh = raw.stream()
                .filter(o -> !committed.contains(o.key()))
                .sorted(Comparator.comparing(ArchiveObject::key))
                .toList();

        SkipReason skipped = fresh.size() >= policy.minInputObjects() ? null : SkipReason.NOTHING_TO_COMPACT;
        return new PartitionPlan(partition, leftovers, fresh, skipped);
    }
}
