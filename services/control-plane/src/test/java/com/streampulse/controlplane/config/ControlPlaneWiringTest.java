/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.controlplane.adapter.out.outbox.JdbcConfigChangeOutbox;
import com.streampulse.controlplane.application.TenantService;
import com.streampulse.controlplane.domain.ConfigChangeOutbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The Spring context assembles with no Docker: Flyway is switched off, Hibernate is told not to read
 * metadata from the database, and Hikari connects lazily, so nothing touches a network. Named *Test.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
            "spring.datasource.hikari.initialization-fail-timeout=-1"
        })
class ControlPlaneWiringTest {

    @Autowired
    private HealthEndpointGroups groups;

    @Autowired
    private ConfigChangeOutbox outbox;

    @Autowired
    private TenantService tenantService;

    @Test
    @DisplayName("the outbox port is the JDBC adapter")
    void outboxIsWired() {
        assertThat(outbox).isInstanceOf(JdbcConfigChangeOutbox.class);
    }

    @Test
    @DisplayName("services are transactional proxies: a call from outside gets a transaction")
    void servicesAreProxied() {
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(tenantService))
                .isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .isFalse();
    }

    @Test
    @DisplayName("liveness depends on no store; readiness is the availability state")
    void healthGroups() {
        assertThat(groups.get("liveness").isMember("livenessState")).isTrue();
        assertThat(groups.get("liveness").isMember("db")).isFalse();
        assertThat(groups.get("readiness").isMember("readinessState")).isTrue();
    }
}
