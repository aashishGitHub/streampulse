/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * The compactor's only window onto the object store. Every method throws an unchecked exception
 * on failure, so a partition's work fails as a unit and is reported, never half-hidden.
 */
public interface ArchiveStore {

    /** Every object whose key starts with {@code prefix}, across all pages. */
    List<ArchiveObject> list(String prefix);

    /** A stream the caller must close; never the whole object in memory. */
    InputStream open(String key);

    String readText(String key);

    /** Uploads a local file under {@code key}, replacing any object already there. */
    void put(String key, Path file);

    void putText(String key, String text);

    /**
     * Deletes every key, tolerating keys that are already gone. Must throw if the store reports a
     * key it could not delete: the manifest, not this call, is what makes a partial delete safe.
     */
    void delete(Collection<String> keys);
}
