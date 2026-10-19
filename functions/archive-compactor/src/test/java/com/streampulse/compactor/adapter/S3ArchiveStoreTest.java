/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.streampulse.compactor.adapter.out.S3ArchiveStore;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.S3Error;

/** Covers what an emulator cannot: moto never reports a per-key delete failure. */
class S3ArchiveStoreTest {

    private final S3Client s3 = mock(S3Client.class);
    private final S3ArchiveStore store = new S3ArchiveStore(s3, "bucket");

    @Test
    @DisplayName("a per-key delete error is raised even though the call itself succeeded")
    void deleteErrorsAreRaised() {
        var failed = S3Error.builder().key("k2").code("AccessDenied").build();
        when(s3.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().errors(failed).build());

        assertThatThrownBy(() -> store.delete(java.util.List.of("k1", "k2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("k2")
                .hasMessageContaining("AccessDenied");
    }

    @Test
    @DisplayName("deletes are sent in batches of at most 1000 keys")
    void deletesAreBatched() {
        when(s3.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().build());
        var keys = IntStream.range(0, 2_500).mapToObj(i -> "k" + i).toList();

        store.delete(keys);

        var captor = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3, times(3)).deleteObjects(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(r -> r.delete().objects().size())
                .containsExactly(1000, 1000, 500);
    }

    @Test
    @DisplayName("deleting nothing sends nothing")
    void emptyDeleteSendsNothing() {
        store.delete(java.util.List.of());

        verify(s3, times(0)).deleteObjects(any(DeleteObjectsRequest.class));
    }
}
