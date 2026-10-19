# Phase 12 — Activity feed ("friends are watching"): Requirements

**Status:** in progress · **Depends on:** Phase 1 (contracts) · **Delivers:** a follow graph, a
watch signal, a hybrid fan-out, and a ranked, cursor-paginated feed · **Interview question:** SD2

## Why

SD2 asks for a Twitter-like platform: follows, a feed, a prioritized dashboard. The domain here
is small on purpose: a user starts watching a title, and the people who follow them see
"X is watching T". The interesting part is not the data, it is the shape of the work:

- Building every follower's feed when an author posts costs one write per follower. Fine for an
  author with 200 followers, ruinous for one with 20 million.
- Building the feed when it is read costs a lookup per followed author on every read. Fine for
  a few authors, ruinous for 2,000 followed accounts.
- Neither is right for everyone, so the strategy must depend on the author, and the read path
  must combine both.

**An honest gap.** Nothing in the telemetry pipeline carries a user id (events carry only a
session id), so nothing emits the watch signal yet. This phase defines that signal as its own
contract and consumes it; wiring a real producer to it is not built.

## Requirements (EARS; no technology named)

### The watch signal

- **REQ-AF-01** WHEN a user reports that they started watching a title THE SYSTEM SHALL accept
  the report once it is durably recorded for later processing, and SHALL NOT wait for any
  follower's view to change. `[SD2]`
- **REQ-AF-02** WHEN a report names an identifier that is not valid THE SYSTEM SHALL reject it
  with a machine-readable problem description and record nothing. `[J1]`
- **REQ-AF-03** WHEN a report cannot be durably recorded THE SYSTEM SHALL tell the caller it
  was not accepted, never acknowledge it. `[M2]`
- **REQ-AF-04** WHEN the same report, identified by its event id, is processed more than once
  THE SYSTEM SHALL leave every follower's view exactly as if it had been processed once. `[M2]`
- **REQ-AF-05** WHEN a recorded report cannot be decoded or carries an invalid value THE SYSTEM
  SHALL preserve its original bytes and the reason in a dead-letter destination and SHALL
  continue with the next report. `[M2]`
- **REQ-AF-06** THE SYSTEM SHALL report a watch signal as consumed only after every view update
  it causes is durable, so a crash can only cause reprocessing, never loss. `[M2]`

### The follow graph

- **REQ-AF-10** WHEN a user follows another THE SYSTEM SHALL record it once, however often the
  request is repeated; WHEN a user unfollows THE SYSTEM SHALL remove it once. A user SHALL NOT
  follow themselves. `[SD2]`
- **REQ-AF-11** THE SYSTEM SHALL keep each author's follower count equal to the number of
  distinct followers, including when the same follow is requested concurrently. `[SD2]`
- **REQ-AF-12** WHEN asked for an author's follower count THE SYSTEM SHALL return it together
  with the fan-out strategy that count selects. `[SD2]`

### Hybrid fan-out

- **REQ-AF-20** WHEN a watch is processed for an author whose follower count is below the
  celebrity threshold THE SYSTEM SHALL write the entry into every follower's precomputed view
  (fan-out on write). `[SD2]`
- **REQ-AF-21** WHEN the author's follower count is at or above the threshold THE SYSTEM SHALL
  NOT write into any follower's view (fan-out on read). `[SD2]`
- **REQ-AF-22** THE SYSTEM SHALL record every processed watch in the author's own recent
  activity, whichever strategy applies, so the read path can find it if the author is later
  classified differently. `[SD2]`
- **REQ-AF-23** THE SYSTEM SHALL bound the number of entries in a precomputed view and in an
  author's recent activity, and SHALL let an unused view expire. `[SD2]`
- **REQ-AF-24** WHEN writing into many followers' views THE SYSTEM SHALL group the writes
  rather than make one round trip per follower. `[SD2]`

### The read path

- **REQ-AF-30** WHEN a user requests their feed THE SYSTEM SHALL return entries drawn from
  their precomputed view AND from the recent activity of each high-follower author they
  follow. `[SD2]`
- **REQ-AF-31** THE SYSTEM SHALL list an entry at most once, and SHALL list at most one entry
  for the same author and the same title (the newest). `[SD2]`
- **REQ-AF-32** WHEN a user has unfollowed an author THE SYSTEM SHALL NOT return that author's
  entries, even if they are still present in the precomputed view. `[SD2]`
- **REQ-AF-33** THE SYSTEM SHALL order entries by a score that decays with the entry's age and
  grows with the number of followed users watching the same title. The formula is in
  design.md. `[SD2]`
- **REQ-AF-34** WHEN the pages of a feed are fetched in sequence while new entries arrive
  THE SYSTEM SHALL return every entry that existed when the first page was served exactly
  once, and none twice. Entries that arrive later appear when the feed is fetched afresh. `[SD2]`
- **REQ-AF-35** WHEN a page position token is malformed, has been altered, or was issued to a
  different user THE SYSTEM SHALL reject the request with a machine-readable problem
  description. `[J1] [SD2]`
- **REQ-AF-36** THE SYSTEM SHALL bound the page size and reject a size outside the bound. `[J1]`

### Operations

- **REQ-AF-40** THE SYSTEM SHALL report how many view writes fan-out performed, how many
  recent-activity merges the read path performed, and how long reading a feed takes. `[M4]`
- **REQ-AF-41** THE SYSTEM SHALL expose liveness that does not depend on any store. `[M4]`

## Non-functional

- Celebrity threshold: **10,000 followers** is a **target**, chosen so that the largest
  fan-out on write is on the order of ten thousand view writes; it is configurable and not
  measured. Timeline length 200, recent-activity length 1,000, half-life 30 minutes: targets.
- Delivery is at-least-once; *at-least-once delivery + idempotent write = effectively-once*.
- Concurrent removals (an unfollow, a trimmed entry) while a user pages are NOT covered by
  REQ-AF-34: a position token is stateless, so a removal can still shift a later page.

## Out of scope (listed so nobody assumes it exists)

Authentication, a user directory (any well-formed id may follow or be followed), per-user rate
limits, engagement counters (likes), skipping inactive users when fanning out, a real producer
of the watch signal, multi-node Redis, rebuilding a lost timeline from the follow graph.
