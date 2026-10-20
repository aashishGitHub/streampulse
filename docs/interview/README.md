# Interview pack — StreamPulse

Prepares for a **senior Java backend / platform** interview, using this repository as the evidence.
Every answer points at a file you can open. Every number says whether it was measured.

Written 2026-10-19 against the code at that date. `scripts/check-interview-pack.py` (run
`make interview-check`) fails if a path or a `path:line` reference stops existing, a code example no longer
matches the file it quotes, an answer is not a paragraph of sentences, or a number loses its source.

## The three rules

1. **Say what you built, not what exists nearby.** If a phase says "not built" or "never run", say that
   first. An interviewer who finds the gap themselves trusts the rest less; one who hears you name it trusts
   it more.
2. **Quote numbers only from [`06-numbers.md`](06-numbers.md)**, with the conditions: one laptop, one broker,
   one run, load generator on the same machine. Never "it handles 45k per second".
3. **The scenarios BS-01 to BS-10 are design exercises, not incidents.** The things that actually broke are
   in [`05-what-broke.md`](05-what-broke.md), and they broke in *local runs*, not production.

## Where to start

| You have | Do this |
|---|---|
| 10 minutes | Read [`01-the-story.md`](01-the-story.md), say the 2-minute version out loud. |
| 1 hour | Add [`05-what-broke.md`](05-what-broke.md): pick 3 stories and practise telling each in 2 minutes. |
| A week | Follow the ladder below, one phase per sitting. |
| A mock | [`08-mock-interviews.md`](08-mock-interviews.md), or ask me: "mock me on phase 3 at senior level". |

## The phases

Each phase file has the same shape: status, code map, then **Beginner (B), Mid (M), Senior (S)**
questions, then "say this honestly". Every question is laid out the same way:

- **Answer:** a model answer in complete sentences that you can read, then say in your own words.
- **Example:** a short snippet of real code with its `path:first-last` lines. `make interview-check` fails if
  the snippet does not match those lines, so the code on the page is always the code in the repository.
- **Code:** the files and line numbers to open.
- **If they push:** the likely follow-up question and a sentence-length answer.

| Phase | File | What is true about it |
|---|---|---|
| 0 Foundation | [phase-00](phases/phase-00-foundation.md) | built; **CI runs on GitHub and has failed on every run** (a platform-dependent test, story 12) |
| 1 Contracts | [phase-01](phases/phase-01-contracts.md) | built; **no schema registry** despite two notes saying one was coming |
| 2 Ingest gateway | [phase-02](phases/phase-02-ingest-gateway.md) | built and measured on one machine |
| 3 Stream processing | [phase-03](phases/phase-03-stream-processing.md) | built; **crash test fails**, unfixed |
| 4 Sink and storage | [phase-04](phases/phase-04-sink-and-storage.md) | built; DynamoDB and S3 sinks not built |
| 5 Query API | [phase-05](phases/phase-05-query-api.md) | built; no authentication |
| 6 Control plane, CDC, alerts | [phase-06](phases/phase-06-control-plane-cdc-alerts.md) | built; alert state lost on restart |
| 7 React dashboard | [phase-07](phases/phase-07-react-dashboard.md) | built; tests only, never run against real backends |
| 8 Observability | [phase-08](phases/phase-08-observability.md) | **written, never run** |
| 9 Resilience and load | [phase-09](phases/phase-09-resilience-and-load.md) | partly done; the most useful results |
| 10 AWS and Terraform | [phase-10](phases/phase-10-aws-and-terraform.md) | emulators only |
| 11 Containers and Kubernetes | [phase-11](phases/phase-11-containers.md) | **only a Dockerfile** |
| 12 Activity feed | [phase-12](phases/phase-12-activity-feed.md) | built; no real producer |
| 13 MCP server | [phase-13](phases/phase-13-mcp-server.md) | built; read-only by construction |

## The ladder: beginner, then mid, then senior

Do **one level across all phases before the next**; the questions at each level build on each other.

**Pass 1: Beginner (B questions, all phases).** Goal: crisp definitions plus the file where this project uses
the idea. You pass a phase when you can answer each B question in under 45 seconds and name the file.
Suggested order, because each leans on the one before: 2, 3, 4, 5, 1, 0, 6, 12, 7, 13, 10, 9, 8, 11.

**Pass 2: Mid (M questions).** Goal: explain *why*, and what the alternative costs, with a test or an ADR as
evidence. Start with the phases the role will probe hardest: 2, 3, 4, 6, then 9, 5, 12.

**Pass 3: Senior (S questions).** Goal: trade-offs, failure modes, and what you would do next, with measured
evidence where it exists and a clear "not measured" where it does not. Rehearse these aloud. The four that
carry the interview:

1. Phase 3 S4, the processor crash, and what you would do about it.
2. Phase 2 S2 and S5, Redis down and the real saturation point.
3. Phase 6 S1 and S2, why CDC over polling and what happens when the connector is down.
4. Phase 9 S3 and S4, how you would build a load-test methodology you trust.

**Pass 4: Out loud.** Run [`08-mock-interviews.md`](08-mock-interviews.md) with a person or with me, and
record yourself telling two stories from [`05-what-broke.md`](05-what-broke.md).

## Also in this pack

- [`01-the-story.md`](01-the-story.md): 2-minute and 10-minute walkthroughs, the picture, and an honest
  status table.
- [`05-what-broke.md`](05-what-broke.md): eleven stories of things found only by running the system.
- [`06-numbers.md`](06-numbers.md): every quotable number, with its source or its label.
- [`07-question-map.md`](07-question-map.md): your sibling Q&A files mapped onto these phases.
- [`08-mock-interviews.md`](08-mock-interviews.md): timed sets with follow-ups and a scoring guide.

## What this pack does not do

- It does not teach algorithms; those live in `../Java/interview-qa/17-coding-lru-and-graphs.md`.
- It is not experience you do not have. Phase 11 (Kubernetes) and parts of phases 8 and 10 are design
  thinking, labelled as such. Where an answer would need production experience, the sibling files have
  `> Your story:` placeholders for yours.
- It does not replace `docs/script.md` and `docs/deep-dive.md`, which are the longer spoken answer and the
  three-depth deep dive; those were corrected against the current code at the same time as this pack.
