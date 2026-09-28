// ============================================================================
//  settings.gradle.kts  —  WHICH modules exist. Runs BEFORE any build script.
// ============================================================================
//
//  GRADLE'S THREE PHASES (worth memorising — it explains most Gradle confusion)
//
//    1. INITIALIZATION  ← this file. Decides which projects are in the build.
//    2. CONFIGURATION   ← every build.gradle.kts runs top-to-bottom, building
//                          a task graph. NO task has executed yet.
//    3. EXECUTION       ← the tasks you asked for actually run.
//
//  The classic beginner bug: putting real work directly in a build script.
//  It runs during CONFIGURATION — on every single build, even `gradle help`.
//
//  .NET ANALOGY: this is the .sln file. It lists projects; it doesn't build them.
// ============================================================================

rootProject.name = "streampulse"

// ---------------------------------------------------------------------------
// COMPOSITE BUILD: build-logic is a SEPARATE Gradle build that produces our
// convention plugins. It is compiled first, then its plugins are available to
// every module below. This is the modern replacement for the old `buildSrc`.
//
// Why not buildSrc? A change to buildSrc invalidates the whole build cache.
// An included build is cached independently and is far friendlier in CI.
// ---------------------------------------------------------------------------
pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// ---------------------------------------------------------------------------
// Where dependencies come from. PREFER_SETTINGS means a module CANNOT declare
// its own repository — so nobody can quietly pull a jar from a random mirror.
// Supply-chain hygiene, enforced by the build rather than by code review.
// ---------------------------------------------------------------------------
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        mavenCentral()
    }
}

// ---------------------------------------------------------------------------
// THE MODULES.
//
// Phase 0 deliberately contains NO Spring and NO services. We prove the build,
// the toolchain, the tests and the architecture rules work on plain Java first.
// Every later phase adds modules here — the file grows as the system does.
//
//   libs:platform  shared kernel → becomes our own Spring Boot starter.
//                  Phase 0 ships the domain primitives every service will use
//                  (TenantId, EventId) plus the architecture rules that police
//                  the whole codebase.
//
// Coming later:
//   libs:contracts            Phase 1  (Avro + OpenAPI + proto codegen)
//   services:ingest-gateway   Phase 2
//   services:stream-processor Phase 3
//   services:sink-writer      Phase 4
//   services:query-api        Phase 5
//   services:control-plane    Phase 6
// ---------------------------------------------------------------------------
include("libs:platform")
