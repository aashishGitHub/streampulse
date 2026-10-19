// ============================================================================
//  activity-feed: "friends are watching" (SD2). A follow graph in Postgres, hybrid fan-out into
//  Redis timelines, and a ranked, cursor-paginated feed. ADR-0021.
// ============================================================================

plugins {
    id("streampulse.spring-service")
}

dependencies {
    implementation(project(":libs:contracts"))

    implementation(libs.spring.boot.starter.web) // MVC: every call blocks, and virtual threads make that cheap
    implementation(libs.spring.boot.starter.validation) // request bodies, parameters and typed properties
    implementation(libs.spring.boot.starter.jdbc) // JdbcTemplate, Hikari
    implementation(libs.spring.boot.starter.data.redis) // StringRedisTemplate, pipelining
    implementation(libs.spring.kafka)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql) // required for PostgreSQL since Flyway 10
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.postgresql)
}
