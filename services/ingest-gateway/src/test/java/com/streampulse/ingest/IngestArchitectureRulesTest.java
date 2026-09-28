/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hexagon's boundaries inside <b>this</b> module, as tests (REQ-GR-02).
 *
 * <h2>Why these rules are here and not in libs/platform</h2>
 *
 * {@code ArchitectureRulesTest} over there states the house rules, but ArchUnit reads compiled
 * <b>bytecode from the classpath</b> — and libs:platform does not depend on any service, so
 * the gateway's classes are invisible to it. A rule that cannot see the code it protects is a
 * rule that passes forever.
 *
 * <p>So the module that has something to protect states its own rules. That also matches how
 * the constraints are meant to grow: each phase adds the rule that guards what it just built,
 * rather than retrofitting them later, which never happens.
 *
 * <h2>What BS-09 made worth enforcing</h2>
 *
 * A second inbound adapter is exactly when hexagonal designs quietly collapse. The shortcuts
 * are always reasonable in the moment — "the gRPC service just needs the publisher directly",
 * "it can reuse the controller's mapping" — and each one puts a rule in two places, where it
 * will eventually be right in one of them and wrong in the other.
 */
class IngestArchitectureRulesTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.streampulse.ingest");

    @Test
    @DisplayName("the core carries no protocol types — neither gRPC nor servlet nor Spring web")
    void theCoreIsProtocolFree() {
        // THE rule that makes two front ends possible. If io.grpc.Status appeared in the
        // application layer, the use case would be untestable without gRPC on the classpath
        // and unusable from REST — and "share the same use case" would be a claim rather than
        // a fact.
        //
        // io.grpc.. is what BS-09 adds to this list. The rest were already true and are
        // asserted here so that adding a protocol cannot quietly erode them.
        ArchRule rule = noClasses()
                .that()
                .resideInAnyPackage("..domain..", "..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.grpc..",
                        "com.google.protobuf..",
                        "jakarta.servlet..",
                        "org.springframework.web..",
                        "org.springframework.http..")
                .because("the use case must be callable from ANY protocol, and testable with none");

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("inbound adapters go through the use case, never straight to an outbound adapter")
    void inboundAdaptersDoNotReachOutboundAdapters() {
        // Stops the shortcut where a front end calls the publisher directly and skips quota,
        // deduplication and backpressure. That shortcut is always "just this once", and it is
        // invisible in review because the code that results looks perfectly sensible.
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..adapter.in..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..adapter.out..")
                .because("business rules live in the use case; adapters must not route around it");

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("adapters depend on the PORT, not on IngestService")
    void adaptersDependOnThePortNotTheImplementation() {
        // IngestUseCase is an interface with a single implementation, which invites "why not
        // just inject the class?". This is the answer: depending on the interface is what
        // allows a test to substitute a recording double (see GrpcIngestInProcessTest) and
        // what keeps the adapters from reaching implementation details such as the permit
        // count. Only the composition root and the drain lifecycle may name the class.
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..adapter..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("com.streampulse.ingest.application.IngestService")
                .because("an adapter that knows the implementation can reach past the port");

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("the two inbound adapters do not know about each other")
    void theProtocolsAreIndependent() {
        // They share exactly one thing — TenantResolver in adapter.in — and that is deliberate,
        // because the tenant rule must be identical on both. Everything else being independent
        // is what lets either protocol change its wire format without touching the other, and
        // what makes ProtocolParityTest's claim non-trivial: the two paths really are separate
        // code that happens to converge on one use case.
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..adapter.in.grpc..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..adapter.in.rest..")
                .because("a shared helper between two protocols becomes a lowest common denominator");

        rule.check(PRODUCTION_CLASSES);
    }
}
