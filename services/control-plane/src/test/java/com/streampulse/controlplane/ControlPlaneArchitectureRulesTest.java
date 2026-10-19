/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.GeneralCodingRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * This module's boundaries as tests. They live here, not in libs/platform, because ArchUnit
 * reads bytecode from the classpath and platform cannot see a service's classes: a rule that
 * cannot see the code it protects passes forever.
 */
class ControlPlaneArchitectureRulesTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.streampulse.controlplane");

    @Test
    @DisplayName("the domain knows neither Spring, JPA, Avro, Jackson nor the contracts module")
    void domainIsFrameworkFree() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "org.apache.avro..",
                        "com.fasterxml.jackson..",
                        "com.streampulse.contracts..",
                        "io.micrometer..")
                .because("the rules must run in a unit test with none of them on the classpath")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("the domain depends on neither the application, the adapters nor the wiring")
    void domainDependsOnNothingOutward() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..application..", "..adapter..", "..config..")
                .because("dependencies point inward")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("the application layer reaches the world only through domain ports")
    void applicationDoesNotDependOnAdapters() {
        noClasses()
                .that()
                .resideInAPackage("..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..adapter..", "jakarta.persistence..", "org.hibernate..", "org.springframework.web..")
                .because("a service that names an entity or a controller cannot be tested without them")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("controllers do not touch persistence or the outbox")
    void webDoesNotTouchPersistence() {
        noClasses()
                .that()
                .resideInAPackage("..adapter.in..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..adapter.out..", "jakarta.persistence..")
                .because("every write must pass through a transactional service, or the outbox row is skipped")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("constructor injection only")
    void noFieldInjection() {
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION.check(PRODUCTION);
    }

    @Test
    @DisplayName("production code never reads the wall clock directly")
    void noDirectClockUse() {
        ArchRuleDefinition.noClasses()
                .that()
                .resideOutsideOfPackage("..config..")
                .should()
                .callMethod(java.time.Instant.class, "now")
                .orShould()
                .callMethod(java.time.LocalDateTime.class, "now")
                .because("time comes from the injected Clock so it can be controlled in tests")
                .check(PRODUCTION);
    }
}
