variable "region" {
  description = "AWS region for the tf-dev environment"
  type        = string
  default     = "ap-northeast-2"
}

variable "name_prefix" {
  description = "Prefix applied to every resource name in the tf-dev environment"
  type        = string
  default     = "tf-dev"
}

variable "vpc_cidr" {
  description = "CIDR block for the tf-dev VPC. Must not overlap existing manual VPCs (10.20.0.0/16, 172.31.0.0/16)."
  type        = string
  default     = "10.30.0.0/16"
}

variable "subnet_cidr" {
  description = "CIDR block for the single tf-dev subnet"
  type        = string
  default     = "10.30.1.0/24"
}

variable "availability_zone" {
  description = "Availability zone for the tf-dev subnet"
  type        = string
  default     = "ap-northeast-2a"
}

variable "sqs_max_receive_count" {
  description = "Number of receives before a message is moved to the DLQ"
  type        = number
  default     = 5
}
