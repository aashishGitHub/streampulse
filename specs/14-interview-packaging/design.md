# Phase 14 — Interview packaging: Design

## Flow of control and data

```
  code + ADRs + specs + MILESTONES.md            (the only sources of truth)
        │  per-area fact sheets (read-only research, not committed)
        v
  docs/interview/
    README.md              how to use the pack; the beginner -> mid -> senior ladder
    01-the-story.md        2-minute and 10-minute walkthroughs, picture, honest status table
    phases/phase-NN-*.md   ONE FILE PER PHASE (0..13): status, code map, then
                           Beginner (B1..), Mid (M1..), Senior (S1..) questions, each with the
                           points a strong answer contains and a file to open; "say this honestly"
    05-what-broke.md       stories found only by running the system (local runs, told as such)
    06-numbers.md          every quotable number: value | kind | source
    07-question-map.md     ../Java/interview-qa notes mapped onto the phases
    08-mock-interviews.md  timed sets, follow-ups, scoring guide
        │
        v
  scripts/check-interview-pack.py   `make interview-check`
        │  - every backticked repo path and relative link exists (REQ-IP-01)
        │  - `MISSING:path` asserts a file is absent, and fails if it appears
        │  - every row in 06-numbers.md is measured, target or assumption; a measured row names a real
        │    MILESTONES.md heading (REQ-IP-02)
        │  - every phase file has B, M and S question headings (REQ-IP-04)
        v
  docs/script.md, docs/deep-dive.md corrected in place against the audit (REQ-IP-06)
```

Why per phase and not per depth: the candidate prepares phase by phase, and an interviewer probes one
system area at a time. The depth ladder is a study order (README), not a file split.

## What the check can and cannot prove

| Requirement | Checked by | Limit |
|---|---|---|
| REQ-IP-01 paths exist | `check-interview-pack.py` resolves every backticked repo path and link | Class names in prose are not resolved |
| REQ-IP-02 numbers sourced | every row of the numbers table has a kind; a measured row's source matches a MILESTONES heading | Numbers written in other files are not parsed |
| REQ-IP-04 questions at three depths | the check requires `### B1.`, `### M1.`, `### S1.` style headings per phase file | It does not judge their quality |
| REQ-IP-03, 05, 07 | review | Not mechanical |
| REQ-IP-06 | the audit's correction list, each row applied or answered | Review |

## Failure modes

| Failure | Behaviour | Test that proves it |
|---|---|---|
| A referenced file is renamed later | check fails, naming file and line | mutation: a bogus path makes it fail (run and recorded) |
| A "missing" claim goes stale | check fails when the file appears | mutation: `MISSING:` on an existing file fails (run and recorded) |
| A measured number cites a heading that does not exist | check fails | mutation: a bogus source fails (run and recorded) |
| A target is labelled measured | not detectable mechanically | review against `MILESTONES.md` |
| An answer overclaims | not detectable mechanically | the "say this honestly" sections and review |

## Decisions

No ADR: this phase changes no runtime behaviour.
