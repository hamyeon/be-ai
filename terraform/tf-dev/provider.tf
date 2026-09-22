provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "auction-infra"
      Environment = "tf-dev"
      ManagedBy   = "Terraform"
    }
  }
}
