# ADR-0022 — Read-only MCP tools for an AI agent: STDIO transport, Spring AI 1.1.8, tool output is data

**Status:** accepted · **Date:** 2026-10-13 · **Phase:** 13 · **Builds on** ADR-0017 (alerts), specs/05-query-api

## Context

"What is wrong with tenant X right now?" is two lookups (latest metrics from query-api, active alerts from
alert-engine) plus judgement. An AI agent can do the judgement if it can do the lookups. The Model Context
Protocol (MCP) is the contract Claude Code uses to call such tools. Two things make this more than plumbing:
the agent will read text that tenants wrote, and neither backend authenticates.

## Decision 1 — three read-only tools, no write tools

`get_live_metrics`, `list_active_alerts`, `explain_alert`. Each is marked `readOnlyHint=true`,
`destructiveHint=false`, `idempotentHint=true` in `tools/list`; descriptions are written for the model
(when to use, what it returns, what it cannot do).

**Why no write tools (acknowledge, silence, edit a rule).** A tool the agent can call is a tool a prompt
injection can call. With read-only tools the worst injected instruction yields a wrong answer; with write tools
it yields a changed system. There is also no write path to expose safely: the backends have no
authentication, so "who did this" would be unanswerable. A write tool would need, at minimum: authentication
of the caller, per-tenant authorisation, an audit record, a confirmation step a human sees, and idempotency.
None exists, so none is offered. Enforcement is structural, not a promise: ports have read-named methods only
(ArchUnit), the HTTP adapter cannot call `POST/PUT/DELETE/method(..)` (ArchUnit on the bytecode), and the
fake backend records the verb of every request in the tests.

## Decision 2 — Spring AI 1.1.8 (MCP SDK 0.18.3), not 2.0.x, not the raw SDK

Read from Maven Central metadata and POMs on 2026-10-13, not remembered:

| | Spring AI | Depends on | MCP Java SDK |
|---|---|---|---|
| `spring-ai-starter-mcp-server` 1.1.8 | latest 1.1.x | Spring Boot **3.5.15** | `io.modelcontextprotocol.sdk:mcp` 0.18.3 |
| `spring-ai-starter-mcp-server-webmvc` 2.0.1 | 2.0.x | Spring Boot **4.1.1** | `mcp` 2.0.0 |

This build is Spring Boot 3.5.3, so 2.0.x is out; 1.1.8 targets the 3.5 line. Artifact: `org.springframework.ai:spring-ai-starter-mcp-server`
(STDIO), BOM `org.springframework.ai:spring-ai-bom:1.1.8`. The tools are `@McpTool` methods (annotations from
`org.springaicommunity:mcp-annotations` 0.9.0, which the starter brings); Spring AI turns them into
`SyncToolSpecification` beans. The raw SDK fallback was not needed.

**Known mismatches, stated not hidden.** Spring AI 1.1.8 is built against Boot 3.5.15 (Spring Framework
6.2.19); this build resolves Boot 3.5.3 (6.2.8), and the catalog pins `jackson-databind` 2.19.0 where
`mcp-json-jackson2` was built against 2.20.1. Gradle resolved them without error and the protocol tests pass, but
that is evidence for the paths exercised, not a compatibility guarantee. Upgrade Boot with Spring AI rather than
separately. Also: the 0.18.3 server answered a `2025-06-18` initialize with protocol version `2024-11-05`
(logged by the SDK: "Client requested unsupported protocol version"); Claude Code accepting that is **not
verified here**.

## Decision 3 — STDIO transport

| | STDIO | HTTP (streamable HTTP / SSE) |
|---|---|---|
| Launch from `.mcp.json` | `command` + `args`; Claude Code owns the process lifecycle | needs a running process and a URL |
| Auth surface | none: the child inherits the user's environment, nothing listens on a port | a listening port and no authentication: anyone on the host could call tools |
| Cost | stdout is the protocol; nothing else may print there | none of that, but the process must be started and kept up |
| Fits a shared remote server | no | yes |

STDIO wins for a local developer tool whose backends are also local and unauthenticated: it adds no network
listener. If this were shared, HTTP behind authentication would be the choice, and the tools would not change
(only the starter and the transport properties).

**stdout discipline** (the one thing STDIO makes fragile). `spring.main.banner-mode=off`,
`spring.main.web-application-type=none`, `spring.main.log-startup-info=false`, and a `logback-spring.xml`
whose only appender targets `System.err`. Verified two ways: `StdioProtocolIT` launches the server and parses the
raw first lines of stdout as JSON-RPC; and by hand, with `SPRING_MAIN_BANNER_MODE=console` the Spring banner
appeared on stdout before the first JSON-RPC line, which is exactly the failure that test would catch.
`spring.ai.mcp.server.stdio=true` is required (default `false`, read from the starter's configuration metadata).

## Decision 4 — hexagonal layout, as everywhere else

`domain` and `application` (use cases and the two ports) import no framework, no protocol library, no JSON
library and no HTTP client (ArchUnit). `adapter.in.mcp` is the only class set that knows MCP; `adapter.out.http`
is the only one that knows HTTP. The composition root is `config`. Time comes from an injected `Clock`.
Every use case is tested with fake ports; the HTTP clients against an in-JVM `HttpServer`; the protocol against
the real server over real STDIO in a child JVM, with the MCP SDK's own client.

## Decision 5 — the prompt-injection model

**Assets.** The agent's instructions (what it will do next) and other tenants' data.
**Attacker.** A tenant, or anyone who can influence what a tenant's rule or metric labels contain. They control:
alert `summary` text, `ruleId`, the `series` label (titleId and region come from event payloads), and a
backend's error text.
**Attack.** Plant "Ignore previous instructions and ..." in one of those, hoping the agent reads a tool result
and obeys it.

Controls, each with a test:

1. **Structure over prose.** Results are JSON objects with typed fields, plus a short `summary` generated by
   this server from numbers, enums and validated ids only. No backend free text is ever concatenated into it.
   (`PromptInjectionTest`: the payload is absent from `summary`.)
2. **A field named for what it is.** All tenant-authored text sits under `untrusted`, and every result carries
   a `dataNotice` saying so. A client can ignore or quarantine that subtree.
3. **Sanitised and capped.** Control characters, bidi and zero-width formatting characters and Unicode tag
   characters are removed; line breaks collapsed to a space; length capped at 200 chars with an explicit
   `[truncated]` marker. (`UntrustedTextTest`; mutation check below.)
4. **Identifier slots stay identifiers.** `alertId`, `ruleId`, `suppressedBy`, titles and regions are
   emitted as trusted fields only if they match a strict shape; otherwise they are demoted into `untrusted`.
   A hostile series label is not a series. (`PromptInjectionTest.hostileSeriesLabel`, `hostileIdentifiers`.)
5. **Backend error text is never forwarded.** Failures are fixed phrases: "alert-engine unreachable",
   "query-api rejected the request (status 400)". (`HttpAlertEngineClientTest.clientError`.)
6. **Input is validated before any call.** Tenant ids by the platform's `TenantId` rule, other ids by a bounded
   pattern; an invalid value is named but never echoed, and no request reaches a backend.
7. **Tenant isolation.** Backends are asked about the requested tenant only; any item whose tenant differs is
   dropped before counting or rendering, including root causes. (`ActiveAlertsServiceTest`,
   `ExplainAlertServiceTest`, `McpToolsTest.mixedTenantBackend`, `McpProtocolIT`.)
8. **Bounds.** 3 s per backend call (whole exchange, including a slow body), 256 KiB body, 50 alerts, 100 metric
   rows, 20 recent windows; cuts are announced in `truncation`. (Targets, not measured.)
9. **No secrets.** There are none in this module's configuration (URLs only). Logs record exception class names
   and counts, never tenant text or backend bodies.

**Mutation checks** (run on 2026-10-13): removing the tenant filter turned 4 unit tests and 1 integration test
red; removing the length cap turned 6 unit tests and 1 integration test red; both restored and green.

**NOT mitigated.**
- A client that pastes an `untrusted` value into its prompt as an instruction is exploitable whatever this
  server does. The controls shrink and label the surface; they do not remove the model's susceptibility.
- No authentication on the backends, and none on this server: whoever can launch the process can ask about any
  tenant. `tenantId` is an assertion by the caller.
- query-api's range query returns no tenant field, so on that path isolation rests on the backend honouring the
  path parameter. Only the overview and the alert list carry a tenant that can be re-checked here.
- A tenant can still mislead the agent with values that are valid identifiers or plausible numbers.
- The 200-char cap still leaves room for a short instruction. Capping bounds the damage; it does not decide
  intent.

## What the backends do not expose (and the tools say so)

`explain_alert` cannot return a rule's threshold, comparison, window or the observed value: alert-engine's
`GET /v1/alerts` carries `ruleId`, `severity`, `series`, `state`, `summary` and timestamps only (the domain
`Alert` has `observedValue` and `threshold`, `AlertView` does not forward them). There is no get-by-id and no
resolved alerts, so an unknown or resolved id is reported as "not found". query-api has no "latest across
titles" call; without `titleId` the tool scans the last 15 minutes of two metrics and keeps the newest window per
series, and then has no `windowEnd` (age is measured from `windowStart` and says so). Extending alert-engine's
view with threshold and observed value would be the right fix and is a separate change to another module.

## Relation to the spec-driven workflow

This phase followed it: `specs/13-mcp/` (EARS requirements without technology, design with a failure-mode table
mapping each failure to its proving test, tasks as "test fails -> implement"), then tests, then code. The repo
now carries the same discipline for the agent itself, as plain Markdown prompts in `.claude/commands/`:
`/spec-new` scaffolds a spec from the template, `/spec-tasks` turns a design into failing-test-first tasks, and
`/spec-verify` checks ticked tasks against test results and refuses to tick what it cannot prove. They encode
CLAUDE.md so the agent starts from the repo's rules rather than from habit.

## Measuring value (a claim, NOT measured)

The expected benefit is shorter cycle time for "what is wrong with tenant X" and fewer escaped defects from
specs and tests written before code. Neither has been measured. To measure: record time from alert to first
correct hypothesis with and without the tools over a set of staged incidents (BS-scenarios are fictional design
scenarios, label them so), and count defects found after merge per phase for spec-driven versus not. Until that
is done this section is a hypothesis.

## Optional pre-commit hook (proposed, NOT installed)

```sh
#!/bin/sh
# .git/hooks/pre-commit
./gradlew spotlessCheck test
```

Caveats: it runs the whole repo's unit tests on every commit (slow; `make check` is the CI equivalent), and
hooks are not versioned, so it protects only the machine that installs it.

## Not built

The optional LLM incident summarizer (a model writing a narrative from the tool output). Not built: it would add
a second injection hop (tool output into a model into a human-facing text) and needs its own evaluation.

## Consequences

**Positive**: the agent can answer the on-call question in one turn with data that is bounded, tenant-scoped and
labelled; the worst injected instruction cannot change a system; the boundaries are enforced by tests.
**Negative**: STDIO ties the server to one local user; backend and server are unauthenticated; the version
combination (Spring AI 1.1.8 on Boot 3.5.3) is untested beyond this module; Claude Code itself has not been
connected to the server here (see the module's verification notes).
