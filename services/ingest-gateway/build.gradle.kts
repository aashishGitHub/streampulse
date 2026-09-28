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
    implementation(libs.spring.boot.starter.data.redis) // idempotency claims + rate-limit buckets
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.rs) // JWT bearer tokens
    implementation(libs.resilience4j.spring.boot3)
    // ---- the event backbone ----
    // Kinesis is the DEFAULT path (AWS-native, and moto fakes it locally for free).
    // Kafka is a second adapter behind a profile — open source, free, and the comparison
    // between shards and partitions is worth having. See ADR-0008.
    implementation(platform(libs.aws.bom))
    implementation(libs.aws.kinesis)
    implementation(libs.aws.netty.client)
    implementation(libs.spring.kafka)

    // ---- the second inbound protocol (BS-09) ----
    // The stubs themselves come from :libs:contracts, which exposes grpc-api/stub/protobuf
    // as `api`. Two things it does NOT expose, because they are a SERVICE's choice and not a
    // contract's:
    //
    //   grpc-netty-shaded  the transport. `runtimeOnly` on purpose — we name no Netty type,
    //                      we only need a ServerProvider on the classpath for
    //                      ServerBuilder.forPort() to find via the ServiceLoader. Making it
    //                      `implementation` would invite someone to import a shaded class,
    //                      and shaded classes are not API.
    //   proto-google-common-protos  google.rpc.RetryInfo, which we put in error trailers.
    //                      Compile-time type ⇒ declared explicitly, never leaned on as a
    //                      transitive.
    runtimeOnly(libs.grpc.netty.shaded)
    implementation(libs.proto.google.common.protos)

    testImplementation(platform(libs.testcontainers.bom))
    // An in-process gRPC server: real interceptors, real stubs, real flow control, no sockets
    // and no port to collide with. See GrpcIngestInProcessTest.
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.awaitility)
    testImplementation(libs.spring.security.test)
}

// ---------------------------------------------------------------------------
// The Keycloak realm that `make up-core` boots is ALSO on the test classpath, so
// KeycloakOidcIT imports the identical file rather than a copy of it.
//
// A copy would drift, and the drift is invisible: the test would keep passing
// against a realm nobody runs, which is the failure mode the test exists to
// prevent. One file, two consumers — compose mounts the directory, this puts it
// on the classpath, and MountableFile.forClasspathResource finds it at
// "realms/streampulse-realm.json".
//
// The adversary realm deliberately does NOT live here: it is in src/test/
// resources, so it can never be imported into a developer's local stack.
// ---------------------------------------------------------------------------
sourceSets {
    test {
        resources {
            srcDir(rootProject.layout.projectDirectory.dir("docker/keycloak"))
        }
    }
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
//
// Applied to JavaExec as well as Test, because `bootRun` is a JavaExec and it
// talks to the SAME moto. Without it, `make up-core && ./gradlew bootRun` accepts
// a request, retries four times and answers 500 — and the message names neither
// CBOR nor moto:
//
//   KinesisException: Service returned HTTP status code 500
//     (Service: Kinesis, Status Code: 500, Request ID: null) (SDK Attempt Count: 4)
//
// It is a SYSTEM property, not a Spring one, so `--args=--aws.cborEnabled=false`
// does not help and looks like it should. Worth knowing before losing an hour to
// it: the tests passed the whole time, because only they had the flag.
// ---------------------------------------------------------------------------
tasks.withType<Test>().configureEach {
    systemProperty("aws.cborEnabled", "false")
}

tasks.withType<JavaExec>().configureEach {
    systemProperty("aws.cborEnabled", "false")
}
