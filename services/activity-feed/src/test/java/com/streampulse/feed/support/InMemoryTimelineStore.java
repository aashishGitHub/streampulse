/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.support;

import com.streampulse.feed.domain.FeedEntry;
import com.streampulse.feed.domain.TimelineStore;
import com.streampulse.feed.domain.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keyed by member, like a sorted set: writing the same entry twice leaves one. */
public final class InMemoryTimelineStore implements TimelineStore {

    private static final Comparator<FeedEntry> NEWEST_FIRST =
            Comparator.comparingLong(FeedEntry::score).reversed().thenComparing(FeedEntry::member);

    private final Map<UserId, Map<String, FeedEntry>> timelines = new HashMap<>();
    private final Map<UserId, Map<String, FeedEntry>> outboxes = new HashMap<>();
    private final List<Integer> pushSizes = new ArrayList<>();
    private final List<List<UserId>> outboxLookups = new ArrayList<>();

    @Override
    public void pushToTimelines(Collection<UserId> followers, FeedEntry entry) {
        pushSizes.add(followers.size());
        for (UserId follower : followers) {
            timelines.computeIfAbsent(follower, k -> new LinkedHashMap<>()).put(entry.member(), entry);
        }
    }

    @Override
    public void appendToOutbox(FeedEntry entry) {
        outboxes.computeIfAbsent(entry.author(), k -> new LinkedHashMap<>()).put(entry.member(), entry);
    }

    @Override
    public List<FeedEntry> timeline(UserId user, Instant asOf, int limit) {
        return newest(timelines.get(user), asOf, limit);
    }

    @Override
    public List<FeedEntry> outboxes(Collection<UserId> authors, Instant asOf, int perAuthorLimit) {
        outboxLookups.add(List.copyOf(authors));
        var all = new ArrayList<FeedEntry>();
        for (UserId author : authors) {
            all.addAll(newest(outboxes.get(author), asOf, perAuthorLimit));
        }
        return all;
    }

    private static List<FeedEntry> newest(Map<String, FeedEntry> entries, Instant asOf, int limit) {
        if (entries == null) {
            return List.of();
        }
        return entries.values().stream()
                .filter(e -> !e.occurredAt().isAfter(asOf))
                .sorted(NEWEST_FIRST)
                .limit(limit)
                .toList();
    }

    public void putInTimeline(UserId user, FeedEntry entry) {
        pushToTimelines(List.of(user), entry);
        pushSizes.clear();
    }

    public List<FeedEntry> timelineOf(UserId user) {
        return List.copyOf(timelines.getOrDefault(user, Map.of()).values());
    }

    public List<FeedEntry> outboxOf(UserId author) {
        return List.copyOf(outboxes.getOrDefault(author, Map.of()).values());
    }

    public boolean hasTimeline(UserId user) {
        return timelines.containsKey(user);
    }

    public List<Integer> pushSizes() {
        return List.copyOf(pushSizes);
    }

    public List<List<UserId>> outboxLookups() {
        return List.copyOf(outboxLookups);
    }
}
