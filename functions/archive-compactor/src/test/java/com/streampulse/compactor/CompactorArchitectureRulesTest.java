/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** This module's boundaries as tests; they live here because ArchUnit reads this module's bytecode. */
class CompactorArchitectureRulesTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.streampulse.compactor");

    @Test
    @DisplayName("the domain knows neither Spring nor the AWS SDK")
    void domainIsFrameworkFree() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "software.amazon..", "com.fasterxml..")
                .because("the compaction rules must run in a unit test with none of them on the classpath")
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
    @DisplayName("adapters do not reach into the Spring wiring")
    void adaptersDoNotDependOnConfig() {
        noClasses()
                .that()
                .resideInAPackage("..adapter..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..config..", "org.springframework..")
                .because("an adapter must be constructible with plain new, so a test needs no container")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("nothing is injected into a field")
    void constructorInjectionOnly() {
        noFields()
                .should()
                .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                .because("constructor injection keeps dependencies explicit and final")
                .check(PRODUCTION);
    }
}
