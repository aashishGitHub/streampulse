// ============================================================================
//  query-api — dashboards read here: historical queries, a title overview, a live SSE stream.
//
//  The one WebFlux service. The ingest gateway chose MVC + virtual threads (ADR-0006); this
//  workload is streaming with backpressure, which is what Reactor is for (ADR-0014).
//  Do NOT add spring-boot-starter-web: with both on the classpath Boot silently picks MVC.
// ============================================================================

plugins {
    id("streampulse.spring-service")
}

dependencies {
    implementation(project(":libs:platform"))

    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.validation) // @Validated on QueryProperties
    implementation(libs.spring.boot.starter.data.redis.reactive) // StreamReceiver, ReactiveStringRedisTemplate
    implementation(libs.spring.boot.starter.jdbc) // blocking JDBC, isolated on its own executor
    runtimeOnly(libs.postgresql)

    testImplementation(libs.reactor.test)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
