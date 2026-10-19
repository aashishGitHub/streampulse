/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code score = 0.5^(age / halfLife) * (1 + ln n)}, n being the entries on the same title.
 * Every candidate is from someone the reader follows, so n is "how many of the people you follow
 * are watching this" without any extra lookup. Not personalised by the reader's history.
 */
public final class Ranker {

    private final double halfLifeMillis;

    public Ranker(Duration halfLife) {
        if (halfLife.isZero() || halfLife.isNegative()) {
            throw new IllegalArgumentException("halfLife must be positive, got " + halfLife);
        }
        this.halfLifeMillis = halfLife.toMillis();
    }

    public List<RankedEntry> rank(Collection<FeedEntry> candidates, Instant asOf) {
        Map<TitleId, Integer> perTitle = new HashMap<>();
        for (FeedEntry entry : candidates) {
            perTitle.merge(entry.title(), 1, Integer::sum);
        }
        return candidates.stream()
                .map(e -> new RankedEntry(e, score(e, asOf, perTitle.get(e.title()))))
                .sorted(RankedEntry.ORDER)
                .toList();
    }

    private double score(FeedEntry entry, Instant asOf, int sameTitle) {
        long age = Math.max(0, asOf.toEpochMilli() - entry.score());
        // StrictMath: a cursor stores this score and the next page may be served by another JVM.
        // Math.pow and Math.log may differ by an ulp between the interpreter and the JIT; StrictMath does not.
        double recency = StrictMath.pow(0.5, age / halfLifeMillis);
        double affinity = 1 + StrictMath.log(sameTitle);
        return recency * affinity;
    }
}
