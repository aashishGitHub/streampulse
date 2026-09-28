// ============================================================================
//  ingest-gateway — the front door.
//
//  Accepts batched telemetry over HTTP (and gRPC), validates it, deduplicates
//  it, and publishes it to Kafka. Everything upstream of Kafka happens here, so
//  this is where correctness is decided.
// ============================================================================

plugins {
    id("streampulse.spring-service")
}

dependencies {
    // Our own modules. `implementation`, not `api`: a service is a leaf — nobody
    // compiles against it, so nothing needs to see these transitively.
    implementation(project(":libs:platform")) // TenantId, EventId, architecture rules
    implementation(project(":libs:contracts")) // generated Avro types

    implementation(libs.spring.boot.starter.web) // Spring MVC (not WebFlux — see ADR-0006)
    implementation(libs.spring.boot.starter.validation) // Bean Validation on request bodies
    implementation(libs.spring.boot.starter.data.redis) // idempotency claims
    // ---- the event backbone ----
    // Kinesis is the DEFAULT path (AWS-native, and moto fakes it locally for free).
    // Kafka is a second adapter behind a profile — open source, free, and the comparison
    // between shards and partitions is worth having. See ADR-0008.
    implementation(platform(libs.aws.bom))
    implementation(libs.aws.kinesis)
    implementation(libs.aws.netty.client)
    implementation(libs.spring.kafka)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.awaitility)
}

// ---------------------------------------------------------------------------
// Kinesis speaks CBOR (a binary JSON encoding) by default in the AWS SDKs. moto
// does not implement it, and the failure is deeply unhelpful: HTTP 500 with no
// message, after the SDK has silently retried four times.
//
// Disabling CBOR makes the SDK fall back to plain JSON, which moto handles.
// Real AWS accepts both, so this only affects local simulation.
//
// VERIFY: if moto gains CBOR support, or the SDK changes the property name,
// this can be removed. Check before assuming it is still needed.
// ---------------------------------------------------------------------------
tasks.withType<Test>().configureEach {
    systemProperty("aws.cborEnabled", "false")
}
