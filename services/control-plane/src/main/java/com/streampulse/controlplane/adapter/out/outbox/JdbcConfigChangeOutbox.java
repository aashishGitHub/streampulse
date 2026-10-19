/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.outbox;

import com.streampulse.controlplane.config.ControlPlaneSchema;
import com.streampulse.controlplane.domain.ChangeEvent;
import com.streampulse.controlplane.domain.ConfigChangeOutbox;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JdbcConfigChangeOutbox implements ConfigChangeOutbox {

    private static final String INSERT = "INSERT INTO " + ControlPlaneSchema.NAME
            + ".outbox (id, aggregate_type, aggregate_id, tenant_id, operation, payload, created_at)"
            + " VALUES (:id, :type, :key, :tenant, :operation, :payload, :createdAt)";

    private final JdbcClient jdbc;
    private final AvroConfigChangeEncoder encoder;

    public JdbcConfigChangeOutbox(JdbcClient jdbc, AvroConfigChangeEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    /**
     * MANDATORY, not REQUIRED: called with no transaction this throws instead of opening one that
     * would commit on its own. REQUIRES_NEW here would let the row outlive a rolled-back change.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(ChangeEvent event) {
        jdbc.sql(INSERT)
                .param("id", event.changeId())
                .param("type", event.kind().name())
                .param("key", event.entityId())
                .param("tenant", event.tenantId())
                .param("operation", event.operation().name())
                .param("payload", encoder.encode(event), Types.BINARY)
                .param("createdAt", OffsetDateTime.ofInstant(event.changedAt(), ZoneOffset.UTC))
                .update();
    }
}
