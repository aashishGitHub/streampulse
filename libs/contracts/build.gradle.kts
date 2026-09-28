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

dependencies {
    // avro (runtime) IS part of our public API: generated classes extend Avro types,
    // so anything consuming this module needs it on its compile classpath.
    api(libs.avro)

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

// Add the generated directory to the main source set so javac compiles it, and
// make compileJava depend on the generator. Without the dependsOn you get a race:
// a clean build sometimes compiles before the sources exist.
sourceSets.main {
    java.srcDir(avroOutputDir)
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(generateAvroJava)

    // Avro's generated code triggers deprecation and "this-escape" warnings that we
    // neither caused nor can fix. -Xlint:all is on for OUR code (see the conventions
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
