/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * What to do to one partition.
 *
 * @param leftoverKeys raw objects a committed manifest already names: safe to delete, never to merge again
 * @param inputs raw objects to merge, sorted by key
 * @param skipped null when {@code inputs} should be compacted
 */
public record PartitionPlan(
        PartitionId partition, List<String> leftoverKeys, List<ArchiveObject> inputs, SkipReason skipped) {

    public PartitionPlan {
        leftoverKeys = List.copyOf(leftoverKeys);
        inputs = List.copyOf(inputs);
    }

    /** Null when nothing will be written. */
    public String targetKey() {
        return skipped == null
                ? partition.prefix() + ArchiveKey.COMBINED_PREFIX + fingerprint() + ArchiveKey.DATA_SUFFIX
                : null;
    }

    public String manifestKey() {
        return skipped == null
                ? partition.prefix() + ArchiveKey.COMBINED_PREFIX + fingerprint() + ArchiveKey.MANIFEST_SUFFIX
                : null;
    }

    // WHY the key is a hash of the input set: a re-run after a crash sees the same inputs, so it
    // computes the same key and overwrites identical bytes instead of leaving a second copy.
    private String fingerprint() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (ArchiveObject input : inputs) {
                digest.update(input.key().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
