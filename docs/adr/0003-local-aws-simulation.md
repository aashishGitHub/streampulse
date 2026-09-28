# ADR-0003 — Simulating AWS locally, for free, on a Mac

**Status:** accepted · **Date:** 2026-09-28 · **Phase:** 0 (exercised from Phase 4)

## Context

StreamPulse targets S3, DynamoDB, SQS, Kinesis and Lambda, with Terraform managing them. It
must run entirely on a laptop, at zero cost, with no AWS account required — while staying
honest about what a local simulation can and cannot prove.

## Decision

Use a **patchwork of free, purpose-built emulators**, each chosen for the service it fakes
best, wired together with Docker Compose profiles:

| AWS service | Local stand-in | Fidelity |
|---|---|---|
| S3, SQS, Kinesis, Lambda | **moto server** | good at the API level |
| DynamoDB | **DynamoDB Local** (official AWS image) | high — it is AWS's own |
| Kafka / MSK | **Apache Kafka in KRaft mode** | real Kafka, not an emulator |
| Auth (Cognito) | **Keycloak** | real OIDC/JWT, different provider |
| Terraform | provider **endpoint overrides** pointed at the emulators | API-level only |

This mirrors the approach already proven in the author's Go projects (`ticket-booking`,
`offline-sync-app`), which used moto rather than a paid tier.

## Alternatives considered

| Option | Verdict |
|---|---|
| **Free emulator patchwork** (chosen) | zero cost, no account, runs offline |
| LocalStack | broader coverage in one tool, but licensing/free-tier terms need verification and some features are paid. Rejected to keep the project unconditionally free |
| A real AWS account | highest fidelity, but costs money, needs credentials in CI, and risks a surprise bill from a load test |
| MinIO for S3 | previously the obvious choice, but its image/licensing changed in 2025 — **verify current terms**. moto covers S3 adequately for our use, so we avoid the dependency |

## Consequences

**Positive**
- The entire stack runs with `make up-full` and costs nothing.
- Compose **profiles** mean you start only what the current phase needs, rather than the
  whole estate on every `docker compose up`.
- Terraform modules are genuinely written and `apply`-ed, so the IaC is exercised rather
  than merely `validate`-d.

**Negative — and these must be stated plainly, not hidden**
- **VPC isolation cannot be simulated.** Terraform will create subnets and security groups
  against moto's API, but no packet is ever actually blocked. `docs/network.md` will say so.
- **IAM is not enforced.** A policy that would deny in production succeeds locally. Local
  success is *not* evidence the policy is correct.
- **Performance characteristics are meaningless.** DynamoDB throttling, Kinesis shard limits
  and S3 latency are all absent or fake. Load-test numbers describe the laptop, not AWS.
- moto's Lambda support needs Docker-in-Docker and may not work on macOS — fallback is the
  AWS SAM CLI. **Verify before Phase 10.**

**Mitigation:** every local-only claim is labelled in the docs. `MILESTONES.md` records what
was actually run, and modules that can only be `validate`-d (MSK, EKS, real VPC) are marked
as such rather than presented as deployed.

## Interview relevance

Saying *"I ran it locally with moto, and here is precisely what that does and does not
prove"* is a much stronger signal than claiming production AWS experience that unravels on
the first follow-up. Knowing the limits of your test environment is a senior trait.
