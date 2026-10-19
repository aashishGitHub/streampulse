output "stream_name" {
  value = module.kinesis.stream_name
}

output "history_table_name" {
  value = module.dynamodb.table_name
}

output "archive_bucket_name" {
  value = module.s3.bucket_name
}

output "alerts_queue_url" {
  value = module.sqs.queue_url
}

output "alerts_dlq_url" {
  value = module.sqs.dlq_url
}

output "iam_policy_arns" {
  value = module.iam.policy_arns
}

output "iam_policy_verified_from_code" {
  value = module.iam.verified_from_code
}
