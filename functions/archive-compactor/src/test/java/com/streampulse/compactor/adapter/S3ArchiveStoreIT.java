/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.streampulse.compactor.adapter.out.S3ArchiveStore;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The adapter against moto. Proves the SDK calls behave as the adapter assumes (pagination, batch
 * size, streaming); proves nothing about IAM, latency or real S3 consistency (ADR-0003).
 */
@Testcontainers
class S3ArchiveStoreIT {

    private static final String BUCKET = "store-it";

    @Container
    static final GenericContainer<?> MOTO =
            new GenericContainer<>(DockerImageName.parse("motoserver/moto:5.2.2")).withExposedPorts(5000);

    private static S3Client s3;
    private static S3ArchiveStore store;

    @BeforeAll
    static void bucket() {
        s3 = S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create("http://" + MOTO.getHost() + ":" + MOTO.getMappedPort(5000)))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        s3.createBucket(b -> b.bucket(BUCKET));
        store = new S3ArchiveStore(s3, BUCKET);
    }

    @Test
    @DisplayName("an uploaded file streams back byte for byte, and text round-trips")
    void putOpenAndReadText(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("f.bin");
        Files.write(file, new byte[] {1, 2, 3, 4});

        store.put("rt/file.bin", file);
        store.putText("rt/note.manifest", "records=1\n");

        try (var in = store.open("rt/file.bin")) {
            assertThat(in.readAllBytes()).containsExactly(1, 2, 3, 4);
        }
        assertThat(store.readText("rt/note.manifest")).isEqualTo("records=1\n");
    }

    @Test
    @DisplayName("a listing reports the store's own last-modified time and size")
    void listReportsLastModifiedAndSize() {
        s3.putObject(b -> b.bucket(BUCKET).key("lm/a"), RequestBody.fromString("12345"));

        var listed = store.list("lm/");

        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).size()).isEqualTo(5);
        assertThat(listed.get(0).lastModified()).isNotNull();
    }

    @Test
    @DisplayName("a listing follows pagination past one page of 1000 keys")
    void listsMoreObjectsThanOnePage() {
        List<String> keys = IntStream.range(0, 1_005)
                .mapToObj(i -> "page/k%04d".formatted(i))
                .toList();
        keys.forEach(k -> s3.putObject(b -> b.bucket(BUCKET).key(k), RequestBody.fromString("x")));

        assertThat(store.list("page/")).hasSize(1_005);
    }

    @Test
    @DisplayName("deleting 1005 keys takes more than one batch and removes them all")
    void deletesMoreObjectsThanOneBatch() {
        List<String> keys = IntStream.range(0, 1_005)
                .mapToObj(i -> "del/k%04d".formatted(i))
                .toList();
        keys.forEach(k -> s3.putObject(b -> b.bucket(BUCKET).key(k), RequestBody.fromString("x")));

        store.delete(keys);

        assertThat(store.list("del/")).isEmpty();
    }

    @Test
    @DisplayName("deleting a key that is already gone is not an error")
    void deletingAMissingKeyIsIdempotent() {
        assertThatCode(() -> store.delete(List.of("never/existed"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("text is stored as UTF-8")
    void utf8() {
        store.putText("u/t", "é");

        assertThat(store.readText("u/t").getBytes(StandardCharsets.UTF_8)).hasSize(2);
    }
}
