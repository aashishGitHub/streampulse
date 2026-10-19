variable "name" {
  description = "Alerts queue name; the dead-letter queue is <name>-dlq. The default matches streampulse.aws.alerts-queue-name in services/alert-engine."
  type        = string
  default     = "alerts"
}

variable "max_receive_count" {
  description = "Receives before a message moves to the dead-letter queue. 5 is a target, not tuned."
  type        = number
  default     = 5

  validation {
    condition     = var.max_receive_count >= 1
    error_message = "max_receive_count must be at least 1."
  }
}

variable "visibility_timeout_seconds" {
  type    = number
  default = 30
}

variable "message_retention_seconds" {
  description = "Main queue retention."
  type        = number
  default     = 345600
}

variable "dlq_message_retention_seconds" {
  description = "Dead-letter retention; longer than the main queue so a message cannot expire before someone looks at it."
  type        = number
  default     = 1209600
}

variable "tags" {
  type    = map(string)
  default = {}
}
