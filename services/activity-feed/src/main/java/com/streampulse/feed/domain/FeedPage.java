/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

import java.util.List;
import java.util.Optional;

public record FeedPage(List<RankedEntry> items, Optional<Cursor> next) {}
