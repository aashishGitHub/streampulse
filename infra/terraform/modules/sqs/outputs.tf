output "queue_url" {
  value = aws_sqs_queue.alerts.url
}

output "queue_arn" {
  value = aws_sqs_queue.alerts.arn
}

output "dlq_url" {
  value = aws_sqs_queue.alerts_dlq.url
}

output "dlq_arn" {
  value = aws_sqs_queue.alerts_dlq.arn
}
