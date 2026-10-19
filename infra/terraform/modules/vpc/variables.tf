variable "name" {
  type    = string
  default = "streampulse"
}

variable "cidr_block" {
  type    = string
  default = "10.0.0.0/16"
}

variable "azs" {
  description = "Availability zones, one public and one private subnet each."
  type        = list(string)
}

variable "public_subnet_cidrs" {
  type = list(string)
}

variable "private_subnet_cidrs" {
  type = list(string)
}

variable "single_nat_gateway" {
  description = "One NAT gateway for all private subnets (cheaper, one point of failure) instead of one per zone."
  type        = bool
  default     = true
}

variable "tags" {
  type    = map(string)
  default = {}
}
