---
description: Scaffold specs/NN-name/{requirements,design,tasks}.md from specs/_template and the CLAUDE.md rules
argument-hint: <short-name> [one-line goal]
---

Start a new phase spec for: $ARGUMENTS

1. Read `CLAUDE.md` (the "Process" and "Honesty rules" sections) and `specs/_template/*.md`.
2. List `specs/` and pick the next free `NN` (do not reuse a number; a gap is fine). Name the folder
   `specs/NN-<short-name>` in kebab-case.
3. Before writing anything, ask me the questions you need answered: who uses it, which phase it depends on,
   what it must NOT do. Wait for my answers. Do not guess.
4. Create the three files from the template, nothing else:
   - `requirements.md`: user stories and EARS criteria (`WHEN ... THE SYSTEM SHALL ...`) numbered
     `REQ-XX-nn`. **Name no technology** (no framework, protocol, product). Numbers are targets, labelled so.
   - `design.md`: one ASCII or Mermaid flow of control and data, then a failure-mode table whose last column
     names the test that proves each row, then links to ADRs.
   - `tasks.md`: every line is `test <Name> fails -> implement <Class>`, all unticked.
5. Write no production code, no tests and no ADR in this step. Do not touch `MILESTONES.md`.
6. End with the list of open questions and `VERIFY:` items you could not settle, and the path of the folder.
