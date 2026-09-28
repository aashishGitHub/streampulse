# How to read this repo

This codebase is a **teaching artifact**. It is commented far more heavily than production
code should be, because the thing that normally gets lost — *why* a design was chosen and
what the alternatives cost — is exactly the thing interviews are about.

---

## The three-layer reading model

Every phase has the same three layers. Read them in this order:

```
   ┌─────────────────────────────────────────────────────────┐
   │  1. WHY   specs/NN-*/requirements.md                     │
   │           The business problem, as user stories and      │
   │           acceptance criteria. No technology named.      │
   ├─────────────────────────────────────────────────────────┤
   │  2. HOW   specs/NN-*/design.md  +  docs/adr/*.md         │
   │           Flow of control, data flow, diagrams, the      │
   │           alternatives rejected and why.                 │
   ├─────────────────────────────────────────────────────────┤
   │  3. WHAT  the code, and its tests                        │
   │           Comments explain the non-obvious decision,     │
   │           never what the line already says.              │
   └─────────────────────────────────────────────────────────┘
```

If you read only the code, you will learn syntax. If you read the specs first, you will
learn **judgement** — which is the thing being assessed at senior level.

---

## The learning path, beginner to senior

Each phase is chosen to make one hard concept concrete. The left column is roughly the
level at which that concept starts to matter.

| Level | Phase | The concept you actually learn |
|---|---|---|
| Foundation | **0** | A build you can trust. Toolchains, dependency management, architecture as tests. |
| Junior → Mid | 1 | Contracts before code. Schema evolution, and why field numbers are permanent. |
| Mid | 2 | HTTP done properly: validation, idempotency, **backpressure**, rate limiting. |
| Mid → Senior | 3 | Stream processing: windows, event time vs processing time, exactly-once's real limits. |
| Mid → Senior | 4 | Storage chosen by **access pattern**; dead-letter queues; idempotent consumers. |
| Senior | 5 | Reactive vs blocking, argued from your own benchmark rather than a blog post. |
| Senior | 6 | The dual-write problem, the outbox pattern, CDC. The most valuable pattern here. |
| Mid | 7 | A real-time UI: reconnection, backoff, resuming a stream. |
| Senior | 8 | What "healthy" means numerically. SLIs, SLOs, error budgets. |
| Senior | 9 | Why it is slow, and proving it. Profilers, flame graphs, GC, load models. |
| Senior | 10 | Infrastructure as code, and the honest limits of local simulation. |
| Senior | 11 | Running stateful stream apps on Kubernetes without losing data. |
| Senior | 12 | The fan-out problem — the classic feed design, actually implemented. |
| Senior | 13 | AI tooling as engineering, with guardrails, not as a demo. |

---

## What the comment style means

Comments here fall into four kinds. Recognising which is which tells you how to read them.

| Marker | Meaning |
|---|---|
| A `WHY …` heading | A design decision with alternatives. **This is the interview content.** |
| `.NET ANALOGY` | A bridge from what you already know. Never an exact equivalence. |
| `VERIFY:` | I am not fully certain, or it is version-sensitive. Check current docs. |
| `←` inline arrows | Points at the specific token that matters on that line |

**A comment that restates the code is a bug.** If you find one, delete it.

---

## Honesty rules this repo follows

These exist because a portfolio project is only useful if you can defend every claim in it.

1. **The business scenarios are fictional.** BS-01…BS-10 are design exercises. They are not
   production incidents anyone lived through. Present them that way.
2. **Numbers are targets until measured.** Every latency and throughput figure is a target
   chosen for the exercise until Phase 9 measures it. `MILESTONES.md` holds the real ones.
3. **`MILESTONES.md` contains real terminal output.** Not "this works" — the actual output,
   pasted. If a step was skipped, it says so.
4. **Local simulation is labelled.** ADR-0003 spells out what moto and DynamoDB Local do
   *not* prove. Saying "I ran it locally and here is what that does and does not demonstrate"
   is a stronger signal than an overclaim that collapses on follow-up.

---

## How to actually study this

**Do not read it end to end.** Pick a phase and do this loop:

1. Read `requirements.md`. Close it. Say out loud what problem the phase solves.
2. Read `design.md`. Before looking at the code, sketch how *you* would build it.
3. Read the code. Where it differs from your sketch, work out which is better and why.
   Sometimes yours will be — the design notes are opinions, not scripture.
4. Break something on purpose. Delete the `fresh > 0` guard in `RottingOranges` and watch
   which test fails. **A test suite you have never seen fail is not evidence of anything.**
5. Open the matching file in `../Java/interview-qa/` and answer its questions out loud.

Step 4 is the one people skip, and it is the one that converts reading into knowing.

---

## Where the interview material lives

Study material sits in the sibling repo: [`../Java/interview-qa/`](../../Java/interview-qa/)

Each phase here maps to files there. Phase 0's foundations connect to
`01-spring-core.md` (build and lifecycle), `14-testing.md` (the pyramid, ArchUnit) and
`17-coding-lru-and-graphs.md` (the katas in `kata/`).
