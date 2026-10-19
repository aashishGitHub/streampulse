variable "name" {
  description = "Table name."
  type        = string
  default     = "streampulse-aggregate-history"
}

variable "point_in_time_recovery" {
  description = "Continuous backups. Off by default because DynamoDB Local does not implement the continuous-backups API (VERIFY); turn on for a real account."
  type        = bool
  default     = false
}

variable "tags" {
  type    = map(string)
  default = {}
}
