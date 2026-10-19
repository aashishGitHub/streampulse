output "policy_arns" {
  description = "Policy ARN per service."
  value       = { for k, p in aws_iam_policy.this : k => p.arn }
}

output "verified_from_code" {
  description = "Whether each policy was derived from reading the service's code (true) or is a forward-looking guess (false)."
  value       = { for k, v in local.policies : k => v.verified }
}
