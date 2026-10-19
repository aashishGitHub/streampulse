# DESIGN ONLY: no service writes this table. The durable aggregate store today is the Postgres
# metric_window table (services/sink-writer/src/main/resources/schema.sql). This module records
# the single-table layout that would replace it if history moved to DynamoDB.
#
#   PK      TENANT#<tenant>#METRIC#<metric>      SK  WINDOW#<window start, ISO-8601 UTC>
#   GSI1PK  TENANT#<tenant>#TITLE#<title>        GSI1SK  WINDOW#<window start>
#
# Tenant is the leading component of every key, so a query cannot cross tenants by construction.

resource "aws_dynamodb_table" "aggregate_history" {
  name         = var.name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"
  range_key    = "SK"

  attribute {
    name = "PK"
    type = "S"
  }

  attribute {
    name = "SK"
    type = "S"
  }

  attribute {
    name = "GSI1PK"
    type = "S"
  }

  attribute {
    name = "GSI1SK"
    type = "S"
  }

  global_secondary_index {
    name            = "title-index"
    projection_type = "ALL"

    key_schema {
      attribute_name = "GSI1PK"
      key_type       = "HASH"
    }

    key_schema {
      attribute_name = "GSI1SK"
      key_type       = "RANGE"
    }
  }

  # Emitted only when asked for. With the block present (even enabled = false) the provider calls
  # UpdateContinuousBackups after create, DynamoDB Local does not implement it, and apply hangs
  # waiting. Observed against amazon/dynamodb-local:2.5.4 with provider 6.68.0.
  dynamic "point_in_time_recovery" {
    for_each = var.point_in_time_recovery ? [1] : []

    content {
      enabled = true
    }
  }

  tags = var.tags
}
