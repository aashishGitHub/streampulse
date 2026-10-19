# Phase 14 — Interview packaging: Requirements

**Status:** draft · **Depends on:** every built phase (0–7, 9 in progress, 10, 12, 13) · **Delivers:** a
study and practice pack, grounded in this repo, that prepares for a senior Java backend / platform
interview at beginner, mid and senior depth.

## Why (3 lines max)

The code holds the evidence, but an interview needs it as answers: what was built, why, what it
costs, what broke when it was run. The Phase 3 docs (`docs/script.md`, `docs/deep-dive.md`) predate
most of the system and now contradict it in places.

## User stories

- As a candidate, I want questions at three depths with the points a strong answer must hit, each
  tied to a file I can open, so that I can answer from what I built rather than from memory.
- As a candidate, I want every number I might quote listed with where it came from and whether it
  was measured or only targeted, so that I never present a target as a result.
- As a candidate, I want the stories of what broke when things were actually run, told accurately,
  so that I can answer "tell me about a hard problem" with something real.
- As a candidate, I want the old docs corrected, so that no document I read contradicts the code.

## Requirements (EARS)

- **REQ-IP-01** WHEN the pack names a file or directory of this repository THE PACK SHALL name one
  that exists.
- **REQ-IP-02** WHEN the pack states a number about this system THE PACK SHALL label it measured or
  target and, if measured, name the `MILESTONES.md` section it comes from.
- **REQ-IP-03** WHEN the pack describes a design scenario labelled BS-01 to BS-10 THE PACK SHALL
  describe it as a design exercise, never as an incident that happened.
- **REQ-IP-04** WHEN a question is listed THE PACK SHALL give its depth (beginner, mid, senior) and
  the points a strong answer contains.
- **REQ-IP-05** WHEN the pack tells a "what broke" story THE PACK SHALL say it happened in local
  runs of this project, not in production.
- **REQ-IP-06** WHEN an older interview document states something the code or `MILESTONES.md` now
  contradicts THE SYSTEM SHALL correct that statement or mark it as superseded.
- **REQ-IP-07** WHEN a topic was not built or not verified THE PACK SHALL say so where the topic is
  discussed, not only in a closing list.

## Non-functional

- Every measured number is from one developer machine running Docker Compose. The pack says so next
  to the numbers.

## Out of scope

- Algorithm and coding-round practice (kept in `../Java/interview-qa/17-coding-lru-and-graphs.md`).
- Editing the sibling question bank `../Java/interview-qa/` or `~/Downloads/Question bank.md`; the
  pack links to them.
- Front-end architect preparation beyond a short section on the dashboard.
