// ============================================================================
//  ROOT build.gradle.kts
//
//  Deliberately almost empty. The root project is a CONTAINER, not a place to
//  put logic — real configuration lives in convention plugins (build-logic/),
//  so that modules opt IN rather than having behaviour pushed onto them from
//  above.
//
//  If you have seen `subprojects { ... }` or `allprojects { ... }` in an older
//  Gradle build: that is the pattern convention plugins replace. It couples
//  every module to the root, breaks Gradle's configuration cache, and makes
//  "why does this module have that dependency?" unanswerable.
// ============================================================================

tasks.register("architectureReport") {
    group = "verification"
    description = "Prints the module layout and which conventions each module applies."
    doLast {
        println("\nStreamPulse modules")
        println("-".repeat(60))
        subprojects.sortedBy { it.path }.forEach { p ->
            val plugins = p.plugins.mapNotNull { pl ->
                pl::class.simpleName?.takeIf { it.contains("Conventions", true) }
            }
            println("%-28s %s".format(p.path, if (plugins.isEmpty()) "" else plugins.joinToString()))
        }
        println()
    }
}
