// ============================================================================
//  alert-engine — aggregates and rules in, de-duplicated alerts out.
//
//  The domain (rule strategies, alert state machine, BFS root-cause suppression, Kahn cycle
//  detection) is framework-free and unit-tested alone. Kafka, SQS and SSE are adapters.
//  MVC + SseEmitter on virtual threads, not WebFlux: ADR-0017.
// ============================================================================

plugins {
    id("streampulse.spring-service")
}

dependencies {
    implementation(project(":libs:platform"))
    implementation(project(":libs:contracts"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation) // @Validated on AlertProperties
    implementation(libs.spring.kafka)
    implementation(libs.jackson.databind) // rule payload JSON, alert JSON

    implementation(platform(libs.aws.bom))
    implementation(libs.aws.sqs)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.awaitility)
    testImplementation(libs.swagger.parser)
    testImplementation(libs.jackson.yaml)
}

// SQS does not use CBOR, but the property is cheap insurance against the Kinesis trap documented in
// ingest-gateway's build file: it is a SYSTEM property and a Spring property does not reach it.
tasks.withType<Test>().configureEach {
    systemProperty("aws.cborEnabled", "false")
}

tasks.withType<JavaExec>().configureEach {
    systemProperty("aws.cborEnabled", "false")
}
