// ============================================================================
//  sink-writer — aggregates in, durable rows and a fast projection out.
//
//  Postgres is the durable idempotent store, Redis a rebuildable projection, and Kafka
//  offsets are committed only after the durable write. ADR-0013.
// ============================================================================

plugins {
    id("streampulse.spring-service")
}

dependencies {
    implementation(project(":libs:platform"))
    implementation(project(":libs:contracts"))

    implementation(libs.spring.boot.starter.web) // actuator needs an HTTP server for the probes
    implementation(libs.spring.boot.starter.validation) // @Validated on SinkProperties
    implementation(libs.spring.boot.starter.jdbc) // JdbcClient, batchUpdate, Hikari
    implementation(libs.spring.boot.starter.data.redis) // StringRedisTemplate, pipelining
    implementation(libs.spring.kafka)
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.postgresql)
}
