/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * This module's boundaries as tests. They live here, not in libs/platform, because ArchUnit
 * reads bytecode from the classpath and platform cannot see a service's classes: a rule that
 * cannot see the code it protects passes forever.
 */
class AlertArchitectureRulesTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.streampulse.alert");

    private static final String[] INFRASTRUCTURE = {
        "org.apache.kafka..",
        "org.springframework..",
        "org.apache.avro..",
        "com.streampulse.contracts..",
        "io.micrometer..",
        "software.amazon..",
        "com.fasterxml.jackson..",
        "jakarta.."
    };

    @Test
    @DisplayName("the domain knows no Kafka, Spring, Avro, AWS, Jackson or Micrometer type")
    void domainIsFrameworkFree() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(INFRASTRUCTURE)
                .because("the rules must run in a unit test with none of them on the classpath")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("the application layer is framework-free too")
    void applicationIsFrameworkFree() {
        noClasses()
                .that()
                .resideInAPackage("..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(INFRASTRUCTURE)
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("the graph package imports nothing but java.*, so the control plane can lift it unchanged")
    void graphPackageIsPureJava() {
        noClasses()
                .that()
                .resideInAPackage("..domain.graph..")
                .should()
                .dependOnClassesThat()
                .resideOutsideOfPackages("java..", "com.streampulse.alert.domain.graph..")
                .because("it is meant to move to a shared library")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("the domain and application depend on neither the adapters nor the wiring")
    void dependenciesPointInward() {
        noClasses()
                .that()
                .resideInAnyPackage("..domain..", "..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..adapter..", "..config..", "..lifecycle..")
                .because("dependencies point inward")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("adapters do not reach into the Spring wiring")
    void adaptersDoNotDependOnConfig() {
        noClasses()
                .that()
                .resideInAPackage("..adapter..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..config..", "..lifecycle..")
                .because("an adapter must be constructible with plain new in a test")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("dependencies are injected through constructors, never fields")
    void noFieldInjection() {
        noFields()
                .should()
                .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                .orShould()
                .beAnnotatedWith("jakarta.annotation.Resource")
                .check(PRODUCTION);
    }
}
