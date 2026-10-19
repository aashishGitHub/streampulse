/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CompactionPlannerTest {

    private static final Instant NOW = Instant.parse("2026-01-15T14:00:00Z");
    private static final Duration QUIET = Duration.ofMinutes(15);
    private static final String H12 = "tenant=streamflix/dt=2026-01-15/hour=12/";
    private static final String H13 = "tenant=streamflix/dt=2026-01-15/hour=13/";

    private final CompactionPlanner planner = new CompactionPlanner(new CompactionPolicy(QUIET, 2));

    private static ArchiveObject raw(String prefix, String name, Instant modified) {
        return new ArchiveObject(prefix + name + ".ndjson.gz", modified, 100);
    }

    private static Instant ago(Duration d) {
        return NOW.minus(d);
    }

    private PartitionPlan onlyPlan(List<ArchiveObject> objects, Set<String> committed) {
        List<PartitionPlan> plans = planner.plan(objects, committed, NOW);
        assertThat(plans).hasSize(1);
        return plans.get(0);
    }

    @Test
    @DisplayName("objects are grouped by tenant, date and hour")
    void groupsByTenantDateAndHour() {
        var objects = List.of(
                raw(H12, "a", ago(Duration.ofHours(2))),
                raw(H12, "b", ago(Duration.ofHours(2))),
                raw(H13, "c", ago(Duration.ofHours(1))),
                raw(H13, "d", ago(Duration.ofHours(1))),
                raw("tenant=sportsnow/dt=2026-01-15/hour=12/", "e", ago(Duration.ofHours(2))),
                raw("tenant=sportsnow/dt=2026-01-15/hour=12/", "f", ago(Duration.ofHours(2))));

        List<PartitionPlan> plans = planner.plan(objects, Set.of(), NOW);

        assertThat(plans)
                .extracting(p -> p.partition().prefix())
                .containsExactlyInAnyOrder(H12, H13, "tenant=sportsnow/dt=2026-01-15/hour=12/");
        assertThat(plans).allSatisfy(p -> assertThat(p.inputs()).hasSize(2));
    }

    @Test
    @DisplayName("inputs are ordered by key, whatever order the listing arrived in")
    void inputsAreSortedByKey() {
        var objects = new ArrayList<>(List.of(
                raw(H12, "c", ago(Duration.ofHours(2))),
                raw(H12, "a", ago(Duration.ofHours(2))),
                raw(H12, "b", ago(Duration.ofHours(2)))));
        Collections.shuffle(objects);

        assertThat(onlyPlan(objects, Set.of()).inputs())
                .extracting(ArchiveObject::key)
                .containsExactly(H12 + "a.ndjson.gz", H12 + "b.ndjson.gz", H12 + "c.ndjson.gz");
    }

    @Test
    @DisplayName("a partition whose newest object is exactly one quiet period old is compacted")
    void quietPeriodBoundaryIsInclusive() {
        var atBoundary = List.of(raw(H12, "a", ago(QUIET)), raw(H12, "b", ago(Duration.ofHours(1))));
        var justInside = List.of(raw(H12, "a", ago(QUIET).plusMillis(1)), raw(H12, "b", ago(Duration.ofHours(1))));

        assertThat(onlyPlan(atBoundary, Set.of()).skipped()).isNull();
        assertThat(onlyPlan(justInside, Set.of()).skipped()).isEqualTo(SkipReason.STILL_BEING_WRITTEN);
    }

    @Test
    @DisplayName("one recent object holds back the whole partition")
    void oneRecentObjectHoldsBackThePartition() {
        var objects = List.of(
                raw(H12, "a", ago(Duration.ofHours(3))),
                raw(H12, "b", ago(Duration.ofHours(3))),
                raw(H12, "c", ago(Duration.ofMinutes(1))));

        PartitionPlan plan = onlyPlan(objects, Set.of());

        assertThat(plan.skipped()).isEqualTo(SkipReason.STILL_BEING_WRITTEN);
        assertThat(plan.targetKey()).isNull();
    }

    @Test
    @DisplayName("a partition that holds only a combined object and its manifest has nothing to do")
    void alreadyCompactedPartitionHasNothingToDo() {
        var objects = List.of(
                new ArchiveObject(H12 + "compacted-0123456789abcdef.ndjson.gz", ago(Duration.ofHours(5)), 900),
                new ArchiveObject(H12 + "compacted-0123456789abcdef.manifest", ago(Duration.ofHours(5)), 90));

        PartitionPlan plan = onlyPlan(objects, Set.of());

        assertThat(plan.skipped()).isEqualTo(SkipReason.NOTHING_TO_COMPACT);
        assertThat(plan.inputs()).isEmpty();
        assertThat(plan.leftoverKeys()).isEmpty();
    }

    @Test
    @DisplayName("a combined object is never an input, even beside new raw objects")
    void combinedObjectsAreNeverInputs() {
        var objects = List.of(
                new ArchiveObject(H12 + "compacted-0123456789abcdef.ndjson.gz", ago(Duration.ofHours(5)), 900),
                raw(H12, "late-1", ago(Duration.ofHours(2))),
                raw(H12, "late-2", ago(Duration.ofHours(2))));

        assertThat(onlyPlan(objects, Set.of()).inputs())
                .extracting(ArchiveObject::key)
                .containsExactly(H12 + "late-1.ndjson.gz", H12 + "late-2.ndjson.gz");
    }

    @Test
    @DisplayName("a combined object written a moment ago does not make the partition look busy")
    void ourOwnFreshOutputDoesNotHoldBackTheQuietCheck() {
        var objects = List.of(
                new ArchiveObject(H12 + "compacted-0123456789abcdef.ndjson.gz", ago(Duration.ofSeconds(5)), 900),
                new ArchiveObject(H12 + "compacted-0123456789abcdef.manifest", ago(Duration.ofSeconds(5)), 90),
                raw(H12, "late-1", ago(Duration.ofHours(2))),
                raw(H12, "late-2", ago(Duration.ofHours(2))));

        assertThat(onlyPlan(objects, Set.of()).skipped()).isNull();
    }

    @Test
    @DisplayName("fewer inputs than the minimum are left alone")
    void singleObjectIsLeftAlone() {
        PartitionPlan plan = onlyPlan(List.of(raw(H12, "a", ago(Duration.ofHours(2)))), Set.of());

        assertThat(plan.skipped()).isEqualTo(SkipReason.NOTHING_TO_COMPACT);
    }

    @Test
    @DisplayName("the combined key depends only on the set of inputs")
    void targetKeyIsDeterministic() {
        var abc = List.of(
                raw(H12, "a", ago(Duration.ofHours(2))),
                raw(H12, "b", ago(Duration.ofHours(2))),
                raw(H12, "c", ago(Duration.ofHours(2))));
        var reversed = new ArrayList<>(abc);
        Collections.reverse(reversed);
        var ab = abc.subList(0, 2);

        String first = onlyPlan(abc, Set.of()).targetKey();

        assertThat(first).startsWith(H12 + "compacted-").endsWith(".ndjson.gz");
        assertThat(onlyPlan(reversed, Set.of()).targetKey()).isEqualTo(first);
        assertThat(onlyPlan(ab, Set.of()).targetKey()).isNotEqualTo(first);
        assertThat(onlyPlan(abc, Set.of()).manifestKey()).isEqualTo(first.replace(".ndjson.gz", ".manifest"));
    }

    @Test
    @DisplayName("an input that a committed manifest already names is a leftover to delete, not data to merge")
    void committedSourcesBecomeLeftovers() {
        var objects = List.of(
                raw(H12, "a", ago(Duration.ofHours(4))),
                raw(H12, "b", ago(Duration.ofHours(4))),
                raw(H12, "c", ago(Duration.ofHours(4))));
        Set<String> committed = Set.of(H12 + "a.ndjson.gz", H12 + "b.ndjson.gz");

        PartitionPlan plan = onlyPlan(objects, committed);

        assertThat(plan.leftoverKeys()).containsExactlyInAnyOrder(H12 + "a.ndjson.gz", H12 + "b.ndjson.gz");
        assertThat(plan.inputs()).extracting(ArchiveObject::key).containsExactly(H12 + "c.ndjson.gz");
        assertThat(plan.skipped()).isEqualTo(SkipReason.NOTHING_TO_COMPACT);
    }

    @Test
    @DisplayName("leftovers are still deleted when there are enough new inputs to compact as well")
    void leftoversAndFreshInputsTogether() {
        var objects = List.of(
                raw(H12, "a", ago(Duration.ofHours(4))),
                raw(H12, "x", ago(Duration.ofHours(2))),
                raw(H12, "y", ago(Duration.ofHours(2))));

        PartitionPlan plan = onlyPlan(objects, Set.of(H12 + "a.ndjson.gz"));

        assertThat(plan.skipped()).isNull();
        assertThat(plan.leftoverKeys()).containsExactly(H12 + "a.ndjson.gz");
        assertThat(plan.inputs())
                .extracting(ArchiveObject::key)
                .containsExactly(H12 + "x.ndjson.gz", H12 + "y.ndjson.gz");
    }

    @Test
    @DisplayName("keys outside the layout, and non-archive names, are ignored")
    void foreignKeysAreIgnored() {
        var objects = List.of(
                new ArchiveObject("readme.txt", ago(Duration.ofHours(9)), 1),
                new ArchiveObject("tenant=BAD TENANT/dt=2026-01-15/hour=12/a.ndjson.gz", ago(Duration.ofHours(9)), 1),
                new ArchiveObject(H12 + "_SUCCESS", ago(Duration.ofHours(9)), 0));

        assertThat(planner.plan(objects, Set.of(), NOW)).isEmpty();
    }
}
