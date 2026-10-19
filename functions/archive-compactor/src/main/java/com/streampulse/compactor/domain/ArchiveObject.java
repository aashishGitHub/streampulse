/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

import java.time.Instant;

/** What a listing says about one object. {@code lastModified} is the store's clock, not ours. */
public record ArchiveObject(String key, Instant lastModified, long size) {}
