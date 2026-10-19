-- The control plane's write model. Owned by Flyway; Hibernate only VALIDATES it (ddl-auto=validate),
-- so an entity that drifts from this file fails the boot instead of failing a query in production.
--
-- Every foreign key is RESTRICT: "delete something still referenced" is refused by the database,
-- the only place that is safe against a concurrent insert.

CREATE TABLE tenant (
    tenant_id           text   PRIMARY KEY,
    display_name        text   NOT NULL,
    monthly_event_quota bigint,
    parent_tenant_id    text   REFERENCES tenant (tenant_id) ON DELETE RESTRICT,
    version             bigint NOT NULL DEFAULT 0,
    CONSTRAINT tenant_not_its_own_parent CHECK (parent_tenant_id IS DISTINCT FROM tenant_id)
);
-- PostgreSQL does not index the referencing side of a foreign key. Without these, the tree
-- assembly and every RESTRICT check scan the table.
CREATE INDEX tenant_parent_idx ON tenant (parent_tenant_id);

CREATE TABLE app (
    app_id    text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenant (tenant_id) ON DELETE RESTRICT,
    name      text NOT NULL,
    platform  text NOT NULL
);
CREATE INDEX app_tenant_idx ON app (tenant_id);

CREATE TABLE title (
    title_id  text   PRIMARY KEY,
    tenant_id text   NOT NULL REFERENCES tenant (tenant_id) ON DELETE RESTRICT,
    name      text   NOT NULL,
    kind      text   NOT NULL,
    version   bigint NOT NULL DEFAULT 0
);
CREATE INDEX title_tenant_idx ON title (tenant_id);

CREATE TABLE alert_rule (
    rule_id    text             PRIMARY KEY,
    tenant_id  text             NOT NULL REFERENCES tenant (tenant_id) ON DELETE RESTRICT,
    metric     text             NOT NULL,
    comparator text             NOT NULL,
    threshold  double precision NOT NULL,
    severity   text             NOT NULL,
    category   text,
    version    bigint           NOT NULL DEFAULT 0
);
CREATE INDEX alert_rule_tenant_idx ON alert_rule (tenant_id);

CREATE TABLE alert_rule_dependency (
    rule_id           text NOT NULL REFERENCES alert_rule (rule_id) ON DELETE CASCADE,
    upstream_category text NOT NULL,
    PRIMARY KEY (rule_id, upstream_category)
);

CREATE TABLE service_node (
    node_id   text   PRIMARY KEY,
    tenant_id text   NOT NULL REFERENCES tenant (tenant_id) ON DELETE RESTRICT,
    name      text   NOT NULL,
    category  text   NOT NULL,
    version   bigint NOT NULL DEFAULT 0
);
CREATE INDEX service_node_tenant_idx ON service_node (tenant_id);

CREATE TABLE service_node_dependency (
    node_id            text NOT NULL REFERENCES service_node (node_id) ON DELETE CASCADE,
    depends_on_node_id text NOT NULL REFERENCES service_node (node_id) ON DELETE RESTRICT,
    PRIMARY KEY (node_id, depends_on_node_id)
);
CREATE INDEX service_node_dependency_upstream_idx ON service_node_dependency (depends_on_node_id);

-- The transactional outbox. Column names are the Debezium EventRouter's configuration, see
-- docker/debezium/outbox-connector.json: aggregate_id is the Kafka key, payload the value.
-- payload is raw Avro (bytea), NULL for a delete, which the router turns into a tombstone.
CREATE TABLE outbox (
    id             uuid        PRIMARY KEY,
    aggregate_type text        NOT NULL,
    aggregate_id   text        NOT NULL,
    tenant_id      text,
    operation      text        NOT NULL,
    payload        bytea,
    created_at     timestamptz NOT NULL
);
