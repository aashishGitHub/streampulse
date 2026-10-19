/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.compactor.adapter.in.CompactArchiveFunction;
import com.streampulse.compactor.adapter.in.CompactionRequest;
import com.streampulse.compactor.domain.ArchiveObject;
import com.streampulse.compactor.domain.ArchiveStore;
import com.streampulse.compactor.domain.CompactionPlanner;
import com.streampulse.compactor.domain.CompactionPolicy;
import com.streampulse.compactor.domain.Compactor;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompactArchiveFunctionTest {

    @TempDir
    Path workDir;

    /** An empty archive that remembers the prefixes it was asked to list. */
    private static final class EmptyStore implements ArchiveStore {
        final List<String> listed = new ArrayList<>();

        @Override
        public List<ArchiveObject> list(String prefix) {
            listed.add(prefix);
            return List.of();
        }

        @Override
        public InputStream open(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String readText(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void put(String key, Path file) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putText(String key, String text) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Collection<String> keys) {
            throw new UnsupportedOperationException();
        }
    }

    private final EmptyStore store = new EmptyStore();

    private CompactArchiveFunction function() {
        var clock = Clock.fixed(Instant.parse("2026-01-15T14:00:00Z"), ZoneOffset.UTC);
        var planner = new CompactionPlanner(new CompactionPolicy(Duration.ofMinutes(15), 2));
        return new CompactArchiveFunction(new Compactor(store, planner, clock, workDir));
    }

    @Test
    @DisplayName("no request fields means every tenant")
    void noFieldsMeansEverything() {
        function().apply(new CompactionRequest(null, null));
        function().apply(null);

        assertThat(store.listed).containsExactly("", "");
    }

    @Test
    @DisplayName("a tenant and a date narrow the listing")
    void tenantAndDateNarrowTheListing() {
        function().apply(new CompactionRequest("streamflix", "2026-01-15"));

        assertThat(store.listed).containsExactly("tenant=streamflix/dt=2026-01-15/");
    }

    @Test
    @DisplayName("a date without a tenant is rejected, not widened")
    void dateWithoutTenantIsRejected() {
        assertThatThrownBy(() -> function().apply(new CompactionRequest(null, "2026-01-15")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.listed).isEmpty();
    }

    @Test
    @DisplayName("an invalid tenant is rejected before anything is listed")
    void invalidTenantIsRejected() {
        assertThatThrownBy(() -> function().apply(new CompactionRequest("Not A Tenant!", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.listed).isEmpty();
    }
}
