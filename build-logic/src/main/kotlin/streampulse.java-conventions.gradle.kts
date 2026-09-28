// ============================================================================
//  streampulse.java-conventions
//  Applied by EVERY Java module. Java version, tests, coverage, formatting,
//  compiler strictness — configured once, inherited everywhere.
// ============================================================================

import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`                      // NOT plain `java` — see the note below
    jacoco                              // code coverage (built into Gradle)
    id("com.diffplug.spotless")         // auto-formatting
}

// ---------------------------------------------------------------------------
// WHY `java-library` RATHER THAN `java`
//
// It adds one thing: the `api` vs `implementation` distinction, which controls
// what LEAKS onto a consumer's compile classpath.
//
//   implementation(x)  x is used INSIDE this module. Consumers cannot see x.
//   api(x)             x appears in this module's PUBLIC signatures, so
//                      consumers need it to compile against us.
//
// Default to `implementation`. It keeps consumers' classpaths small, and it
// means you can swap the library later without breaking anyone. Use `api` only
// when a type genuinely appears in your public API — e.g. libs:platform exposes
// SLF4J types, so SLF4J is `api` there.
//
// .NET has no real equivalent: a project reference is transitively visible by
// default. This is Gradle being stricter than NuGet, and it is a good thing —
// it is how you stop one module's transitive dependency becoming everyone's
// problem.
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// READING THE VERSION CATALOG FROM A CONVENTION PLUGIN
//
// In a normal build.gradle.kts you write `libs.assertj` and it just works:
// Gradle generates type-safe accessors for the catalog.
//
// Inside a PRECOMPILED SCRIPT PLUGIN (this file) those accessors do NOT exist.
// The plugin is compiled before any project's accessors are generated, so
// `libs.assertj` fails with "Unresolved reference". This surprises everyone
// once; it is one of the best-known rough edges in Gradle.
//
// There is a popular workaround that injects the generated `LibrariesForLibs`
// class onto the classpath via a `files(...)` reflection trick. We deliberately
// do NOT use it: it is fragile and it teaches you a hack instead of the model.
//
// Instead we use the ordinary, documented API. It is more verbose, but every
// line says exactly what it does — and lookups fail loudly at configuration
// time with the missing alias named, rather than silently resolving wrong.
// ---------------------------------------------------------------------------
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).orElseThrow {
    IllegalStateException("No library '$alias' in gradle/libs.versions.toml")
}

fun bundle(alias: String) = libs.findBundle(alias).orElseThrow {
    IllegalStateException("No bundle '$alias' in gradle/libs.versions.toml")
}

fun version(alias: String) = libs.findVersion(alias).orElseThrow {
    IllegalStateException("No version '$alias' in gradle/libs.versions.toml")
}.requiredVersion

// ---------------------------------------------------------------------------
// TOOLCHAIN — Gradle DOWNLOADS this exact JDK if it isn't present, so the build
// is identical on your Mac, a colleague's Linux box and CI. It does NOT use
// whatever `java -version` happens to say.
//
// This is strictly better than "install the right SDK and hope", and it is the
// single most valuable Gradle feature to know about.
// ---------------------------------------------------------------------------
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(version("java").toInt())
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"          // never rely on the platform default
    options.compilerArgs.addAll(listOf(
        "-Xlint:all",                   // turn on every compiler warning
        "-Xlint:-processing",           // ...except annotation-processor noise
        "-parameters"                   // keep parameter names in bytecode.
                                        // Spring, Jackson and Mockito all read
                                        // them; without this you get cryptic
                                        // "arg0, arg1" failures at runtime.
    ))
}

// ---------------------------------------------------------------------------
// TESTS
// ---------------------------------------------------------------------------
dependencies {
    // platform() imports a BOM: it aligns versions across a family of artifacts
    // WITHOUT adding a dependency itself.
    add("testImplementation", platform(lib("junit-bom")))
    add("testImplementation", bundle("testing"))
    add("testImplementation", lib("archunit-junit5"))

    // JUnit 5 needs its launcher on the test runtime classpath. Omit it and you
    // get "no tests found" — a confusing failure, because compilation succeeded.
    add("testRuntimeOnly", lib("junit-platform-launcher"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()                  // run JUnit 5, not the ancient JUnit 4 runner

    // Show what actually happened. Gradle's default output is famously silent,
    // which teaches people to distrust their own test suite.
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false     // flip to true when debugging a test
    }

    maxHeapSize = "1g"

    // Coverage is produced after every test run, so `./gradlew test` always
    // leaves a usable report behind at build/reports/jacoco/.
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required = true             // for CI tooling
        html.required = true            // build/reports/jacoco/test/html/index.html
    }
}

// ---------------------------------------------------------------------------
// FORMATTING — mechanical, not a matter of taste.
//
//   ./gradlew spotlessApply   rewrites files to the standard
//   ./gradlew spotlessCheck   fails the build if anything is off (runs in CI)
//
// The point is to delete an entire category of code-review comment. Nobody
// should ever again write "nit: spacing" on a pull request.
// ---------------------------------------------------------------------------
spotless {
    java {
        palantirJavaFormat(version("palantirFormat"))
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()

        // Every source file gets this header — a cheap pointer to the spec that
        // explains why the file exists.
        licenseHeader("/* StreamPulse — see docs/ and specs/ for the design. */\n\n")
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
