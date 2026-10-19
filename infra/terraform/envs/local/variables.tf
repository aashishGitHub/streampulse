variable "region" {
  type    = string
  default = "us-east-1"
}

variable "moto_endpoint" {
  description = "moto server (Kinesis, S3, SQS, IAM, STS, DynamoDB). The compose stack publishes it on host port 5001."
  type        = string
  default     = "http://localhost:5001"
}

variable "dynamodb_endpoint" {
  description = <<-EOT
    Empty means "use moto_endpoint". DynamoDB Local (port 8000 under `make up-aws`) is NOT usable
    with this configuration: with AWS provider 6.68.0 and amazon/dynamodb-local:2.5.4 the table is
    created and reports ACTIVE, but apply never returns because the provider keeps polling
    DescribeTable. Cause not proven (DynamoDB Local's response has no WarmThroughput field; VERIFY).
  EOT
  type        = string
  default     = ""
}
