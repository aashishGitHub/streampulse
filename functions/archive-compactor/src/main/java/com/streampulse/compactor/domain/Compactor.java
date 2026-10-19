/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

import com.streampulse.compactor.domain.PartitionOutcome.Status;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * Compacts quiet partitions, one at a time, in an order that survives a crash at any step.
 *
 * <pre>
 *   merge inputs into a temp file  →  put combined  →  re-read and count  →  put manifest  →  delete inputs
 *                                                       must equal the sum     (commit)
 * </pre>
 *
 * Nothing is deleted before the re-read count has matched, and the manifest is what lets a later
 * run finish a half-done delete instead of merging the survivors a second time.
 */
public final class Compactor {

    private static final int BUFFER = 64 * 1024;

    private final ArchiveStore store;
    private final CompactionPlanner planner;
    private final Clock clock;
    private final Path workDir;
    private final NdjsonMerger merger = new NdjsonMerger();

    public Compactor(ArchiveStore store, CompactionPlanner planner, Clock clock, Path workDir) {
        this.store = store;
        this.planner = planner;
        this.clock = clock;
        this.workDir = workDir;
    }

    public CompactionReport compact(Scope scope) {
        var now = clock.instant();
        List<ArchiveObject> listing = store.list(scope.prefix());

        List<PartitionOutcome> outcomes = new ArrayList<>();
        Set<String> committed = new HashSet<>();
        Set<PartitionId> unreadable = readManifests(listing, committed, outcomes);

        List<ArchiveObject> plannable = listing.stream()
                .filter(o -> ArchiveKey.parse(o.key())
                        .map(k -> !unreadable.contains(k.partition()))
                        .orElse(true))
                .toList();

        for (PartitionPlan plan : planner.plan(plannable, committed, now)) {
            outcomes.add(execute(plan));
        }
        return new CompactionReport(outcomes);
    }

    /** Reads only the manifests of partitions that still hold raw objects: the rest have nothing to decide. */
    private Set<PartitionId> readManifests(
            List<ArchiveObject> listing, Set<String> committed, List<PartitionOutcome> outcomes) {
        Set<PartitionId> withRaw = new HashSet<>();
        Map<PartitionId, List<String>> manifestKeys = new HashMap<>();
        for (ArchiveObject object : listing) {
            ArchiveKey.parse(object.key()).ifPresent(key -> {
                if (key.kind() == ArchiveKey.Kind.RAW) {
                    withRaw.add(key.partition());
                } else if (key.kind() == ArchiveKey.Kind.MANIFEST) {
                    manifestKeys
                            .computeIfAbsent(key.partition(), p -> new ArrayList<>())
                            .add(object.key());
                }
            });
        }

        Set<PartitionId> unreadable = new HashSet<>();
        for (PartitionId partition : withRaw) {
            for (String manifestKey : manifestKeys.getOrDefault(partition, List.of())) {
                try {
                    committed.addAll(CompactionManifest.parse(store.readText(manifestKey))
                            .sources());
                } catch (RuntimeException e) {
                    unreadable.add(partition);
                    outcomes.add(failed(partition, "unreadable manifest " + manifestKey, e));
                    break;
                }
            }
        }
        return unreadable;
    }

    private PartitionOutcome execute(PartitionPlan plan) {
        PartitionId partition = plan.partition();
        if (plan.skipped() == SkipReason.STILL_BEING_WRITTEN) {
            return new PartitionOutcome(partition, Status.SKIPPED_STILL_BEING_WRITTEN, 0, 0, null, "");
        }
        try {
            if (!plan.leftoverKeys().isEmpty()) {
                store.delete(plan.leftoverKeys());
            }
            if (plan.skipped() != null) {
                Status status = plan.leftoverKeys().isEmpty() ? Status.SKIPPED_NOTHING_TO_COMPACT : Status.CLEANED_UP;
                return new PartitionOutcome(partition, status, 0, 0, null, "");
            }
            return compactInputs(plan);
        } catch (RuntimeException e) {
            return failed(partition, "compaction stopped", e);
        }
    }

    private PartitionOutcome compactInputs(PartitionPlan plan) {
        Path temp = null;
        try {
            temp = Files.createTempFile(workDir, "compact-", ".ndjson.gz");
            long expected = mergeInto(temp, plan.inputs());

            String target = plan.targetKey();
            store.put(target, temp);

            long actual = countOf(target);
            if (actual != expected) {
                store.delete(List.of(target));
                return new PartitionOutcome(
                        plan.partition(),
                        Status.FAILED,
                        0,
                        0,
                        null,
                        "verification failed: expected %d records, read back %d".formatted(expected, actual));
            }

            List<String> inputKeys =
                    plan.inputs().stream().map(ArchiveObject::key).toList();
            store.putText(plan.manifestKey(), new CompactionManifest(expected, inputKeys).render());
            store.delete(inputKeys);
            return new PartitionOutcome(plan.partition(), Status.COMPACTED, expected, inputKeys.size(), target, "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(temp);
        }
    }

    private long mergeInto(Path temp, List<ArchiveObject> inputs) throws IOException {
        long total = 0;
        try (OutputStream out =
                new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(temp), BUFFER), BUFFER)) {
            for (ArchiveObject input : inputs) {
                try (InputStream in = store.open(input.key())) {
                    total += merger.append(in, out);
                } catch (IOException | RuntimeException e) {
                    throw new IOException("could not read " + input.key() + ": " + e.getMessage(), e);
                }
            }
        }
        return total;
    }

    private long countOf(String key) throws IOException {
        try (InputStream in = store.open(key)) {
            return merger.count(in);
        }
    }

    private static PartitionOutcome failed(PartitionId partition, String what, Exception cause) {
        return new PartitionOutcome(
                partition,
                Status.FAILED,
                0,
                0,
                null,
                what + ": " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Left in the function's scratch space; it is ephemeral, and failing the run over it would hide the real
            // outcome.
        }
    }
}
