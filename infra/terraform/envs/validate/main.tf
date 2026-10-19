# VALIDATE-ONLY. This root exists so `terraform validate` can check the vpc, msk and eks modules
# together with their real wiring. It must never be planned or applied: it has no emulator to
# point at, and an emulator would not make the result meaningful anyway (ADR-0019).

provider "aws" {
  region                      = "us-east-1"
  access_key                  = "validate-only"
  secret_key                  = "validate-only"
  skip_credentials_validation = true
  skip_requesting_account_id  = true
  skip_metadata_api_check     = "true"
}

module "vpc" {
  source = "../../modules/vpc"

  azs                  = ["us-east-1a", "us-east-1b", "us-east-1c"]
  public_subnet_cidrs  = ["10.0.0.0/24", "10.0.1.0/24", "10.0.2.0/24"]
  private_subnet_cidrs = ["10.0.10.0/24", "10.0.11.0/24", "10.0.12.0/24"]
}

module "msk" {
  source = "../../modules/msk"

  client_subnet_ids  = module.vpc.private_subnet_ids
  security_group_ids = [module.vpc.kafka_security_group_id]
}

module "eks" {
  source = "../../modules/eks"

  subnet_ids = module.vpc.private_subnet_ids
}
