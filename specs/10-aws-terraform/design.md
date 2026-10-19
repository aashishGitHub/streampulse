# Phase 10 — AWS simulation, Terraform and the archive compactor: Design

Local simulation proves API-level behaviour only (ADR-0003). Nothing here has touched a real
cloud account. No service writes the raw archive or the aggregate-history table today.

## Flow of control and data: Terraform

```
 infra/terraform/
   modules/            one directory per resource family; each has versions.tf (provider constraint only),
   │                   variables.tf, main.tf, outputs.tf
   ├─ kinesis  dynamodb  s3  sqs  iam        applied against emulators (envs/local)
   └─ msk  vpc  eks                          validate-only: never planned, never applied
   envs/local/         provider with endpoint overrides ──▶ moto (stream, bucket, queues, IAM)
                                                       └──▶ DynamoDB Local (history table)
   envs/validate/      wires msk, vpc, eks so `terraform validate` can check them (no provider
                       credentials, no backend, never planned)
```

```
 terraform init ─▶ fmt -check ─▶ validate (every module + env) ─▶ plan ─▶ apply ─▶ read back (AWS CLI) ─▶ destroy
                                 well-formed?                    set of    API accepted   resource really        nothing
                                                                  resources  the calls      exists with that shape  left over
```

## Flow of control and data: archive compactor

```
 trigger {tenant?, date?}
   │
   ▼
 CompactArchiveFunction  (java.util.function.Function; the only thing the function catalog sees)
   │  maps request → Scope
   ▼
 Compactor.compact(scope)                       clock.instant() read ONCE per run
   │ 1 list     ArchiveStore.list(prefix)                       tenant=<t>/dt=<d>/hour=<h>/<name>
   │ 2 manifests ArchiveStore.readText(each *.manifest)         "these raw objects are already folded in"
   │ 3 plan     CompactionPlanner (pure)  ── per partition ──▶  Skip(STILL_BEING_WRITTEN | NOTHING_TO_DO)
   │                                                            or Plan(leftovers, inputs, targetKey)
   │ 4 per Plan, one partition at a time, failures isolated:
   │      a. merge   stream each input (gunzip, line by line) ─▶ gzip writer ─▶ temp file   (counts records)
   │      b. put     temp file ─▶ compacted-<hash>.ndjson.gz
   │      c. verify  stream the compacted object back, count lines, must equal the sum of (a)
   │      d. commit  put compacted-<hash>.manifest (record count + source keys)     ◀── the commit point
   │      e. delete  inputs (and leftovers) in batches
   ▼
 CompactionReport[ per partition: COMPACTED | CLEANED_UP | SKIPPED_QUIET | SKIPPED_NOTHING_TO_DO | FAILED ]
```

Where a crash can land, and what the next run sees:

```
 crash after a      nothing written                     next run: identical plan, starts over
 crash after b      combined object, no manifest        next run: same inputs ⇒ same hash ⇒ same key, overwritten with identical bytes
 crash after c      same as b (verified, but not committed)
 crash after d      combined + manifest, all inputs     next run: every input is named in a manifest ⇒ "leftover": deleted, nothing re-merged
 crash during e     combined + manifest, SOME inputs    next run: the remaining ones are leftovers ⇒ deleted. Without the manifest they would be
                                                         compacted AGAIN into a second object and every record would exist twice.
```

## Keys, partitions, ordering

| Thing | Key | Why |
|---|---|---|
| Archive object | `tenant=<tenant>/dt=<yyyy-mm-dd>/hour=<hh>/<name>.ndjson.gz` | tenant first: lifecycle and access control per tenant, and a prefix listing never crosses tenants. Hive-style `k=v` so query engines can prune partitions |
| Partition | (tenant, date, hour) | the unit of compaction, and of the quiet-period decision |
| Combined object | `compacted-<first 16 hex of SHA-256 of the sorted input keys>.ndjson.gz` | deterministic, so a re-run of the same input set writes the same key (idempotent) |
| Commit manifest | `compacted-<same hash>.manifest` | records which raw objects are folded into that combined object |
| Input order | sorted by key | determinism: the same input set always produces the same bytes |
| Tenant id | `TenantId` (libs/platform) | the same validation every other service applies; a bad prefix is skipped, never compacted |
| Aggregate-history table | `PK=TENANT#<t>#METRIC#<m>`, `SK=WINDOW#<window start ISO-8601 UTC>`, GSI `title-index` on (`GSI1PK=TENANT#<t>#TITLE#<title>`, `GSI1SK=WINDOW#<ts>`) | DESIGN ONLY: no service writes it. The Postgres `metric_window` table is the real store today |
| Stream | `telemetry-raw-v1` | the name `IngestEndToEndIT` already uses |
| Alert queue | `alerts` + `alerts-dlq`, `maxReceiveCount = 5` (a target) | `alerts` is `streampulse.aws.alerts-queue-name` in services/alert-engine; redrive policy |

## Failure modes

| Failure | Behaviour | Test or command that proves it |
|---|---|---|
| Partition newest object written less than the quiet period ago | partition untouched: no read, no write, no delete | `CompactorTest.partitionStillBeingWrittenIsNeverTouched`, `CompactionPlannerTest.quietPeriodBoundaryIsInclusive`, `ArchiveCompactionIT.partitionStillBeingWrittenIsLeftAlone` |
| Combined object's re-read count differs from the sum of inputs | inputs kept, bad combined object removed, partition reported FAILED | `CompactorTest.corruptedWriteKeepsInputsAndRemovesTheBadTarget` |
| Inputs deleted before verification (the ordering bug) | caught: write, re-read, commit, delete order is asserted | `CompactorTest.originalsAreDeletedOnlyAfterTheCombinedObjectIsReadBack` (+ mutation run) |
| Crash between writing the combined object and deleting inputs | re-run converges to the uninterrupted end state, no duplicate records | `CompactorTest.crashAfterWriteIsRecoveredWithoutDuplicates` |
| Crash part-way through deleting inputs | re-run deletes the leftovers named in the manifest, does not re-merge them | `CompactorTest.crashDuringDeleteIsRecoveredWithoutDuplicates`, `ArchiveCompactionIT.interruptedDeleteIsFinishedByTheNextRun` |
| Re-run on already compacted data | zero mutating calls | `CompactorTest.rerunOnCompactedDataChangesNothing`, `ArchiveCompactionIT.secondRunLeavesEveryObjectUntouched` |
| One partition fails | the others are still processed; each outcome reported | `CompactorTest.oneFailingPartitionDoesNotStopTheOthers` |
| Object with a trailing record lacking a newline | records are never glued together | `NdjsonMergerTest.missingTrailingNewlineDoesNotGlueRecords` |
| Concatenated gzip members in one input | all members are read | `NdjsonMergerTest.multiMemberGzipIsReadToTheEnd` |
| Hour too large to hold in memory | streamed through a temp file; input and output are never collected | `NdjsonMergerTest.largeInputIsStreamed` (correctness at 1M records; memory bound is by construction, NOT measured) |
| Temp file left behind after a failure | removed in every outcome | `CompactorTest.temporaryFileIsRemovedAfterAFailedVerification` |
| More than 1,000 objects to delete or list | paginated list, batched delete | `S3ArchiveStoreIT.listsMoreObjectsThanOnePage`, `S3ArchiveStoreIT.deletesMoreObjectsThanOneBatch` |
| Delete call reports per-key errors | raised, so the manifest stays and the next run finishes the job | `S3ArchiveStoreTest.deleteErrorsAreRaised` (mocked client: the emulator never reports per-key errors, so no integration test exists) |
| Hour compacted by one batch of 1005 keys or more | listed across pages, deleted in batches of 1000 | `S3ArchiveStoreTest.deletesAreBatched` |
| Domain imports a framework | build fails | `CompactorArchitectureRulesTest` |
| The function is not registered | context fails to start / lookup is null | `CompactorFunctionWiringTest` |
| Terraform module is malformed | non-zero exit | `terraform fmt -check -recursive`, `terraform validate` per module and env |
| Alert queue has no dead-letter queue | redrive policy absent from the read-back | `aws sqs get-queue-attributes --attribute-names RedrivePolicy` after `apply` (manual command; output kept in the Phase 10 report) |
| Table block makes the provider call an API the emulator lacks | apply hangs | observed: point-in-time recovery against DynamoDB Local; the block is now emitted only when requested |

## Known limits (stated, not hidden)

- **Late arrivals produce a second combined object.** If objects land in an hour after it was
  compacted and later go quiet, they are compacted into a new `compacted-<hash2>` object; the first
  is not rewritten. So "one object per hour" holds only when no data arrives after a compaction.
  Merging combined objects would need a manifest that supersedes another, which this phase does not build.
- A single input set below `minInputObjects` (default 2) is left alone: compacting one object into
  one gains nothing.
- Only `*.ndjson.gz` objects are inputs. Anything else under the prefix is ignored and not deleted.
- Two overlapping runs are safe by construction (same inputs, same key, same bytes, deletes are
  idempotent) but that is reasoned, not tested.
- No conditional delete (If-Match/ETag): an object overwritten between listing and deletion under the
  same key would be deleted. Raw archive keys are treated as write-once.

## Decisions

- ADR-0019 — local-only Terraform strategy.
- ADR-0020 — compaction design, why not Glue/EMR here, the small-files problem.
