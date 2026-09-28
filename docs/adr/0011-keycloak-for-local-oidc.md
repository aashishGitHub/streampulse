# ADR-0011 — A real Keycloak for OIDC, because symmetric test tokens cannot prove the parts that matter

**Status:** accepted · **Date:** 2026-09-29 · **Phase:** 2 · **Requirements:** REQ-IG-07, REQ-IG-08, REQ-IG-09

## Context

Phase 2 shipped an OAuth2 resource server and a test suite that looked thorough: missing
token, garbage token, expired token, token with no tenant claim, and the body-cannot-override
-the-tenant test. All green.

They were all driven by `TestJwtSupport`, which supplies a **symmetric-key** `JwtDecoder` and
mints HS256 tokens with the same 32-byte secret. That is fast, needs no Docker, and proves
real things. It also cannot, even in principle, prove the three checks production depends on —
not because the tests were badly written, but because in that setup the things being checked
**do not exist**.

| Property | Symmetric HS256 suite | Why |
|---|---|---|
| signature verified | ✅ | a MAC is still a signature |
| expiry enforced | ✅ | `exp` is checked by the same validator either way |
| tenant claim read from the token | ✅ | `TenantResolver` is the real thing |
| **RS256 + public key fetched from the issuer** | ❌ | there is no key to fetch; the secret is a constant in the test source |
| **key rotation** | ❌ | a constant cannot rotate |
| **`iss` validated** | ❌ | the decoder was given no issuer to compare against |
| **`aud` validated** | ❌ | the test tokens carry no `aud` claim at all |

The sharpest way to put it: **with a shared secret, every party that can verify a token can
also mint one.** There is no asymmetry, so there is no trust boundary, so there is nothing
for an issuer check to protect. A suite built on one can never be evidence about issuer or
audience validation, however many cases it covers.

And the gap was not theoretical. Before this ADR, `application.yml` configured **no**
resource-server properties at all — no `issuer-uri`, no `audiences`. The tests passed because
`TestJwtSupport` supplied the `JwtDecoder` bean directly. Started outside a test, the
application would have failed at context refresh with no `JwtDecoder` to build the filter
chain from. The test double was not just hiding weak validation; it was hiding the absence of
any production configuration.

## Decision

Run a **real Keycloak** — in `docker/compose.yml` under the `core` profile with a realm
import, and in a Testcontainers integration test (`KeycloakOidcIT`) that boots the *same
realm file*.

Configure the resource server with exactly two properties:

```yaml
spring.security.oauth2.resourceserver.jwt:
  issuer-uri: ${OIDC_ISSUER_URI:http://localhost:8081/realms/streampulse}
  audiences:
    - ${OIDC_AUDIENCE:streampulse-ingest}
```

`issuer-uri` is one line doing four jobs. Verified against `spring-boot-autoconfigure`
3.5.3's bytecode rather than from memory — it becomes
`NimbusJwtDecoder.withIssuerLocation(...)` plus `JwtValidators.createDefaultWithIssuer(...)`:

1. fetch the OIDC discovery document from `<issuer>/.well-known/openid-configuration`
2. read `jwks_uri` from it and fetch the **public** key — this service holds no key material
3. re-fetch on an unknown `kid`, so a rotation needs no restart
4. install a `JwtIssuerValidator`, so a token from another provider is refused

`audiences` adds the check `issuer-uri` cannot make, and it is a `JwtClaimValidator` on `aud`
using `Collections.disjoint` — i.e. "at least one of the token's audiences is one we accept".

**The symmetric-key suite stays exactly as it is.** This is an addition, not a replacement.

## Why `aud` is a separate decision from `iss`

This is the part that is easy to skip and expensive to skip.

One Keycloak realm has **one signing key**. Every client of that realm — the ingest gateway,
the analytics dashboard, a batch job, a partner integration — gets tokens signed by the same
key, carrying the same issuer. The signature check literally cannot tell them apart.

```
  iss = streampulse   kid = K1   aud = [streampulse-ingest]     ← may write telemetry
  iss = streampulse   kid = K1   aud = [streampulse-analytics]  ← read-only dashboard
                      ^^^^^^^^
                      identical signature validity
```

`aud` is the only claim that says *which API* a token was minted for. Without it, a leaked
read-only dashboard credential is a valid **write** credential for every tenant's telemetry,
and nothing in the logs distinguishes it from a legitimate call.

This was verified by mutation, not asserted. Deleting the `audiences` property and re-running:

```
KeycloakOidcIT > a token with the WRONG AUDIENCE is rejected — same issuer, SAME signing key FAILED
    expected: 401 UNAUTHORIZED
     but was: 202 ACCEPTED
```

**Four of the five tests still passed.** Only the one written for that specific property
noticed — the same lesson as ADR-0009's decorator ordering, on different material.

## How each negative is isolated

A test that rejects a token proves nothing unless you know *why* it was rejected. Each case
differs from the accepted token in exactly one respect, and the test asserts that premise
before asserting the 401.

| Case | `iss` | `kid` | `aud` | `tenant` | What it isolates |
|---|---|---|---|---|---|
| accepted | streampulse | K1 | streampulse-ingest | streamflix | — |
| wrong audience | streampulse | **K1** | **streampulse-analytics** | streamflix | only `aud` can be responsible |
| wrong issuer | **rogue-idp** | **K2** | streampulse-ingest | streamflix | a foreign provider's token |

The wrong-audience case is the strong one, because the signature is genuinely valid: the
control client lives in the same realm, so it shares the signing key, and the test asserts
`kid` equality before asserting the rejection.

The wrong-issuer case is honest about a limitation. A separate realm has its own keypair, so
the **signature check fails first** and the `JwtIssuerValidator` is the second line of defence
rather than the one that fired. That ordering is correct — an unknown key should never reach
claim validation — and it is exactly why the audience case is built the way it is. Producing
a validly-signed token with a foreign `iss` would mean stealing the realm's private key, at
which point the issuer check is no longer the interesting control.

## Key rotation, and the overlap that makes it safe

`KeycloakOidcIT` warms the decoder's JWK cache with a real request, then adds a second
`rsa-generated` key provider at priority 200 over Keycloak's default 100, which makes it the
active signer. Observed directly:

```
old kid: QpUunJLwZdbL1eeyoNQCpOO31XBdxT_wn4_RUYEge9s
new kid: ATO39U2IVLlg58SFlY_1Ex85YcTBPMW4vtGBCJ6m0ZM
jwks kids: [ATO39U2IVLlg…, yebGopNonQEp…, QpUunJLwZdbL…]   count: 3
```

Both directions are asserted, and the second matters as much as the first:

- a token signed by the **new** key is accepted with no restart
- a token signed by the **old** key still verifies, because the old key stays in the JWKS

A rotation that invalidated tokens already in flight would be an outage, not a rotation. The
overlap window is the feature.

That the re-fetch is immediate rather than delayed was also verified against the jar, not
assumed: `spring-security-oauth2-jose` 6.5.1 builds its JWK source with
`refreshAheadCache(false)` and **`rateLimited(false)`**, so an unknown `kid` forces a fetch on
the next request. If it were rate limited, this test would need polling and the ADR would
have to say so.

## Why a plain `GenericContainer` and not `dasniko/testcontainers-keycloak`

The community module is real, maintained, and would have saved about twenty lines. It was
still not taken:

| | community module | plain `GenericContainer` |
|---|---|---|
| lines of setup | ~5 | ~20 |
| extra dependency | yes, with its own Testcontainers pin | none |
| image tag | its default | **identical to `docker/compose.yml`** |
| support policy | "no LTS, no backports — stay up to date" | ours |
| what the reader learns | a wrapper's API | `--import-realm`, discovery, JWKS |

The decisive one is the Testcontainers pin. This build does **not** get Testcontainers from
`libs.versions.toml`: the catalog says 1.21.3 and the resolved jar is **1.21.2**, because
`io.spring.dependency-management` applies the Boot BOM with Maven-style precedence. Putting a
third party's opinion into an already surprising resolution buys a future afternoon of
debugging for twenty lines today.

The second is version drift. A wrapper that defaults to a different Keycloak would let the
test prove things about a version nobody runs locally — which is the failure mode this ADR
exists to close, reintroduced one layer up.

Same reasoning as ADR-0004's rejection of the community Avro Gradle plugin. Reach for a
third-party convenience when it owns a problem you do not want; not when it inserts itself
between you and the thing you are trying to learn.

## One realm file, two consumers

`docker/keycloak/realms/streampulse-realm.json` is mounted by compose **and** added to the
ingest-gateway test classpath by `build.gradle.kts`:

```kotlin
sourceSets { test { resources { srcDir(rootProject.layout.projectDirectory.dir("docker/keycloak")) } } }
```

A copied fixture would drift, and the drift would be invisible: the test keeps passing
against a realm nobody runs. The adversary realm (`rogue-idp`) deliberately does **not** live
there — it is in `src/test/resources`, and `KeycloakOidcIT` creates it over the admin API, so
it can never be imported into a developer's local stack.

## Consequences

**Good**

- `iss`, `aud`, RS256 and key rotation are now proven against a real issuer, including the
  negative cases, with each negative isolated to one differing claim.
- `make up-core` boots a working IdP with no manual admin-console clicking, so the realm is
  reproducible rather than living in somebody's browser history.
- The service now has actual resource-server configuration, so it can start outside a test.
- The fast suite is untouched: 44 of the 49 ingest-gateway tests still need no Keycloak, and
  the four health-probe tests need no Docker at all.
- The gateway holds no key material. It can verify tokens and cannot mint them.

**Costs / open items**

- One more container, and Keycloak is the slow one: ~40s to boot, because it runs 148
  Liquibase changesets against H2 on first start. `KeycloakOidcIT` takes ~24s wall clock with
  the image already pulled; the first run also pays a ~450 MB pull.
- The `tenant` claim is a hardcoded realm mapper. Real tenancy comes from the client's
  organisation; the token **shape** is identical, which is all the resource server validates,
  but this realm is not a model of a production identity design.
- Client secrets are in git. Local-only placeholders, the same category as the Postgres
  password in `compose.yml` — but they are secrets-shaped, and Phase 11 should replace them
  with something injected.
- `sslRequired: none`. Correct for a local stack over plain HTTP, wrong everywhere else.
- Nothing yet proves **authorization** — scopes and roles. The token says who you are and
  which API you are for; it does not yet restrict what you may do within ingest.

## Alternatives rejected

**Keep only the symmetric-key tests.** Fast and Docker-free, and they stay — but as shown
above they cannot be evidence about issuer or audience, because neither exists in that setup.
"We validate the issuer" would have been an unfalsifiable claim.

**Hand-mint RS256 tokens with a locally generated keypair and serve a static JWKS.** Proves
more than HS256 and needs no container. Still never exercises discovery, never exercises
Keycloak's `iss` spelling, and — the real objection — the test would then be asserting against
a JWKS *we* wrote. The interesting failures are the ones where a real issuer does something
slightly different from what we assumed.

**Mock the `JwtDecoder` and assert it was called.** Asserts that the code calls the code.
Every one of the three properties here lives *inside* the decoder's configuration, which is
precisely what a mock replaces.

**A hosted IdP (Auth0, Cognito) for local development.** Needs an account, network access and
a shared tenant that two developers can break for each other. Fails the same test ADR-0003
applied to AWS: local development must work with no account and no credit card.

**Point the test at a token endpoint with a fixed port on the host.** Simpler than
Testcontainers' dynamic ports — and it silently couples the test to whether `make up-core` is
running, so it passes on the author's machine and fails in CI, which is the least useful
possible failure mode.
