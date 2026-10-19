# LOCAL SIMULATION ONLY. Everything here talks to emulators through endpoint overrides; no real
# cloud account is involved and none of it has ever run against one. What an apply here proves
# and does not prove is in docs/adr/0019-local-only-terraform.md and ADR-0003.

provider "aws" {
  region     = var.region
  access_key = "test"
  secret_key = "test"

  skip_credentials_validation = true
  skip_requesting_account_id  = true
  skip_metadata_api_check     = "true"
  s3_use_path_style           = true

  endpoints {
    s3       = var.moto_endpoint
    kinesis  = var.moto_endpoint
    sqs      = var.moto_endpoint
    iam      = var.moto_endpoint
    sts      = var.moto_endpoint
    dynamodb = coalesce(var.dynamodb_endpoint, var.moto_endpoint)
  }
}

locals {
  tags = {
    project     = "streampulse"
    environment = "local"
  }
}

module "kinesis" {
  source = "../../modules/kinesis"

  shard_count = 2
  tags        = local.tags
}

module "dynamodb" {
  source = "../../modules/dynamodb"

  tags = local.tags
}

module "s3" {
  source = "../../modules/s3"

  force_destroy = true
  tags          = local.tags
}

module "sqs" {
  source = "../../modules/sqs"

  tags = local.tags
}

module "iam" {
  source = "../../modules/iam"

  stream_arn         = module.kinesis.stream_arn
  archive_bucket_arn = module.s3.bucket_arn
  alerts_queue_arn   = module.sqs.queue_arn
  history_table_arn  = module.dynamodb.table_arn
  tags               = local.tags
}
