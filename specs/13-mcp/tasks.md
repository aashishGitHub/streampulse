# Phase 13 — Agent tooling: Tasks

Each line is `test <Name> fails → implement <Class>`. Tick it only with passing output pasted
into MILESTONES.md (the coordinator records it; this module's agent does not edit that file).

Honest note on order: the specs and every test file were written before any production class, but the first
run was the first compile, so "fails first" was observed as a compile failure, not test by test. What was
observed test by test is the two mutation checks (tenant filter, length cap) in ADR-0022. The wiring test
failed three times on real wiring facts before it passed (no `ObjectMapper` bean without a web stack; Spring AI
registers two `List<SyncToolSpecification>` beans; and I first picked the wrong one: the annotated tools are in
`toolSpecs`, `syncTools` is the ToolCallback path).

- [x] T13-01 spec, design, ADR-0022 written before code; Spring AI / MCP SDK versions verified against the jars
- [x] T13-02 `McpArchitectureRulesTest` (domain framework-free, GET-only HTTP adapter, constructor injection) fails → package layout `domain`, `application`, `adapter`, `config`
- [x] T13-03 `UntrustedTextTest` (control chars, bidi/invisible, newlines, cap + marker, null) fails → `UntrustedText`
- [x] T13-04 `SeriesTest` (3 parts, wildcard, hostile label) fails → `Series`, `SafeId`
- [x] T13-05 `ToolInputsTest` (tenant rule, bounded ids, hostile strings, no echo of raw input) fails → `ToolInputs`, `InvalidToolInput`
- [x] T13-06 `ActiveAlertsServiceTest` (split FIRING/SUPPRESSED, tenant filter, truncation, ordering) fails → `ActiveAlertsService`
- [x] T13-07 `LiveMetricsServiceTest` (latest per series, age basis, title filter, tenant check, degraded) fails → `LiveMetricsService`
- [x] T13-08 `ExplainAlertServiceTest` (suppressed + root, not found, other tenant, metrics down, wildcard series, no rule parameters) fails → `ExplainAlertService`
- [x] T13-09 `HttpAlertEngineClientTest`, `HttpQueryApiClientTest` (200, 4xx problem+json, 5xx, timeout, malformed, wrong shape, oversized, unreachable, GET only) fail → `HttpAlertEngineClient`, `HttpQueryApiClient`, `JsonGetter`
- [x] T13-10 `PromptInjectionTest` (hostile summary confined and capped, hostile series, hostile ruleId) fails → `ToolViews`
- [x] T13-11 `McpToolsTest` (invalid input never reaches a backend, mixed-tenant backend, backend down, bounded size) fails → `McpTools`
- [x] T13-12 `McpServerWiringTest` (context starts, three tool specs, no Docker) fails → `McpServerConfig`, `McpProperties`
- [x] T13-13 `McpProtocolIT` (initialize, tools/list exactly three read-only, tools/call against fakes) fails → STDIO server entry point
- [x] T13-14 `StdioProtocolIT.stdoutCarriesOnlyProtocolMessages` fails → `application.yml`, `logback-spring.xml` (logs to stderr, banner off)
- [x] T13-15 mutation (a): remove the tenant filter → isolation tests red; restore → green
- [x] T13-16 mutation (b): remove the free-text cap → injection test red; restore → green
- [x] T13-17 `.mcp.json`, `.claude/commands/{spec-new,spec-tasks,spec-verify}.md` (files written; `claude mcp get streampulse` parsed `.mcp.json` and reported "Pending approval"; the server was NOT connected from Claude Code itself)
- [ ] T13-18 verified output into `MILESTONES.md` (left to the coordinator)
- [ ] T13-19 (not built) LLM incident summarizer
