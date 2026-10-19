/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.controlplane.support.PostgresIT;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Booting at all is the main assertion: ddl-auto=validate makes Hibernate compare every entity with
 * the migrated schema, so drift between them fails the context before any test runs.
 */
class FlywayMigrationIT extends PostgresIT {

    @Test
    @DisplayName("the migration creates the write model and the outbox in the control_plane schema only")
    void schemaIsOwnedByTheControlPlane() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'control_plane' order by 1",
                String.class);

        assertThat(tables)
                .contains(
                        "tenant",
                        "app",
                        "title",
                        "alert_rule",
                        "alert_rule_dependency",
                        "service_node",
                        "service_node_dependency",
                        "outbox",
                        "flyway_schema_history");
    }

    @Test
    @DisplayName("nothing is created in the public schema: metric_window belongs to the sink writer")
    void publicSchemaIsUntouched() {
        List<String> publicTables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);

        assertThat(publicTables).isEmpty();
    }

    @Test
    @DisplayName("the outbox payload column is bytea and nullable, so a delete can be a tombstone")
    void outboxPayloadShape() {
        var column = jdbc.queryForMap("select data_type, is_nullable from information_schema.columns"
                + " where table_schema = 'control_plane' and table_name = 'outbox' and column_name = 'payload'");

        assertThat(column).containsEntry("data_type", "bytea").containsEntry("is_nullable", "YES");
    }

    @Test
    @DisplayName("the database itself refuses a tenant that is its own parent")
    void selfParentIsAlsoRefusedByTheDatabase() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> jdbc.update(
                                "insert into control_plane.tenant (tenant_id, display_name, parent_tenant_id) values ('a','A','a')"))
                .hasMessageContaining("tenant_not_its_own_parent");
    }
}
