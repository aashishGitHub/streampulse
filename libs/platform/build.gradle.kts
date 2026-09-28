// ============================================================================
//  libs:platform — our own shared library, eventually a Spring Boot STARTER.
//
//  Phase 0:  architecture rules only (proves ArchUnit is wired up).
//  Phase 2+: auto-configuration, the RFC 7807 error model, idempotency,
//            tracing — the pieces every service would otherwise re-implement.
//
//  Building a starter is also the best possible answer to the interview
//  question "how does Spring Boot auto-configuration actually work?", because
//  you will have written one. See interview-qa/01-spring-core.md Q4.
// ============================================================================

plugins {
    id("streampulse.java-conventions")
}

dependencies {
    // A library depends on the logging FACADE, never on an implementation.
    // Binding to Logback here would force that choice on every consumer.
    api(libs.slf4j.api)
}
