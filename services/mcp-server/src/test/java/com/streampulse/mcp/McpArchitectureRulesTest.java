/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** This module's boundaries as tests: they live here because ArchUnit reads this module's bytecode. */
class McpArchitectureRulesTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.streampulse.mcp");

    @Test
    @DisplayName("the domain knows no framework, no protocol library, no JSON library, no HTTP client, no logger")
    void domainIsFrameworkFree() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "org.springaicommunity..",
                        "io.modelcontextprotocol..",
                        "com.fasterxml.jackson..",
                        "java.net.http..",
                        "org.slf4j..")
                .because("the rules must run in a unit test with none of them on the classpath")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("the application layer (use cases and ports) is also free of frameworks, protocol and HTTP")
    void applicationIsFrameworkFree() {
        noClasses()
                .that()
                .resideInAPackage("..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "org.springaicommunity..",
                        "io.modelcontextprotocol..",
                        "com.fasterxml.jackson..",
                        "java.net.http..")
                .because("use cases are tested with fake ports, nothing else")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("dependencies point inward")
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
        noClasses()
                .that()
                .resideInAPackage("..adapter.in..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..adapter.out..")
                .because("the protocol side talks to use cases, never straight to a backend client")
                .check(PRODUCTION);
        noClasses()
                .that()
                .resideInAPackage("..adapter.out..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..adapter.in..")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("the backend adapters can only read: no POST, PUT, DELETE or arbitrary-method request is ever built")
    void httpAdapterIsGetOnly() {
        noClasses()
                .that()
                .resideInAPackage("..adapter.out..")
                .should()
                .callMethod(HttpRequest.Builder.class, "POST", HttpRequest.BodyPublisher.class)
                .orShould()
                .callMethod(HttpRequest.Builder.class, "PUT", HttpRequest.BodyPublisher.class)
                .orShould()
                .callMethod(HttpRequest.Builder.class, "DELETE")
                .orShould()
                .callMethod(HttpRequest.Builder.class, "method", String.class, HttpRequest.BodyPublisher.class)
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.net.HttpURLConnection")
                .orShould()
                .dependOnClassesThat()
                .resideInAPackage("org.springframework.web.client..")
                .because("this server is read-only (ADR-0022); a second HTTP stack would bypass this rule")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("a port method that sounds like a write cannot be declared")
    void portsAreReadOnly() {
        methods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAPackage("..application.port..")
                .should()
                .haveNameNotMatching(
                        "(?i)(create|update|delete|put|post|patch|save|set|write|remove|add|ack|silence|resolve|mute).*")
                .because("ports expose reads only")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("constructor injection only")
    void noFieldInjection() {
        noFields()
                .should()
                .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                .orShould()
                .beAnnotatedWith("jakarta.inject.Inject")
                .because("CLAUDE.md: constructor injection only")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("time comes from an injected Clock")
    void noAmbientClock() {
        noClasses()
                .should()
                .callMethod(Instant.class, "now")
                .orShould()
                .callMethod(LocalDateTime.class, "now")
                .orShould()
                .callMethod(System.class, "currentTimeMillis")
                .because("CLAUDE.md: inject a Clock")
                .check(PRODUCTION);
    }
}
