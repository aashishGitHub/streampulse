// ============================================================================
//  streampulse.spring-service
//  Applied by every deployable Spring Boot service. Layered on top of
//  java-conventions, so a service gets the toolchain, tests, coverage and
//  formatting from there, plus Spring wiring from here.
// ============================================================================

import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("streampulse.java-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).orElseThrow {
    IllegalStateException("No library '$alias' in gradle/libs.versions.toml")
}

// ---------------------------------------------------------------------------
// WHAT THE dependency-management PLUGIN DOES
//
// It imports the Spring Boot BOM, which declares a consistent version for every
// Spring artifact AND for the third-party libraries Boot integrates with —
// Jackson, Kafka clients, Micrometer, Testcontainers, and so on.
//
// That is why the catalog lists `spring-boot-starter-web` with NO version. If
// you pinned versions yourself you would eventually mix a Jackson that Boot was
// not tested against, and get a NoSuchMethodError at runtime rather than a
// resolution failure at build time.
//
// .NET ANALOGY: the closest thing is a metapackage that fixes a whole framework's
// versions together, so you upgrade the framework rather than 40 packages.
// ---------------------------------------------------------------------------
dependencies {
    // Every service gets these three. Anything else is the service's own choice.
    add("implementation", lib("spring-boot-starter-actuator")) // health, metrics, probes
    add("implementation", lib("micrometer-prometheus")) // /actuator/prometheus
    add("testImplementation", lib("spring-boot-starter-test"))
}

// ---------------------------------------------------------------------------
// VIRTUAL THREADS (Java 21, Project Loom)
//
// Every request is handled on a virtual thread instead of a pooled platform
// thread. For this workload — mostly blocking on Kafka, Redis and HTTP — that is
// the right model: a blocked virtual thread UNMOUNTS from its carrier instead of
// occupying an OS thread, so concurrency is bounded by memory rather than by a
// pool size someone guessed.
//
// It is set here rather than per service so the choice is uniform and visible.
// ADR-0006 records why this instead of WebFlux.
//
// CAVEAT worth knowing: a virtual thread PINS its carrier inside a `synchronized`
// block that blocks. Under Java 21 that can starve the carrier pool. Phase 9
// measures it. Prefer ReentrantLock over synchronized around blocking calls.
// VERIFY: JEP 491 reportedly removes this pinning — check your JDK.
// ---------------------------------------------------------------------------
tasks.withType<Test>().configureEach {
    systemProperty("spring.threads.virtual.enabled", "true")
}

// A reproducible jar: no timestamps, stable file order. Two builds of the same
// source then produce byte-identical output, which makes "did anything actually
// change?" answerable.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
