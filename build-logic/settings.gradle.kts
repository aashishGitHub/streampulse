// Settings for the build-logic composite build (our convention plugins).
// It needs its own repositories because it is a genuinely separate build.
rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }

    // Make the MAIN build's version catalog readable from convention plugins,
    // so versions are declared in exactly one file for the whole repo.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
