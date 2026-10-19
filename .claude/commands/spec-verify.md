---
description: Check every ticked task in a tasks.md against the code and the last test run; tick nothing unproven
argument-hint: <specs/NN-name | NN>
---

Verify the tasks of: $ARGUMENTS

For each line of `tasks.md`:

1. Find the test class and the implementation class it names (search the repo; do not trust the path).
   Missing either means NOT PROVEN.
2. Find the last test run for that module: `<module>/build/test-results/test/*.xml` (and `integrationTest`
   for `*IT` classes). Read the XML: the named test must be there with no failure and no skip.
   If the XML is older than the source file, or absent, the run is STALE: say so and print the exact command
   (`./gradlew :<module>:test`) for me to run. Do not run it yourself unless I ask.
3. A ticked task that is NOT PROVEN or STALE: report it, and do not leave it ticked silently (propose the edit).
   An unticked task that is fully proven: report it as "provable"; tick it only if I say so.
4. Never tick a task because the code looks right. Proof is a passing test result you have read.
5. CLAUDE.md: a tick belongs with passing output pasted into `MILESTONES.md`. Print the per-class counts you
   read from the XML so I can paste them; do not edit `MILESTONES.md` yourself.

Output a table: task | tests found | last run | verdict (PROVEN / STALE / NOT PROVEN).
