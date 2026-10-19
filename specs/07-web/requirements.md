# Phase 7 — Web dashboard: Requirements

**Status:** in progress · **Depends on:** Phase 5 (query API contract), Phase 6 (control plane and alert engine contracts)
· **Delivers:** a browser dashboard: live metric charts, a live alert feed, and alert-rule administration

## Why

Every earlier phase ends at an HTTP or stream boundary. The dashboard is the first consumer
that has to live with the boundary's failure modes: a stream that drops, a slow reader, an
error body from a validation rule. Each requirement below is one of those meeting points.

## Requirements (EARS; no technology named)

### Live stream connection

- **REQ-WEB-01** WHEN a live stream connection fails to open or drops THE SYSTEM SHALL reconnect
  on its own, waiting longer after each consecutive failure up to a ceiling, and randomising each
  wait so that many viewers do not reconnect in lockstep.
- **REQ-WEB-02** WHEN a connection opens successfully THE SYSTEM SHALL restart the waiting
  schedule from its shortest wait.
- **REQ-WEB-03** WHEN reconnecting THE SYSTEM SHALL tell the server the id of the last event it
  received, so the server can resume without a gap. This must hold for every reconnect, not
  only the ones the browser performs itself.
- **REQ-WEB-04** WHEN the viewer leaves the screen or switches tenant THE SYSTEM SHALL close the
  connection, cancel any pending reconnect, and forget the resume position (an id from one
  tenant's stream is meaningless on another's).
- **REQ-WEB-05** WHEN the server refuses a request with a client-error status (other than
  "request timeout" and "too many requests") THE SYSTEM SHALL stop retrying and show the
  failure, because the same request would be refused again.
- **REQ-WEB-06** WHILE a connection is open and neither data nor a heartbeat has arrived for
  longer than a threshold THE SYSTEM SHALL treat it as dead and reconnect.
- **REQ-WEB-07** THE SYSTEM SHALL show the connection state (connecting, open, retrying, closed)
  to the viewer as text.

### Live metrics

- **REQ-WEB-10** WHEN a tenant is selected THE SYSTEM SHALL show one live chart per series
  (title, region, metric) for concurrent viewers and error rate.
- **REQ-WEB-11** WHEN many updates arrive in quick succession THE SYSTEM SHALL refresh the
  display at most once per refresh interval (target 250 ms), and the refresh SHALL include every
  update received in that interval.
- **REQ-WEB-12** THE SYSTEM SHALL keep at most a fixed number of recent points per series
  (target 120) and discard the oldest first.
- **REQ-WEB-13** WHEN an update carries the window start of a point already held THE SYSTEM
  SHALL replace that point rather than add another.
- **REQ-WEB-14** THE SYSTEM SHALL give every chart a text equivalent (latest value, range, number
  of points) and a table of recent values reachable by keyboard.
- **REQ-WEB-15** WHEN an update is malformed THE SYSTEM SHALL ignore it and keep the stream
  running.
- **REQ-WEB-16** WHEN the list of tenants cannot be loaded THE SYSTEM SHALL fall back to the
  configured default tenants and say so.

### Alert feed

- **REQ-WEB-20** WHEN the alert view is shown THE SYSTEM SHALL list the tenant's alerts and apply
  each live change as it arrives.
- **REQ-WEB-21** THE SYSTEM SHALL tell firing, resolved and suppressed alerts apart by a text
  label and a symbol, never by colour alone.
- **REQ-WEB-22** WHEN an alert is suppressed THE SYSTEM SHALL show what suppressed it as the
  root cause.
- **REQ-WEB-23** WHEN a live change arrives while the list is being (re)loaded THE SYSTEM SHALL
  keep it: the loaded list is reconciled with changes received in the meantime.
- **REQ-WEB-24** WHEN the alert list cannot be loaded THE SYSTEM SHALL show the error and offer
  to try again.
- **REQ-WEB-25** THE SYSTEM SHALL order alerts firing first, then by severity, then newest first.

### Alert-rule administration

- **REQ-WEB-30** WHEN an administrator submits a rule THE SYSTEM SHALL send it to the control
  plane and confirm the result.
- **REQ-WEB-31** WHEN the control plane rejects a rule with a problem description THE SYSTEM
  SHALL show its title, detail and trace id next to the form and keep what the user typed.
- **REQ-WEB-32** WHEN the control plane cannot be reached THE SYSTEM SHALL say so instead of
  failing silently.
- **REQ-WEB-33** WHEN a required field is empty, or a value breaks a constraint the contract states
  (id format, a category being required when the rule depends on others) THE SYSTEM SHALL say which
  one before sending. The server stays the authority: when it disagrees, its message is shown.
- **REQ-WEB-34** WHEN the administrator opens the rule screen THE SYSTEM SHALL list the selected
  tenant's rules with their condition, severity, category, dependencies and version.
- **REQ-WEB-35** WHEN the administrator opens one rule THE SYSTEM SHALL read it from the control
  plane and show every field, including the version the server announces.
- **REQ-WEB-36** WHEN the administrator saves a change to a rule THE SYSTEM SHALL send the editable
  fields, keep the rule's id and tenant fixed, and refresh the list with the new version. WHEN the
  control plane refuses (a dependency cycle, a concurrent change) THE SYSTEM SHALL show the server's
  message and keep what the administrator typed.
- **REQ-WEB-37** WHEN the administrator asks to delete a rule THE SYSTEM SHALL ask for explicit
  confirmation first, SHALL send nothing if the administrator declines, and SHALL place keyboard focus
  on the safe choice. WHEN the control plane refuses or the rule is already gone THE SYSTEM SHALL say so.
- **REQ-WEB-38** WHEN the control plane lists per-field validation messages THE SYSTEM SHALL show each.

### Sign-in

- **REQ-WEB-40** WHERE sign-in is enabled THE SYSTEM SHALL let the viewer sign in at the identity
  provider with the authorization-code flow protected by a proof key, and SHALL attach the access
  token to every request, including each stream reconnect.
- **REQ-WEB-41** WHERE sign-in is disabled, or the viewer has not signed in, THE SYSTEM SHALL
  work without a token.
- **REQ-WEB-42** WHEN sign-in completes THE SYSTEM SHALL remove the one-time code and state from
  the address bar.

## Non-functional

- Every view passes an automated accessibility scan with zero violations; keyboard-only use
  reaches every control. (An automated scan covers a subset of WCAG; it is not a claim of full
  conformance.)
- The browser only ever talks to its own origin in development (a proxy fronts the services).
- Numbers (250 ms refresh, 120 points, 30 s backoff ceiling, 45 s idle threshold) are **targets**,
  not measurements.

## Out of scope (stated, not hidden)

- Historical backfill of a chart from the query API; a chart starts empty and fills from the stream.
- Sending the rule's version with an edit: the update operation in the contract has no field or
  header for it, so concurrent edits are detected only by the server's own conflict answer.
- Managing tenants, titles, apps and service nodes.
- Enforcement of authentication on the services (none of them enforce it yet).
- Internationalisation; a dark theme; mobile layout beyond "does not break".
