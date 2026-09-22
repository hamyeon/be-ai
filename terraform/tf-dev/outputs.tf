output "vpc_id" {
  value = aws_vpc.this.id
}

output "subnet_id" {
  value = aws_subnet.this.id
}

output "route_table_id" {
  value = aws_route_table.this.id
}

output "security_group_id" {
  value = aws_security_group.this.id
}

output "sqs_main_queue_url" {
  value = aws_sqs_queue.main.url
}

output "sqs_main_queue_arn" {
  value = aws_sqs_queue.main.arn
}

output "sqs_dlq_url" {
  value = aws_sqs_queue.dlq.url
}

output "sqs_dlq_arn" {
  value = aws_sqs_queue.dlq.arn
}

output "ecr_repository_url" {
  value = aws_ecr_repository.this.repository_url
}
