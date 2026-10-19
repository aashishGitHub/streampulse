output "table_name" {
  value = aws_dynamodb_table.aggregate_history.name
}

output "table_arn" {
  value = aws_dynamodb_table.aggregate_history.arn
}

output "title_index_name" {
  value = "title-index"
}
