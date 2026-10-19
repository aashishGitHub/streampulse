# Phase 13 — Agent tooling (read-only operations tools): Requirements

**Status:** draft · **Depends on:** Phase 5 (query-api), Phase 6B (alert-engine) · **Delivers:** three
read-only tools that let an AI agent answer "what is wrong with tenant X right now?", and the repo-level
agent workflow (slash commands) that keeps the specs in front of the agent.

## Why (3 lines max)

On-call questions are lookups across two services (latest metrics, active alerts). An agent can do them
in one turn if it has tools, but every tool is an injection surface: tenants control the text the agent
will read. So the tools are read-only, bounded, tenant-scoped, and label what they cannot vouch for.

## Vocabulary

- **Tool**: one named operation the agent can call, with typed arguments and a typed result.
- **Backend**: the metrics service or the alert service this system reads from.
- **Untrusted text**: free text whose author is a tenant (rule summaries, rule names, series labels).
- **Trusted field**: a field this system produces or validates itself (counts, states, timestamps,
  identifiers matching a strict shape, the plain-text summary this system writes).

## Requirements (EARS, no technology named)

### Tools and what they return

- **REQ-MCP-01** WHEN a client asks for the list of tools, THE SYSTEM SHALL offer exactly three: live
  metrics, active alerts, explain alert, and SHALL mark each as read-only. `[surface]`
- **REQ-MCP-02** WHEN live metrics are requested for a tenant, THE SYSTEM SHALL return, for each series,
  the latest value of concurrent viewers and error rate with the window it covers, its sample count and
  its age. `[metrics]`
- **REQ-MCP-03** WHEN live metrics are requested for one title, THE SYSTEM SHALL return only that
  title's series. `[metrics]`
- **REQ-MCP-04** WHEN active alerts are requested for a tenant, THE SYSTEM SHALL return firing alerts and
  suppressed alerts in separate lists, and for each suppressed alert the identifier of its root cause.
  `[alerts]`
- **REQ-MCP-05** WHEN an alert is explained, THE SYSTEM SHALL return the alert, its root cause if it is
  suppressed, the recent metric values of the alert's series, and a short plain-text summary. `[explain]`
- **REQ-MCP-06** WHEN a detail the backends do not provide (for example a rule's threshold) cannot be
  returned, THE SYSTEM SHALL say so explicitly instead of omitting it silently or inventing a value.
  `[honesty]`
- **REQ-MCP-07** WHEN the alert to explain is not among the tenant's active alerts, THE SYSTEM SHALL say
  it was not found, and that resolved alerts are not retained by the backend, rather than fail. `[explain]`

### Read-only

- **REQ-MCP-08** THE SYSTEM SHALL NOT offer any tool that creates, changes or deletes anything, and SHALL
  NOT issue any request to a backend other than a read. `[read-only]`

### Input validation and bounds

- **REQ-MCP-09** WHEN a tenant identifier does not match the platform's tenant identifier rule, or any
  identifier is empty, over its length bound, or contains characters outside its allowed set, THE
  SYSTEM SHALL reject the call with a message naming the argument, before any backend is contacted.
  `[validation]`
- **REQ-MCP-10** WHEN a backend does not answer within its time limit, THE SYSTEM SHALL stop waiting and
  report which backend timed out. `[timeout]`
- **REQ-MCP-11** WHEN a backend answers with a body larger than the size limit, malformed content, or an
  error status, THE SYSTEM SHALL report which backend failed and in what category, and SHALL NOT pass
  the backend's own error text to the client. `[errors]`
- **REQ-MCP-12** WHEN a backend cannot be reached, THE SYSTEM SHALL return an error result that names the
  backend as unreachable, and SHALL NOT fabricate or substitute data. `[errors]`
- **REQ-MCP-13** WHEN the explanation's alert is found but only the metrics backend fails, THE SYSTEM
  SHALL still return the alert and mark the metrics part unavailable with the reason. `[degradation]`
- **REQ-MCP-14** WHEN a result would exceed its item limit, THE SYSTEM SHALL cut it at the limit and state
  in the result that it was cut, how many items were left out. `[bounds]`

### Security

- **REQ-MCP-15** THE SYSTEM SHALL return free text authored by a tenant only inside a field named for
  being untrusted, one separate from every trusted field, never mixed into the plain-text summary.
  `[injection]`
- **REQ-MCP-16** WHEN untrusted text is returned, THE SYSTEM SHALL remove control and invisible
  formatting characters, collapse line breaks, and cut it to a fixed length with an explicit marker.
  `[injection]`
- **REQ-MCP-17** WHEN an identifier-like value from a backend does not match its allowed shape, THE
  SYSTEM SHALL treat it as untrusted text, not as an identifier. `[injection]`
- **REQ-MCP-18** THE SYSTEM SHALL ask the backends only about the tenant named in the call, and WHEN a
  backend returns an item of another tenant, SHALL drop it and SHALL NOT include it in any result or in
  a count. `[isolation]`
- **REQ-MCP-19** THE SYSTEM SHALL NOT write secrets, free text of a tenant, or full backend bodies to its
  log, and SHALL NOT include any in a result other than via the untrusted field. `[secrets]`
- **REQ-MCP-20** THE SYSTEM SHALL write nothing but protocol messages to its protocol channel (no banner,
  no log lines). `[transport]`

### Agent workflow (repository level)

- **REQ-MCP-21** WHEN a developer starts a new phase with the scaffold command, THE SYSTEM SHALL create
  the three spec files from the template, following the repository's working agreements. `[workflow]`
- **REQ-MCP-22** WHEN a developer asks to verify a task list, THE SYSTEM SHALL tick nothing that is not
  proven by the code and a test run it has seen. `[workflow]`

## Non-functional

- Per-call time limit per backend: 3 s; body limit 256 KiB; alert list limit 50; metric row limit 100;
  untrusted text limit 200 characters. All are **targets** until used under real load.
- Startup to ready under 5 s on a laptop (target, not measured).

## Out of scope

- Any tool that writes (acknowledge, silence, edit a rule): rejected on purpose, see ADR-0022.
- Authentication of the tool server or of the backends (neither exists yet: a known gap).
- An LLM-written incident summary: **not built**.
- Streaming (live subscription) of metrics or alerts.
