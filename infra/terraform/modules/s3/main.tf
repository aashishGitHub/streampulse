resource "aws_s3_bucket" "raw_archive" {
  bucket        = var.bucket_name
  force_destroy = var.force_destroy
  tags          = var.tags
}

resource "aws_s3_bucket_public_access_block" "raw_archive" {
  bucket                  = aws_s3_bucket.raw_archive.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "raw_archive" {
  bucket = aws_s3_bucket.raw_archive.id

  rule {
    id     = "age-out-raw-events"
    status = "Enabled"

    filter {
      prefix = "tenant="
    }

    transition {
      days          = var.transition_to_infrequent_access_days
      storage_class = "STANDARD_IA"
    }

    expiration {
      days = var.expiration_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = var.abort_incomplete_multipart_days
    }
  }
}
