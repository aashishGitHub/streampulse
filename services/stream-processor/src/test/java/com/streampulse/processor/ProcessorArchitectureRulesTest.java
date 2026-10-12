/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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
class ProcessorArchitectureRulesTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.streampulse.processor");

    @Test
    @DisplayName("the domain knows neither Kafka, Spring, Avro nor Micrometer")
    void domainIsFrameworkFree() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.apache.kafka..",
                        "org.springframework..",
                        "org.apache.avro..",
                        "com.streampulse.contracts..",
                        "io.micrometer..")
                .because("the rules must run in a unit test with none of them on the classpath")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("the domain depends on neither the adapters nor the wiring")
    void domainDependsOnNothingOutward() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..adapter..", "..config..")
                .because("dependencies point inward")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("the topology does not reach into the Spring wiring")
    void adaptersDoNotDependOnConfig() {
        noClasses()
                .that()
                .resideInAPackage("..adapter..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..config..", "org.springframework..")
                .because("the topology must stay constructible with plain new, so TopologyTestDriver can run it")
                .check(PRODUCTION);
    }
}
