/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed;

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
class FeedArchitectureRulesTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.streampulse.feed");

    private static final String[] INFRASTRUCTURE = {
        "org.apache.kafka..",
        "org.springframework..",
        "org.apache.avro..",
        "com.streampulse.contracts..",
        "io.micrometer..",
        "java.sql..",
        "javax.sql..",
        "io.lettuce..",
        "org.postgresql..",
        "jakarta.."
    };

    @Test
    @DisplayName("the domain knows no Kafka, Spring, Avro, JDBC, Redis, Micrometer or Jakarta type")
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
    @DisplayName("the domain and application depend on neither the adapters nor the wiring")
    void dependenciesPointInward() {
        noClasses()
                .that()
                .resideInAnyPackage("..domain..", "..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..adapter..", "..config..")
                .because("dependencies point inward")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("the domain does not depend on the application layer")
    void domainDoesNotDependOnApplication() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..application..")
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
                .resideInAPackage("..config..")
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
