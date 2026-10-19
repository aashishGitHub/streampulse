variable "name_prefix" {
  type    = string
  default = "streampulse"
}

variable "stream_arn" {
  description = "ARN of the telemetry stream."
  type        = string
}

variable "archive_bucket_arn" {
  description = "ARN of the raw archive bucket."
  type        = string
}

variable "alerts_queue_arn" {
  description = "ARN of the alerts queue."
  type        = string
}

variable "history_table_arn" {
  description = "ARN of the aggregate history table."
  type        = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
