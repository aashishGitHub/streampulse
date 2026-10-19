# Phase 10 — AWS simulation, Terraform and the archive compactor: Requirements

**Status:** in progress · **Depends on:** Phases 2-5 (the stream, the aggregate shape, the services whose permissions are derived) · **Delivers:**
infrastructure definitions that are validated and applied against emulators only, and a small
function that compacts raw-event archive objects safely

## Why

The target runs on managed cloud services, and an infrastructure definition nobody has ever
planned or applied is a guess. Local emulators cannot prove permissions, networking or
performance (ADR-0003), but they can prove that the definitions are well-formed, that the
resources they describe can be created through the real API surface, and that the application
code's assumptions about names and shapes hold. Separately, a raw archive made of many tiny
objects is slow and expensive to read, so something has to compact it without ever losing a record.

**Honest framing, repeated on purpose.** Nothing in this phase has touched a real cloud
account. Every claim below is about API-level behaviour against an emulator. No service in the
repo writes raw events to the archive yet; the compactor is tested against objects its own
tests generate.

## Requirements (EARS; no technology named)

### Infrastructure definitions

- **REQ-AT-01** THE SYSTEM SHALL describe each backing resource (event stream, aggregate
  history table, raw archive, alert queue with dead-letter queue, per-service permissions) as
  an independently validatable module with declared inputs and outputs.
- **REQ-AT-02** WHEN the local environment definition is applied to the emulators THE SYSTEM
  SHALL create every emulated resource with the names the services expect, and WHEN it is
  destroyed THE SYSTEM SHALL remove them all.
- **REQ-AT-03** WHEN the alert queue's message has been received more than the configured
  number of times without being deleted THE SYSTEM SHALL move it to the dead-letter queue.
- **REQ-AT-04** THE SYSTEM SHALL grant each service only the actions that service's code
  performs, on only the resources it performs them on, and SHALL say which grants were derived
  from reading code and which are unverified.
- **REQ-AT-05** THE SYSTEM SHALL expire or move raw archive objects according to a lifecycle
  rule, and SHALL lay out archive objects by tenant, date and hour.
- **REQ-AT-06** THE SYSTEM SHALL keep definitions for managed streaming, network and
  container-orchestration resources validate-only: they are checked for well-formedness and are
  never planned or applied.
- **REQ-AT-07** THE SYSTEM SHALL pin the provider to a stable release range, keep the committed
  dependency lock, and exclude state files and downloaded plugins from version control.
- **REQ-AT-08** THE SYSTEM SHALL describe, as a design only, how the platform would survive the
  loss of a region, with the recovery targets labelled as assumptions.

### Archive compaction

- **REQ-AC-01** WHEN an hour's partition of a tenant holds several small archive objects and
  has been quiet for at least the configured period THE SYSTEM SHALL combine their records into
  one object for that tenant and hour.
- **REQ-AC-02** WHEN a partition's newest object was written less recently than the quiet
  period allows THE SYSTEM SHALL leave that partition completely untouched.
- **REQ-AC-03** THE SYSTEM SHALL remove the originals only after the combined object has been
  written and re-read, and its record count equals the sum of the originals' counts.
- **REQ-AC-04** IF the re-read count differs THEN THE SYSTEM SHALL keep every original, remove
  the bad combined object, and report the partition as failed.
- **REQ-AC-05** IF the run stops after the combined object is written and before the originals
  are removed THEN a re-run SHALL end in the same state as an uninterrupted run, with no record
  duplicated and none lost.
- **REQ-AC-06** WHEN the run finds nothing to compact THE SYSTEM SHALL make no change at all,
  not even a rewrite of identical content.
- **REQ-AC-07** THE SYSTEM SHALL process records as a stream, so that memory use does not grow
  with the size of an hour's data.
- **REQ-AC-08** IF one partition fails THEN THE SYSTEM SHALL continue with the others and
  report each partition's outcome.
- **REQ-AC-09** THE SYSTEM SHALL take the current time from an injected clock, so every
  time-dependent decision is testable.

## Non-functional

- Quiet period default 15 minutes: a **target** chosen so a still-flushing writer is not raced,
  not measured against any real writer (none exists).
- Compacted object size target 128 MB to 1 GB compressed is a general guideline for analytics
  scans, **unverified for this data**; this phase does not measure it.
- Cost and latency statements are targets until Phase 9 measures them.

## Out of scope

- The writer of raw events into the archive (never built; named as a gap, not hidden).
- A real cloud deployment, real permission evaluation, real network isolation (ADR-0003).
- Multi-region replication as running code (design only, `docs/multi-region-design.md`).
- Merging an existing combined object with late arrivals into one object (see design, known limit).
