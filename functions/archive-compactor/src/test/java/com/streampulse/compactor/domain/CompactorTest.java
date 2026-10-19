/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.compactor.domain.PartitionOutcome.Status;
import com.streampulse.platform.domain.TenantId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompactorTest {

    private static final Instant NOW = Instant.parse("2026-01-15T14:00:00Z");
    private static final Instant OLD = NOW.minus(Duration.ofHours(2));
    private static final String H12 = "tenant=streamflix/dt=2026-01-15/hour=12/";
    private static final String H13 = "tenant=streamflix/dt=2026-01-15/hour=13/";
    private static final String OTHER = "tenant=sportsnow/dt=2026-01-15/hour=12/";
    private static final String COMBINED = ".*/compacted-[0-9a-f]{16}\\.ndjson\\.gz";
    private static final String MANIFEST = ".*/compacted-[0-9a-f]{16}\\.manifest";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryArchiveStore store = new InMemoryArchiveStore(clock);

    @TempDir
    Path workDir;

    private Compactor compactor;

    @BeforeEach
    void setUp() {
        compactor = new Compactor(
                store, new CompactionPlanner(new CompactionPolicy(Duration.ofMinutes(15), 2)), clock, workDir);
    }

    private void threeObjectsIn(String prefix) {
        store.addRaw(prefix + "a.ndjson.gz", OLD, "{\"n\":1}", "{\"n\":2}");
        store.addRaw(prefix + "b.ndjson.gz", OLD, "{\"n\":3}", "{\"n\":4}", "{\"n\":5}");
        store.addRaw(prefix + "c.ndjson.gz", OLD, "{\"n\":6}");
    }

    private PartitionOutcome outcomeFor(CompactionReport report, String prefix) {
        return report.outcomes().stream()
                .filter(o -> o.partition().prefix().equals(prefix))
                .findFirst()
                .orElseThrow();
    }

    private List<String> rawKeys() {
        return store.keysMatching(".*/(a|b|c|x|y)\\.ndjson\\.gz|.*/late-\\d\\.ndjson\\.gz");
    }

    @Test
    @DisplayName("every record of an hour ends up in one object, in key order")
    void mergesEveryRecordOfAnHourIntoOneObject() {
        threeObjectsIn(H12);

        CompactionReport report = compactor.compact(Scope.all());

        PartitionOutcome outcome = outcomeFor(report, H12);
        assertThat(outcome.status()).isEqualTo(Status.COMPACTED);
        assertThat(outcome.records()).isEqualTo(6);
        assertThat(store.keysMatching(COMBINED)).hasSize(1);
        assertThat(store.contentOf(store.keysMatching(COMBINED).get(0)))
                .isEqualTo("{\"n\":1}\n{\"n\":2}\n{\"n\":3}\n{\"n\":4}\n{\"n\":5}\n{\"n\":6}\n");
        assertThat(rawKeys()).isEmpty();
        assertThat(store.textOf(store.keysMatching(MANIFEST).get(0)))
                .startsWith("records=6\n")
                .contains(H12 + "a.ndjson.gz", H12 + "b.ndjson.gz", H12 + "c.ndjson.gz");
    }

    @Test
    @DisplayName("each tenant and hour gets its own object")
    void oneObjectPerTenantAndHour() {
        threeObjectsIn(H12);
        threeObjectsIn(H13);
        threeObjectsIn(OTHER);

        CompactionReport report = compactor.compact(Scope.all());

        assertThat(report.count(Status.COMPACTED)).isEqualTo(3);
        assertThat(store.keysMatching(COMBINED))
                .allSatisfy(k -> assertThat(store.recordsIn(k)).isEqualTo(6))
                .hasSize(3);
    }

    @Test
    @DisplayName("originals are deleted only after the combined object is written, re-read and committed")
    void originalsAreDeletedOnlyAfterTheCombinedObjectIsReadBack() {
        threeObjectsIn(H12);

        compactor.compact(Scope.all());

        int put = store.indexOfCall("put:" + H12 + "compacted-");
        int readBack = store.indexOfCall("open:" + H12 + "compacted-");
        int commit = store.indexOfCall("putText:" + H12 + "compacted-");
        int delete = store.indexOfCall("delete:");
        assertThat(put).isGreaterThanOrEqualTo(0);
        assertThat(put).isLessThan(readBack);
        assertThat(readBack).isLessThan(commit);
        assertThat(commit).isLessThan(delete);
    }

    @Test
    @DisplayName("a combined object that re-reads short keeps every original and is removed")
    void corruptedWriteKeepsInputsAndRemovesTheBadTarget() {
        threeObjectsIn(H12);
        store.corruptNextDataPut();

        CompactionReport report = compactor.compact(Scope.all());

        PartitionOutcome outcome = outcomeFor(report, H12);
        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.detail()).contains("5").contains("6");
        assertThat(rawKeys()).containsExactly(H12 + "a.ndjson.gz", H12 + "b.ndjson.gz", H12 + "c.ndjson.gz");
        assertThat(store.keysMatching(COMBINED)).isEmpty();
        assertThat(store.keysMatching(MANIFEST)).isEmpty();
        assertThat(store.calls).noneMatch(c -> c.startsWith("delete:") && c.contains(H12 + "a.ndjson.gz"));
    }

    @Test
    @DisplayName("a partition still being written is not read, written or deleted")
    void partitionStillBeingWrittenIsNeverTouched() {
        threeObjectsIn(H12);
        store.addRaw(H12 + "d.ndjson.gz", NOW.minus(Duration.ofMinutes(1)), "{\"n\":7}");
        var before = store.snapshot();

        CompactionReport report = compactor.compact(Scope.all());

        assertThat(outcomeFor(report, H12).status()).isEqualTo(Status.SKIPPED_STILL_BEING_WRITTEN);
        assertThat(store.calls).noneMatch(c -> c.startsWith("open:") || c.startsWith("put") || c.startsWith("delete:"));
        assertThat(store.sameAs(before)).isTrue();
    }

    @Test
    @DisplayName("running again on compacted data makes no change at all")
    void rerunOnCompactedDataChangesNothing() {
        threeObjectsIn(H12);
        compactor.compact(Scope.all());
        var after = store.snapshot();
        store.calls.clear();

        CompactionReport second = compactor.compact(Scope.all());

        assertThat(outcomeFor(second, H12).status()).isEqualTo(Status.SKIPPED_NOTHING_TO_COMPACT);
        assertThat(store.mutatingCalls()).isZero();
        assertThat(store.sameAs(after)).isTrue();
    }

    @Test
    @DisplayName("a failed delete after the write is finished by the next run, with nothing duplicated")
    void crashAfterWriteIsRecoveredWithoutDuplicates() {
        threeObjectsIn(H12);
        store.deleteFailsAfter(0);

        CompactionReport first = compactor.compact(Scope.all());
        CompactionReport second = compactor.compact(Scope.all());

        assertThat(outcomeFor(first, H12).status()).isEqualTo(Status.FAILED);
        assertThat(outcomeFor(second, H12).status()).isEqualTo(Status.CLEANED_UP);
        assertThat(rawKeys()).isEmpty();
        assertThat(store.keysMatching(COMBINED)).hasSize(1);
        assertThat(store.recordsIn(store.keysMatching(COMBINED).get(0))).isEqualTo(6);
    }

    @Test
    @DisplayName("a crash before the commit is recovered by rewriting the same object")
    void crashBeforeTheManifestIsRecoveredByRewritingTheSameObject() {
        threeObjectsIn(H12);
        store.failNextPutText();

        CompactionReport first = compactor.compact(Scope.all());
        String firstTarget = store.keysMatching(COMBINED).get(0);
        CompactionReport second = compactor.compact(Scope.all());

        assertThat(outcomeFor(first, H12).status()).isEqualTo(Status.FAILED);
        assertThat(store.keysMatching(MANIFEST)).hasSize(1);
        assertThat(outcomeFor(second, H12).status()).isEqualTo(Status.COMPACTED);
        assertThat(store.keysMatching(COMBINED)).containsExactly(firstTarget);
        assertThat(store.recordsIn(firstTarget)).isEqualTo(6);
        assertThat(rawKeys()).isEmpty();
    }

    @Test
    @DisplayName("a crash part-way through deleting is finished from the manifest, not by merging the survivors again")
    void crashDuringDeleteIsRecoveredWithoutDuplicates() {
        threeObjectsIn(H12);
        store.deleteFailsAfter(2);

        compactor.compact(Scope.all());
        assertThat(rawKeys()).hasSize(1);
        CompactionReport second = compactor.compact(Scope.all());

        assertThat(outcomeFor(second, H12).status()).isEqualTo(Status.CLEANED_UP);
        assertThat(rawKeys()).isEmpty();
        assertThat(store.keysMatching(COMBINED)).hasSize(1);
        assertThat(store.recordsIn(store.keysMatching(COMBINED).get(0))).isEqualTo(6);
    }

    @Test
    @DisplayName("objects that arrive after a compaction become a second combined object (known limit)")
    void lateArrivalsBecomeASecondCombinedObject() {
        threeObjectsIn(H12);
        compactor.compact(Scope.all());
        store.addRaw(H12 + "late-1.ndjson.gz", OLD, "{\"n\":7}");
        store.addRaw(H12 + "late-2.ndjson.gz", OLD, "{\"n\":8}", "{\"n\":9}");

        CompactionReport second = compactor.compact(Scope.all());

        assertThat(outcomeFor(second, H12).status()).isEqualTo(Status.COMPACTED);
        assertThat(store.keysMatching(COMBINED)).hasSize(2);
        assertThat(store.keysMatching(COMBINED).stream()
                        .mapToLong(store::recordsIn)
                        .sum())
                .isEqualTo(9);
    }

    @Test
    @DisplayName("one failing partition does not stop the others")
    void oneFailingPartitionDoesNotStopTheOthers() {
        threeObjectsIn(H12);
        threeObjectsIn(H13);
        store.failOpenFor(key -> key.startsWith(H12));

        CompactionReport report = compactor.compact(Scope.all());

        assertThat(outcomeFor(report, H12).status()).isEqualTo(Status.FAILED);
        assertThat(outcomeFor(report, H13).status()).isEqualTo(Status.COMPACTED);
        assertThat(store.keysMatching(H12 + ".*" + "compacted-.*")).isEmpty();
        assertThat(store.keysMatching(H12 + "[abc]\\.ndjson\\.gz")).hasSize(3);
    }

    @Test
    @DisplayName("an input that is not gzip fails only its own partition")
    void anInputThatIsNotGzipFailsItsPartitionOnly() {
        threeObjectsIn(H12);
        threeObjectsIn(H13);
        store.addObject(H12 + "x.ndjson.gz", "not gzip at all".getBytes(), OLD);

        CompactionReport report = compactor.compact(Scope.all());

        assertThat(outcomeFor(report, H12).status()).isEqualTo(Status.FAILED);
        assertThat(outcomeFor(report, H13).status()).isEqualTo(Status.COMPACTED);
        assertThat(store.keys()).contains(H12 + "a.ndjson.gz", H12 + "x.ndjson.gz");
    }

    @Test
    @DisplayName("an unreadable manifest fails only its own partition")
    void corruptManifestFailsOnlyItsPartition() {
        threeObjectsIn(H12);
        threeObjectsIn(H13);
        store.addText(H12 + "compacted-0123456789abcdef.manifest", "this is not a manifest", OLD);

        CompactionReport report = compactor.compact(Scope.all());

        assertThat(outcomeFor(report, H12).status()).isEqualTo(Status.FAILED);
        assertThat(outcomeFor(report, H13).status()).isEqualTo(Status.COMPACTED);
        assertThat(store.keysMatching(H12 + "[abc]\\.ndjson\\.gz")).hasSize(3);
    }

    @Test
    @DisplayName("a tenant scope lists only that tenant and leaves the others alone")
    void scopeRestrictsTheListing() {
        threeObjectsIn(H12);
        threeObjectsIn(OTHER);

        CompactionReport report = compactor.compact(Scope.of(new TenantId("streamflix"), null));

        assertThat(report.outcomes()).hasSize(1);
        assertThat(store.calls).contains("list:tenant=streamflix/");
        assertThat(store.keysMatching(OTHER + "[abc]\\.ndjson\\.gz")).hasSize(3);
    }

    @Test
    @DisplayName("a tenant and date scope narrows the listing to that day")
    void dateScope() {
        threeObjectsIn(H12);
        threeObjectsIn("tenant=streamflix/dt=2026-01-14/hour=12/");

        CompactionReport report = compactor.compact(Scope.of(new TenantId("streamflix"), LocalDate.of(2026, 1, 15)));

        assertThat(report.outcomes()).hasSize(1);
        assertThat(store.calls).contains("list:tenant=streamflix/dt=2026-01-15/");
    }

    @Test
    @DisplayName("the temporary file is gone after a success and after a failed verification")
    void temporaryFileIsRemovedAfterAFailedVerification() throws IOException {
        threeObjectsIn(H12);
        store.corruptNextDataPut();
        compactor.compact(Scope.all());
        assertThat(filesIn(workDir)).isEmpty();

        compactor.compact(Scope.all());

        assertThat(store.keysMatching(COMBINED)).hasSize(1);
        assertThat(filesIn(workDir)).isEmpty();
    }

    private static List<Path> filesIn(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.toList();
        }
    }
}
