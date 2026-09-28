// ============================================================================
//  build-logic  —  where CONVENTION PLUGINS live.
// ============================================================================
//
//  THE PROBLEM THEY SOLVE
//  ----------------------
//  Without them, every module's build file repeats the same 40 lines: Java
//  version, test framework, JaCoCo, compiler flags. Twelve modules later you
//  have twelve slightly-different copies and no way to change them all at once.
//
//  A convention plugin is "our house style, as code". A module then says:
//
//      plugins { id("streampulse.java-conventions") }
//
//  ...and inherits everything. This is DRY applied to the build itself.
//
//  HOW IT WORKS: the `kotlin-dsl` plugin compiles every .gradle.kts file under
//  src/main/kotlin into a real plugin, named after the file. So
//  `streampulse.java-conventions.gradle.kts` becomes the plugin id
//  `streampulse.java-conventions`. Filename IS the plugin id.
// ============================================================================

plugins {
    `kotlin-dsl`   // lets us write plugins in Kotlin DSL, same language as build files
}

dependencies {
    // To APPLY a third-party plugin from inside a convention plugin, you must
    // declare its MARKER ARTIFACT here. This is unintuitive and catches everyone:
    // the plugin id "com.diffplug.spotless" maps to the artifact
    // "com.diffplug.spotless:spotless-plugin-gradle".
    implementation(libs.spotless.plugin)

    // Avro's compiler, so our custom AvroCodegenTask can call SpecificCompiler
    // directly. This is a BUILD-TIME dependency of the build itself — it never
    // reaches any module's compile or runtime classpath.
    implementation(libs.avro.compiler)
}
