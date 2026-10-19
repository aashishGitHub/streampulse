/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArchiveKeyTest {

    @Test
    @DisplayName("a key splits into tenant, date, hour and name")
    void parsesTheLayout() {
        ArchiveKey key = ArchiveKey.parse("tenant=streamflix/dt=2026-01-15/hour=07/part-0001.ndjson.gz")
                .orElseThrow();

        assertThat(key.partition().tenant().value()).isEqualTo("streamflix");
        assertThat(key.partition().date()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(key.partition().hour()).isEqualTo(7);
        assertThat(key.name()).isEqualTo("part-0001.ndjson.gz");
        assertThat(key.kind()).isEqualTo(ArchiveKey.Kind.RAW);
    }

    @Test
    @DisplayName("a partition renders back to the prefix its objects live under")
    void partitionPrefix() {
        PartitionId partition = ArchiveKey.parse("tenant=streamflix/dt=2026-01-15/hour=07/a.ndjson.gz")
                .orElseThrow()
                .partition();

        assertThat(partition.prefix()).isEqualTo("tenant=streamflix/dt=2026-01-15/hour=07/");
    }

    @Test
    @DisplayName("combined objects and manifests are recognised by name and are never raw")
    void kinds() {
        assertThat(kind("compacted-0123456789abcdef.ndjson.gz")).isEqualTo(ArchiveKey.Kind.COMBINED);
        assertThat(kind("compacted-0123456789abcdef.manifest")).isEqualTo(ArchiveKey.Kind.MANIFEST);
        assertThat(kind("part-1.ndjson.gz")).isEqualTo(ArchiveKey.Kind.RAW);
        assertThat(kind("_SUCCESS")).isEqualTo(ArchiveKey.Kind.OTHER);
        assertThat(kind("notes.txt")).isEqualTo(ArchiveKey.Kind.OTHER);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "readme.txt",
                "tenant=streamflix/dt=2026-01-15/a.ndjson.gz",
                "tenant=streamflix/dt=2026-01-15/hour=24/a.ndjson.gz",
                "tenant=streamflix/dt=2026-13-45/hour=01/a.ndjson.gz",
                "tenant=streamflix/dt=2026-01-15/hour=7/a.ndjson.gz",
                "tenant=StreamFlix/dt=2026-01-15/hour=07/a.ndjson.gz",
                "tenant=has space/dt=2026-01-15/hour=07/a.ndjson.gz",
                "tenant=streamflix/dt=2026-01-15/hour=07/nested/a.ndjson.gz",
                "tenant=streamflix/dt=2026-01-15/hour=07/",
            })
    @DisplayName("a key outside the layout is not an archive key")
    void rejectsForeignKeys(String key) {
        assertThat(ArchiveKey.parse(key)).isEmpty();
    }

    private static ArchiveKey.Kind kind(String name) {
        return ArchiveKey.parse("tenant=streamflix/dt=2026-01-15/hour=07/" + name)
                .orElseThrow()
                .kind();
    }
}
