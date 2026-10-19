/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.controlplane.support.PostgresIT;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The transaction lab (REQ-CP-27). Every claim is observed against a real Postgres: whether a
 * transaction is active, and whether the row survived. Nothing here is assumed from documentation.
 */
@Import(TransactionBehaviourIT.ProbeConfig.class)
class TransactionBehaviourIT extends PostgresIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfig {
        @Bean
        Probe probe(JdbcTemplate jdbc, ObjectProvider<Probe> self) {
            return new Probe(jdbc, self);
        }
    }

    /** A Spring bean, so {@code @Transactional} applies through a proxy; {@code self} is that proxy. */
    public static class Probe {
        private final JdbcTemplate jdbc;
        private final ObjectProvider<Probe> self;

        final AtomicReference<String> outerTxName = new AtomicReference<>();
        final AtomicReference<String> innerTxName = new AtomicReference<>();
        final AtomicReference<Long> outerXid = new AtomicReference<>();
        final AtomicReference<Long> innerXid = new AtomicReference<>();
        final AtomicReference<Long> outerRowsVisibleToInner = new AtomicReference<>();

        /** Read through methods: the injected bean is a proxy, and a proxy's own fields are never set. */
        public Observed observed() {
            return new Observed(
                    outerTxName.get(),
                    innerTxName.get(),
                    outerXid.get(),
                    innerXid.get(),
                    outerRowsVisibleToInner.get());
        }

        Probe(JdbcTemplate jdbc, ObjectProvider<Probe> self) {
            this.jdbc = jdbc;
            this.self = self;
        }

        private void insert(String label) {
            jdbc.update("insert into control_plane.tx_probe (label) values (?)", label);
        }

        private long xid() {
            return jdbc.queryForObject("select txid_current()", Long.class);
        }

        @Transactional
        public boolean transactionActive() {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }

        public boolean callThroughThis() {
            return this.transactionActive();
        }

        public boolean callThroughProxy() {
            return self.getObject().transactionActive();
        }

        @Transactional
        public void insertThenFail(String label) {
            insert(label);
            throw new IllegalStateException("runtime failure");
        }

        public void callFailingMethodThroughThis(String label) {
            try {
                this.insertThenFail(label);
            } catch (IllegalStateException expected) {
                // swallowed: the question is whether the insert was undone
            }
        }

        @Transactional
        public void insertThenThrowChecked(String label) throws Exception {
            insert(label);
            throw new Exception("checked failure");
        }

        @Transactional(rollbackFor = Exception.class)
        public void insertThenThrowCheckedWithRollbackFor(String label) throws Exception {
            insert(label);
            throw new Exception("checked failure");
        }

        @Transactional
        public void outerThenInner(String outerLabel, String innerLabel, boolean innerRequiresNew) {
            insert(outerLabel);
            outerTxName.set(TransactionSynchronizationManager.getCurrentTransactionName());
            outerXid.set(xid());
            if (innerRequiresNew) {
                self.getObject().innerRequiresNew(innerLabel, outerLabel);
            } else {
                self.getObject().innerRequired(innerLabel, outerLabel);
            }
            throw new IllegalStateException("outer fails after the inner returned");
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void innerRequiresNew(String label, String outerLabel) {
            observeInner(label, outerLabel);
        }

        @Transactional(propagation = Propagation.REQUIRED)
        public void innerRequired(String label, String outerLabel) {
            observeInner(label, outerLabel);
        }

        private void observeInner(String label, String outerLabel) {
            innerTxName.set(TransactionSynchronizationManager.getCurrentTransactionName());
            innerXid.set(xid());
            outerRowsVisibleToInner.set(jdbc.queryForObject(
                    "select count(*) from control_plane.tx_probe where label = ?", Long.class, outerLabel));
            insert(label);
        }
    }

    public record Observed(
            String outerTxName, String innerTxName, Long outerXid, Long innerXid, Long outerRowsVisibleToInner) {}

    @Autowired
    private Probe probe;

    @BeforeEach
    void freshProbeTable() {
        jdbc.execute("create table if not exists control_plane.tx_probe (label text not null)");
        jdbc.execute("truncate control_plane.tx_probe");
    }

    private boolean rowExists(String label) {
        return jdbc.queryForObject("select count(*) from control_plane.tx_probe where label = ?", Long.class, label)
                > 0;
    }

    @Test
    @DisplayName("self-invocation: this.method() bypasses the proxy, so @Transactional never starts a transaction")
    void selfInvocationBypassesTheProxy() {
        assertThat(probe.callThroughThis()).as("through this: no transaction").isFalse();
        assertThat(probe.callThroughProxy())
                .as("through the proxy: a transaction")
                .isTrue();
    }

    @Test
    @DisplayName("self-invocation: a runtime failure is NOT rolled back through this, and IS through the proxy")
    void selfInvocationAlsoMeansNoRollback() {
        probe.callFailingMethodThroughThis("via-this");
        assertThat(rowExists("via-this"))
                .as("autocommitted, nothing to roll back")
                .isTrue();

        assertThatThrownBy(() -> probe.insertThenFail("via-proxy")).isInstanceOf(IllegalStateException.class);
        assertThat(rowExists("via-proxy")).as("rolled back").isFalse();
    }

    @Test
    @DisplayName("a checked exception COMMITS by default; rollbackFor makes it roll back")
    void checkedExceptionCommitsByDefault() {
        assertThatThrownBy(() -> probe.insertThenThrowChecked("default")).hasMessage("checked failure");
        assertThat(rowExists("default")).as("committed despite the exception").isTrue();

        assertThatThrownBy(() -> probe.insertThenThrowCheckedWithRollbackFor("with-rollbackFor"))
                .hasMessage("checked failure");
        assertThat(rowExists("with-rollbackFor")).isFalse();
    }

    @Test
    @DisplayName("REQUIRES_NEW suspends the outer transaction: its own xid, blind to the outer's rows, commits alone")
    void requiresNewSurvivesTheOuterRollback() {
        assertThatThrownBy(() -> probe.outerThenInner("outer-row", "inner-row", true))
                .isInstanceOf(IllegalStateException.class);

        var seen = probe.observed();
        assertThat(seen.innerXid()).as("a different database transaction").isNotEqualTo(seen.outerXid());
        assertThat(seen.innerTxName()).isNotEqualTo(seen.outerTxName());
        assertThat(seen.outerRowsVisibleToInner())
                .as("the outer's uncommitted row is invisible to the suspended-over inner")
                .isZero();
        assertThat(rowExists("inner-row")).as("committed independently").isTrue();
        assertThat(rowExists("outer-row")).as("rolled back with the outer").isFalse();
    }

    @Test
    @DisplayName("contrast: REQUIRED joins the outer transaction, same xid, and rolls back with it")
    void requiredJoinsTheOuter() {
        assertThatThrownBy(() -> probe.outerThenInner("outer-row", "inner-row", false))
                .isInstanceOf(IllegalStateException.class);

        var seen = probe.observed();
        assertThat(seen.innerXid()).isEqualTo(seen.outerXid());
        assertThat(seen.outerRowsVisibleToInner()).isEqualTo(1);
        assertThat(rowExists("inner-row")).isFalse();
        assertThat(rowExists("outer-row")).isFalse();
    }
}
