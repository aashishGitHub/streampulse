# Mock interviews

Four timed sets. Run them with a person, or tell me "run mock set 2" and I will play the interviewer one
question at a time, wait for your answer, then critique it against the code.

**Scoring each answer (0 to 3)**

| Score | It sounds like |
|---|---|
| 0 | A definition with nothing from this project, or a claim the code contradicts. |
| 1 | Correct, but generic. Could have been said by anyone who read about it. |
| 2 | Correct and specific: names the file, the number, or the test, and says what it costs. |
| 3 | Everything in 2, plus the limit: what was not measured or not built, stated without being asked. |

The jump from 2 to 3 is the point of this pack.

---

## Set 1 — System design, 45 minutes: "Design a real-time telemetry platform"

Use [`01-the-story.md`](01-the-story.md) and `docs/script.md` as the spine. Timings are a guide.

| Time | Interviewer asks | A strong answer reaches |
|---|---|---|
| 0:00 | "10 million viewers, one heartbeat per 10 seconds. Where do you start?" | Numbers first, labelled as assumptions: about 1 M events/s, then the aggregation reduction. |
| 0:05 | "Draw the pipeline." | Ingest, log, windowed aggregation, sink, query; why the sink is cheap and the front door is not. |
| 0:12 | "How do you avoid losing or double-counting events?" | Idempotency key, producer idempotence, exactly-once boundary, idempotent upsert, ack after write. |
| 0:20 | "A device is offline for an hour, then sends everything. What happens?" | Event time, grace, late data dropped; the cost of a bigger grace. |
| 0:27 | "What happens if your stream processor restarts?" | State restore; then, unprompted, the finding: on-time events dropped after a long outage. |
| 0:35 | "How do you know it works?" | Accounting check, mutation checks, what the happy-path run missed. |
| 0:40 | "What is the weakest part?" | Alert state in memory, no auth on reads, single broker, observability never run. |

**Follow-ups to prepare:** "Why not WebFlux for ingest?", "What is your p99?" (with conditions), "How would
you shard a hot tenant?", "What changes at 10x?"

---

## Set 2 — Reliability deep dive, 45 minutes

| Interviewer asks | Points to the phase |
|---|---|
| "Redis is down. What does ingest do?" | [2](phases/phase-02-ingest-gateway.md) S1, including the quota side effect. |
| "Your breaker is closed but you see 503s. Why?" | [2](phases/phase-02-ingest-gateway.md) S5. |
| "Tell me about a bug your tests could not catch." | [05-what-broke.md](05-what-broke.md) stories 3, 6, 8. |
| "How do you know a test can fail?" | Mutation habit; stories 2 and 7. |
| "Your load generator says p99 is 39 seconds. What do you do?" | Story 10; validate against raw rows. |
| "How do you test a fault-injection setup?" | Story 8; prove the fault bites. |
| "The connector has been down for an hour." | [6](phases/phase-06-control-plane-cdc-alerts.md) S2. |
| "What is still unproven here?" | The 503 mechanism, pinned threads, the crash fix, the observability rules. |

**Trap questions.** "So it's production-ready?" (No, and here is the list.) "Exactly-once means no loss,
right?" (No: exactly-once is a Kafka statement; story 3 is the counter-example.) "It handles 45k events per
second?" (Only with every condition from [06-numbers.md](06-numbers.md).)

---

## Set 3 — Fundamentals rapid-fire, 30 minutes

Ask one Beginner and one Mid question from each of phases 2 to 6, about 90 seconds each. Pass mark: every
Beginner answer under 45 seconds and naming a file, and at least four of five Mid answers scoring 2.

Quick bank, one per phase:

| Phase | Beginner | Mid |
|---|---|---|
| 2 | B3 429 versus 503 | M2 why `SET NX EX` |
| 3 | B2 window plus grace | M1 suppress and its cost |
| 4 | B3 why manual ack | M1 ack order |
| 5 | B1 SSE and `Last-Event-ID` | M3 the idle-poll bug |
| 6 | B2 outbox | M1 why `MANDATORY` |

---

## Set 4 — Ownership and judgement, 30 minutes

Behavioural, answered from [05-what-broke.md](05-what-broke.md). Use the five-step shape: context, symptom
with a number, real cause, fix and how you know it works, one-sentence lesson.

1. "Tell me about a time you were wrong." (story 4)
2. "Tell me about a hard bug." (story 1 or 3)
3. "Tell me about a time you found a problem in your own work." (story 11, or the loadgen freshness figure)
4. "How do you handle a design you cannot fully verify?" (phases 8 and 11, framed honestly)
5. "What would you do differently?" (write the accounting check before the processor; add a late-drop metric)

**What interviewers listen for:** that you name the limit of your own evidence, and that "how do you know?"
has a concrete answer every time.

---

## After a mock

Write down, for each answer scored 0 or 1, the phase file and question number to reread. Repeat that
set in two days. Aim for no 0s and mostly 2s before an interview, with a 3 on the four senior questions
listed in the [README](README.md).
