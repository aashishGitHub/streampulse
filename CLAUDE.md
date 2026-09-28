# StreamPulse — working agreements

Context for AI assistance in this repo. Read `docs/learning/00-how-to-read-this-repo.md` first.

## What this project is

A learning-focused portfolio project: a real-time telemetry platform in Java 21 + Spring
Boot. Every component exists to make a senior-level concept concrete. Optimise explanations
and comments for **teaching**, not for brevity.

## Process — spec-driven, no exceptions

Work one phase at a time, in this order:

1. `specs/NN-name/requirements.md` — user stories + EARS acceptance criteria (`WHEN … THE
   SYSTEM SHALL …`). No technology named at this stage.
2. `specs/NN-name/design.md` — flow of control, data flow, diagrams, failure modes.
3. Contracts (OpenAPI / AsyncAPI / Avro / proto) before implementation.
4. `specs/NN-name/tasks.md` — each task is "write the failing test, then the code".
5. Tests first: acceptance → unit → slice → integration.
6. Verified output into `MILESTONES.md`, plus an ADR for any real decision.

**Do not skip to code.** The specs are the point.

## Code conventions

- Java 21. Records for value objects, sealed types where a closed hierarchy is meant.
- **Constructor injection only.** Enforced by ArchUnit.
- Hexagonal: `domain` imports no framework types. Enforced by ArchUnit.
- `java.time` only; inject a `Clock` so time-dependent code is testable.
- Comments explain **why**, never what the line already says. A comment restating the code
  is a bug — delete it.
- Every non-obvious design decision gets a `WHY` block or an ADR.
- Diagrams (ASCII or Mermaid) wherever control flow or data flow is non-trivial.

## Build

- Versions go in `gradle/libs.versions.toml` and nowhere else. **Stable releases only** — no
  `-M`, `-RC`, `-SNAPSHOT`.
- Shared build config goes in `build-logic/` convention plugins, never `allprojects`.
- Pin Docker image tags. Never `latest`.

## Honesty rules — these are not negotiable

- BS-01…BS-10 are **fictional design scenarios**. Never write them as lived production
  incidents.
- Numbers are **targets** until Phase 9 measures them. Label them as such.
- `MILESTONES.md` gets **real terminal output**. If something was skipped or only partly
  works, say so there.
- Do not invent API names, config keys or library behaviour. If uncertain, add a `VERIFY:`
  comment naming what to check.
- Local AWS simulation proves API-level behaviour only. See ADR-0003 for what it does not
  prove; repeat that caveat wherever it matters.

## Commands

```bash
make help      # everything available
make build     # compile + test
make format    # before committing
make check     # what CI runs
make doctor    # verify the machine has what's needed
```
