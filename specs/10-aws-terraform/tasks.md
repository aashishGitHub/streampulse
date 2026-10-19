# Phase 10 — AWS simulation, Terraform and the archive compactor: Tasks

Each line: failing test first, then code. Tick only with passing output (MILESTONES.md is
owned by the integrator; literal output lives in the agent report).

## Archive compactor

- [x] T10-01 spec, design, ADR-0019, ADR-0020 written
- [x] T10-02 `CompactorArchitectureRulesTest` fails (no module sources) -> module skeleton with `domain`/`adapter`/`config`
- [x] T10-03 `ArchiveKeyTest` fails -> `ArchiveKey` (parse `tenant=/dt=/hour=/name`, recognise combined objects)
- [x] T10-04 `CompactionPlannerTest` fails -> `CompactionPlanner`, `CompactionPolicy`, `ArchiveObject`, `PartitionPlan`
- [x] T10-05 `NdjsonMergerTest` fails -> `NdjsonMerger` (streaming, counts, newline normalisation)
- [x] T10-06 `CompactorTest` fails -> `Compactor` + `ArchiveStore` port (ordering write, re-read, delete)
- [x] T10-07 mutation: delete inputs BEFORE verifying; `corruptedWriteKeepsInputsAndRemovesTheBadTarget` goes red; restore
- [x] T10-08 mutation: remove the quiet-period check; `partitionStillBeingWrittenIsNeverTouched` goes red; restore
- [x] T10-09 `S3ArchiveStoreIT` fails -> `S3ArchiveStore` (paginated list, streaming read, file upload, batched delete)
- [x] T10-10 `CompactorFunctionWiringTest` fails -> `CompactorConfig`, `CompactorProperties` (function registered in the catalog)
- [x] T10-11 `ArchiveCompactionIT` fails -> end to end through the function catalog against an emulator
- [x] T10-12 full module run: `:functions:archive-compactor:spotlessApply :test :integrationTest`

## Terraform

- [x] T10-20 `terraform fmt -check -recursive` and `validate` for every module and `envs/local` (the "test" for IaC: fails until the module is well-formed)
- [x] T10-21 modules: `kinesis`, `dynamodb`, `s3`, `sqs`, `iam`
- [x] T10-22 modules (validate-only): `msk`, `vpc`, `eks`
- [x] T10-23 `envs/local` with endpoint overrides; `plan` shows the expected resource set against a throwaway moto
- [x] T10-24 `apply` against moto (13 resources); read back with the AWS CLI (stream, queue + redrive proven by 5 receives then DLQ, bucket + lifecycle, table + index, policies). DynamoDB Local did not work, so the table is applied against moto
- [x] T10-25 `destroy` leaves nothing behind
- [x] T10-26 ADR-0019 written; `docs/multi-region-design.md` written (design only)

## Not done (stated, not hidden)

- A second `plan` is not empty against moto (tags on the stream and bucket are not returned; `redrive_policy` is re-serialised). Not fixed: it is emulator behaviour.
- DynamoDB Local cannot be used with provider 6.68.0 (apply never returns); cause unproven.
- Point-in-time recovery is untested (DynamoDB Local has no such API; the block is off locally).
- No `make` targets were added (the Makefile is not mine); proposed text is in the Phase 10 report.
- No service writes the raw archive or the aggregate-history table; those modules describe a target.
- No deployment of the compactor as a real serverless function; it runs in-process and in tests only.
- `msk`, `vpc`, `eks` have never been planned or applied.
- Memory-boundedness of the compactor is by construction (stream in, stream out), not measured.
