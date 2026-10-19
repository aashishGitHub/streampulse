variable "cluster_name" {
  type    = string
  default = "streampulse"
}

variable "kubernetes_version" {
  description = "VERIFY against the versions EKS supports today."
  type        = string
  default     = "1.31"
}

variable "subnet_ids" {
  description = "Private subnets for the control plane ENIs and the node group."
  type        = list(string)
}

variable "node_instance_types" {
  type    = list(string)
  default = ["m6i.large"]
}

variable "node_desired_size" {
  type    = number
  default = 3
}

variable "node_min_size" {
  type    = number
  default = 3
}

variable "node_max_size" {
  type    = number
  default = 6
}

variable "tags" {
  type    = map(string)
  default = {}
}
