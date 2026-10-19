output "bucket_name" {
  value = aws_s3_bucket.raw_archive.bucket
}

output "bucket_arn" {
  value = aws_s3_bucket.raw_archive.arn
}
