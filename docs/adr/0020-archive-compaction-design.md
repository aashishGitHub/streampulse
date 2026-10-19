# ADR-0020 — Compacting the raw archive: write, re-read, commit, then delete

**Status:** accepted · **Date:** 2026-10-12 · **Phase:** 10 · **Builds on** ADR-0003

## Context

The raw archive is meant to hold every telemetry event as gzip NDJSON, laid out as
`tenant=<t>/dt=<yyyy-mm-dd>/hour=<hh>/<name>.ndjson.gz`. A writer that flushes often produces many
small objects per tenant-hour. That is the **small-files problem**:

- **Reads pay per object, not per byte.** Every object is at least one request and one open, so a
  scan of an hour is dominated by per-object overhead when objects are KB-sized.
- **Request charges scale with object count.** I do not have a verified current price; check the
  provider's pricing page. The shape is what matters: N objects cost N requests to read and N to list.
- **Some storage classes bill a minimum object size** (I recall 128 KB for the infrequent-access
  class; VERIFY before relying on it), so tiny objects can cost more in a cheaper class than in the
  standard one. The lifecycle rule in `infra/terraform/modules/s3` moves objects to that class.
- Listing a prefix is paginated at 1,000 keys per call, so object count also costs time.

**Honest status.** No service writes this archive. The raw path was never built; the Kafka
topic and the Kinesis stream are consumed, but nothing lands raw events in object storage. The
compactor is therefore tested only against objects its own tests generate, and every figure above
is a reason, not a measurement.

## Decision

A function (`functions/archive-compactor`, Spring Cloud Function, `java.util.function.Function`)
that, per tenant-hour partition, merges the small objects into one, in this order:

```
merge to a temp file → put combined → re-read and count → put manifest (commit) → delete inputs
```

1. **Quiet period.** A partition is touched only when its newest raw object is at least the
   configured period old (default 15 minutes, a target) by the store's `LastModified`, judged
   against an injected clock. A partition still being written is not read, written or deleted.
2. **Verify before delete.** The combined object is streamed back and its records counted; the
   count must equal the sum of the inputs' counts, which were counted while merging. A mismatch
   keeps every input, removes the bad combined object and reports the partition failed.
3. **A manifest is the commit record.** It lists the sources folded into a combined object and is
   written only after verification. On the next run, a raw object that a manifest names is a
   *leftover to delete*, never data to merge again.
4. **Deterministic names.** The combined key embeds a hash of the sorted input keys, so a rerun of
   the same inputs rewrites the same key with identical bytes instead of creating a second copy.
5. **Streaming.** Records are copied as bytes through a fixed buffer into a temp file, then
   uploaded from the file. Nothing collects an hour in memory. Bytes, not decoded text, so an
   invalid UTF-8 sequence is preserved rather than silently replaced.

Where a crash can land and what the next run does:

| Crash point | State left behind | Next run |
|---|---|---|
| before the put | nothing | starts over |
| after the put, before the manifest | combined object, no manifest | same inputs, same key, overwritten identically |
| after the manifest, before any delete | combined + manifest + all inputs | all inputs are leftovers: deleted |
| part-way through the delete | combined + manifest + some inputs | the survivors are leftovers: deleted |

The last row is the reason for the manifest. Without it, the survivors would be compacted again
into a second combined object, and every one of their records would exist twice. The tests prove
this case (`CompactorTest.crashDuringDeleteIsRecoveredWithoutDuplicates`,
`ArchiveCompactionIT.interruptedDeleteIsFinishedByTheNextRun`).

## Alternatives considered

| Option | Verdict |
|---|---|
| **Fix it at the writer** (buffer by size or time before writing; a managed delivery stream with buffering hints) | The better long-term answer and the first thing to do once a writer exists. Compaction is still needed for the tail of small objects a time-based flush leaves and for late data. Not built because the writer does not exist |
| **Glue or EMR (Spark) compaction job** | Right at warehouse scale, wrong here: a job-startup and cluster cost floor for an hourly merge, nothing that runs on a laptop against an emulator, and far more machinery than concatenating gzip NDJSON. Reconsider if the archive becomes a queryable table |
| **A table format with built-in compaction** (Iceberg or Delta) | The real answer if the archive becomes an analytics table: compaction, atomic commits and snapshot isolation come with it. Heavier than this phase justifies; the manifest here is a small, deliberate stand-in for one atomic commit |
| **Athena `INSERT`/`CTAS` rewrite** | Needs a catalog and a table definition over data nothing writes yet. Cannot run locally |
| **Concatenate gzip members without decompressing** | Cheap, and a valid gzip file, but cannot count records for verification and would glue records if an input lacks a final newline |
| **No manifest; delete, then re-list on rerun** | Cannot tell survivors of a partial delete from new data. Rejected: duplicates |

## Consequences

**Positive**
- Idempotent at every step; a rerun on compacted data makes no mutating call.
- Data loss needs a bug in the verify step, which has a test and a mutation check (deleting before
  verifying turns six tests red).
- Domain logic (grouping, quiet period, planning, ordering) is unit-tested with a fake store; the
  S3 adapter and the end-to-end path are tested against an emulator.

**Negative and known limits**
- **Late arrivals create a second combined object** instead of being merged into the first.
  "One object per hour" holds only if nothing arrives after a compaction. Merging combined
  objects would need manifests that supersede each other; not built.
- **No conditional delete.** A raw key overwritten between listing and deletion would be deleted.
  Raw keys are treated as write-once.
- **Per-key delete errors** are raised so the manifest survives and the next run finishes the
  job. The emulator never produces them, so this is covered by a mocked-client unit test only.
- **Overlapping runs** are safe by reasoning (same inputs, same key, same bytes, idempotent
  deletes), not by a test.
- **Temp-file size** equals the compressed hour. A real serverless runtime has a bounded scratch
  directory and a bounded run time (I recall 512 MB to 10 GB and 15 minutes; VERIFY current
  limits). An hour larger than that would fail its partition and be reported, not corrupted.
- **Memory is bounded by construction, not measured.**
- **Never deployed.** It runs in-process and in tests. A real deployment adds the Lambda adapter
  and packaging (a shaded jar, not the Boot fat jar), which are not built or tested here.
- The emulator proves API-level behaviour only (ADR-0003): not permissions, not real consistency,
  not latency.
