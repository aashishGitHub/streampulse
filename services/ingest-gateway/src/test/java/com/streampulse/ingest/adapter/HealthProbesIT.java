/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.ingest.lifecycle.IngestDrainLifecycle;
import com.streampulse.ingest.support.TestJwtSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Kubernetes probes, and the drain they exist to make safe. BS-01 / REQ-LC-01, REQ-LC-02.
 *
 * <h2>What was missing until this class</h2>
 *
 * The health groups were configured in {@code application.yml} and {@code
 * IngestDrainLifecycle} published {@code REFUSING_TRAFFIC}, and nothing asserted either. That
 * is the difference between "we configured probes" and "we proved the pod stops taking
 * traffic before it stops working" — and the second one is the whole point of BS-01.
 *
 * <p>A probe you have never seen change is not a probe. The specific failure it was hiding:
 * flip one line in the readiness group to include a dependency, or delete the
 * {@code AvailabilityChangeEvent.publish} call, and every existing test stayed green while a
 * rolling deploy started dropping in-flight batches on every pod.
 *
 * <h2>The two questions, and why they must not share an answer</h2>
 *
 * <pre>
 *   readiness  "can I serve traffic right now?"   → K8s removes the pod from the Service
 *   liveness   "am I broken beyond recovery?"     → K8s RESTARTS the container
 * </pre>
 *
 * The drain needs the first and must not trigger the second. A pod that is deliberately
 * refusing traffic is working exactly as designed; restarting it mid-drain would abandon the
 * in-flight batches the drain exists to finish. That is the assertion in
 * {@link #readinessGoesOutOfServiceOnDrainButLivenessStaysUp()}.
 *
 * <h2>No containers</h2>
 *
 * This boots the whole application context and talks real HTTP, but needs no Docker: the
 * probes are answered from in-process availability state. Keeping it out of the Testcontainers
 * suite is deliberate — it stays in the fast run, where a broken probe is noticed in seconds.
 */
// streampulse.ingest.grpc.port=0 asks the OS for a free port. Without it the gRPC server
// binds the configured 9090, and the SECOND @SpringBootTest context in the JVM dies with
// "Address already in use" -- a failure that appears only once more than one context runs,
// which is to say only in a full build and never when you run the class on its own.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "streampulse.ingest.grpc.port=0")
@Import(TestJwtSupport.class)
// The drain is a ONE-WAY DOOR: nothing sets readiness back to ACCEPTING_TRAFFIC. Ordering is
// therefore explicit rather than incidental, and the context is discarded afterwards so a
// later test class does not inherit a gateway that is refusing traffic.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HealthProbesIT {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private IngestDrainLifecycle drain;

    private ResponseEntity<String> probe(String group) {
        // No bearer token on purpose. SecurityConfig permits /actuator/health/** because
        // kubelet does not carry one — a liveness probe that 401s is a restart loop.
        return rest.getForEntity("/actuator/health/" + group, String.class);
    }

    @Test
    @Order(1)
    @DisplayName("REQ-LC-01: a healthy pod answers both probes UP, and neither probe needs a token")
    void bothProbesAreUpAndUnauthenticated() {
        ResponseEntity<String> liveness = probe("liveness");
        ResponseEntity<String> readiness = probe("readiness");

        assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(liveness.getBody()).contains("\"status\":\"UP\"");

        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @Order(2)
    @DisplayName("the liveness group contains ONLY livenessState — a dependency in here is a restart storm")
    void livenessGroupHasNoDependencies() {
        // This asserts an OMISSION, which is unusual and deliberate. The dangerous change is
        // not adding a bad indicator by accident; it is somebody "improving" liveness by
        // adding Redis to it during an incident.
        //
        // What that would cost: Redis blips → every pod fails liveness at once → Kubernetes
        // restarts the entire deployment → a cold JVM and a cold cache on top of the original
        // problem, and the ingest path did not even need Redis (it fails open, ADR-0007).
        String body = probe("liveness").getBody();

        assertThat(body).contains("livenessState");
        assertThat(body)
                .as("liveness must never depend on anything outside this JVM")
                .doesNotContain("redis")
                .doesNotContain("readinessState")
                .doesNotContain("circuitBreakers");
    }

    @Test
    @Order(3)
    @DisplayName("the readiness group contains ONLY readinessState, so the drain is what drives it")
    void readinessGroupIsDrivenByAvailabilityState() {
        String body = probe("readiness").getBody();

        assertThat(body).contains("readinessState");
        assertThat(body).doesNotContain("livenessState");
    }

    @Test
    @Order(4)
    @DisplayName("REQ-LC-01: readiness goes OUT_OF_SERVICE during the drain, and liveness stays UP")
    void readinessGoesOutOfServiceOnDrainButLivenessStaysUp() {
        // Precondition, asserted so a failure below cannot be read as "it was never UP".
        assertThat(probe("readiness").getStatusCode()).isEqualTo(HttpStatus.OK);

        // Exactly what SIGTERM triggers. SmartLifecycle phases stop in DESCENDING order, so
        // this bean at phase 100 runs after the HTTP connector at 200 — and the first thing it
        // does, before waiting for a single in-flight batch, is publish REFUSING_TRAFFIC.
        drain.stop();

        ResponseEntity<String> readiness = probe("readiness");

        // 503, not 200-with-a-different-body. Kubernetes reads the STATUS CODE; a probe that
        // returns 200 while saying OUT_OF_SERVICE in its body keeps receiving traffic, and the
        // drain silently buys nothing.
        assertThat(readiness.getStatusCode())
                .as("K8s removes the endpoint on a non-2xx readiness probe")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(readiness.getBody()).contains("OUT_OF_SERVICE");

        // THE assertion that makes the drain work at all. A pod that is deliberately refusing
        // traffic is not broken. If liveness moved with readiness, Kubernetes would restart
        // the container instead of draining it — abandoning precisely the in-flight batches
        // the drain exists to finish, on every pod, on every deploy.
        ResponseEntity<String> liveness = probe("liveness");
        assertThat(liveness.getStatusCode())
                .as("draining is not dying — liveness must not move")
                .isEqualTo(HttpStatus.OK);
        assertThat(liveness.getBody()).contains("\"status\":\"UP\"");
    }
}
