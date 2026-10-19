// ============================================================================
//  control-plane — the write model: tenants, titles, alert rules, service nodes.
//
//  Every change also lands in a transactional outbox row in the SAME transaction; Debezium
//  relays that row to config.changes.v1. ADR-0015, ADR-0016.
// ============================================================================

plugins {
    id("streampulse.spring-service")
}

dependencies {
    implementation(project(":libs:platform")) // TenantId
    implementation(project(":libs:contracts")) // ConfigChange + AvroBinaryCodec

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation) // @Valid on DTOs, @Validated on properties
    implementation(libs.spring.boot.starter.data.jpa) // Hibernate, Spring Data, Hikari, spring-jdbc (JdbcClient)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql) // Flyway 10+ split per-database support out of flyway-core
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.debezium.testcontainers) // DebeziumContainer: Kafka Connect + registerConnector
    testImplementation(libs.spring.kafka) // test-side only: the CDC IT reads the topic with a plain KafkaConsumer
}
