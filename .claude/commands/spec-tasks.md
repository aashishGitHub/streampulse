---
description: Turn a phase's design.md into an ordered, failing-test-first task list in tasks.md
argument-hint: <specs/NN-name | NN>
---

Write the task list for: $ARGUMENTS

1. Read `CLAUDE.md`, then `requirements.md` and `design.md` of that spec folder. If either is missing or
   still has `<placeholders>`, stop and say so.
2. Every row of the design's failure-mode table becomes at least one task, named after the test already
   listed in its last column. A row with no test name is a gap in the design: report it, do not invent a name.
3. Order the tasks the way the tests are written: architecture rules (ArchUnit) first, then acceptance, unit,
   slice, integration. Domain before adapters. Contracts (OpenAPI, AsyncAPI, schemas) before the code that
   serves them.
4. Format each line exactly: `- [ ] T<phase>-<nn> test \`<TestName>\` fails -> implement \`<Class>\``.
   Add the requirement ids it covers in parentheses. Leave every box unticked.
5. Add the closing tasks CLAUDE.md demands: verified output into `MILESTONES.md`, and an ADR for each real
   decision. Add mutation-check tasks for the one or two invariants whose failure would be silent.
6. Overwrite `tasks.md` only after showing me the list and getting a yes. Write no code.
