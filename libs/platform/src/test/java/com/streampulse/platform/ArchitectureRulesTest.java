/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.platform;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architecture rules, expressed as tests.
 *
 * <h2>Why this file exists</h2>
 *
 * Architecture written in a wiki decays. Every team has a diagram that stopped matching
 * the code eighteen months ago, and nobody notices until a refactor becomes impossible.
 *
 * <p>ArchUnit makes the rule <b>executable</b>. "The domain must not import Spring" stops
 * being a code-review opinion someone has to remember, and becomes a failing build on the
 * first commit that breaks it. That shift — from convention to constraint — is the whole
 * point.
 *
 * <h2>What we protect, and why</h2>
 *
 * <pre>
 *   inbound adapters          application core                 outbound adapters
 *   ┌──────────────────┐     ┌─────────────────────┐          ┌────────────────────┐
 *   │ RestController   │────▶│ IngestUseCase       │─────────▶│ KafkaEventPublisher│
 *   │ GrpcService      │────▶│  (interface)        │          │ RedisIdempotency   │
 *   └──────────────────┘     │ domain model        │          └────────────────────┘
 *                            └─────────────────────┘
 *                             no framework imports
 *
 *   Dependencies point INWARD. The domain declares the interfaces it needs;
 *   infrastructure implements them. That inversion is what let BS-09 add a gRPC
 *   adapter without touching a line of business logic.
 * </pre>
 *
 * <p>Phase 0 ships the rules that already apply. Each later phase adds the rule that
 * protects what it just built, so the constraints grow with the system rather than being
 * retrofitted (which never happens).
 */
class ArchitectureRulesTest {

    /**
     * ArchUnit works on compiled bytecode, so this reads {@code build/classes}, not source.
     * We exclude test classes: tests legitimately do things production code must not.
     */
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(HexagonalMarker.ROOT_PACKAGE);

    @Test
    @DisplayName("no field injection anywhere — constructor injection only")
    void noFieldInjection() {
        // Constructor injection gives final fields, fail-fast on circular dependencies,
        // and testability with plain `new`. Field injection gives none of those and hides
        // the dependency list. See interview-qa/01-spring-core.md Q1.
        //
        // We match on the annotation NAME rather than the class, so this rule compiles in
        // Phase 0 where Spring is not yet a dependency, and still works from Phase 2 on.
        ArchRule rule = noFields()
                .should()
                .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                .because("constructor injection only: explicit dependencies, final fields, "
                        + "and unit-testable without a Spring context")
                .allowEmptyShould(true); // Phase 0 has no Spring classes yet

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("the domain layer stays free of framework imports")
    void domainIsFrameworkFree() {
        // THE load-bearing rule of this codebase. The moment a JPA annotation or a Kafka
        // type appears in the domain, the domain is no longer testable in isolation and
        // the hexagon has collapsed into a layered monolith.
        //
        // allowEmptyShould(true) because Phase 0 has no ..domain.. packages yet. From
        // Phase 2 onward this rule has real classes to police.
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "org.apache.kafka..",
                        "jakarta.persistence..",
                        "software.amazon.awssdk..",
                        "io.grpc..")
                .because("the domain must be testable with plain `new` and must survive " + "a change of framework")
                .allowEmptyShould(true);

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("inbound adapters go through the use case, never straight to outbound")
    void inboundAdaptersDoNotReachOutboundAdapters() {
        // Stops the shortcut where a controller calls the Kafka publisher directly,
        // bypassing validation and idempotency. That shortcut is always "just this once".
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..adapter.in..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..adapter.out..")
                .because("business rules live in the use case; adapters must not route around it")
                .allowEmptyShould(true);

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("no java.util.Date or Calendar — use java.time")
    void modernTimeApiOnly() {
        // Mutable, badly-specified, and a genuine source of production bugs. java.time is
        // immutable and explicit about time zones. Also: injecting a java.time.Clock is
        // what makes time-dependent code testable (see interview-qa/14-testing.md Q12).
        ArchRule rule = noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.util.Date")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.util.Calendar")
                .because("java.time is immutable, unambiguous about zones, and testable " + "via an injected Clock")
                .allowEmptyShould(true);

        rule.check(PRODUCTION_CLASSES);
    }
}
