# Phase 13 — MCP server and AI tooling

**Status:** core complete. 87 tests, 0 failures. The server is the same one registered in this project's
`.mcp.json`, exposing three **read-only** tools to an agent. Not built: an LLM incident summariser. Not
mitigated: a client that pastes the server's untrusted text into its own prompt, and any authentication.

**One line to say first:** "An MCP server that lets an agent read alerts and live metrics, with no write
tools by construction, and with everything a tenant can write treated as untrusted data."

## Code map

| Thing | Where |
|---|---|
| The three tools | `services/mcp-server/src/main/java/com/streampulse/mcp/adapter/in/mcp/McpTools.java` |
| Result shaping and the untrusted subtree | `services/mcp-server/src/main/java/com/streampulse/mcp/adapter/in/mcp/ToolViews.java` |
| Sanitising tenant-written text | `services/mcp-server/src/main/java/com/streampulse/mcp/domain/UntrustedText.java` |
| Wiring | `services/mcp-server/src/main/java/com/streampulse/mcp/config/McpServerConfig.java` |
| Structural "read-only" rules | `services/mcp-server/src/test/java/com/streampulse/mcp/McpArchitectureRulesTest.java` |
| Prompt-injection tests | `services/mcp-server/src/test/java/com/streampulse/mcp/adapter/in/mcp/PromptInjectionTest.java` |
| STDIO protocol test | `services/mcp-server/src/test/java/com/streampulse/mcp/StdioProtocolIT.java` |
| How it is launched | `.mcp.json` |
| Decision | `docs/adr/0022-mcp-read-only-agent-tools.md` |

---

## Beginner

### B1. What is MCP?

**A strong answer contains**
- The Model Context Protocol: a standard way for an agent to discover and call tools and read resources from
  a server. A client lists the server's tools, then calls them with arguments.
- Here the server offers `list_active_alerts`, `explain_alert` and `get_live_metrics`.

**Open** `services/mcp-server/src/main/java/com/streampulse/mcp/adapter/in/mcp/McpTools.java`

### B2. Why only read-only tools?

**A strong answer contains**
- "A tool the agent can call is a tool a prompt injection can call." With no write tools, the worst a
  hijacked agent can do through this server is read data.
- Each tool is annotated read-only and idempotent, and the code structure enforces it (S1).

**Open** `docs/adr/0022-mcp-read-only-agent-tools.md`

### B3. Why use standard input and output as the transport?

**A strong answer contains**
- No listening port, so no unauthenticated network surface, and the client owns the process lifecycle.
- Because stdout *is* the protocol, banners and logs must go to stderr; a test guards that stdout carries
  only protocol messages.

**Open** `services/mcp-server/src/test/java/com/streampulse/mcp/StdioProtocolIT.java`

---

## Mid

### M1. What is prompt injection, and where does it enter here?

**A strong answer contains**
- Text that a user or tenant controls ends up in a model's context and is read as instructions. Here, alert
  names, summaries and metric series labels are written by tenants.
- So the server builds its own summaries from numbers, enums and validated ids, and puts tenant text under a
  field named `untrusted`, with a notice on every result.

**Open** `services/mcp-server/src/main/java/com/streampulse/mcp/adapter/in/mcp/ToolViews.java`

### M2. What does `UntrustedText` do?

**A strong answer contains**
- Strips control, bidirectional, zero-width and tag characters; collapses newlines; caps at 200 characters
  with a truncation marker.
- Mutation: removing the length cap turned the injection test red (seven tests across unit and integration).

**Open** `services/mcp-server/src/main/java/com/streampulse/mcp/domain/UntrustedText.java`

### M3. How is tenant isolation enforced?

**A strong answer contains**
- Inputs are validated before any backend call and never echoed; items from a mixed-tenant backend response
  are dropped. Removing the tenant filter turned five tests red.
- Limit: there is no authentication anywhere; the tenant is whatever the caller asserts, and on the range
  path isolation rests on the backend.

**Open** `services/mcp-server/src/test/java/com/streampulse/mcp/adapter/in/mcp/PromptInjectionTest.java`

---

## Senior

### S1. How do you make "read-only" a property of the code and not of the documentation?

**A strong answer contains**
- Architecture tests: the HTTP adapter may only issue GET (it bans builder calls for POST, PUT, DELETE and a
  generic method), and the outbound ports may not declare methods named like create, update, delete, ack,
  silence or resolve.
- A future change that adds a write must change the test, which a reviewer will see.
- Limit: a name-based rule is a convention a determined author can sidestep.

**Open** `services/mcp-server/src/test/java/com/streampulse/mcp/McpArchitectureRulesTest.java`

### S2. What does this design not protect against?

**A strong answer contains**
- A client that pastes the `untrusted` text into its own prompt is still exposed; 200 characters is still room
  for a short instruction; there is no authentication; and the protocol version negotiated was older than the
  one the client asked for, which I did not verify the client accepts.
- Defence in depth, not a guarantee.

**Open** `docs/adr/0022-mcp-read-only-agent-tools.md`

### S3. Why not an HTTP transport with authentication?

**A strong answer contains**
- STDIO is right for a local, single-user server. A shared server needs HTTP with authentication and
  per-tenant authorisation, and that brings an attack surface and the question of who the caller is.
- The ADR says to choose HTTP when the server is shared.

**Open** `docs/adr/0022-mcp-read-only-agent-tools.md`

### S4. What version risks does the build carry?

**A strong answer contains**
- The Spring AI starter used is built against a newer Boot patch than this build resolves, and a newer major
  needs a newer Boot. The ADR records it as a known mismatch.
- Answer: pin, test the protocol over the real transport (done), and revisit when the platform moves.

**Open** `docs/adr/0022-mcp-read-only-agent-tools.md`

---

## Say this honestly

- No LLM summariser (cut). No authentication. The server was reported up to date by the build and was
  demonstrably live as the tools the agent used in this project.
- Whether the client accepts the negotiated protocol version was not verified.

## Stories and numbers

[Numbers](../06-numbers.md): MCP test count.
