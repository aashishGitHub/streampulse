/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.adapter.out;

import com.streampulse.compactor.domain.ArchiveObject;
import com.streampulse.compactor.domain.ArchiveStore;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * {@link ArchiveStore} over S3. Reads stream, uploads come from a file, and the quiet-period
 * decision uses S3's {@code LastModified}, not a timestamp parsed from the key.
 */
public final class S3ArchiveStore implements ArchiveStore {

    /** S3's hard limit for one DeleteObjects request. */
    static final int DELETE_BATCH = 1000;

    private final S3Client s3;
    private final String bucket;

    public S3ArchiveStore(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public List<ArchiveObject> list(String prefix) {
        var request =
                ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build();
        List<ArchiveObject> objects = new ArrayList<>();
        s3.listObjectsV2Paginator(request)
                .contents()
                .forEach(o -> objects.add(new ArchiveObject(o.key(), o.lastModified(), o.size())));
        return objects;
    }

    @Override
    public InputStream open(String key) {
        return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public String readText(String key) {
        return s3.getObjectAsBytes(
                        GetObjectRequest.builder().bucket(bucket).key(key).build())
                .asString(StandardCharsets.UTF_8);
    }

    @Override
    public void put(String key, Path file) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromFile(file));
    }

    @Override
    public void putText(String key, String text) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromString(text));
    }

    @Override
    public void delete(Collection<String> keys) {
        List<String> all = List.copyOf(keys);
        for (int from = 0; from < all.size(); from += DELETE_BATCH) {
            List<ObjectIdentifier> batch = all.subList(from, Math.min(from + DELETE_BATCH, all.size())).stream()
                    .map(k -> ObjectIdentifier.builder().key(k).build())
                    .toList();
            DeleteObjectsResponse response = s3.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(Delete.builder().objects(batch).quiet(true).build())
                    .build());
            // DeleteObjects answers 200 even when individual keys fail, exactly like Kinesis PutRecords.
            if (response.hasErrors() && !response.errors().isEmpty()) {
                var first = response.errors().get(0);
                throw new IllegalStateException("S3 could not delete %d of %d keys; first: %s (%s)"
                        .formatted(response.errors().size(), batch.size(), first.key(), first.code()));
            }
        }
    }
}
