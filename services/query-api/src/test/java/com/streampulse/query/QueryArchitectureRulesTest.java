/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * This module's boundaries as tests. ArchUnit reads bytecode from the classpath, so the rules live
 * beside the code they protect: a rule that cannot see the classes passes forever.
 */
class QueryArchitectureRulesTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.streampulse.query");

    @Test
    @DisplayName("domain and application know neither Spring, Reactor, Redis nor JDBC")
    void coreIsFrameworkFree() {
        noClasses()
                .that()
                .resideInAnyPackage("..domain..", "..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "reactor..",
                        "org.reactivestreams..",
                        "io.lettuce..",
                        "java.sql..",
                        "javax.sql..",
                        "org.postgresql..",
                        "com.zaxxer.hikari..",
                        "com.fasterxml.jackson..")
                .because("the rules must run in a unit test with none of them on the classpath")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("dependencies point inward: domain depends on nothing, application only on domain")
    void dependenciesPointInward() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..application..", "..adapter..", "..config..")
                .check(PRODUCTION);
        noClasses()
                .that()
                .resideInAPackage("..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..adapter..", "..config..")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("Reactor lives only in the adapters")
    void reactorOnlyInAdapters() {
        noClasses()
                .that()
                .resideOutsideOfPackage("..adapter..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("reactor..", "org.reactivestreams..")
                .because("a Flux in the core would make blocking-versus-reactive a question the core must answer")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("inbound adapters do not reach into outbound ones, and no adapter reaches into the wiring")
    void adaptersStayApart() {
        noClasses()
                .that()
                .resideInAPackage("..adapter.in..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..adapter.out..")
                .because("the inbound side owns the port it consumes; the wiring connects the two")
                .check(PRODUCTION);
        noClasses()
                .that()
                .resideInAPackage("..adapter..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..config..")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("constructor injection only: no field or setter injection")
    void constructorInjectionOnly() {
        noFields()
                .should()
                .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                .orShould()
                .beAnnotatedWith("jakarta.annotation.Resource")
                .check(PRODUCTION);
        noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.beans.factory.annotation.Autowired")
                .because("@Autowired on a constructor is redundant and on anything else is forbidden")
                .check(PRODUCTION);
    }
}
