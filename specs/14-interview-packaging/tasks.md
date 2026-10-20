# Phase 14 — Interview packaging: Tasks

Each line: failing check first, then the content. Tick only with passing output in `MILESTONES.md`.

- [x] T14-01 requirements and design written
- [x] T14-02 `check-interview-pack.py` fails (no `docs/interview/`) → the check itself; mutations: a bogus
      path, a bogus number source, and a `MISSING:` claim about an existing file each make it fail
- [x] T14-03 per-area fact sheets gathered from code, ADRs and `MILESTONES.md` (research, not committed);
      claims the sheets flagged as unverified are marked `VERIFY` in the pack
- [x] T14-04 `06-numbers.md` passes the number rule: every quotable number with kind and source
- [x] T14-05 fourteen phase files (0 to 13), each with Beginner, Mid and Senior questions, pass the path
      rule and the level rule
- [x] T14-06 `01-the-story.md`, `05-what-broke.md`, `07-question-map.md`, `08-mock-interviews.md`, `README.md`
- [x] T14-07 `docs/script.md` and `docs/deep-dive.md` corrected against the audit list
- [x] T14-08 `make interview-check` target; output into `MILESTONES.md`; README phase table updated

## Not done (state here if cut)

- The mock interviews are written but have not been run with a person.
- No answer in the pack was checked by an external reviewer; the claims are checked against the repo only.
- `RESUME-HERE.md` still contains statements the audit found stale (flagged, not edited). The README
  architecture diagram is unchanged, with a note under it saying it is the target design, not the built system.
- Phase 7 was written without a research agent (it was stopped); see the Phase 14 entry in `MILESTONES.md`.
