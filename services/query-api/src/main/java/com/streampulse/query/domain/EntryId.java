/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.domain;

/** A Redis Stream entry id, {@code <millis>-<sequence>}. Strings sort wrongly ("9-0" > "10-0"); this does not. */
public record EntryId(long millis, long sequence) implements Comparable<EntryId> {

    public static EntryId parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("entry id is required");
        }
        int dash = raw.indexOf('-');
        if (dash <= 0 || dash == raw.length() - 1) {
            throw new IllegalArgumentException("entry id must look like <millis>-<sequence>: " + raw);
        }
        try {
            long millis = Long.parseLong(raw.substring(0, dash));
            long sequence = Long.parseLong(raw.substring(dash + 1));
            if (millis < 0 || sequence < 0) {
                throw new IllegalArgumentException("entry id parts must not be negative: " + raw);
            }
            return new EntryId(millis, sequence);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("entry id must look like <millis>-<sequence>: " + raw, e);
        }
    }

    @Override
    public int compareTo(EntryId other) {
        int byMillis = Long.compare(millis, other.millis);
        return byMillis != 0 ? byMillis : Long.compare(sequence, other.sequence);
    }

    @Override
    public String toString() {
        return millis + "-" + sequence;
    }
}
