# Least privilege, derived from code where code exists.
#
# VERIFIED FROM CODE (read the source, listed the SDK calls):
#   ingest_gateway     services/ingest-gateway: the only AWS call is KinesisAsyncClient.putRecords
#   archive_compactor  functions/archive-compactor S3ArchiveStore: listObjectsV2, getObject,
#                      putObject, deleteObjects
#   alerts_producer    services/alert-engine SqsAlertChannel: createQueue (on demand, same name, no
#                      attributes) and sendMessage. CreateQueue is granted because the code calls it;
#                      a real deployment should have Terraform own the queue and drop that call.
#
# UNVERIFIED (no code calls these APIs yet; the actions are what such a service WOULD need):
#   alerts_consumer, history_writer, history_reader
#
# Not present on purpose: stream-processor, sink-writer, query-api, control-plane, activity-feed
# and mcp-server make no AWS API call in the current code, so they get no policy.
# Kafka access on MSK would be a separate grant, and the services use no IAM authentication today.
#
# Nothing here has been evaluated: the emulator does not enforce IAM (ADR-0003). The mapping from
# SDK call to IAM action (for example DeleteObjects needing s3:DeleteObject) is from the AWS
# documentation as remembered. VERIFY it against the current S3 and Kinesis authorization docs.

data "aws_iam_policy_document" "ingest_gateway" {
  statement {
    sid       = "PublishToTelemetryStream"
    actions   = ["kinesis:PutRecords"]
    resources = [var.stream_arn]
  }
}

data "aws_iam_policy_document" "archive_compactor" {
  statement {
    sid       = "ListArchive"
    actions   = ["s3:ListBucket"]
    resources = [var.archive_bucket_arn]
  }

  statement {
    sid       = "ReadWriteDeleteArchiveObjects"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${var.archive_bucket_arn}/tenant=*"]
  }
}

data "aws_iam_policy_document" "alerts_producer" {
  statement {
    sid       = "SendAlerts"
    actions   = ["sqs:SendMessage", "sqs:CreateQueue"]
    resources = [var.alerts_queue_arn]
  }
}

data "aws_iam_policy_document" "alerts_consumer" {
  statement {
    sid = "ConsumeAlerts"
    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:ChangeMessageVisibility",
      "sqs:GetQueueAttributes",
    ]
    resources = [var.alerts_queue_arn]
  }
}

data "aws_iam_policy_document" "history_writer" {
  statement {
    sid       = "WriteAggregateWindows"
    actions   = ["dynamodb:PutItem", "dynamodb:BatchWriteItem"]
    resources = [var.history_table_arn]
  }
}

data "aws_iam_policy_document" "history_reader" {
  statement {
    sid       = "ReadAggregateWindows"
    actions   = ["dynamodb:GetItem", "dynamodb:Query"]
    resources = [var.history_table_arn, "${var.history_table_arn}/index/title-index"]
  }
}

locals {
  policies = {
    ingest-gateway = {
      document    = data.aws_iam_policy_document.ingest_gateway.json
      verified    = true
      description = "VERIFIED FROM CODE: ingest-gateway publishes to the telemetry stream with PutRecords."
    }
    archive-compactor = {
      document    = data.aws_iam_policy_document.archive_compactor.json
      verified    = true
      description = "VERIFIED FROM CODE: archive-compactor lists, reads, writes and deletes archive objects."
    }
    alerts-producer = {
      document    = data.aws_iam_policy_document.alerts_producer.json
      verified    = true
      description = "VERIFIED FROM CODE: alert-engine creates the queue on demand and sends alerts to it."
    }
    alerts-consumer = {
      document    = data.aws_iam_policy_document.alerts_consumer.json
      verified    = false
      description = "UNVERIFIED: no code consumes the alerts queue yet."
    }
    history-writer = {
      document    = data.aws_iam_policy_document.history_writer.json
      verified    = false
      description = "UNVERIFIED: no code writes the aggregate history table yet."
    }
    history-reader = {
      document    = data.aws_iam_policy_document.history_reader.json
      verified    = false
      description = "UNVERIFIED: no code reads the aggregate history table yet."
    }
  }
}

resource "aws_iam_policy" "this" {
  for_each = local.policies

  name        = "${var.name_prefix}-${each.key}"
  description = each.value.description
  policy      = each.value.document
  tags        = var.tags
}
