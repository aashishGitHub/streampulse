/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

/**
 * "Strictly after this entry, in the ranking as it stood at {@code asOfMillis}." Position in the
 * ordering, not a count of what came before, which is why an insert cannot shift it.
 */
public record Cursor(long asOfMillis, double score, String entryId) {}
