// ============================================================================
//  libs:contracts — the data contracts every service codes against.
//
//  Both producers and consumers depend on this module, which is exactly why it
//  is its own module: if the schemas lived inside ingest-gateway, every consumer
//  would depend on ingest-gateway's jar and everything else inside it.
// ============================================================================

plugins {
    id("streampulse.java-conventions")
}

// ---------------------------------------------------------------------------
// TOOL CONFIGURATIONS for protobuf codegen (BS-09).
//
// These resolve the protoc compiler and the gRPC codegen plugin as ordinary
// dependencies. They are deliberately their OWN configurations: a native
// executable must never land on anybody's compile or runtime classpath, and the
// only way to guarantee that is to keep it off `implementation` entirely. Same
// isolation the Avro compiler gets in build-logic.
//
// isCanBeResolved = true, isCanBeConsumed = false: this build resolves them for
// its own use and publishes them to nobody.
// ---------------------------------------------------------------------------
// `configurations.create(name)`, not the `by configurations.creating` delegate: the delegate
// form is deprecated in Gradle 9.6 and removed in 10, and a learning repo should not teach the
// syntax that is on its way out.
val protocTool = configurations.create("protocTool") {
    isCanBeResolved = true
    isCanBeConsumed = false
}
val grpcCodegenTool = configurations.create("grpcCodegenTool") {
    isCanBeResolved = true
    isCanBeConsumed = false
}

// The classifier of the protoc/plugin binary to download. Maven Central has one
// artifact per platform, so the build has to say which machine it is on.
//
// VERIFY when a new platform joins the team or CI: the published classifiers are
// listed on Maven Central under com.google.protobuf:protoc. Failing loudly here
// is deliberate — a wrong guess would surface as "cannot run binary" much later.
val protocClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val aarch64 = arch == "aarch64" || arch == "arm64"
    when {
        os.contains("mac") || os.contains("darwin") -> if (aarch64) "osx-aarch_64" else "osx-x86_64"
        os.contains("linux") -> if (aarch64) "linux-aarch_64" else "linux-x86_64"
        os.contains("windows") -> "windows-x86_64"
        else -> error("No published protoc binary for os='$os' arch='$arch'; see Maven Central.")
    }
}

dependencies {
    // avro (runtime) IS part of our public API: generated classes extend Avro types,
    // so anything consuming this module needs it on its compile classpath.
    api(libs.avro)

    // ---- gRPC contract (BS-09) ----
    // `api`, not `implementation`: the generated stubs ARE this module's public surface.
    // A consumer writing `extends TelemetryIngestGrpc.TelemetryIngestImplBase` needs every
    // one of these to compile, so hiding them would just move the dependency declaration
    // into each service.
    api(platform(libs.grpc.bom)) // aligns every grpc-* version, consumers included
    api(libs.protobuf.java)
    api(libs.grpc.api)
    api(libs.grpc.stub)
    api(libs.grpc.protobuf)

    // The executables. Classifier and extension cannot be expressed in the version catalog
    // (a catalog entry is group:name:version and nothing else), so the coordinate is built
    // here — but the VERSION still comes from the catalog, which is the rule that matters.
    protocTool("com.google.protobuf:protoc:${libs.versions.protobuf.get()}:$protocClassifier@exe")
    grpcCodegenTool("io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}:$protocClassifier@exe")

    testImplementation(libs.swagger.parser)
    testImplementation(libs.jackson.yaml)
}

// ---------------------------------------------------------------------------
// CODE GENERATION
//
// AvroCodegenTask lives in build-logic/ — see that file for why we call
// SpecificCompiler directly rather than using a plugin or the avro-tools CLI.
// ---------------------------------------------------------------------------
val avroOutputDir = layout.buildDirectory.dir("generated/avro/java")

val generateAvroJava by tasks.registering(AvroCodegenTask::class) {
    group = "build"
    description = "Generates Java classes from the .avsc schemas in src/main/avro"
    schemaDir = layout.projectDirectory.dir("src/main/avro")
    outputDir = avroOutputDir
}

// ---------------------------------------------------------------------------
// PROTOBUF + gRPC CODEGEN (BS-09 / REQ-GR-01)
//
// ProtoCodegenTask lives in build-logic/ alongside AvroCodegenTask — see that file
// for why we drive protoc ourselves instead of applying com.google.protobuf.
// ---------------------------------------------------------------------------
val protoOutputDir = layout.buildDirectory.dir("generated/proto/java")

val generateProtoJava = tasks.register<ProtoCodegenTask>("generateProtoJava") {
    group = "build"
    description = "Generates protobuf message classes and gRPC stubs from src/main/proto"
    protoDir = layout.projectDirectory.dir("src/main/proto")
    outputDir = protoOutputDir
    protocExecutable.from(protocTool)
    grpcPluginExecutable.from(grpcCodegenTool)
    // The well-known .proto files (timestamp.proto and friends) ship inside this jar.
    // Taking them from the artifact we compile against is what stops the definitions and
    // the runtime drifting apart.
    wellKnownTypes.from(configurations.compileClasspath.map { cp ->
        cp.filter { it.name.startsWith("protobuf-java-") }
    })
}

// Add both generated directories to the main source set so javac compiles them, and make
// compileJava depend on the generators. Without the dependsOn you get a race: a clean build
// sometimes compiles before the sources exist.
sourceSets.main {
    java.srcDir(avroOutputDir)
    java.srcDir(protoOutputDir)
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(generateAvroJava, generateProtoJava)

    // Avro's and protobuf's generated code trigger deprecation and "this-escape" warnings that
    // we neither caused nor can fix. -Xlint:all is on for OUR code (see the conventions
    // plugin); silencing it here keeps real warnings visible instead of drowning them.
    options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-this-escape", "-Xlint:-rawtypes", "-Xlint:-serial"))
}

// Spotless must not try to format generated code: it would reformat files that get
// deleted and rewritten on the next build, producing an endless diff.
spotless {
    java {
        targetExclude("build/generated/**")
    }
}
