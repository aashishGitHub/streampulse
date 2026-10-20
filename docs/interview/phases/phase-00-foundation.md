# Phase 0 — Build foundation, conventions, domain primitives, architecture rules

**Status:** done. CI has run on GitHub and is currently failing: `gh run list` shows all seven runs on
`main` since 2026-10-12 ended in failure (one of them cancelled), and the last three failed in Gradle at
`:services:query-api:test` (see S5 and S7). Not enforced: the `Clock` injection rule in the shared platform
rules, a coverage threshold, dependency locking.

**One line to say first:** "A Gradle multi-module build where shared behaviour comes from convention
plugins that modules opt into, with ArchUnit rules and value objects that make the architecture and
the tenant boundary executable."

## Code map

| Thing | Where |
|---|---|
| Module list, repository policy | `settings.gradle.kts` |
| Convention plugins (Java, then Spring service) | `build-logic/src/main/kotlin/streampulse.java-conventions.gradle.kts`, `streampulse.spring-service.gradle.kts` |
| Every version, in one place | `gradle/libs.versions.toml` |
| Value objects | `libs/platform/src/main/java/com/streampulse/platform/domain/TenantId.java`, `EventId.java` |
| Architecture rules (platform copy) | `libs/platform/src/test/java/com/streampulse/platform/ArchitectureRulesTest.java` |
| One entry point for non-Gradle tasks | `Makefile` |
| CI | `.github/workflows/ci.yml` |
| Decisions | `docs/adr/0001-multi-module-single-repo.md`, `0002-gradle-kotlin-dsl.md`, `0003-local-aws-simulation.md` |
| Working agreements | `CLAUDE.md` |

---

## Beginner

### B1. What is a Gradle convention plugin and why does this repo have one?

**Answer:** A convention plugin is a Gradle plugin that I write myself, in the `build-logic` build, to hold the build configuration every module shares. The plugin id is simply the file name, so `streampulse.java-conventions.gradle.kts` becomes `id("streampulse.java-conventions")`, and a module opts in by applying that one line. The shared plugin sets the Java 21 toolchain, the compiler flags, the test setup, JaCoCo coverage and Spotless formatting, so twelve modules do not each repeat forty lines that would slowly drift apart. It is the opposite of an `allprojects` block in the root, which pushes configuration down onto modules that never asked for it. A second plugin, `streampulse.spring-service`, layers Spring Boot on top for the services, so a library module does not inherit Spring.

**Example:** `libs/contracts/build.gradle.kts:9-11`
```kotlin
plugins {
    id("streampulse.java-conventions")
}
```
This is the whole opt-in: one line, and the module inherits everything defined in the convention file.

**Code:** `build-logic/src/main/kotlin/streampulse.java-conventions.gradle.kts:10-14` (what the plugin applies), `build-logic/src/main/kotlin/streampulse.spring-service.gradle.kts:10-14` (the Spring layer on top), `libs/platform/build.gradle.kts:14` (another module opting in)

---

### B2. What does the version catalog do?

**Answer:** The version catalog, `gradle/libs.versions.toml`, is the one file that holds every dependency version in the repository. Modules refer to libraries by alias, so a build file says `libs.assertj` and never a literal version string, and the IDE autocompletes the alias, so a typo is caught in the build script rather than as a resolution failure minutes later. Bundles group libraries that always travel together, such as the testing bundle, and BOMs (bills of materials, which fix versions for a whole family) align JUnit, the AWS SDK and gRPC. The repo's own rule, written in `CLAUDE.md` and the file header, is that only stable releases go in: no milestones, release candidates or snapshots. That rule is a convention that a person has to follow, and I have not seen the build enforce it.

**Example:** `gradle/libs.versions.toml:196-199`
```toml
[bundles]
...
testing = ["junit-jupiter", "assertj", "mockito-core", "mockito-junit", "awaitility"]
```
One line in a module replaces five dependency declarations.

**Code:** `gradle/libs.versions.toml:32-34` (the versions section), `gradle/libs.versions.toml:91-99` (BOM and library aliases), `gradle/libs.versions.toml:196-199` (the bundle)

---

### B3. Why `TenantId` instead of a `String`?

**Answer:** If tenant id and title id are both strings, a call with the two arguments swapped compiles and runs, and in a multi-tenant system that is a cross-tenant data leak, not a harmless bug. With a distinct `TenantId` type that call is a compile error. The type also validates once at the boundary: it trims and lower-cases the value with `Locale.ROOT`, then rejects anything that fails the allowed pattern or exceeds 32 characters, so every layer downstream can trust it without re-checking. That matters because the value ends up in Kafka record keys, Redis key prefixes, DynamoDB partition keys, S3 prefixes and Prometheus label values, and a slash or a hash in it would break at least one of them. The limit is that this only protects code that uses the type, and it says nothing about who is allowed to act as that tenant.

**Example:** `libs/platform/src/main/java/com/streampulse/platform/domain/TenantId.java:57-59`
```java
private static final Pattern VALID = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?$");

private static final int MAX_LENGTH = 32;
```
The pattern allows only lowercase letters, digits and internal hyphens, which is why the value is safe inside keys and labels.

**Code:** `libs/platform/src/main/java/com/streampulse/platform/domain/TenantId.java:47-88` (the record and its validation), `libs/platform/src/main/java/com/streampulse/platform/domain/TenantId.java:108-110` (`keyPrefix`)

---

### B4. Why is the event id generated by the client?

**Answer:** The client generates the id once and reuses it on every retry, so a retry carries the same id as the original. If the network drops the response after the write already happened, the retry arrives with an id the server has seen, and a de-duplication check turns at-least-once delivery into effectively-once processing. If the server generated the id, the retry would look like a new event and the duplicate would be undetectable. A UUID suits this because a player can generate it offline with no coordination, and players buffer telemetry while offline. It is the same idea as the idempotency key on a payment API. `EventId.random()` exists for tests and the load generator, and the ingest path must never use it, because it has to use the id the client sent.

**Example:** `libs/platform/src/main/java/com/streampulse/platform/domain/EventId.java:79-92`
```java
public static EventId random() {
    return new EventId(UUID.randomUUID().toString());
}
...
public String idempotencyKey(TenantId tenant) {
    return tenant.keyPrefix() + ":event#" + value;
}
```
The dedup key is the tenant prefix plus the event id, so the same id from two tenants never collides.

**Code:** `libs/platform/src/main/java/com/streampulse/platform/domain/EventId.java:8-45` (why the client generates it), `libs/platform/src/main/java/com/streampulse/platform/domain/EventId.java:46-69` (validation)

---

### B5. What are `test` and `integrationTest`, and why split them?

**Answer:** `test` runs everything that is not named `*IT`, needs no Docker and gives the fast feedback loop. `integrationTest` runs only the classes ending in `IT`, which start containers, so it needs Docker. The `check` task depends on `integrationTest`, which means a full build runs both. They are not two source sets: they share the one `test` source set and are split purely by file name, because a second source set would mean a second directory tree and duplicated test dependencies. An old note says `integrationTest` is its own source set, and that is wrong. The trade-off is that the split depends on people naming classes correctly, so a misnamed class lands in the wrong task without any error.

**Example:** `build-logic/src/main/kotlin/streampulse.java-conventions.gradle.kts:215-238`
```kotlin
tasks.named<Test>("test") {
    exclude("**/*IT.class")
...
val integrationTest = tasks.register<Test>("integrationTest") {
...
    include("**/*IT.class")
...
tasks.named("check") {
    dependsOn(integrationTest)
}
```
Point at the exclude and the include: the same pattern, used in opposite directions.

**Code:** `build-logic/src/main/kotlin/streampulse.java-conventions.gradle.kts:201-238` (both tasks and the comment explaining the single source set)

---

### B6. What does `make check` do, and why is there a Makefile at all?

**Answer:** `make check` runs `./gradlew spotlessCheck build`, which is the format check plus the full build: compile, unit tests, integration tests and the ArchUnit rules. It is described in the Makefile as everything CI runs, although the CI workflow actually runs those two commands as separate steps. The Makefile exists because not everything is a Gradle task. Docker Compose, Terraform and the demo scripts are not, so a single entry point is better than a README of commands that people copy wrong, and `make help` lists every target from the `##` comments. The cost is one more tool to know, and the Makefile is a convenience layer, not the source of truth, which stays in Gradle.

**Example:** `Makefile:46-48`
```make
.PHONY: check
check: ## Everything CI runs: format check + tests + architecture rules
	./gradlew spotlessCheck build
```
The comment after `##` is what `make help` prints.

**Code:** `Makefile:18-25` (the self-documenting `help` target), `Makefile:30-57` (build, test, format, check targets), `.github/workflows/ci.yml:45-49` (the two CI steps it mirrors)

---

## Mid

### M1. Why a compact constructor in a record, and what can go wrong?

**Answer:** A compact constructor runs before the record's fields are assigned, so it is the natural place to validate and normalise a value once, at the boundary. In `TenantId` I trim and lower-case first and only then check the pattern, because normalising after validation would reject inputs I mean to accept, such as a value with stray spaces. Reassigning the parameter really does change what gets stored, so the order matters. Because equality comes from the normalised component, two spellings of the same tenant produce equal keys everywhere. The thing to watch is that a record is only shallowly immutable, so a mutable component, such as a list, would still leak, and `TenantId` is safe only because its one component is a `String`.

**Example:** `libs/platform/src/main/java/com/streampulse/platform/domain/TenantId.java:70-84`
```java
// Normalise BEFORE validating, so " StreamFlix " is accepted and stored as
// "streamflix". Normalising after validation would reject values we intend to allow.
value = value.trim().toLowerCase(java.util.Locale.ROOT);
...
if (!VALID.matcher(value).matches()) {
```
The assignment to `value` is the point: it replaces the parameter before any check runs, so the pattern sees the normalised form.

**Code:** `libs/platform/src/main/java/com/streampulse/platform/domain/TenantId.java:66-88` (the compact constructor), `libs/platform/src/test/java/com/streampulse/platform/domain/TenantIdTest.java:26-45` (normalisation tests)

**If they push:** "Why `Locale.ROOT`?" With a Turkish default locale, lower-casing `I` produces a dotless `ı`, so the same input would give different ids on different machines. I did not find a test that sets that locale, so I treat that case as untested.

---

### M2. What is the catalog-accessor problem in a precompiled script plugin?

**Answer:** The generated `libs.` accessors, which work in an ordinary `build.gradle.kts`, do not exist inside a precompiled script plugin, because the plugin is compiled before any project's accessors are generated. The repo recorded this as `libs.assertj` failing with "Unresolved reference". A popular workaround injects the generated `LibrariesForLibs` class by a reflection trick, and I declined it. Instead the plugin uses the documented `VersionCatalogsExtension` and calls `findLibrary`, so a missing alias fails at configuration time with the alias named in the message. The cost is more verbose code with small helper functions, and the gain is that there is no hack to maintain across Gradle upgrades. In `build-logic` itself, the catalog is shared by pointing its settings file at the same TOML file.

**Example:** `build-logic/src/main/kotlin/streampulse.java-conventions.gradle.kts:56-60`
```kotlin
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).orElseThrow {
    IllegalStateException("No library '$alias' in gradle/libs.versions.toml")
}
```
The `orElseThrow` is what makes a wrong alias fail loudly and by name.

**Code:** `build-logic/src/main/kotlin/streampulse.java-conventions.gradle.kts:37-68` (the explanation and helpers), `build-logic/settings.gradle.kts:13-17` (how `build-logic` reads the root catalog), `specs/00-foundation/design.md:100-104` (the design note)

**If they push:** "Why not `buildSrc`?" The repo's comment says a change to `buildSrc` invalidates the whole build cache and an included build is cached separately. That is the repo's claim and I have not verified it myself.

---

### M3. What does ArchUnit enforce here, and what are `allowEmptyShould` and the test-class exclusion for?

**Answer:** The platform copy has four rules: no field injection, the domain package imports no framework types, inbound adapters do not reach outbound ones, and no `java.util.Date` or `Calendar`. ArchUnit reads compiled bytecode, so the test excludes test classes, because tests legitimately do things production code must not. `allowEmptyShould(true)` lets a rule pass when no class matches it, which was needed in Phase 0 before the packages existed. The danger is that rename a package and the rule goes vacuously green, so it can never fail. The domain rule is no longer empty in the platform module, since `TenantId` and `EventId` live in a `domain` package, but the comment in the test still says there are none, so that comment is stale.

**Example:** `libs/platform/src/test/java/com/streampulse/platform/ArchitectureRulesTest.java:86-98`
```java
ArchRule rule = noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
                "org.springframework..",
                "org.apache.kafka..",
                "jakarta.persistence..",
                "software.amazon.awssdk..",
                "io.grpc..")
        .because("the domain must be testable with plain `new` and must survive " + "a change of framework")
        .allowEmptyShould(true);
```
Point at `allowEmptyShould(true)` on the last line: that is the switch that lets the rule pass with nothing to check.

**Code:** `libs/platform/src/test/java/com/streampulse/platform/ArchitectureRulesTest.java:54-56` (production classes only), `libs/platform/src/test/java/com/streampulse/platform/ArchitectureRulesTest.java:58-75` (the field-injection rule)

**If they push:** "How would you stop the vacuous pass?" Remove `allowEmptyShould(true)` now that the package has classes, so a rename makes the rule fail on an empty match rather than pass.

---

### M4. How do you prove a quality gate works?

**Answer:** I violate it on purpose, watch it fail, and revert. A gate that nobody has seen fail is not a gate. For Spotless I appended badly formatted code and `spotlessCheck` failed in 900 ms, naming the file. For ArchUnit I added a throwaway class returning `java.util.Date`, and the failure named the class and the method that used it. There is an honesty caveat I would say before an interviewer finds it: those demonstrations ran against an earlier tree. The Spotless demo hit a kata file that has since been removed, and the ArchUnit class was a temporary one. So I would rerun them before citing them as current evidence.

**Example:** `MILESTONES.md:111-115`
```
ArchitectureRulesTest > no java.util.Date or Calendar — use java.time FAILED
    Architecture Violation [Priority: MEDIUM] - Rule 'no classes should depend on classes
    that have fully qualified name 'java.util.Date' ...' was violated (2 times):
    Method <com.streampulse.platform.Temp.now()> calls constructor <java.util.Date.<init>()>
    Method <com.streampulse.platform.Temp.now()> has return type <java.util.Date>
```
The failure names the rule, the count and the exact methods, which is what makes it actionable.

**Code:** `MILESTONES.md:74-115` (the section "Quality gates — verified by breaking them"), `MILESTONES.md:84-88` (the Spotless failure)

**If they push:** "Is the CI gate proven the same way?" No, and CI is currently red for a different reason; see S5 and S7.

---

### M5. Why does Testcontainers need a Docker socket, an API-version pin and a Ryuk override on macOS?

**Answer:** Docker Desktop on macOS exposes many unix sockets and only some of them answer. The build picks the first candidate that exists and sets `DOCKER_HOST` to it, pins the Docker API version to 1.44 because the newer engine rejects the client's old default of 1.32, and sets the socket path that Ryuk, the sidecar that reaps containers, mounts inside the engine. All of this applies only when `DOCKER_HOST` is unset, so CI and anyone with a deliberate setting are unaffected. The trap that cost me time is that a socket file existing does not mean it answers: an old path still accepted a connection and never replied, and Testcontainers hung with no error. The cost is a Docker Desktop layout detail baked into the build, which the file itself marks to be re-probed when it breaks.

**Example:** `build-logic/src/main/kotlin/streampulse.java-conventions.gradle.kts:163-172`
```kotlin
if (System.getenv("DOCKER_HOST") == null) {
    val home = System.getProperty("user.home")
    val dockerDesktopSocket = listOf(
        File(home, ".docker/run/docker.sock"),                                  // Docker Desktop, current
        File("/var/run/docker.sock"),                                           // Linux, CI, and the macOS symlink
        File(home, "Library/Containers/com.docker.docker/Data/docker.raw.sock"), // older Docker Desktop
    ).firstOrNull { it.exists() }

    if (dockerDesktopSocket != null) {
        environment("DOCKER_HOST", "unix://${dockerDesktopSocket.absolutePath}")
```
The candidates are ordered by what Docker Desktop serves today, with the historical path last.

**Code:** `build-logic/src/main/kotlin/streampulse.java-conventions.gradle.kts:124-196` (the whole block and its comments), `docs/interview/05-what-broke.md` (story 4, "Docker is broken was wrong, twice")

**If they push:** "Why does `exists()` fail as a check?" A dead socket file still exists. The fix I documented is to probe it with `curl --unix-socket` and a short timeout, which a live socket answers instantly.

---

### M6. What does `failOnNoDiscoveredTests = false` do, and what is the risk?

**Answer:** Gradle 9 fails a test task that discovers no tests. `libs:platform` and `libs:contracts` have no `*IT` classes, so without this setting their `integrationTest` task would fail the build for a correct reason I do not want to treat as an error. The risk is that it hides a real mistake: if someone misspells the `IT` suffix on a container-based test, that test lands in no task, or in the wrong one, and the build stays green. A mitigation I have not built would be a convention test that asserts every class using Testcontainers matches the naming pattern. I would call this a known gap, not a solved problem.

**Example:** `build-logic/src/main/kotlin/streampulse.java-conventions.gradle.kts:224-234`
```kotlin
val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs *IT classes (start containers; needs Docker)."
    group = "verification"
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    include("**/*IT.class")
    // Gradle 9 fails a Test task that discovers nothing. libs:platform and libs:contracts
    // have no *IT classes, and that is correct, not an error.
    failOnNoDiscoveredTests = false
    shouldRunAfter(tasks.named("test"))
}
```
The comment above the flag gives the reason, and the include pattern above it is what a misspelt suffix would miss.

**Code:** `build-logic/src/main/kotlin/streampulse.java-conventions.gradle.kts:224-234` (the task and flag)

---

## Senior

### S1. Convention plugins versus `allprojects`: what is the real argument?

**Answer:** The main argument is about coupling and traceability. An `allprojects` or `subprojects` block couples every module to the root and answers "why does this module have that dependency?" badly, whereas a convention plugin lets a module opt in, so a library module such as `libs:contracts` does not inherit Spring. I would be careful with the configuration-cache claim in the repo's header, which says `allprojects` breaks it. This repo does not have the configuration cache enabled: there is no `gradle.properties` in the repo or in my `~/.gradle`, and every Gradle run prints "Consider enabling configuration cache to speed up this build". The root build also has an `architectureReport` task that reads `subprojects` inside `doLast`, which I believe would be a configuration-cache problem; I have not run it with the cache on. The repo also claims a `buildSrc` change invalidates the whole build cache, and I have not verified that.

**Example:** `build.gradle.kts:15-22`
```kotlin
tasks.register("architectureReport") {
    group = "verification"
    description = "Prints the module layout and which conventions each module applies."
    doLast {
        println("\nStreamPulse modules")
        println("-".repeat(60))
        subprojects.sortedBy { it.path }.forEach { p ->
```
The `subprojects` access sits inside `doLast`, so it runs at execution time, which is the pattern I believe the configuration cache does not like.

**Code:** `build.gradle.kts:9-13` (the claim in the header), `settings.gradle.kts:25-26` (the `buildSrc` claim), `build-logic/src/main/kotlin/streampulse.spring-service.gradle.kts:10-14` (opt-in layering)

**If they push:** "So would you turn the cache on?" Yes, as an experiment: add a `gradle.properties` with the cache enabled, run the build, and fix what Gradle reports, starting with that report task.

---

### S2. Supply chain: what does the build protect, and what is missing?

**Answer:** Three things are protected. The Gradle wrapper distribution has a pinned SHA-256 checksum. Dependency repositories for the main build are declared once in `settings.gradle.kts`, with the mode set to `PREFER_SETTINGS`, and the comment says a module cannot add its own. You may want to verify that against the Gradle docs: I believe `PREFER_SETTINGS` ignores project repositories while `FAIL_ON_PROJECT_REPOS` is the strict mode, and I have not verified it, so the repo's comment may overstate it. The `build-logic` build also has its own settings file with its own repositories, so that policy does not cover it. What is missing is dependency locking, verification metadata for checksums and signatures, and vulnerability scanning in CI. "Stable releases only" is human discipline, and I would add a build check that fails on a milestone or snapshot version.

**Example:** `settings.gradle.kts:36-46`
```kotlin
// Where dependencies come from. PREFER_SETTINGS means a module CANNOT declare
// its own repository — so nobody can quietly pull a jar from a random mirror.
// Supply-chain hygiene, enforced by the build rather than by code review.
// ---------------------------------------------------------------------------
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        mavenCentral()
    }
}
```
Verify that comment against the Gradle docs before repeating it; I have marked it unverified.

**Code:** `settings.gradle.kts:41-46` (repository policy), `gradle/wrapper/gradle-wrapper.properties:3` (pinned checksum), `build-logic/settings.gradle.kts:5-8` (separate repositories for `build-logic`)

**If they push:** "What is the cheapest next step?" Generate dependency verification metadata, because it adds checksum checking without changing any code.

---

### S3. What can ArchUnit not enforce?

**Answer:** ArchUnit reads bytecode from the classpath of the module it runs in, so the platform copy of the rules cannot see a service's classes. A rule that cannot see the code it protects passes forever, which is why each service has its own rules test: there are ten rules test classes, the platform plus nine modules. It also means a module that is dropped, or one that never got a rules test, loses its protection silently. The domain rule is a finite denylist of package prefixes, not an allowlist, so a new framework package would not be caught until someone adds it. And it cannot enforce runtime behaviour such as transaction boundaries or idempotency semantics. The `Clock` rule is only a convention in the platform module, which bans `Date` and `Calendar` but not `Instant.now()`. The control-plane module adds its own ban on `Instant.now()` and `LocalDateTime.now()`, which still leaves other ways of reading the clock.

**Example:** `services/control-plane/src/test/java/com/streampulse/controlplane/ControlPlaneArchitectureRulesTest.java:92-104`
```java
@Test
@DisplayName("production code never reads the wall clock directly")
void noDirectClockUse() {
    ArchRuleDefinition.noClasses()
            .that()
            .resideOutsideOfPackage("..config..")
            .should()
            .callMethod(java.time.Instant.class, "now")
            .orShould()
            .callMethod(java.time.LocalDateTime.class, "now")
            .because("time comes from the injected Clock so it can be controlled in tests")
            .check(PRODUCTION);
}
```
Only two method calls are named, and the `config` package is exempt, so this is a narrow rule, not a general one.

**Code:** `services/control-plane/src/test/java/com/streampulse/controlplane/ControlPlaneArchitectureRulesTest.java:15-24` (why the rules live in the service), `libs/platform/src/test/java/com/streampulse/platform/ArchitectureRulesTest.java:120-137` (the platform `Date` and `Calendar` ban)

**If they push:** "How would you notice a service with no rules test?" A convention check that every module applying the Spring service plugin has one; I did not build it.

---

### S4. Why a monorepo, and when would you split it?

**Answer:** The honest framing is coordination cost against deployment independence. Here there is one author and tightly coupled contracts, so coordination cost dominates: a contract change and both sides of its usage land in one commit, one build proves everything compiles together, and there is no version skew between producer and consumer. The negatives are real. The build slows as modules accumulate, and nothing physically stops one module reaching into another, which is why the ArchUnit rules exist from Phase 0. ADR-0001 says to revisit if CI exceeds about ten minutes or release cadences genuinely diverge. The last CI run I can see took 32m4s on the run list, while the two before it were around seven minutes, and I have not looked into why. So on that evidence the ADR's own trigger may already be near.

**Example:** `docs/adr/0001-multi-module-single-repo.md:41-42`
```
**Revisit if:** the build exceeds ~10 minutes in CI, or genuinely independent release
cadences become necessary. Neither applies to a single-author learning project.
```
The sentence names measurable triggers, and the second sentence is a claim worth rechecking against the CI durations.

**Code:** `docs/adr/0001-multi-module-single-repo.md:20-24` (the options table), `docs/adr/0001-multi-module-single-repo.md:35-39` (the negatives), `.github/workflows/ci.yml:27` (the 20-minute timeout)

**If they push:** "What would you do first if CI got slow?" Split fast and slow jobs, and run only affected modules, which the ADR lists as the mitigation.

---

### S5. Assess this CI pipeline.

**Answer:** The pipeline runs `spotlessCheck`, then `./gradlew build --no-daemon`, which includes the integration tests, and it uploads the unit test and coverage reports. The important fact is that it is red. `gh run list` shows seven runs on `main` since 2026-10-12: six failed and one was cancelled, and the last three failed in Gradle at `:services:query-api:test` on `BlockingIsolationTest` (S7 explains the cause). Gradle stops at the first failing module, so other failures may be hidden behind it, and I do not know whether any exist. An earlier run on 2026-10-12 failed early for a reason I did not examine. What is missing is a coverage gate, protobuf lint, a web build and test, Terraform validation, Prometheus rule tests, an image build and dependency scanning, and the integration-test reports are not uploaded because the path covers only `reports/tests/test/`. My proposal is to fix the red test first, then split fast and slow jobs, cache on the catalog, and add path filters per module.

**Example:** `.github/workflows/ci.yml:45-58`
```yaml
- name: Verify formatting
  run: ./gradlew spotlessCheck

- name: Build and test
  run: ./gradlew build --no-daemon
...
- name: Publish test report
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: test-results
    path: '**/build/reports/tests/test/'
```
Point at the report path: it matches the `test` task only, so `integrationTest` output is never uploaded.

**Code:** `.github/workflows/ci.yml:23-49` (the job and its steps), `.github/workflows/ci.yml:51-66` (the two upload steps)

**If they push:** "Do you trust your local green?" No: the test that fails in CI passes on my macOS laptop, so local green only proves the macOS path.

---

### S6. Value objects as a security boundary: what do they cover and what not?

**Answer:** They cover shape and collision. Keys are namespaced as `tenant#x:event#id`, so one tenant's keys cannot collide with another's, and because `TenantId` only allows lowercase letters, digits and hyphens, and `EventId` must be a canonical UUID, characters that could be abusive inside a key or a label never get in. The types also stop an argument swap at compile time. They do not cover authorisation, and they do not say where the tenant comes from, which is a token concern in a later phase: a perfectly valid `TenantId` can still be the wrong tenant. Phase 0 proves the type behaviour only, through the unit tests. It does not prove Redis atomicity, which the `EventId` comment says depends on `SET NX EX` and is exercised in a later phase.

**Example:** `libs/platform/src/main/java/com/streampulse/platform/domain/EventId.java:90-92`
```java
public String idempotencyKey(TenantId tenant) {
    return tenant.keyPrefix() + ":event#" + value;
}
```
The method takes a `TenantId`, not a string, so the key cannot be built without a validated tenant.

**Code:** `libs/platform/src/main/java/com/streampulse/platform/domain/EventId.java:83-92` (the key and its comment), `libs/platform/src/main/java/com/streampulse/platform/domain/TenantId.java:101-110` (`keyPrefix`), `libs/platform/src/test/java/com/streampulse/platform/domain/EventIdTest.java:93` (different tenants do not collide)

**If they push:** "Where does the tenant come from at runtime?" From the authenticated token in a later phase; I would point at that phase's code and not claim Phase 0 covers it.

---

### S7. CI is red on a test that passes locally. What is wrong and how would you fix it?

**Answer:** The failing test is `BlockingIsolationTest` in `query-api`, and the failure was an assertion that every request thread name contains `http-nio`, where the actual names were `webflux-http-epoll-2`. The cause is the platform: on the Linux CI runner Reactor Netty uses the epoll transport, so event-loop threads are named with `epoll`, while on macOS they use the NIO transport and the names contain `nio`. So the test is coupled to the transport my laptop happens to use. There is a worse consequence in the same assertion block. The negative half, which says port threads must not contain `http-nio`, would pass vacuously on Linux even if a blocking call ran on an event loop, because an event-loop thread there would never be named `http-nio`. Today the positive assertion fails first and protects against that, so I must not fix it by deleting it. A proposed fix is to match `http-(nio|epoll|kqueue)`; I have not applied it, and I have not run it on a Linux runner.

**Example:** `services/query-api/src/test/java/com/streampulse/query/config/BlockingIsolationTest.java:108-111`
```java
assertThat(REQUEST_THREADS).isNotEmpty().allMatch(name -> name.contains("http-nio"));
assertThat(PORT_THREADS).hasSize(4);
assertThat(PORT_THREADS)
        .noneMatch(name -> name.contains("http-nio"))
```
Line 108 is the positive check that failed, and the `noneMatch` below it is the half that would be vacuous on Linux.

**Code:** `services/query-api/src/test/java/com/streampulse/query/config/BlockingIsolationTest.java:88-113` (the test), `services/query-api/src/test/java/com/streampulse/query/config/BlockingIsolationTest.java:32-33` (the comment about thread names)

**If they push:** "How would you stop this class of bug?" Run the same test on Linux before merging, for example in a container locally, and compare names against a transport-independent marker, not a transport-specific substring.

---

## Say this honestly

- CI has run on GitHub and is failing. All seven runs on `main` since 2026-10-12 ended in failure (one cancelled), and the last three failed on `BlockingIsolationTest` because Linux uses epoll thread names. The proposed fix is not applied, and other failures may be hidden behind it.
- The earlier 2026-10-12 run, "services ad docs", failed early for a reason I did not examine.
- The Gradle configuration cache is not enabled: there is no `gradle.properties` in the repo or in `~/.gradle`, and every run prints "Consider enabling configuration cache to speed up this build".
- `Clock` injection is a stated rule that the shared platform rules do not enforce; only the control-plane module bans `Instant.now()` and `LocalDateTime.now()`. Coverage is a report, not a gate.
- `integrationTest` is not a separate source set (an old note says it is); it is a second task over the same one.
- Spec task boxes in `specs/00-foundation/tasks.md` are stale; the `settings.gradle.kts` header still says there are no services.
- The incremental-build figure (about 1 s) is a quoted approximation, not a pasted run.
- The exact semantics of `RepositoriesMode.PREFER_SETTINGS` are not verified by me; check the Gradle docs.

## Stories and numbers

[What broke](../05-what-broke.md): stories 4, 5, 11. [Numbers](../06-numbers.md): build rows.
