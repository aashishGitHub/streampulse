// ============================================================================
//  mcp-server — READ-ONLY tools that let an AI agent ask "what is wrong with tenant X right now?".
//
//  Domain and application are framework-free (ArchUnit enforces it). The MCP protocol (Spring AI's
//  STDIO server starter) and the HTTP clients for query-api / alert-engine are adapters.
//  Transport, version choice and the prompt-injection model: ADR-0022.
// ============================================================================

plugins {
    id("streampulse.spring-service")
}

dependencyManagement {
    imports {
        val bom = libs.spring.ai.bom.get()
        mavenBom("${bom.module}:${bom.versionConstraint.requiredVersion}")
    }
}

dependencies {
    implementation(project(":libs:platform")) // TenantId: one tenant-id rule for the whole platform

    implementation(libs.spring.ai.starter.mcp.server)
    implementation(libs.jackson.databind)
}

// The integration tests start the real server in a child JVM over stdio. They need its classpath as a
// string; java.class.path inside a Gradle test worker is not a reliable source for it.
tasks.withType<Test>().configureEach {
    val testRuntime = sourceSets.test.get().runtimeClasspath
    doFirst { systemProperty("streampulse.test.classpath", testRuntime.asPath) }
}

tasks.bootJar {
    archiveFileName = "mcp-server.jar" // a stable path for .mcp.json
}
