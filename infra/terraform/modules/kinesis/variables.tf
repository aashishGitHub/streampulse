variable "name" {
  description = "Stream name. The ingest gateway's Kinesis adapter is configured with this exact name."
  type        = string
  default     = "telemetry-raw-v1"
}

variable "shard_count" {
  description = "Provisioned shards. Each shard is a hard 1 MB/s or 1000 records/s write quota; the default is a placeholder, not a sized number."
  type        = number
  default     = 2

  validation {
    condition     = var.shard_count >= 1
    error_message = "shard_count must be at least 1."
  }
}

variable "retention_hours" {
  description = "How long records stay readable. 24 is the service default; the maximum is a provider/service limit, check current docs."
  type        = number
  default     = 24

  validation {
    condition     = var.retention_hours >= 24
    error_message = "retention_hours must be at least 24."
  }
}

variable "tags" {
  type    = map(string)
  default = {}
}
