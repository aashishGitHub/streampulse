# Phase 10 — AWS simulation, Terraform and the archive compactor

**Status:** core complete, on **emulators only**. Eight Terraform modules validate; five were applied
against moto (13 resources, read back, destroyed). `msk`, `vpc` and `eks` are validate-only. The archive
compactor is tested but **never deployed**, and **nothing writes the raw archive it compacts**.

**One line to say first:** "Terraform that I actually applied, read back and destroyed against an AWS
emulator, with a written list of what that does and does not prove."

## Code map

| Thing | Where |
|---|---|
| Local environment (endpoint overrides) | `infra/terraform/envs/local/main.tf` |
| Modules | `infra/terraform/modules/kinesis`, `dynamodb`, `s3`, `sqs`, `iam` (applied); `msk`, `vpc`, `eks` (validate only) |
| What emulation proves and does not | `docs/adr/0003-local-aws-simulation.md`, `docs/adr/0019-local-only-terraform.md` |
| Compactor core: order of operations | `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/Compactor.java` |
| Which partitions to compact (quiet period) | `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/CompactionPlanner.java` |
| The commit record | `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/CompactionManifest.java` |
| S3 adapter | `functions/archive-compactor/src/main/java/com/streampulse/compactor/adapter/out/S3ArchiveStore.java` |
| Compactor design | `docs/adr/0020-archive-compaction-design.md` |
| Region-loss thinking (design only) | `docs/multi-region-design.md` |

---

## Beginner

### B1. What do `terraform plan`, `apply` and `destroy` do, and what is state?

**A strong answer contains**
- `plan` computes the difference between configuration and recorded state; `apply` makes it so; `destroy`
  removes it. State maps configuration to real resource ids.
- Here state is local and disposable; there is no remote backend, so no locking and no sharing.

**Open** `infra/terraform/envs/local/main.tf`

### B2. What is moto and why use it?

**A strong answer contains**
- An AWS API emulator: Terraform and the AWS SDK run against it with no account, no cost and no credentials.
- It answers API calls; it does not behave like the real services.

**Open** `docs/adr/0003-local-aws-simulation.md`

### B3. What does the compactor do and why?

**A strong answer contains**
- Many small raw objects are merged per tenant, day and hour into one combined object, because small objects
  are slow and costly to list and read.
- It leaves a partition alone until it has been quiet for a while, so it never merges something still being
  written.

**Open** `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/CompactionPlanner.java`

### B4. What does least privilege mean for the IAM modules?

**A strong answer contains**
- Each service gets only the actions it needs on only the resources it needs. The modules define one policy
  per service.
- Honest wrinkle: the alert engine calls `CreateQueue` at start-up, which needs a broader permission than a
  pre-created queue would.

**Open** `infra/terraform/modules/iam`

---

## Mid

### M1. How do you point Terraform at an emulator?

**A strong answer contains**
- Test credentials; skip credential validation, account-id lookup and metadata checks; path-style S3
  addressing; and per-service endpoint overrides to the emulator's URL.
- DynamoDB can be routed to a separate endpoint, but see S2.

**Open** `infra/terraform/envs/local/main.tf`

### M2. In what order does the compactor work, and why that order?

**A strong answer contains**
- Merge to a temporary file; write the combined object; **re-read it and count records**; write the manifest
  (the commit record); only then delete the inputs.
- Delete-before-verify would lose data if the write was bad; a mutation doing exactly that turned the
  "corrupted write keeps inputs and removes the bad target" test red.
- Streaming through a fixed buffer keeps memory bounded by construction (not measured) and preserves bytes
  exactly.

**Open** `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/Compactor.java`

### M3. What is the manifest for?

**A strong answer contains**
- It is the commit record. On the next run, a raw object named in a manifest is a leftover to delete, never
  data to merge again. So a crash part-way through the deletes cannot create duplicates.
- The combined object's name includes a hash of the sorted input names, so a rerun rewrites identical bytes.

**Open** `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/CompactionManifest.java`

### M4. How do you test time-dependent code like the quiet period?

**A strong answer contains**
- An injected clock, never the system clock. In the integration test the clock is movable because the
  emulator stamps `LastModified` with real time.
- Mutation: removing the quiet-period check turned "a partition still being written is never touched" red.

**Open** `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/CompactionPlanner.java`

---

## Senior

### S1. What does a successful `apply` against moto not prove?

**A strong answer contains**
- Not proven: that real AWS accepts the configuration, IAM enforcement, network isolation, quotas and
  throttling, or that every attribute is stored faithfully.
- Proven: the configuration is well formed, calls are answered, names and shapes come back, dependency order
  and destroy work (ADR-0019's own table).
- That is why `msk`, `vpc` and `eks` are validate-only: an apply would give a green result that says nothing
  about isolation, and real clusters cost money.

**Open** `docs/adr/0019-local-only-terraform.md`

### S2. The second plan shows three changes with no configuration change. Is that drift?

**A strong answer contains**
- It is emulator round-trip loss: moto does not return tags on the stream and bucket and re-serialises the
  queue's redrive policy JSON. Diagnose by reading the resources back with the CLI.
- In production: a remote backend with locking, and normalise JSON with `jsonencode`, or use `ignore_changes`
  only when it is justified.
- A related finding: DynamoDB Local made `apply` hang with this provider version (cause unproven), so the
  table was applied against moto, which is lower fidelity than AWS's own image. ADR-0003's table still lists
  DynamoDB Local; note the contradiction.

**Open** `MILESTONES.md` (Phase 10, "Honest gaps")

### S3. Walk through the compactor's crash points.

**A strong answer contains**
- Crash before the combined object exists: nothing changed. After it, before the manifest: the object is
  unreferenced and a rerun rewrites it identically. After the manifest, before deletes finish: the manifest
  lets the next run delete leftovers without re-merging.
- A test covers the delete crash ("recovered without duplicates").
- Known limits: no conditional delete (raw keys assumed write-once), a late arrival creates a second combined
  object, and overlapping runs are safe by reasoning, not by test.

**Open** `docs/adr/0020-archive-compaction-design.md`

### S4. How would you deploy this, and what would change?

**A strong answer contains**
- It is a Spring Cloud Function with no Lambda adapter or shaded jar yet, so first package it and add a
  trigger (a schedule).
- Check memory and timeout sizing against object sizes (memory is bounded by streaming, unmeasured), idempotent
  reruns, and the permissions in its IAM policy.
- And note nothing produces raw archive objects yet, so a deployed compactor would have nothing to do.

**Open** `functions/archive-compactor/src/main/java/com/streampulse/compactor/adapter/in/CompactArchiveFunction.java`

### S5. Region loss: what does the design claim and what is it worth?

**A strong answer contains**
- `docs/multi-region-design.md` sets a recovery point of about 60 seconds of raw telemetry as a budget to
  measure against. Cross-region lag under load is unmeasured, so it is a design, not a promise.
- It is design only, with assumptions and verify marks. Use it to show how you reason about RPO and RTO.

**Open** `docs/multi-region-design.md`

---

## Say this honestly

- Emulators only; `terraform fmt` and `validate` are not in CI.
- Point-in-time recovery is untested; no service writes the archive bucket or the aggregate-history table.
- The compactor runs only in-process and in tests. Its memory bound is by construction, not measured.

## Stories and numbers

[Numbers](../06-numbers.md): Terraform and compactor test rows.
