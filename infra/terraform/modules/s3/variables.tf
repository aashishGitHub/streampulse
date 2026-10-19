variable "bucket_name" {
  description = "Raw archive bucket. Objects live at tenant=<t>/dt=<yyyy-mm-dd>/hour=<hh>/<name>.ndjson.gz."
  type        = string
  default     = "streampulse-raw-archive-local"
}

variable "force_destroy" {
  description = "Allow destroy to delete a non-empty bucket. True only for throwaway environments."
  type        = bool
  default     = false
}

variable "transition_to_infrequent_access_days" {
  description = "Days before objects move to the infrequent-access class. A target, not sized from any measurement."
  type        = number
  default     = 30
}

variable "expiration_days" {
  description = "Days before objects are deleted. A placeholder: real retention is a legal/product decision."
  type        = number
  default     = 365
}

variable "abort_incomplete_multipart_days" {
  type    = number
  default = 7
}

variable "tags" {
  type    = map(string)
  default = {}
}
