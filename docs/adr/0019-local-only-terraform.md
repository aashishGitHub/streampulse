# ADR-0019 — Terraform applied only to emulators; MSK, VPC and EKS are validate-only

**Status:** accepted · **Date:** 2026-10-12 · **Phase:** 10 · **Builds on** ADR-0003

## Context

ADR-0003 chose free emulators over a real account. Phase 10 turns that into infrastructure code:
modules for the stream, the history table, the archive bucket, the alert queue and the
per-service permissions, plus modules for managed Kafka, a network and a Kubernetes cluster.
The question is what each kind of Terraform command proves when the target is an emulator.

## Decision

1. **`envs/local` is applied, for real, against emulators only.** The provider has the skip flags
   and path-style addressing for S3 and per-service endpoint overrides: moto for the stream, bucket, queues,
   policies and the table. DynamoDB Local was tried for the table and did not work (see "What did not
   work"). The sequence is `init`, `fmt -check`, `validate`,
   `plan`, `apply`, read the resources back with the AWS CLI, `destroy`.
2. **`msk`, `vpc` and `eks` are `validate`-only.** They are called from `envs/validate`, which is
   never planned or applied. See the table below for why.
3. **The lock file is committed for the two roots** (`envs/local`, `envs/validate`); state and
   `.terraform/` are ignored. The provider is constrained to `~> 6.0` (verified against the
   registry on 2026-10-12: the latest release was 6.68.0, and `init` resolved it).
4. **No remote backend.** State is local and disposable, because it describes an emulator.

## What `apply` against an emulator proves, and does not

| It proves | It does not prove |
|---|---|
| The configuration is well-formed and the provider accepts it | That the same configuration is accepted by the real service. Emulators are more permissive and skip validation the real API performs |
| The calls the provider makes are answered, and the resources can be read back with the expected names and shapes | Anything about permissions. IAM is not enforced: a policy that would deny in production succeeds here. A correct-looking policy document is not evidence it is correct |
| Dependency order and `destroy` cleanup work through the provider | Capacity, throttling, latency or cost. Shard quotas, request limits and DynamoDB throughput do not exist locally |
| The names the services expect (`telemetry-raw-v1`) exist | Network isolation. Subnets and security groups can be created but no packet is ever blocked |
| Dependency order and the apply-then-destroy round trip leave nothing behind (verified by listing every service afterwards) | That every attribute the provider sends is stored faithfully: an emulator can accept an attribute and ignore it. Observed: see below |

## Why `msk`, `vpc` and `eks` are validate-only

| Module | Reason |
|---|---|
| `vpc` | The emulator records subnets, route tables and security groups but enforces none of it. An `apply` would produce a green result that says nothing about isolation, which is the only reason to have a network |
| `msk` | A managed-Kafka control plane is not something the emulator provides faithfully, and a real cluster takes tens of minutes and costs money while it exists. Kafka itself runs locally in Docker as real Kafka, so the behaviour that matters is already exercised without this module |
| `eks` | Nothing schedules pods against an emulator, and a real cluster has an hourly control-plane charge. Binding the permission policies to pods is not defined here and is not claimed |

`validate` still catches real mistakes in these modules: an unknown argument, a missing required
block, a type error, an undefined reference. It cannot catch a value the real service rejects
(an unsupported Kafka version, an instance type absent from a region). The variables for those are
marked `VERIFY`.

## Consequences

**Positive**
- The infrastructure is exercised, not just parsed, at zero cost and with no account.
- The permission policies are written against the code, with each marked as verified from code or
  not; the unverified ones say so in their description.

**Negative**
- Local success is weak evidence. It must be repeated wherever someone might read a green apply
  as a deployment guarantee.
- Moto does not model everything: see "What did not work" below.
- The aggregate-history table and the raw archive bucket describe a target. No service writes them.
- Multi-region is a design document only (`docs/multi-region-design.md`); nothing in it is
  simulated.

## What did not work (observed, not hypothesised)

- **A second `plan` is not empty against moto: 3 resources show in-place changes.** The Kinesis stream
  and the S3 bucket show their `tags` as missing, and the main alert queue shows a
  `redrive_policy` "whitespace change". The first two mean moto accepts tags on those two resources
  and does not return them; the third means moto re-serialises the JSON it stores. The same
  configuration against real AWS would, I expect, plan clean, but I have not seen that. Treat an
  empty second plan as unavailable here, not as a defect in the modules.

- **DynamoDB Local with AWS provider 6.68.0.** `apply` created the table, `describe-table` reported
  it and its index `ACTIVE`, and the provider still polled `DescribeTable` until interrupted (over two
  minutes, no error). The same table applied against moto in 6 seconds. DynamoDB Local's response
  carries no `WarmThroughput` field; that may be what the provider waits on, but I did not prove
  it (VERIFY). Consequence: moto is the table's emulator, which is a lower-fidelity DynamoDB than
  AWS's own local image.
- **Point-in-time recovery cannot be exercised locally.** DynamoDB Local answers
  `DescribeContinuousBackups` with `UnknownOperationException`. The module emits the block only when
  asked for, so the local environment never tests it.
- **Applies are slow against moto**, tens of seconds per resource for the stream, the queues and the
  lifecycle configuration (about a minute for the lifecycle rule), because the provider polls for
  consistency after each create. That is provider behaviour against an emulator, not a fault in the
  configuration; I did not measure it beyond the apply timings recorded in the Phase 10 report.
- **The alert queue is also created by the service.** `services/alert-engine` calls `CreateQueue`
  with the queue name and no attributes at start-up. Against real SQS I believe that returns the
  existing queue when the name matches (VERIFY), so the redrive policy Terraform sets would survive;
  but it means the service needs `sqs:CreateQueue`, which least privilege would otherwise withhold.

## Interview relevance

Being able to say exactly which of `validate`, `plan` and `apply` an emulator can honestly stand
behind, and which it cannot, is the senior answer to "have you run this in production?".
