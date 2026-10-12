// ============================================================================
//  stream-processor — raw telemetry in, windowed aggregates out.
//
//  Kafka Streams runs inside this JVM: no separate cluster, only the broker. The
//  topology is a pure function of its inputs (TelemetryTopology.build), so it is
//  tested with TopologyTestDriver, in memory, with no broker. ADR-0012.
// ============================================================================

plugins {
    id("streampulse.spring-service")
}

dependencies {
    implementation(project(":libs:platform"))
    implementation(project(":libs:contracts"))

    implementation(libs.spring.boot.starter.web) // actuator needs an HTTP server for the probes
    implementation(libs.spring.boot.starter.validation) // @Validated on ProcessorProperties
    implementation(libs.spring.kafka) // @EnableKafkaStreams, the factory bean, the Micrometer listener
    implementation(libs.kafka.streams)

    testImplementation(libs.kafka.streams.test.utils)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.awaitility)
}
