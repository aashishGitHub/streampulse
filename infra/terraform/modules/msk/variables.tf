variable "name" {
  type    = string
  default = "streampulse"
}

variable "kafka_version" {
  description = "VERIFY against the Kafka versions the managed service supports today before any real use; the services here are built against Kafka 3.9 clients."
  type        = string
  default     = "3.9.x"
}

variable "number_of_broker_nodes" {
  description = "Must be a multiple of the number of subnets."
  type        = number
  default     = 3
}

variable "instance_type" {
  description = "VERIFY the instance type exists in the target region. A placeholder, not sized."
  type        = string
  default     = "kafka.m7g.large"
}

variable "ebs_volume_size_gib" {
  type    = number
  default = 100
}

variable "client_subnet_ids" {
  type = list(string)
}

variable "security_group_ids" {
  type = list(string)
}

variable "tags" {
  type    = map(string)
  default = {}
}
