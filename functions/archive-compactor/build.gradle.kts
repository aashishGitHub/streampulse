// ============================================================================
//  archive-compactor — a Lambda-shaped function that compacts the raw archive.
//
//  A Spring Cloud Function `java.util.function.Function` registered in the function
//  catalog. It runs in-process and in tests; it has NEVER been deployed as a real
//  serverless function (ADR-0020). The Lambda adapter would be a deploy-time
//  concern and is not added here.
// ============================================================================

plugins {
    id("streampulse.spring-service")
}

dependencyManagement {
    imports {
        val bom = libs.spring.cloud.bom.get()
        mavenBom("${bom.module}:${bom.versionConstraint.requiredVersion}")
    }
}

dependencies {
    implementation(project(":libs:platform")) // TenantId: the same validation as every other service

    implementation(libs.spring.cloud.function.context)
    implementation(platform(libs.aws.bom))
    implementation(libs.aws.s3)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
}
